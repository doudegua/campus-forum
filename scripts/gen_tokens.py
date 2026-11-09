#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_tokens.py —— 批量生成压测用的 JWT

为什么需要它：
    压测写路径时，用同一个账号狂打测到的只是限流器拒绝得有多快，不是业务本身。
    抢课压测更极端：同一个 uid 抢第二次会被 Redis 的 SADD 去重挡成 DUPLICATE，
    于是你测的是去重逻辑，而不是并发扣减。
    所以必须有 N 个不同用户的 token。

    走正常注册流程造 N 个账号要 N 次 HTTP + N 次邮件验证码，又慢又烧 SMTP 配额。
    这里直接按项目的签发规则本地签 —— 密钥就是 .env 里的 JWT_KEY，
    签名算法和字段结构照抄 utils/JwtUtils.java 的 createJwt()。

对照的 Java 实现（utils/JwtUtils.java）：
    Algorithm.HMAC256(key)           → HS256，密钥取字符串的 UTF-8 字节
    .withJWTId(uuid)                 → jti
    .withClaim("id", id)             → id   （数值）
    .withClaim("name", u)            → name （字符串）
    .withClaim("authorities", list)  → 字符串数组
    .withExpiresAt / withIssuedAt    → exp / iat，秒为单位的 NumericDate

用法：
    python3 gen_tokens.py <起始uid> <个数> [输出路径] [角色]

    角色默认 ROLE_USER；要打 /api/admin/** 的接口时传 ROLE_ADMIN。
    例：python3 gen_tokens.py 4 1 /tmp/admin.txt ROLE_ADMIN

注意：
    - 这些 token **不校验 uid 是否存在**。后端只从 JWT 里取 id，
      业务层再决定要不要查库。所以 uid 可以随便给（压测常用一批虚构 uid）。
    - 产物含可用凭证，用完记得删。
"""

import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

# .env 在脚本的上一级（项目根）
ENV_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".env")
DEFAULT_OUT = "/tmp/perftest_tokens.txt"
EXPIRE_DAYS = 7


def b64url(raw: bytes) -> str:
    """JWT 用的 base64url，且不带 = 填充（RFC 7515）"""
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def read_jwt_key() -> str:
    # 环境变量优先：本地 .env 里 JWT_KEY 常常是空的（走 application.yaml 的默认值），
    # 这时 `JWT_KEY=666666 python3 gen_tokens.py ...` 就能本地出一批 token 自测。
    env_key = os.environ.get("JWT_KEY", "")
    if env_key:
        return env_key
    with open(ENV_PATH, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("JWT_KEY="):
                value = line[len("JWT_KEY="):]
                if not value:
                    sys.exit("JWT_KEY 存在但是空的")
                return value
    sys.exit("在 %s 里没找到 JWT_KEY" % ENV_PATH)


def make_token(key: str, uid: int, now: int, role: str) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "jti": str(uuid.uuid4()),          # 每个 token 唯一，用于登出黑名单
        "id": uid,                          # 后端靠它拿当前用户
        "name": "perftest_%d" % uid,
        # ⚠️ 必须是「ROLE_ + 大写角色名」。Spring Security 的 hasRole("ADMIN")
        #    实际比对的是 authority 字符串 "ROLE_ADMIN"，大小写敏感 ——
        #    写成 ROLE_admin 会导致 /api/admin/** 一直 403。
        "authorities": [role],
        "exp": now + EXPIRE_DAYS * 86400,
        "iat": now,
    }
    # separators 去掉空格，和 Java 侧的紧凑输出保持一致
    h = b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    p = b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = "%s.%s" % (h, p)
    sig = hmac.new(key.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    return "%s.%s" % (signing_input, b64url(sig))


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    uid_start = int(sys.argv[1])
    count = int(sys.argv[2])
    out_path = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_OUT
    role = sys.argv[4] if len(sys.argv) > 4 else "ROLE_USER"

    key = read_jwt_key()
    now = int(time.time())

    tokens = [make_token(key, uid_start + i, now, role) for i in range(count)]

    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(tokens) + "\n")

    print("已生成 %d 个 token -> %s" % (len(tokens), out_path))
    print("uid 范围: %d ~ %d，角色: %s" % (uid_start, uid_start + count - 1, role))
    print("示例(前 50 字符): %s..." % tokens[0][:50])


if __name__ == "__main__":
    main()
