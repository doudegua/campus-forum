#!/usr/bin/env python3
"""生成压测用的 JWT token 文件（每行一个 token）。

压测不能用真实用户的 token（会污染真实数据，也拿不到那么多），
但后端只认 JWT。所以按项目的 JWT 规则自己签一批"假用户"：
    HMAC256，key = application.yaml 里的 spring.security.jwt.key（默认 666666），
    claims 必须有 id / name / authorities / jti / iat / exp。

⚠️ authorities 必须是 JSON 数组（["ROLE_USER"]），写成字符串会让 JwtUtils
   里 Claim.asList() 返回 null，然后 NPE 500。这条坑已经踩过。

用法（和 perf-test/README.md 里的调用一致）：
    python3 perf-test/scripts/gen_tokens.py 1 500 /tmp/perftest/tokens.txt
    # 第 1 个参数：起始 uid；第 2 个：数量；第 3 个：输出文件

    # 抢课预热接口 /api/admin/** 要 ROLE_ADMIN，生成时加 --admin
    python3 perf-test/scripts/gen_tokens.py 1 500 /tmp/perftest/tokens.txt --admin

    # 换密钥 / 换有效期
    JWT_KEY=xxxx python3 perf-test/scripts/gen_tokens.py 1 500 /tmp/t.txt --days 1
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import time


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def sign_jwt(uid: int, key: str, days: int, authorities: list[str]) -> str:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "jti": secrets.token_hex(16),
        "id": uid,
        "name": f"perftest{uid}",
        "authorities": authorities,
        "iat": now,
        "exp": now + days * 86400,
    }
    signing_input = (
        b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
        + "."
        + b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    )
    signature = hmac.new(key.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    return f"{signing_input}.{b64url(signature)}"


def main() -> None:
    parser = argparse.ArgumentParser(description="生成压测用 JWT token 文件")
    parser.add_argument("start_uid", nargs="?", type=int, default=1, help="起始 uid（默认 1）")
    parser.add_argument("count", nargs="?", type=int, default=500, help="数量（默认 500）")
    parser.add_argument("out", nargs="?", default="/tmp/perftest/tokens.txt", help="输出文件")
    parser.add_argument("--admin", action="store_true", help="额外带上 ROLE_ADMIN（预热接口需要）")
    parser.add_argument("--days", type=int, default=7, help="有效期天数（默认 7）")
    args = parser.parse_args()

    key = os.environ.get("JWT_KEY", "666666")
    authorities = ["ROLE_USER"] + (["ROLE_ADMIN"] if args.admin else [])

    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)

    tokens = [sign_jwt(args.start_uid + i, key, args.days, authorities) for i in range(args.count)]
    with open(args.out, "w", encoding="utf-8") as fp:
        fp.write("\n".join(tokens) + "\n")

    print(f"已写入 {len(tokens)} 个 token -> {args.out}")
    print(f"  uid: {args.start_uid}..{args.start_uid + args.count - 1}")
    print(f"  authorities: {authorities}  有效期: {args.days} 天")
    # 打印第一个 token 的解码结果，肉眼确认 claims 对不对
    payload_b64 = tokens[0].split(".")[1]
    payload = json.loads(base64.urlsafe_b64decode(payload_b64 + "=" * (-len(payload_b64) % 4)))
    print(f"  第一个 token 的 payload: {json.dumps(payload, ensure_ascii=False)}")


if __name__ == "__main__":
    main()
