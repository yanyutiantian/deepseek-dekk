#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/webping 域名 — 自动探测 HTTP/HTTPS，发起请求并计时
"""

import asyncio
import re

from config import SOCKET_TIMEOUT
from utils.network_utils import measure_tcp_http


async def handle_webping(target: str) -> str:
    if not target or not target.strip():
        return "请提供域名，例如: /webping example.com"
    target = target.strip().split()[0]

    host = re.sub(r"^https?://", "", target).split("/")[0].split(":")[0]

    loop = asyncio.get_event_loop()

    # 先试 HTTPS，不通则 HTTP
    r = await loop.run_in_executor(None, lambda: measure_tcp_http(host, port=443, use_tls=True))
    if r["error"]:
        r = await loop.run_in_executor(None, lambda: measure_tcp_http(host, port=80, use_tls=False))
        protocol = "HTTP"
    else:
        protocol = "HTTPS"

    if r["error"]:
        return f"WebPing {host} — [失败] {r['error']}"

    return (
        f"WebPing: {host}  ({protocol})\n"
        f"{'-' * 30}\n"
        f"TCP 握手: {r['connect_ms']} ms\n"
        f"完整请求: {r['total_ms']} ms ({r['total_s']} 秒)\n"
        f"状态码: {r['status_code']}\n"
        f"响应大小: {r['body_size']} 字节"
    )