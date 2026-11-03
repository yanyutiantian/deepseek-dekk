import traceback
import asyncio
import requests as req
from io import BytesIO

from khl import Bot, Message, PublicMessage
from khl._types import MessageTypes
from khl.card import Card, CardMessage, Module, Element, Types as CardTypes

from config import BOT_TOKEN
from handlers.ping_handler import handle_ping
from handlers.ssl_handler import handle_ssl
from handlers.whois_handler import handle_whois
from handlers.ip_handler import handle_ip
from handlers.http_handler import handle_http
from handlers.https_handler import handle_https
from handlers.github_handler import handle_github
from handlers.dns_handler import handle_dns
from handlers.xt_handler import handle_xt
from handlers.tcp_handler import handle_tcp
from handlers.port_handler import handle_port
from handlers.webping_handler import handle_webping
from handlers.ssh_handler import handle_ssh
from handlers.tr_handler import handle_tr
from handlers.bgp_handler import handle_bgp

bot = Bot(token=BOT_TOKEN)

POEM_API = "https://api.tangdouz.com/a/hotpoet.php"

# 所有指令列表（用于 /help）
HELP_TEXT = (
    "支持的命令:\n"
    "/ping /ssl /whois /ip /http /https /github\n"
    "/dns /xt /tcp /port /webping /ssh /tr /bgp"
)


# ──────── 卡片构建 ────────

def _text_to_card(text: str, footer: str = "") -> Card:
    """将返回文本转为 KOOK 卡片。首行作为标题，其余作为内容，可附加底部古诗。"""
    lines = text.split("\n")
    title = lines[0].strip() if lines else "结果"
    body_lines = []
    for l in lines[1:]:
        stripped = l.strip()
        if stripped in ("---", "-" * 35, "-" * 40, "-" * 30, ""):
            continue
        body_lines.append(l)
    body = "\n".join(body_lines) if body_lines else "无内容"

    if len(body) > 3800:
        body = body[:3750] + "\n\n[内容过长，已截断]"

    modules = [
        Module.Header(title),
        Module.Divider(),
        Module.Section(Element.Text(body, type=CardTypes.Text.KMD)),
    ]
    if footer:
        modules.append(Module.Divider())
        modules.append(Module.Context(Element.Text(footer, type=CardTypes.Text.KMD)))

    return Card(*modules)


def _err_card(err_msg: str) -> Card:
    return Card(
        Module.Header("内部错误"),
        Module.Divider(),
        Module.Section(Element.Text(err_msg, type=CardTypes.Text.KMD)),
    )


async def _fetch_poem() -> str:
    """获取随机古诗，失败返回空字符串。"""
    try:
        loop = asyncio.get_event_loop()
        resp = await loop.run_in_executor(
            None,
            lambda: req.get(POEM_API, timeout=5,
                            headers={"User-Agent": "Mozilla/5.0"}),
        )
        if resp.status_code == 200:
            return resp.text.strip()[:100]
    except Exception:
        pass
    return ""


# ──────── 指令注册 ────────

@bot.command(name="ping")
async def cmd_ping(msg: Message, target: str = ""):
    await _text_cmd(msg, lambda: handle_ping(target))

@bot.command(name="ssl")
async def cmd_ssl(msg: Message, domain: str = ""):
    await _text_cmd(msg, lambda: handle_ssl(domain))

@bot.command(name="whois")
async def cmd_whois(msg: Message, domain: str = ""):
    await _text_cmd(msg, lambda: handle_whois(domain))

@bot.command(name="ip")
async def cmd_ip(msg: Message, target: str = ""):
    await _text_cmd(msg, lambda: handle_ip(target))

@bot.command(name="http")
async def cmd_http(msg: Message, target: str = ""):
    await _img_cmd(msg, lambda: handle_http(target))

@bot.command(name="https")
async def cmd_https(msg: Message, target: str = ""):
    await _img_cmd(msg, lambda: handle_https(target))

@bot.command(name="github")
async def cmd_github(msg: Message, repo: str = ""):
    await _img_cmd(msg, lambda: handle_github(repo))

@bot.command(name="dns")
async def cmd_dns(msg: Message, domain: str = ""):
    await _text_cmd(msg, lambda: handle_dns(domain))

