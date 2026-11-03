#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/whois 指令 - WHOIS 查询（纯文本返回）
"""

import requests

from config import API_WHOIS, API_REQUEST_TIMEOUT
from utils.network_utils import get_root_domain


async def handle_whois(domain: str) -> str:
    if not domain or not domain.strip():
        return "请提供域名，例如: /whois example.com"
    domain = domain.strip().split()[0]

    root = get_root_domain(domain)

    try:
        resp = requests.get(
            f"{API_WHOIS}?url={root}",
            timeout=API_REQUEST_TIMEOUT,
            headers={"User-Agent": "Mozilla/5.0"},
        )
        resp.encoding = "utf-8"
        text = resp.text.strip()
        if not text:
            return f"WHOIS 查询 {root} 返回为空。"
        return f"WHOIS: {root} (原始: {domain})\n{'-' * 30}\n{text}"
    except requests.RequestException as e:
        return f"[WHOIS 查询失败] {e}"