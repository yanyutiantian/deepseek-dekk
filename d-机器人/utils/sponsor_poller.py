#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
身份发放轮询器
— 每 SPONSOR_POLL_INTERVAL 秒问一次 pay 站点：有没有「已支付但还没发身份」的订单
— 有的话就给对应的 KOOK 用户加「赞助用户」身份，并回执给服务端

放在机器人同一个事件循环里跑；KOOK 的 Bot Token 只在机器人侧使用，不落在 web 侧。
"""

import asyncio
import traceback

from config import SPONSOR_POLL_INTERVAL
from utils.pay_client import (
    _dbg,
    delete_message,
    fetch_pending,
    grant_sponsor_role,
    report_role,
    send_mention,
)


async def sponsor_poll_loop() -> None:
    """常驻轮询循环（异常不影响下一轮）。"""
    # 启动后先缓一下，避免和机器人首连抢资源
    await asyncio.sleep(8)
    _dbg('赞助轮询已启动，间隔=%s 秒' % SPONSOR_POLL_INTERVAL)
    while True:
        try:
            await poll_once()
        except Exception:
            print("[赞助] 轮询异常:", traceback.format_exc()[-500:])
            _dbg('轮询异常: %s' % traceback.format_exc()[-300:])
        await asyncio.sleep(SPONSOR_POLL_INTERVAL)


async def poll_once() -> None:
    """
    处理一轮「已支付未发身份」的订单：
      1) 给发起者加「赞助用户」身份
      2) 撤回机器人之前发的那条充值链接卡片
      3) 在频道里 @用户，告诉他赞助成功
      4) 回执给服务端（失败则下轮重试）
    """
    loop = asyncio.get_running_loop()
    items = await loop.run_in_executor(None, fetch_pending)
    if items:
        _dbg('轮询到 %d 单待发身份' % len(items))

    for item in items:
        if not isinstance(item, dict):
            continue
        out_trade_no = item.get("out_trade_no")
        kook_user_id = item.get("kook_user_id")
        channel_id = item.get("channel_id") or ""
        message_id = item.get("message_id") or ""
        if not out_trade_no or not kook_user_id:
            continue
        try:
            # 1) 发身份
            await loop.run_in_executor(None, grant_sponsor_role, kook_user_id)
            # 2) 撤回那条充值链接卡片（撤回失败不影响后续）
            if message_id:
                await loop.run_in_executor(None, delete_message, message_id)
            # 3) 频道里 @用户告知成功
            if channel_id:
                await loop.run_in_executor(
                    None, send_mention, channel_id, kook_user_id,
                    "赞助成功，感谢你的支持！已为你的 dekk 账号升级为赞助用户，"
                    "并发放了频道「赞助用户」身份。",
                )
            # 4) 回执
            await loop.run_in_executor(None, report_role, out_trade_no, True, "")
            print(f"[赞助] 已完成：order={out_trade_no} kook={kook_user_id} 撤回={bool(message_id)}")
        except Exception as e:
            # 不标记已发放，下一轮继续重试
            await loop.run_in_executor(None, report_role, out_trade_no, False, str(e))
            print(f"[赞助] 发放身份失败：order={out_trade_no} err={e}")