#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KOOK AI 问答机器人（WebSocket）
— 用户发送 "/ai 你的问题" → 调用 AI → 用卡片回复并 @提问者
"""

import asyncio
import json
import traceback

from khl import Bot, Message
from khl.card import CardMessage

from config import (
    BOT_TOKEN,
    COMMAND_NAME,
    MEMORY_ID_PREFIX,
    PAY_ALLOWED_CHANNEL_ID,
    PAY_CHANNEL_NAME,
    PAY_COMMAND_NAME,
    PAY_DEFAULT_MONEY,
    PAY_MONEY,
    PANEL_ALLOWED_CHANNEL_ID,
    PANEL_CHANNEL_NAME,
    PANEL_COMMAND_NAME,
    BIND_ALLOWED_CHANNEL_ID,
    BIND_CHANNEL_NAME,
    BIND_COMMAND_NAME,
    SETPAY_COMMAND_NAME,
    SPONSOR_POLL_INTERVAL,
)
from handlers.ai_handler import handle_ai
from handlers.pay_handler import handle_pay
from utils.card_utils import build_answer_card, build_pay_card
from utils.pay_client import (
    _dbg,
    bind_kook,
    create_panel,
    get_money,
    is_admin,
    mark_message,
    send_card,
    send_direct_card,
    set_money,
)
from utils.sponsor_poller import sponsor_poll_loop

bot = Bot(token=BOT_TOKEN)


def _memory_id(uid) -> str:
    """由 KOOK 用户 ID 生成该用户独立的记忆标识符（前缀 + 纯数字 ID）。"""
    return f"{MEMORY_ID_PREFIX}{uid}"


def _extract_question(content: str) -> str:
    """从 "/ai 问题..." 中取出 "/ai " 之后的部分。"""
    text = (content or "").strip()
    prefix = "/" + COMMAND_NAME
    if text.startswith(prefix):
        text = text[len(prefix):]
    return text.strip()


@bot.command(name=COMMAND_NAME)
async def cmd_ai(msg: Message, *args):
    uid = msg.author_id
    question = _extract_question(msg.content)

    if not question:
        card = build_answer_card(
            uid, "请在 /ai 后面写上你的问题，例如：/ai 你好", title="用法"
        )
        await msg.reply(CardMessage(card))
        return

    try:
        answer = await handle_ai(question, _memory_id(uid))
        card = build_answer_card(uid, answer, title="AI 回答")
    except Exception:
        card = build_answer_card(uid, traceback.format_exc()[-300:], title="出错了")

    await msg.reply(CardMessage(card))


@bot.command(name=PAY_COMMAND_NAME)
async def cmd_pay(msg: Message, *args):
    """
    自助赞助：/pay 注册邮箱对应的QQ号 → 返回 20 元支付宝充值短链。
    仅允许在 PAY_ALLOWED_CHANNEL_ID 指定的频道使用。
    """
    uid = msg.author_id

    # ① 频道限制
    try:
        channel = str(msg.ctx.channel.id)
    except Exception:
        channel = ""
    if PAY_ALLOWED_CHANNEL_ID and channel != str(PAY_ALLOWED_CHANNEL_ID):
        await msg.reply(CardMessage(build_pay_card(
            uid, f"请在 {PAY_CHANNEL_NAME} 频道里使用 /pay。", None, title="自助赞助")))
        return

    text = (msg.content or "").strip()
    prefix = "/" + PAY_COMMAND_NAME
    if text.startswith(prefix):
        text = text[len(prefix):]
    account = text.strip()

    loop = asyncio.get_running_loop()
    try:
        data = await handle_pay(account, uid, PAY_MONEY)
    except ValueError as e:
        # 用法 / 账号格式 / 账号未注册等业务提示
        await msg.reply(CardMessage(build_pay_card(uid, str(e), None, title="自助赞助")))
        return
    except Exception as e:
        await msg.reply(CardMessage(build_pay_card(uid, f"创建充值链接失败：{e}", None, title="出错了")))
        return

    minutes = max(1, int(data.get("expires_seconds", 300)) // 60)
    body = (
        f"已为账号 {data.get('account_show') or account} 创建赞助订单，金额 ¥{data.get('money') or PAY_MONEY}。\n"
        f"充值链接 {minutes} 分钟内有效，请用支付宝完成支付。\n"
        "支付成功后机器人会撤回本条消息，并在频道里 @你 确认赞助成功。"
    )
    card = build_pay_card(uid, body, data.get("url"), title="赞助 dekk")

    # ② 用 REST 发卡片，拿 msg_id 回写服务端（支付成功后要撤回这条）
    try:
        payload = json.dumps(CardMessage(card))
        card_msg_id = await loop.run_in_executor(None, send_card, channel, payload, str(msg.id))
        await loop.run_in_executor(
            None, mark_message, str(data.get("out_trade_no") or ""), channel, card_msg_id)
        _dbg('cmd_pay ok order=%s channel=%s msg=%s' % (data.get('out_trade_no'), channel, card_msg_id))
    except Exception as e:
        _dbg('cmd_pay 发卡片/回写失败: %s: %s' % (type(e).__name__, e))
        print("[赞助] 发卡片/回写消息id失败，回退为普通回复:", e)
        await msg.reply(CardMessage(card))


@bot.command(name=SETPAY_COMMAND_NAME)
async def cmd_setpay(msg: Message, *args):
    """
    /setpay 金额 —— 设定自助赞助金额（仅服务器主/管理员可用，默认 20）。

    不带金额时只显示当前金额。
    """
    uid = msg.author_id

    try:
        channel = str(msg.ctx.channel.id)
    except Exception:
        channel = ""
    if PAY_ALLOWED_CHANNEL_ID and channel != str(PAY_ALLOWED_CHANNEL_ID):
        await msg.reply(CardMessage(build_pay_card(
            uid, f"请在 {PAY_CHANNEL_NAME} 频道里使用 /setpay。",
            None, title="设定金额")))
        return

    text = (msg.content or "").strip()
    prefix = "/" + SETPAY_COMMAND_NAME
    if text.startswith(prefix):
        text = text[len(prefix):]
    money = text.strip()

    loop = asyncio.get_running_loop()

    # ① 权限：只有服务器主 / 管理员组可以用
    try:
        admin = await loop.run_in_executor(None, is_admin, uid)
    except Exception as e:
        _dbg('setpay 权限校验失败: %s: %s' % (type(e).__name__, e))
        admin = False
    if not admin:
        await msg.reply(CardMessage(build_pay_card(
            uid, "只有服务器管理员可以设定赞助金额。", None, title="设定金额")))
        return

    # ② 没带金额 → 显示当前值
    if not money:
        try:
            now = await loop.run_in_executor(None, get_money)
        except Exception:
            now = PAY_DEFAULT_MONEY
        await msg.reply(CardMessage(build_pay_card(
            uid, f"当前自助赞助金额：¥{now}\n用法：/setpay 30（最多两位小数，默认 {PAY_DEFAULT_MONEY}）",
            None, title="设定金额")))
        return

    # ③ 设定
    try:
        data = await loop.run_in_executor(None, set_money, money)
    except Exception as e:
        _dbg('setpay 失败: %s: %s' % (type(e).__name__, e))
        await msg.reply(CardMessage(build_pay_card(uid, f"设定失败：{e}", None, title="设定金额")))
        return
    if not data.get("ok"):
        await msg.reply(CardMessage(build_pay_card(
            uid, data.get("msg") or "设定失败", None, title="设定金额")))
        return

    _dbg('setpay ok money=%s by %s' % (data.get('money'), uid))
    await msg.reply(CardMessage(build_pay_card(
        uid, f"已把自助赞助金额设为 ¥{data.get('money')}（之后新建的订单都按这个金额）。",
        None, title="设定成功")))


@bot.command(name=PANEL_COMMAND_NAME)
async def cmd_panel(msg: Message, *args):
    """
    /panel —— 生成一个临时管理面板（1 小时有效，链接走私信）。

    仅服务器主 / 管理员可用；生成后私信用户，频道里回复「已生成临时面板，请在私信查看」。
    """
    uid = msg.author_id
    try:
        channel = str(msg.ctx.channel.id)
    except Exception:
        channel = ""
    loop = asyncio.get_running_loop()

    # ① 权限
    try:
        admin = await loop.run_in_executor(None, is_admin, uid)
    except Exception as e:
        _dbg('panel 权限校验失败: %s: %s' % (type(e).__name__, e))
        admin = False
    if not admin:
        await msg.reply(CardMessage(build_pay_card(
            uid, "只有服务器管理员可以使用 /panel。", None, title="临时面板")))
        return

    # ② 可选的频道限制（config.PANEL_ALLOWED_CHANNEL_ID 留空表示不限）
    if PANEL_ALLOWED_CHANNEL_ID and channel != str(PANEL_ALLOWED_CHANNEL_ID):
        await msg.reply(CardMessage(build_pay_card(
            uid, f"请在 {PANEL_CHANNEL_NAME} 频道里使用 /panel。", None, title="临时面板")))
        return

    # ③ KOOK 显示名（面板左上角显示的就是它）
    kook_name = ""
    try:
        kook_name = str(getattr(msg.author, 'nickname', '') or getattr(msg.author, 'username', '') or '')
    except Exception:
        kook_name = ""
    if not kook_name:
        kook_name = 'kook:' + str(uid)

    # ④ 创建面板
    try:
        data = await loop.run_in_executor(None, create_panel, uid, kook_name, channel)
    except Exception as e:
        _dbg('panel 创建失败: %s: %s' % (type(e).__name__, e))
        await msg.reply(CardMessage(build_pay_card(uid, f"生成面板失败：{e}", None, title="临时面板")))
        return
    if not data.get("ok"):
        await msg.reply(CardMessage(build_pay_card(
            uid, data.get("msg") or "生成面板失败", None, title="临时面板")))
        return

    url = str(data.get("url") or "")
    minutes = max(1, int(data.get("expires_in", 3600)) // 60)
    _dbg('panel ok user=%s(%s) channel=%s' % (uid, kook_name, channel))

    # ⑤ 私信卡片
    dm_card = build_pay_card(uid, f"你的临时管理面板已生成，{minutes} 分钟内有效。", url,
                             title="管理面板",
                             button_text="点此打开管理面板",
                             hint_text="如果点不开，请长按复制下面这条链接，到浏览器打开：")
    dm_ok = True
    try:
        await loop.run_in_executor(None, send_direct_card, uid, json.dumps(CardMessage(dm_card)))
    except Exception as e:
        dm_ok = False
        _dbg('panel 私信失败: %s: %s' % (type(e).__name__, e))

    # ⑥ 频道回复
    if dm_ok:
        await msg.reply(CardMessage(build_pay_card(
            uid, "已生成临时面板，请在私信查看。", None, title="临时面板")))
    else:
        # 用户可能关闭了私信 —— 直接把链接给出来，避免什么都拿不到
        await msg.reply(CardMessage(build_pay_card(
            uid, f"私信发送失败（可能你关闭了私信）。临时面板链接（{minutes} 分钟内有效）：",
            url, title="临时面板",
            button_text="点此打开管理面板",
            hint_text="如果点不开，请长按复制下面这条链接，到浏览器打开：")))


@bot.command(name=BIND_COMMAND_NAME)
async def cmd_code(msg: Message, *args):
    """
    /code XXXXXX —— 在「账号绑定」频道把 KOOK 账号绑定到 DEKK 账号。

    绑定码由模块生成（App 里弹窗给出），有效期 1 小时。
    """
    uid = msg.author_id
    try:
        channel = str(msg.ctx.channel.id)
    except Exception:
        channel = ""

    # ① 只允许在「账号绑定」频道使用
    if str(BIND_ALLOWED_CHANNEL_ID) and channel != str(BIND_ALLOWED_CHANNEL_ID):
        await msg.reply(CardMessage(build_pay_card(
            uid, f"请在 {BIND_CHANNEL_NAME} 频道里使用 /code。", None, title="账号绑定")))
        return

    # ② 取绑定码
    code = ""
    if args:
        code = str(args[0]).strip().upper()
    if not code:
        await msg.reply(CardMessage(build_pay_card(
            uid, "用法：/code 绑定码\n绑定码请到 App 里获取（打开模块时会提示）。",
            None, title="账号绑定")))
        return

    kook_name = ""
    try:
        kook_name = str(getattr(msg.author, 'nickname', '') or getattr(msg.author, 'username', '') or '')
    except Exception:
        kook_name = ""

    loop = asyncio.get_running_loop()
    try:
        data = await loop.run_in_executor(None, bind_kook, code, str(uid), kook_name)
    except Exception as e:
        _dbg('code 绑定失败: %s: %s' % (type(e).__name__, e))
        await msg.reply(CardMessage(build_pay_card(uid, f"绑定失败：{e}", None, title="账号绑定")))
        return

    if not data.get("ok"):
        await msg.reply(CardMessage(build_pay_card(
            uid, data.get("msg") or "绑定失败", None, title="账号绑定")))
        return

    u = data.get("dekk_user") or {}
    text = ("绑定成功！\n"
            + "账号名称：" + str(u.get("nickname", "")) + "\n"
            + "KOOK 账号：" + str(data.get("kook_name", "")) + "\n\n"
            + "回到 App 即可正常使用全部功能。")
    await msg.reply(CardMessage(build_pay_card(uid, text, None, title="账号绑定")))
    _dbg('code 绑定成功 kook=%s dekk=%s' % (uid, u.get("id")))


if __name__ == "__main__":
    print("KOOK AI 机器人启动中...")
    # Python 3.12+ 主线程不再自动创建事件循环，而 khl.py 的 bot.run() 内部
    # 调用的是 asyncio.get_event_loop()，3.14 下会抛 RuntimeError。
    # 这里先手动创建并绑定一个事件循环，保证 bot.run() 能取到。
    try:
        asyncio.get_event_loop()
    except RuntimeError:
        asyncio.set_event_loop(asyncio.new_event_loop())

    # 自助赞助：身份发放轮询（每 SPONSOR_POLL_INTERVAL 秒检查「已支付未发身份」的订单）
    # 放在 bot.run() 之前用同一个事件循环启动，任务会在 loop 运行后自动执行。
    asyncio.get_event_loop().create_task(sponsor_poll_loop())
    print(f"赞助身份轮询已启动（每 {SPONSOR_POLL_INTERVAL} 秒一次）")

    bot.run()