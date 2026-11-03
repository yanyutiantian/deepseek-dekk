#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自助赞助客户端
— 调 pay.example.com（赞助站点）的接口：建单 / 取待发身份订单 / 回写消息id / 回执
— 调 KOOK API：发卡片、撤回消息、@用户、发放「赞助用户」身份

全部是阻塞调用，由上层放进线程池执行。
"""

import os
import time

import requests

from config import (
    BOT_TOKEN,
    KOOK_ADMIN_ROLE_ID,
    KOOK_API_BASE,
    KOOK_GUILD_ID,
    KOOK_SPONSOR_ROLE_ID,
    PANEL_API_BASE,
    PANEL_INTERNAL_KEY,
    PAY_API_BASE,
    PAY_INTERNAL_KEY,
    PAY_SITE_BASE,
    PAY_TIMEOUT,
)


def _dbg(message: str) -> None:
    """
    关键步骤写本地日志文件 pay_debug.log。

    为什么不用 print：宝塔把进程 stdout/stderr 重定向到文件时是**块缓冲**，
    排查问题时日志根本刷不出来（本次"没撤回"就是被这个坑住了）。
    """
    try:
        path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'pay_debug.log')
        with open(path, 'a', encoding='utf-8') as handle:
            handle.write('[%s] %s\n' % (time.strftime('%Y-%m-%d %H:%M:%S'), message))
    except Exception:
        pass


# ─────────────────────────── 赞助站点（web） ───────────────────────────

def _post_web(path: str, payload: dict, base: str = None) -> dict:
    """POST 站点接口，返回解析后的 JSON（base 默认是赞助站点）。"""
    url = f"{(base or PAY_API_BASE).rstrip('/')}/{path.lstrip('/')}"
    try:
        resp = requests.post(
            url,
            data=payload,
            timeout=PAY_TIMEOUT,
            headers={"Accept": "application/json"},
        )
    except requests.RequestException as e:
        raise RuntimeError(f"请求赞助服务失败：{e}") from e
    if resp.status_code != 200:
        raise RuntimeError(f"赞助服务返回 HTTP {resp.status_code}")
    try:
        data = resp.json()
    except ValueError as e:
        raise RuntimeError("赞助服务返回的不是合法 JSON") from e
    if not isinstance(data, dict):
        raise RuntimeError("赞助服务返回格式异常")
    return data


def create_order(account: str, kook_user_id) -> dict:
    """
    创建充值订单。

    :param account: 注册邮箱对应的 QQ 号（如 123456）或完整邮箱
    返回形如：{"ok": True, "url": "https://pay.example.com/p.php?id=...", "out_trade_no": "...", ...}
    失败时返回 {"ok": False, "msg": "..."}。
    """
    return _post_web(
        "create.php",
        {
            "key": PAY_INTERNAL_KEY,
            "account": account,
            "kook_user_id": str(kook_user_id),
        },
    )


def mark_message(out_trade_no: str, channel_id: str, message_id: str) -> None:
    """回写「充值链接卡片」所在的频道与消息 id（支付成功后要撤回它）。"""
    try:
        data = _post_web(
            "mark_message.php",
            {
                "key": PAY_INTERNAL_KEY,
                "out_trade_no": out_trade_no,
                "channel_id": str(channel_id),
                "message_id": str(message_id),
            },
        )
        _dbg(f'mark_message ok order={out_trade_no} channel={channel_id} msg={message_id} resp={data}')
    except Exception as e:
        # 记不上就会导致支付成功后撤不回那条卡片，所以一定要留痕
        _dbg(f'mark_message 失败 order={out_trade_no} err={type(e).__name__}: {e}')


def fetch_pending(limit: int = 20) -> list:
    """取出「已支付但还没发 KOOK 身份」的订单。"""
    data = _post_web("pending.php", {"key": PAY_INTERNAL_KEY, "limit": limit})
    if not data.get("ok"):
        raise RuntimeError(data.get("msg") or "查询待发身份订单失败")
    items = data.get("items")
    return items if isinstance(items, list) else []


def report_role(out_trade_no: str, granted: bool, message: str = "") -> None:
    """回执：身份是否发放成功（失败时服务端会保留订单，下轮重试）。"""
    try:
        _post_web(
            "role_done.php",
            {
                "key": PAY_INTERNAL_KEY,
                "out_trade_no": out_trade_no,
                "ok": "1" if granted else "0",
                "msg": message or "",
            },
        )
    except Exception:
        pass


# ─────────────────────────── KOOK ───────────────────────────

def _kook_post(path: str, body: dict) -> dict:
    """调 KOOK API，返回 data 字段。"""
    url = f"{KOOK_API_BASE.rstrip('/')}/{path.lstrip('/')}"
    headers = {
        "Authorization": f"Bot {BOT_TOKEN}",
        "Content-Type": "application/json",
    }
    try:
        resp = requests.post(url, json=body, headers=headers, timeout=PAY_TIMEOUT)
    except requests.RequestException as e:
        raise RuntimeError(f"请求 KOOK 接口失败：{e}") from e
    if resp.status_code != 200:
        raise RuntimeError(f"KOOK 接口返回 HTTP {resp.status_code}")
    try:
        data = resp.json()
    except ValueError as e:
        raise RuntimeError("KOOK 接口返回的不是合法 JSON") from e
    if data.get("code") != 0:
        raise RuntimeError(f"KOOK 接口返回：{data.get('message') or data}")
    payload = data.get("data")
    return payload if isinstance(payload, dict) else {}


def _kook_get(path: str, params: dict) -> dict:
    """GET KOOK API，返回 data 字段。"""
    url = f"{KOOK_API_BASE.rstrip('/')}/{path.lstrip('/')}"
    headers = {"Authorization": f"Bot {BOT_TOKEN}"}
    try:
        resp = requests.get(url, params=params, headers=headers, timeout=PAY_TIMEOUT)
    except requests.RequestException as e:
        raise RuntimeError(f"请求 KOOK 接口失败：{e}") from e
    if resp.status_code != 200:
        raise RuntimeError(f"KOOK 接口返回 HTTP {resp.status_code}")
    try:
        data = resp.json()
    except ValueError as e:
        raise RuntimeError("KOOK 接口返回的不是合法 JSON") from e
    if data.get("code") != 0:
        raise RuntimeError(f"KOOK 接口返回：{data.get('message') or data}")
    payload = data.get("data")
    return payload if isinstance(payload, dict) else {}


def is_admin(kook_user_id) -> bool:
    """
    该 KOOK 用户是否【服务器主】或拥有「管理员」身份。

    · 服务器主：GET /guild/view → master_id
    · 管理员组：GET /guild/user-list?role_id=<管理员角色>，再看目标用户在不在名单里
    """
    uid = str(kook_user_id)

    try:
        guild = _kook_get('/guild/view', {'guild_id': str(KOOK_GUILD_ID)})
        if str(guild.get('master_id') or '') == uid:
            return True
    except Exception as e:
        _dbg(f'is_admin: guild/view 失败 {type(e).__name__}: {e}')

    try:
        # 注意：KOOK 的路由是 /guild/user-list（不是 /guild-user/list，后者 404）
        data = _kook_get('/guild/user-list', {
            'guild_id': str(KOOK_GUILD_ID),
            'role_id': int(KOOK_ADMIN_ROLE_ID),
            'page_size': 50,
        })
        for item in (data.get('items') or []):
            if isinstance(item, dict) and str(item.get('id')) == uid:
                return True
        # 管理员人数超过一页时继续翻页
        meta = data.get('meta') or {}
        page_total = int(meta.get('page_total') or 1)
        page = 1
        while page < page_total and page < 20:
            page += 1
            more = _kook_get('/guild/user-list', {
                'guild_id': str(KOOK_GUILD_ID),
                'role_id': int(KOOK_ADMIN_ROLE_ID),
                'page': page,
                'page_size': 50,
            })
            for item in (more.get('items') or []):
                if isinstance(item, dict) and str(item.get('id')) == uid:
                    return True
    except Exception as e:
        _dbg(f'is_admin: guild/user-list 失败 {type(e).__name__}: {e}')

    return False


def set_money(money: str) -> dict:
    """设定自助赞助金额（服务端会再校验一次范围）。"""
    return _post_web('setpay.php', {'key': PAY_INTERNAL_KEY, 'money': money})


def get_money() -> str:
    """查询当前自助赞助金额。"""
    data = _post_web('money.php', {'key': PAY_INTERNAL_KEY})
    return str(data.get('money') or '')


# ─────────────────────── KOOK 账号绑定 ───────────────────────

def bind_kook(code: str, kook_user_id: str, kook_name: str) -> dict:
    """
    把 KOOK 账号绑定到 dekk 账号（用户在「账号绑定」频道发 /code XXXXXX）。

    实际写库在 pay 站点根目录的 code.php（无 WAF，机器人可直接调）。
    密钥用 PANEL_INTERNAL_KEY（code.php 也兼容 pay 站 config 的 internal_key）。

    :return: {"ok": True, "dekk_user": {...}, "kook_name": "..."} 或 {"ok": False, "msg": "..."}
    """
    return _post_web('code.php', {
        'key': PANEL_INTERNAL_KEY,
        'code': code,
        'kook_user_id': str(kook_user_id),
        'kook_name': kook_name or '',
    }, base=PAY_SITE_BASE)


# ─────────────────────── 临时管理面板 ───────────────────────

def create_panel(kook_user_id, kook_name: str, channel_id: str) -> dict:
    """
    申请一个临时管理面板（1 小时有效）。

    :return: {"ok": True, "url": "...", "expires_in": 3600, "kook_name": "..."}
    """
    data = _post_web('create.php', {
        'key': PANEL_INTERNAL_KEY,
        'kook_user_id': str(kook_user_id),
        'kook_name': kook_name or '',
        'channel_id': str(channel_id or ''),
    }, base=PANEL_API_BASE)
    return data


def send_direct_card(kook_user_id, card_json: str) -> str:
    """
    给用户发私信卡片。

    实测要点（KOOK 私有消息 API）：
      1. 先 POST /user-chat/create 建立/获取会话（target_id 传【用户 ID】）；
      2. 再 POST /direct-message/create 发消息 —— 这里的 target_id 仍然要传
         【用户 ID】，传 chat_code 会报「未找到用户」。

    :return: msg_id
    """
    uid = str(kook_user_id)
    try:
        _kook_post('/user-chat/create', {'target_id': uid})
    except Exception as e:
        # 建会话失败不致命，继续尝试直接发（有些情况会话已存在）
        _dbg(f'send_direct_card: user-chat/create 失败 {type(e).__name__}: {e}')

    data = _kook_post('/direct-message/create', {
        'type': 10,
        'target_id': uid,
        'content': card_json,
    })
    msg_id = str(data.get('msg_id') or '')
    if not msg_id:
        raise RuntimeError(f'私信未返回 msg_id：{data}')
    _dbg(f'send_direct_card ok user={uid} msg={msg_id}')
    return msg_id


def send_card(channel_id: str, card_json: str, quote: str = "") -> str:
    """
    在频道里发卡片消息（type=10），返回 msg_id。

    :param card_json: json.dumps(CardMessage(card)) 的结果
    :param quote:     要引用的消息 id（引用用户发的那条 /pay）
    """
    body = {
        "type": 10,
        "target_id": str(channel_id),
        "content": card_json,
    }
    if quote:
        body["quote"] = str(quote)
    data = _kook_post("/message/create", body)
    msg_id = str(data.get("msg_id") or "")
    if not msg_id:
        raise RuntimeError(f"KOOK 未返回 msg_id：{data}")
    _dbg(f'send_card ok channel={channel_id} msg={msg_id} quote={quote or "-"} len={len(card_json)}')
    return msg_id


def delete_message(message_id: str) -> None:
    """撤回消息（失败留痕，不影响赞助结果）。"""
    if not message_id:
        _dbg('delete_message 跳过：message_id 为空')
        return
    try:
        _kook_post("/message/delete", {"msg_id": str(message_id)})
        _dbg(f'delete_message ok msg={message_id}')
    except Exception as e:
        _dbg(f'delete_message 失败 msg={message_id} err={type(e).__name__}: {e}')


def send_mention(channel_id: str, kook_user_id, text: str) -> None:
    """在频道里发一条 KMarkdown 消息并 @指定用户。"""
    body = {
        "type": 9,
        "target_id": str(channel_id),
        "content": f"(met){kook_user_id}(met) {text}",
    }
    _kook_post("/message/create", body)


def grant_sponsor_role(kook_user_id) -> bool:
    """给 KOOK 用户添加「赞助用户」身份。"""
    _kook_post(
        "/guild-role/grant",
        {
            "guild_id": str(KOOK_GUILD_ID),
            "user_id": str(kook_user_id),
            "role_id": int(KOOK_SPONSOR_ROLE_ID),
        },
    )
    return True