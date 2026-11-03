#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/bgp IP — 检测IP线路类型(BGP/双线/单线)并测试连接速度

通过 bgpview.io 获取 ASN 及上游信息判断线路类型，
同时用本地 ping 和 traceroute 测试三网连接质量。
"""

import subprocess
import asyncio
import re
import ipaddress

import requests


# ─────────── 中国三大运营商 ASN 集合 ───────────
TELECOM_ASNS = {
    "4134", "4809", "4811", "4812", "4813",
    "134763", "136188", "137693", "139201", "140527", "23764",
}
UNICOM_ASNS = {
    "4837", "9929", "10099", "134542", "135061",
    "136958", "138421", "140726", "133111", "134543",
}
MOBILE_ASNS = {
    "9808", "58453", "9394", "134810", "135065",
    "137514", "139019", "56044", "56046", "56040",
}

# 运营商名称映射
ASN_LABEL = {
    "4134": "电信(AS4134/CHINANET)",
    "4809": "电信CN2(AS4809)",
    "4837": "联通(AS4837/CHINA169)",
    "9929": "联通CUII(AS9929)",
    "9808": "移动(AS9808/CMNET)",
    "58453": "移动CMI(AS58453)",
    "9394": "铁通(AS9394)",
}


def is_valid_ip(s: str) -> bool:
    try:
        ipaddress.ip_address(s.strip())
        return True
    except ValueError:
        return False


async def handle_bgp(target: str) -> str:
    if not target or not target.strip() or not is_valid_ip(target):
        return "请提供有效的 IP 地址，例如: /bgp 119.29.29.29"
    target = target.strip().split()[0]

    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _bgp_check, target)


def _bgp_check(ip: str) -> str:
    lines = [f"BGP检测: {ip}", "-" * 35]

    # ── 1. bgpview.io 获取 ASN 信息 ──
    asn_num = None
    asn_name = None
    asn_desc = None
    asn_prefix = None

    try:
        r = requests.get(
            f"https://api.bgpview.io/ip/{ip}",
            headers={"User-Agent": "Mozilla/5.0"},
            timeout=15,
        )
        if r.status_code == 200:
            data = r.json()
            if data.get("status") == "ok":
                prefixes = data.get("data", {}).get("prefixes", [])
                if prefixes:
                    p = prefixes[0]
                    a = p.get("asn", {})
                    asn_num = str(a.get("asn", ""))
                    asn_name = a.get("name", "")
                    asn_desc = a.get("description", "")
                    asn_prefix = p.get("prefix", "")
    except Exception:
        pass

    # ── 2. 获取上游 ASN ──
    upstream_asns = []
    if asn_num:
        try:
            r2 = requests.get(
                f"https://api.bgpview.io/asn/{asn_num}/upstreams",
                headers={"User-Agent": "Mozilla/5.0"},
                timeout=15,
            )
            if r2.status_code == 200:
                d2 = r2.json()
                if d2.get("status") == "ok":
                    for u in d2.get("data", {}).get("ipv4_upstreams", []):
                        upstream_asns.append(str(u.get("asn", "")))
        except Exception:
            pass

    # ── 3. 输出 ASN 信息 ──
    if asn_num:
        lines.append(f"ASN   : {asn_num}")
        if asn_name:
            lines.append(f"名称  : {asn_name}")
        if asn_desc:
            lines.append(f"描述  : {asn_desc}")
        if asn_prefix:
            lines.append(f"前缀  : {asn_prefix}")
        if upstream_asns:
            labels = [ASN_LABEL.get(a, f"AS{a}") for a in upstream_asns[:8]]
            lines.append(f"上游  : {', '.join(labels)}")
    else:
        lines.append("ASN   : bgpview.io 查询失败")

    # ── 4. 判断线路类型 ──
    found_telecom = set()
    found_unicom = set()
    found_mobile = set()

    check_set = set(upstream_asns)
    if asn_num:
        check_set.add(asn_num)

    for a in check_set:
        if a in TELECOM_ASNS:
            found_telecom.add(a)
        if a in UNICOM_ASNS:
            found_unicom.add(a)
        if a in MOBILE_ASNS:
            found_mobile.add(a)

    # whois 兜底
    if not upstream_asns and asn_num:
        try:
            wr = subprocess.run(
                ["whois", ip],
                capture_output=True, text=True, timeout=10,
            )
            wtext = wr.stdout.lower()
            if any(k in wtext for k in ["chinanet", "chinatelecom", "电信", "ctcn"]):
                found_telecom.add("whois")
            if any(k in wtext for k in ["china169", "unicom", "联通", "cncgroup", "china unicom"]):
                found_unicom.add("whois")
            if any(k in wtext for k in ["chinamobile", "cmnet", "移动", "tietong", "cmcc"]):
                found_mobile.add("whois")
        except Exception:
            pass

    has_t = len(found_telecom) > 0
    has_u = len(found_unicom) > 0
    has_m = len(found_mobile) > 0
    count = sum([has_t, has_u, has_m])

    lines.append("-" * 35)
    lines.append("线路检测结果:")

    if count == 3:
        lines.append("类型: BGP (三线)")
    elif count == 2:
        parts = []
        if has_t: parts.append("电信")
        if has_u: parts.append("联通")
        if has_m: parts.append("移动")
        lines.append(f"类型: {'+'.join(parts)}双线")
    elif count == 1:
        if has_t:
            lines.append("类型: 电信单线")
        elif has_u:
            lines.append("类型: 联通单线")
        elif has_m:
            lines.append("类型: 移动单线")
    else:
        desc = f"{asn_desc or ''} {asn_name or ''}".lower()
        if "bgp" in desc:
            lines.append("类型: BGP (根据ASN描述推断)")
        elif any(k in desc for k in ["chinanet", "telecom"]):
            lines.append("类型: 推测为电信线路")
        elif any(k in desc for k in ["unicom", "cnc"]):
            lines.append("类型: 推测为联通线路")
        elif any(k in desc for k in ["mobile", "cmnet"]):
            lines.append("类型: 推测为移动线路")
        else:
            lines.append("类型: 未检测到国内运营商线路特征")

    # ── 5. 速度测试 ──
    lines.append("-" * 35)
    lines.append("速度测试 (本服务器视角):")

    # Ping 测试
    try:
        pr = subprocess.run(
            ["ping", "-c", "6", "-W", "3", ip],
            capture_output=True, text=True, timeout=25,
        )
        pout = pr.stdout.strip()
        # 提取统计行
        stats_match = re.findall(
            r"(\d+)\s+packets.*?(\d+)\s+received.*?(\d+(?:\.\d+)?)%\s+packet\s+loss.*?min/avg/max.*?=\s*([\d.]+)/([\d.]+)/([\d.]+)",
            pout,
        )
        if stats_match:
            s = stats_match[-1]
            lines.append(
                f"Ping : 发送{s[0]} 接收{s[1]} 丢包{s[2]}% "
                f"最小{s[3]}ms 平均{s[4]}ms 最大{s[5]}ms"
            )
        elif pr.returncode == 0:
            # 尝试更宽松地提取 rtt 行
            rtt_line = [l for l in pout.split("\n") if "rtt" in l.lower()]
            if rtt_line:
                lines.append(f"Ping : {rtt_line[0].strip()}")
            else:
                # 手动提取
                recv = re.search(r"(\d+)\s+received", pout)
                loss = re.search(r"(\d+(?:\.\d+)?)%\s+packet\s+loss", pout)
                avg = re.search(r"=\s*[\d.]+/([\d.]+)/[\d.]+", pout)
                parts = []
                if recv: parts.append(f"接收={recv.group(1)}")
                if loss: parts.append(f"丢包={loss.group(1)}%")
                if avg: parts.append(f"平均={avg.group(1)}ms")
                lines.append("Ping : " + " ".join(parts) if parts else pout[:200])
        else:
            lines.append(f"Ping : 全部丢包 ({pout[:120] if pout else '无响应'})")
    except subprocess.TimeoutExpired:
        lines.append("Ping : 超时")
    except Exception as e:
        lines.append(f"Ping : 失败 ({e})")

    # Traceroute
    try:
        tr = subprocess.run(
            ["/usr/sbin/traceroute", "-m", "15", "-w", "2", ip],
            capture_output=True, text=True, timeout=45,
        )
        tr_out = tr.stdout.strip() or tr.stderr.strip()
        if tr_out:
            # 截取前 1000 字符
            lines.append("-" * 35)
            lines.append(f"路由追踪:\n{tr_out[:1000]}")
    except subprocess.TimeoutExpired:
        lines.append("路由追踪: 超时")
    except Exception:
        pass

    return "\n".join(lines)