@bot.command(name="xt")
async def cmd_xt(msg: Message):
    await _text_cmd(msg, handle_xt)

@bot.command(name="tcp")
async def cmd_tcp(msg: Message, target: str = ""):
    await _text_cmd(msg, lambda: handle_tcp(target))

@bot.command(name="port")
async def cmd_port(msg: Message, ip: str = ""):
    await _text_cmd(msg, lambda: handle_port(ip))

@bot.command(name="webping")
async def cmd_webping(msg: Message, target: str = ""):
    await _text_cmd(msg, lambda: handle_webping(target))

@bot.command(name="ssh")
async def cmd_ssh(msg: Message, command: str = ""):
    await _text_cmd(msg, lambda: handle_ssh(command))

@bot.command(name="tr")
async def cmd_tr(msg: Message, target: str = ""):
    await _text_cmd(msg, lambda: handle_tr(target))

@bot.command(name="bgp")
async def cmd_bgp(msg: Message, target: str = ""):
    await _text_cmd(msg, lambda: handle_bgp(target))

@bot.command(name="help")
async def cmd_help(msg: Message):
    poem = await _fetch_poem()
    modules = [
        Module.Header("烟雨Bot"),
        Module.Divider(),
        Module.Section(Element.Text(HELP_TEXT, type=CardTypes.Text.KMD)),
        Module.Divider(),
        Module.Context(Element.Text("共 16 个命令", type=CardTypes.Text.KMD)),
    ]
    if poem:
        modules.append(Module.Context(Element.Text(poem, type=CardTypes.Text.KMD)))
    card = Card(*modules)
    await msg.reply(CardMessage(card))


# ──────── 发送逻辑 ────────

async def _text_cmd(msg: Message, handler):
    try:
        r, poem = await asyncio.gather(handler(), _fetch_poem())
        text = r if isinstance(r, str) else str(r)
        card = _text_to_card(text, footer=poem)
        await msg.reply(CardMessage(card))
    except Exception:
        card = _err_card(traceback.format_exc()[-300:])
        await msg.reply(CardMessage(card))


async def _img_cmd(msg: Message, handler):
    """图片类指令：文字用卡片，图片用 IMG 类型独立发送"""
    try:
        r = await handler()
    except Exception:
        card = _err_card(traceback.format_exc()[-300:])
        await msg.reply(CardMessage(card))
        return

    poem = await _fetch_poem()

    if isinstance(r, str):
        await msg.reply(CardMessage(_text_to_card(r, footer=poem)))
        return

    if isinstance(r, dict):
        t = r.get("type", "text")
        if t == "text":
            await msg.reply(CardMessage(_text_to_card(r.get("content", ""), footer=poem)))
        elif t == "image":
            caption = r.get("caption", "")
            img_url = r.get("image_url", "")
            await _send_image(msg, caption, img_url, poem)
        elif t == "mixed":
            text = r.get("text", "")
            img_url = r.get("image_url", "")
            if text:
                await msg.reply(CardMessage(_text_to_card(text, footer=poem)))
            if img_url:
                await _send_image(msg, "", img_url, poem)
        return

    await msg.reply(CardMessage(_text_to_card(str(r), footer=poem)))


async def _send_image(msg: Message, caption: str, img_url: str, poem: str = ""):
    """下载远程图片→上传KOOK→用 type=IMG 发送"""
    if not img_url:
        return

    try:
        resp = req.get(img_url, timeout=20,
                       headers={"User-Agent": "Mozilla/5.0"})
        if resp.status_code != 200:
            card = Card(
                Module.Header("图片加载失败"),
                Module.Section(Element.Text(f"HTTP {resp.status_code}", type=CardTypes.Text.KMD)),
            )
            await msg.reply(CardMessage(card))
            return

        asset_url = await bot.client.create_asset(BytesIO(resp.content))
        await msg.ctx.channel.send(asset_url, type=MessageTypes.IMG)

    except Exception as e:
        card = Card(
            Module.Header("图片上传失败"),
            Module.Section(Element.Text(str(e)[:500], type=CardTypes.Text.KMD)),
        )
        await msg.reply(CardMessage(card))


# ──────── 启动 ────────

if __name__ == "__main__":
    print("KOOK Bot 启动中...")
    bot.run()