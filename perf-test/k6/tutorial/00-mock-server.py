#!/usr/bin/env python3
"""学 k6 用的假接口，不连后端、不连数据库。

为什么需要它：学 k6 的时候不该被"后端没起 / 数据库没数据 / 没有 token"卡住。
这个脚本用 Python 自带的 http.server 起了 4 个接口，行为刚好够练：

    GET  /actuator/health    -> 200 {"status":"UP"}
    GET  /api/course/list    -> 200 {"code":200,...,"data":[...]}（带 20ms 延迟）
    GET  /api/course/slow    -> 200，故意慢 500ms（用来看 threshold 失败）
    GET  /api/course/fail    -> 500（用来看 http_req_failed）
    POST /api/course/select  -> 要 Authorization: Bearer xxx，否则 401；
                                返回 {"code":200,"data":{"status":"QUEUED"}}

启动：
    python3 perf-test/k6/tutorial/00-mock-server.py
    # 换端口 / 换延迟：
    MOCK_PORT=18081 MOCK_LATENCY=0.05 python3 perf-test/k6/tutorial/00-mock-server.py

验证：
    curl -s http://127.0.0.1:18080/actuator/health
"""

import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MOCK_PORT", "18080"))
# 每次"正常"请求人为睡一下，这样 http_req_duration 不是 0，学指标时有东西看
LATENCY = float(os.environ.get("MOCK_LATENCY", "0.02"))

COURSES = [
    {"id": 1, "name": "[mock] 抢课压测课", "capacity": 100, "remaining": 99},
    {"id": 2, "name": "[mock] 另一门课", "capacity": 50, "remaining": 50},
]


class Handler(BaseHTTPRequestHandler):
    # 用 HTTP/1.1 才能复用连接，行为更接近真实后端
    protocol_version = "HTTP/1.1"

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/actuator/health":
            self.send_json(200, {"status": "UP"})
            return
        if self.path == "/api/course/list":
            time.sleep(LATENCY)
            self.send_json(200, {"code": 200, "message": "", "data": COURSES})
            return
        if self.path == "/api/course/slow":
            time.sleep(0.5)
            self.send_json(200, {"code": 200, "message": "", "data": {"status": "QUEUED"}})
            return
        if self.path == "/api/course/fail":
            self.send_json(500, {"code": 500, "message": "mock 故障"})
            return
        self.send_json(404, {"code": 404, "message": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        auth = self.headers.get("Authorization") or ""
        if not auth.startswith("Bearer "):
            self.send_json(401, {"code": 401, "message": "未登录"})
            return
        if self.path != "/api/course/select":
            self.send_json(404, {"code": 404, "message": "not found"})
            return
        try:
            payload = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self.send_json(400, {"code": 400, "message": "body 不是 JSON"})
            return
        if "courseId" not in payload:
            self.send_json(400, {"code": 400, "message": "缺 courseId"})
            return
        time.sleep(LATENCY)
        # 和真后端一样：HTTP 永远是 200，业务状态在 body.data.status 里
        self.send_json(200, {"code": 200, "message": "", "data": {"status": "QUEUED", "remaining": 99}})

    def log_message(self, fmt, *args):
        # 压测时每个请求打一行日志会拖慢假服务，静默
        pass


if __name__ == "__main__":
    print(f"mock server: http://127.0.0.1:{PORT}  (latency={LATENCY}s, Ctrl+C 停止)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
