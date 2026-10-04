#!/usr/bin/env python3
"""
确保 MinIO 里的 bucket 存在。供 docker-compose 的 minio-init 服务使用。

--------------------------------------------------------------------------
为什么需要这个文件（而不是一行 mc 命令）
--------------------------------------------------------------------------
MinIO 在 2025 年 10 月停止了 Docker 镜像的免费分发：
  - Docker Hub 上 minio/minio、minio/mc 的仓库已被移除
  - quay.io 上的同名仓库改为需要认证
  - dl.min.io 的二进制下载返回 410 Gone
  - GitHub Release 只剩标签，一个附件都没有

所以"用官方 mc 镜像建 bucket"这条路没有了。这个脚本改用 Python + boto3，
因为 MinIO 兼容 S3 协议 —— 任何 S3 客户端都能建 bucket，不必依赖 MinIO 自家的工具。

--------------------------------------------------------------------------
为什么 bucket 必须预先建好
--------------------------------------------------------------------------
后端的 ImageServiceImpl 直接调 putObject，**没有**"bucket 不存在就创建"的逻辑。
所以 bucket 不存在时，第一次上传头像/配图会失败，报
    The specified bucket does not exist
而这发生在运行时、不在启动时 —— 很容易被当成代码 bug 去查。

--------------------------------------------------------------------------
为什么是幂等的
--------------------------------------------------------------------------
容器可能会被重复启动（重启、重新 up）。bucket 已存在时直接跳过，不报错。
"""

import os
import sys
import time

# ⚠️ 必须在 import boto3 **之前**设。
# botocore 构建 HTTP 会话时不会把 trust_env 设为 False，所以 urllib3 会去读
# 代理配置。本机若走显式 HTTP 代理，打 MinIO 的请求会被发到代理端口，
# 拿回一个 502 Bad Gateway —— 报错和"MinIO 坏了"完全不像，极难定位。
# 这里把目标主机加进 NO_PROXY 挡掉这种情形。
#
# 注意这挡不住**透明代理/VPN 的 TUN 模式**：那种是在内核层接管路由，
# 根本不过代理环境变量，只能靠关掉 VPN 或在其配置里排除该地址。
# （这个脚本的实测过程里就撞上过：开着 TUN 时 boto3 打 127.0.0.1:9000
#   被劫到代理端口，返回 502。）
_endpoint_host = (os.environ.get("MINIO_ENDPOINT") or "http://minio:9000").split("//")[-1].split("/")[0]
_no_proxy = ",".join(filter(None, [
    os.environ.get("NO_PROXY", ""), os.environ.get("no_proxy", ""),
    _endpoint_host, "localhost", "127.0.0.1",
]))
os.environ["NO_PROXY"] = _no_proxy
os.environ["no_proxy"] = _no_proxy

import boto3
from botocore.client import Config
from botocore.exceptions import ClientError, EndpointConnectionError


def env(name: str, default: str) -> str:
    return os.environ.get(name) or default


ENDPOINT = env("MINIO_ENDPOINT", "http://minio:9000")
ACCESS_KEY = env("MINIO_ACCESS_KEY", "minioadmin")
SECRET_KEY = env("MINIO_SECRET_KEY", "minioadmin")
BUCKET = env("MINIO_BUCKET", "study")
RETRIES = int(env("MINIO_INIT_RETRIES", "30"))


def main() -> int:
    client = boto3.client(
        "s3",
        endpoint_url=ENDPOINT,
        aws_access_key_id=ACCESS_KEY,
        aws_secret_access_key=SECRET_KEY,
        # MinIO 不需要真实 region，但 boto3 要求给一个，否则签名会失败
        region_name="us-east-1",
        # MinIO 走的是 path-style（http://host/bucket/key），不是 AWS 的
        # virtual-hosted-style（http://bucket.host/key）。
        # 不设这个的话，boto3 会拼出 http://study.minio:9000/... 这种域名，
        # 在容器网络里解析不了，报连接错误 —— 这个坑很常见。
        config=Config(
            signature_version="s3v4",
            s3={"addressing_style": "path"},
            # 短超时 + 关掉 botocore 自己的重试。
            # 默认值下，连不上端点时每次都等很久，加上 botocore 内部还会重试，
            # 结果是"2 次重试花了 19 秒"。默认 30 次的话会卡近 10 分钟才报错 ——
            # 初始化容器挂在那里不动、日志只有一行，很难判断是慢还是死了。
            # 重试逻辑由下面那个循环负责，这里只要"快速失败"。
            connect_timeout=3,
            read_timeout=5,
            retries={"max_attempts": 0},
        ),
    )

    # MinIO 的 healthcheck 通过后，S3 接口有时还要再等一下才完全可用。
    # 所以这里带重试，而不是"失败就退出" —— 否则 compose 会因为
    # 几秒的时序差把整个初始化判成失败。
    last_err = None
    for attempt in range(1, RETRIES + 1):
        try:
            client.head_bucket(Bucket=BUCKET)
            print(f"[minio-init] bucket '{BUCKET}' 已存在，跳过")
            return 0
        except ClientError as e:
            code = str(e.response.get("Error", {}).get("Code", ""))
            # 404 / NoSuchBucket 表示"连上了，但 bucket 不存在" → 该建
            if code in ("404", "NoSuchBucket"):
                try:
                    client.create_bucket(Bucket=BUCKET)
                    print(f"[minio-init] bucket '{BUCKET}' 创建成功")
                    return 0
                except ClientError as ce:
                    last_err = ce
            # 403 表示 key/secret 不对 —— 重试也没用，直接说清楚
            elif code in ("403", "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch"):
                print(f"[minio-init] 认证失败：{ce_msg(e)}", file=sys.stderr)
                print("[minio-init] 检查 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD "
                      "和后端用的 MINIO_ACCESS_KEY / MINIO_SECRET_KEY 是否一致", file=sys.stderr)
                return 1
            else:
                last_err = e
        except EndpointConnectionError as e:
            last_err = e

        print(f"[minio-init] 第 {attempt}/{RETRIES} 次未就绪，2 秒后重试…")
        time.sleep(2)

    print(f"[minio-init] 放弃：{ce_msg(last_err) if last_err else '未知错误'}", file=sys.stderr)
    return 1


def ce_msg(e) -> str:
    if hasattr(e, "response"):
        return str(e.response.get("Error", {}).get("Message", e))
    return str(e)


if __name__ == "__main__":
    sys.exit(main())
