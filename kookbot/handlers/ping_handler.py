#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/ping 指令 - 对目标执行 4 次 ping
"""

from utils.shell_utils import ping_host


async def handle_ping(target: str) -> str:
    if not target or not target.strip():
        return "请提供要 ping 的目标，例如: /ping baidu.com"
    target = target.strip().split()[0]

    out, avg_rtt = await _run_async(ping_host, target)

    if not out:
        return "[Ping 失败] 请检查目标是否正确。"

    lines = [f"Ping 结果: {target}", "-" * 30, out]
    if avg_rtt:
        lines.append(f"\n平均延迟: {avg_rtt}")
    return "\n".join(lines)


async def _run_async(func, *args):
    import asyncio
    return await asyncio.get_event_loop().run_in_executor(None, func, *args)