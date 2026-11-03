#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/ip 指令 - 查询 IP 归属信息
"""

import asyncio
import requests

from config import API_IP_LOOKUP, API_REQUEST_TIMEOUT
from utils.network_utils import resolve_host


async def handle_ip(target: str) -> str:
    if not target or not target.strip():
        return "请提供 IP 或域名，例如: /ip 8.8.8.8"
    target = target.strip().split()[0]

    ip = await asyncio.get_event_loop().run_in_executor(None, resolve_host, target)
    if ip is None:
        return f"[失败] 无法解析: {target}"

    try:
        resp = requests.get(
            f"{API_IP_LOOKUP}?ip={ip}",
            timeout=API_REQUEST_TIMEOUT,
            headers={"User-Agent": "Mozilla/5.0"},
        )
        resp.encoding = "utf-8"
        text = resp.text.strip()
        if not text:
            return f"IP 查询返回为空: {ip}"

        lines = [f"IP 查询: {ip}"]
        if target != ip:
            lines[0] += f" (解析自: {target})"
        lines.append("-" * 30)
        lines.append(text)
        return "\n".join(lines)
    except requests.RequestException as e:
        return f"[IP 查询失败] {e}"