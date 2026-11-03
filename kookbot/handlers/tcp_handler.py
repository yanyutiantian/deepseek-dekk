#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/tcp IP:端口 — TCP 端口连通性测试
"""

import socket
import asyncio

from config import SOCKET_TIMEOUT


async def handle_tcp(target: str) -> str:
    if not target or not target.strip() or ":" not in target:
        return "请按格式提供: /tcp IP:端口\n例如: /tcp 203.0.113.10:22"
    target = target.strip().split()[0]

    host, _, port_str = target.partition(":")
    try:
        port = int(port_str)
    except ValueError:
        return f"无效端口号: {port_str}"

    loop = asyncio.get_event_loop()
    ok, ms = await loop.run_in_executor(None, _tcp_test, host, port)

    if ok:
        return f"TCP {host}:{port} — 连通 (耗时 {ms} ms)"
    else:
        return f"TCP {host}:{port} — 不通 ({ms})"


def _tcp_test(host: str, port: int) -> tuple[bool, str]:
    import time
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(SOCKET_TIMEOUT)
    try:
        t0 = time.time()
        sock.connect((host, port))
        ms = round((time.time() - t0) * 1000, 1)
        return True, f"{ms} ms"
    except Exception as e:
        return False, str(e)
    finally:
        sock.close()