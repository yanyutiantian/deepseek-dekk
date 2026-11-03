#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/dns 域名 — 查询 DNS A 记录和 CNAME 记录
"""

import socket
import asyncio


async def handle_dns(domain: str) -> str:
    if not domain or not domain.strip():
        return "请提供域名，例如: /dns example.com"
    domain = domain.strip().split()[0]

    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _dns_lookup, domain)


def _dns_lookup(domain: str) -> str:
    lines = [f"DNS 查询: {domain}", "-" * 30]

    # A 记录
    try:
        info = socket.getaddrinfo(domain, None, socket.AF_INET)
        ips = sorted(set(addr[4][0] for addr in info))
        lines.append(f"A 记录 ({len(ips)} 个):")
        for ip in ips[:8]:
            lines.append(f"  {ip}")
        if len(ips) > 8:
            lines.append(f"  ... 共 {len(ips)} 个")
    except socket.gaierror:
        lines.append("A 记录: 解析失败")

    # CNAME 记录
    try:
        import subprocess
        result = subprocess.run(
            ["dig", "+short", "CNAME", domain],
            capture_output=True, text=True, timeout=10
        )
        cname = result.stdout.strip()
        if cname:
            lines.append(f"\nCNAME 记录: {cname}")
        else:
            lines.append("\nCNAME 记录: 无")
    except Exception:
        try:
            cname = socket.gethostbyname_ex(domain)
            if cname[0] != domain:
                lines.append(f"\nCNAME (推测): {cname[0]}")
            else:
                lines.append("\nCNAME 记录: 无")
        except Exception:
            lines.append("\nCNAME 记录: 查询失败")

    return "\n".join(lines)