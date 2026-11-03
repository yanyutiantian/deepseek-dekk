#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/http 指令 - TCP 测时 + Ping 平均值 + 网页截图
"""

import asyncio
import re

from config import API_SCREENSHOT
from utils.network_utils import measure_tcp_http
from utils.shell_utils import ping_host


async def handle_http(target: str) -> dict:
    return await _handle_http_common(target, use_tls=False)


async def _handle_http_common(target: str, use_tls: bool) -> dict:
    proto = "HTTPS" if use_tls else "HTTP"
    if not target or not target.strip():
        return {"type": "text", "content": f"请提供域名或IP，例如: /{proto.lower()} example.com"}
    target = target.strip().split()[0]

    host = re.sub(r"^https?://", "", target).split("/")[0].split(":")[0]
    port = 443 if use_tls else 80
    scheme = "https" if use_tls else "http"

    loop = asyncio.get_event_loop()

    # TCP + HTTP 测时
    http_r = await loop.run_in_executor(None, lambda: measure_tcp_http(host, port=port, use_tls=use_tls))

    # Ping 4 次
    ping_out, avg_rtt = await loop.run_in_executor(None, ping_host, host)

    # 截图 URL（直接给 KOOK 加载）
    screenshot_url = f"{API_SCREENSHOT}?url={scheme}://{host}"

    # 构建文本
    lines = [f"{proto} 检测: {host}", "-" * 30]
    if http_r["error"]:
        lines.append(f"[请求失败] {http_r['error']}")
    else:
        lines.append(f"TCP 握手耗时: {http_r['connect_ms']} ms")
        lines.append(f"完整请求耗时: {http_r['total_ms']} ms ({http_r['total_s']} 秒)")
        lines.append(f"HTTP 状态码: {http_r['status_code']}")
        lines.append(f"响应体大小: {http_r['body_size']} 字节")

    if avg_rtt:
        lines.append(f"Ping 平均延迟: {avg_rtt}")
    elif ping_out:
        lines.append(f"Ping 结果:\n{ping_out[-200:]}")
    else:
        lines.append("Ping: 无结果")

    result = {"type": "mixed", "text": "\n".join(lines), "image_url": screenshot_url}
    return result