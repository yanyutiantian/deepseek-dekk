#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/pay 指令处理 — 校验账号（QQ 号或邮箱）并生成 20 元支付宝充值短链
"""

import asyncio
import re

from utils.pay_client import create_order

# 注册邮箱对应的 QQ 号（纯数字）
QQ_RE = re.compile(r"^[0-9]{5,12}$")
# 也兼容直接填完整邮箱
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[A-Za-z0-9]{2,}$")


async def handle_pay(account: str, kook_user_id, money: str = "20.00") -> dict:
    """
    校验账号并创建充值订单。

    :param account:      注册邮箱对应的 QQ 号（如 123456）或完整邮箱
    :param kook_user_id: 发起指令的 KOOK 用户 ID（支付成功后给他发身份并 @他）
    :param money:        仅用于日志/展示，实际金额由服务端决定
    :return: {"ok": True, "url": ..., "out_trade_no": ..., "money": ..., "account_show": ...}
    """
    account = (account or "").strip()
    if not account:
        raise ValueError("用法：/pay 注册邮箱对应的QQ号，例如：/pay 123456")
    if not (QQ_RE.match(account) or EMAIL_RE.match(account)):
        raise ValueError("请填写注册邮箱对应的 QQ 号（纯数字）。用法：/pay 123456")

    loop = asyncio.get_running_loop()
    data = await loop.run_in_executor(None, create_order, account, kook_user_id)

    if not data.get("ok"):
        # 账号未注册等业务提示由服务端返回
        raise ValueError(data.get("msg") or "创建充值链接失败，请稍后再试")
    return data