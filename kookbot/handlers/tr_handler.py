#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/tr IP或域名 — 路由追踪 (traceroute)
"""

import subprocess
import asyncio


async def handle_tr(target: str) -> str:
    if not target or not target.strip():
        return "请提供 IP 或域名，例如: /tr baidu.com"
    target = target.strip().split()[0]

    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _traceroute, target)


def _traceroute(target: str) -> str:
    try:
        r = subprocess.run(
            ["/usr/sbin/traceroute", "-m", "20", "-w", "2", target],
            capture_output=True, text=True, timeout=40,
        )
        out = r.stdout.strip() or r.stderr.strip()
        if not out:
            return f"路由追踪 {target} — 无结果"
        return f"路由追踪: {target}\n{'-' * 30}\n{out[:1800]}"
    except subprocess.TimeoutExpired:
        return f"路由追踪 {target} — 超时"
    except FileNotFoundError:
        return "traceroute 未安装，请先执行: apt install traceroute"
    except Exception as e:
        return f"[路由追踪失败] {e}"