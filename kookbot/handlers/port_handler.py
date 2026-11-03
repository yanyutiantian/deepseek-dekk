#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/port IP — 端口扫描（30+ 常用端口）
"""

import socket
import asyncio
from concurrent.futures import ThreadPoolExecutor

SCAN_PORTS = [
    21, 22, 23, 25, 53, 80, 110, 143, 443, 465,
    587, 853, 993, 995, 1433, 1521, 1723, 2082, 2083,
    2086, 2087, 2095, 2096, 3306, 3389, 5432, 5900,
    6379, 8080, 8443, 8888, 9090, 9200, 11211, 27017,
]
PORT_INFO = {
    21: "FTP", 22: "SSH", 23: "Telnet", 25: "SMTP", 53: "DNS",
    80: "HTTP", 110: "POP3", 143: "IMAP", 443: "HTTPS", 465: "SMTPS",
    587: "邮件提交", 853: "DNS-over-TLS", 993: "IMAPS", 995: "POP3S",
    1433: "MSSQL", 1521: "Oracle", 1723: "PPTP", 2082: "cPanel", 2083: "cPanel-SSL",
    2086: "WHM", 2087: "WHM-SSL", 2095: "Webmail", 2096: "Webmail-SSL",
    3306: "MySQL", 3389: "RDP", 5432: "PostgreSQL", 5900: "VNC",
    6379: "Redis", 8080: "HTTP-ALT", 8443: "HTTPS-ALT", 8888: "宝塔",
    9090: "面板", 9200: "ES", 11211: "Memcached", 27017: "MongoDB",
}


async def handle_port(ip: str) -> str:
    if not ip or not ip.strip():
        return "请提供 IP 地址，例如: /port 1.1.1.1"
    ip = ip.strip().split()[0]

    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _port_scan, ip)


def _port_scan(ip: str) -> str:
    lines = [f"端口扫描: {ip}", "-" * 40]

    def test(p):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(2)
        try:
            sock.connect((ip, p))
            sock.close()
            return (p, True)
        except Exception:
            return (p, False)

    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(test, SCAN_PORTS))

    results.sort(key=lambda x: x[0])
    open_ports = []
    for port, ok in results:
        name = PORT_INFO.get(port, "")
        if ok:
            open_ports.append(f"  {port:>5} ({name}) : 开放")
        else:
            lines.append(f"  {port:>5} ({name}) : 关闭")

    lines.append(f"\n已扫描 {len(SCAN_PORTS)} 个端口，开放 {len(open_ports)} 个")
    if open_ports:
        lines.insert(2, "开放端口:")
        for op in open_ports:
            lines.insert(3, op)
        lines.insert(3 + len(open_ports), "\n其他端口:")
    return "\n".join(lines)