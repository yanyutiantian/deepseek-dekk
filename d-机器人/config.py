#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KOOK AI 问答机器人配置
"""

# ── KOOK Bot Token（WebSocket）──
BOT_TOKEN = "YOUR_KOOK_BOT_TOKEN"

# ── 指令 ──
# 用户发送 "/ai 你的问题"，即触发；问题取 "/ai " 之后的部分。
COMMAND_NAME = "ai"

# ── 人设卡提示词 ──
# 目前留空（空白提示词），机器人只负责回答问题。
# 后续把「教程提示词」填在这里即可，会自动拼接在用户问题前面一起发给 AI。
PERSONA_PROMPT = "现在开始，你是DEKK机器人，你的用处是回答用户问题，以下是常见问题：在哪下载？官网在浏览器搜索dekk.example.com；有安卓15和安卓16该下载哪个？打开手机设置-关于手机-版本信息，在这里查看Android版本，数字在15或者15以下，下载安卓15，同理以上下载安卓16；下载安卓15的闪退怎么办？下载安卓16的；下载安卓16的打开FPA后，没有DeepSeek怎么办？1. 可能你没下载安卓16压缩包中的DeepSeek 2. 可能未注入DeepSeek；打开FPA选择了DeepSeek的作用域，但是没有模块怎么办？在官网下载安卓16的压缩包，解压之后安装dekk.apk；下载安卓16的闪退怎么办？切换注入方案，视频链接https://dekk.example.com/help.mp4；DeepSeek可以正常打开，作用域也勾选了dekk，但是没显示模块设置键怎么办？1. 重启DeepSeek 2. 切换注入方案，然后重启DeepSeek；为什么我注入了DeepSeek还是闪退？1. 如果你是把官网中安卓16压缩包中的DeepSeek重新注入了一遍，会造成闪退 2. 切换另一个注入方案；怎么更新dekk？1. 如果下载的是安卓15的，只需要在官网重新下载安卓15的安装包，覆盖安装即可，不要卸载DeepSeek 2. 如果下载的是安卓16的，只需要在官网重新下载安卓16的压缩包，覆盖安装dekk.apk，然后重启DeepSeek就行；DeepSeek要下载什么版本？下载2.5.1；为什么明明注入好了，也重启了，换了各种方案还是无法使用dekk？打开DeepSeek设置，查看版本是不是2.5.1；在哪儿下载DeepSeek2.5.1版本？打开官网，点击DeepSeek下载，选择普通下载，不用选择安全下载，点击安全下载会下载豌豆夹；IOS系统支持吗？支持；dekk能不能和ilke、DeekSeep兼容？不能；使用dekk需不需要root？有没有root都可使用；LSP框架如何加载dekk？打开LSP框架，点击dekk的作用域，会默认推荐DeepSeek，随后重启DeepSeek；在境外地区无法打开官网，是为什么？如果你在香港或台湾，可能会因为网关屏蔽，导致无法访问；在官网下载安装包报毒怎么办？因为修改了DeepSeek软件，签名不一致，手机厂商在安装时会报毒；因为报毒无法安装怎么办？打开飞行模式，安装完之后再关闭；提示解析包错误怎么办？重新下载压缩包或安装包，大概率是下载过程文件损坏；支持安卓17吗？暂未适配安卓17，可能出现闪退，模块无法加载；华为兼容吗？在华为的某些设备上可以使用，某些设备将无法使用；OPPO兼容吗？在OPPO的某些设备上可以使用，某些设备将无法使用；小米兼容吗？在小米的某些设备上可以使用，某些设备将无法使用；一加兼容吗？在一加的某些设备上可以使用，某些设备将无法使用；荣耀兼容吗？在荣耀的某些设备上可以使用，某些设备将无法使用；vivo兼容吗？在vivo的某些设备上可以使用，某些设备将无法使用；iQOO兼容吗？在iQOO的某些设备上可以使用，某些设备将无法使用；realme真我兼容吗？在realme真我某些设备上可以使用，某些设备将无法使用；Redmi红米兼容吗？在Redmi红米某些设备上可以使用，某些设备将无法使用；魅族兼容吗？在魅族的某些设备上可以使用，某些设备将无法使用。对用户的问题给予回答，并帮助用户，需要让用户告诉你安卓版本，手机型号，如果安卓15以下的用户使用nplsp框架，产生闪退，让用户不勾选注入加载器DEX，如注入时报错，告诉用户不可以使用中文目录，清除nplsp框架数据即可，用户说无法安装就让他打开手机自带的文件管理，找到选择的目录，里面有安装包安装，FPA框架，每次注入都要求是从豌豆荚下载的2.5.1未注入的版本，一个安装包多次注入，肯定会造成闪退，IOS砍掉了很多功能，如果你实在无法回答，请告诉这位用户，询问管理员。"

# ── AI 问答接口（OpenAI 风格）──
# 必需参数：apikey / model / qq / msg
# 返回正文在 choices[0].message.content
AI_ENDPOINT = "https://your-review-api.example.com/v1/chat/completions"
AI_APIKEY = "YOUR_AI_API_KEY"
AI_MODEL = "your-model-name"
# 调用方标识（记忆标识符）兜底值：仅当拿不到用户 ID 时才使用。
AI_QQ = "10000"
AI_TIMEOUT = 60  # 秒

# ── 记忆标识符 ──
# 接口的 qq 参数用于区分每个用户的记忆。
# 最终值 = 前缀 + KOOK 用户 ID（author_id，纯数字），例如 kook_123456789，
# 只含英文和数字，且每个用户各自独立、互不串号。
MEMORY_ID_PREFIX = "kook_"

# ── 卡片 ──
# AI 正文最大长度，超出截断（KOOK 卡片文本有限制）
CARD_MAX_LEN = 3800
# 是否转义 KMD 特殊字符（AI 正文里常出现括号/方括号，不转义可能被当成卡片语法）
ESCAPE_KMD = True

# ── 自助赞助（/pay 你的dekk注册邮箱）──
# 流程：机器人校验邮箱 → 调 pay.example.com 建单 → 返回 20 元支付宝充值链接
#       → 用户支付成功 → pay 站点把 dekk 账号标记为赞助用户
#       → 机器人轮询到「已支付未发身份」的订单 → 给发起者加「赞助用户」身份
PAY_COMMAND_NAME = "pay"
PAY_API_BASE = "https://pay.example.com"              # 赞助站点（web 侧）
PAY_INTERNAL_KEY = "YOUR_INTERNAL_KEY"     # 必须与 pay 站点 config.php 的 internal_key 一致
PAY_MONEY = "20.00"                               # 仅用于展示，实际金额由服务端决定
PAY_TIMEOUT = 20                                  # 调用 web 接口的超时（秒）

# 只允许在这个 KOOK 频道里使用 /pay（频道 ID，留空 = 不限制）
PAY_ALLOWED_CHANNEL_ID = ""       # #自助赞助
# 提示文案里显示的频道名（仅用于给用户看，改频道名时同步改这里即可）
PAY_CHANNEL_NAME = "#自助赞助💲"

# ── KOOK 身份 ──
KOOK_API_BASE = "https://www.kookapp.cn/api/v3"
KOOK_GUILD_ID = "0000000000000000"                # DEKK频道交流
KOOK_SPONSOR_ROLE_ID = 0                   # 「赞助用户」角色 id
KOOK_ADMIN_ROLE_ID = 0                     # 「管理员」角色 id（/setpay 权限判断）
SPONSOR_POLL_INTERVAL = 15                        # 轮询「待发身份」订单的间隔（秒）

# ── /setpay（仅管理员可改自助赞助金额）──
SETPAY_COMMAND_NAME = "setpay"
PAY_DEFAULT_MONEY = "20.00"

# ── /panel（管理员临时管理面板，1 小时有效）──
PANEL_COMMAND_NAME = "panel"
# 面板站点地址（放在 pay.example.com，因为 dekk.example.com 前面有雷池 WAF 会拦机器人请求）
PANEL_API_BASE = "https://pay.example.com/panel"
PANEL_INTERNAL_KEY = "YOUR_PANEL_INTERNAL_KEY"
# 允许申请面板的频道 ID（管理员频道）；留空 = 不限频道（仅要求是管理员）
PANEL_ALLOWED_CHANNEL_ID = ""
# 提示文案里显示的频道名（不要写数字 ID）
PANEL_CHANNEL_NAME = "#管理员频道"

# ── /code（KOOK 账号绑定，仅在「账号绑定」频道可用）──
BIND_COMMAND_NAME = "code"
BIND_ALLOWED_CHANNEL_ID = ""
BIND_CHANNEL_NAME = "#账号绑定"
BIND_INVITE = "https://kook.vip/YOUR_INVITE"

# ── 赞助站点根地址（code.php 就在这里；panel 在 /panel/ 下）──
PAY_SITE_BASE = "https://pay.example.com"