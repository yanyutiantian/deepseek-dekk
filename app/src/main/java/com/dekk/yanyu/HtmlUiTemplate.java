package com.dekk.yanyu;

/**
 * 默认 HTML 模板：仿苹果「信息」的聊天气泡界面。
 *
 * <p>模板契约（{@link HtmlUiRenderer} 会替换这些占位符）：
 * <ul>
 * <li>{@code {{messages}}}：消息数组的 JSON，形如 {@code [{"role":"user","text":"..."}]}</li>
 * <li>{@code {{avatar}}}：美化头像的 data URI（未设置时为空串）</li>
 * <li>{@code {{wallpaper}}}：聊天壁纸的 data URI（未设置时为空串）</li>
 * <li>{@code {{wallpaper_css}}}：可直接塞进 CSS 的 {@code url("...")} 或 {@code none}</li>
 * </ul>
 * 模板里只要实现 {@code window.render(messages)}，渲染时就会被调用。
 */
final class HtmlUiTemplate {

    static final String DEFAULT =
        "<!doctype html>\n" +
        "<html>\n" +
        "<head>\n" +
        "<meta charset=\"utf-8\">\n" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">\n" +
        "<style>\n" +
        ":root{--bubble-in:#FFFFFF;--bubble-out:#0B84FF;--text-in:#111111;--text-out:#FFFFFF;}\n" +
        "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent;}\n" +
        "html,body{margin:0;height:100%;overflow:hidden;background:transparent;}\n" +
        "body{font:16px/1.42 -apple-system,\"SF Pro Text\",\"Helvetica Neue\",\"PingFang SC\",system-ui,sans-serif;\n" +
        "  color:var(--text-in);box-sizing:border-box;\n" +
        "  /* 渲染层是全屏铺满的，用这两个变量避开宿主标题栏与输入区 */\n" +
        "  padding:var(--dekk-top-inset,80px) 0 var(--dekk-composer-inset,132px);}\n" +
        "/* 背景只画在「标题栏以下、输入区以上」的范围里，其余部分保持透明（输入框才不会看不见） */\n" +
        "body::before{content:'';position:fixed;left:0;right:0;top:var(--dekk-top-inset,80px);\n" +
        "  bottom:var(--dekk-composer-inset,132px);z-index:-2;\n" +
        "  background:#F2F2F7 {{wallpaper_css}} center/cover no-repeat;}\n" +
        ".app{display:flex;flex-direction:column;height:100%;}\n" +
        ".bar{position:sticky;top:0;z-index:3;display:flex;align-items:center;justify-content:center;gap:8px;\n" +
        "  padding:10px 16px;background:rgba(249,249,249,.88);backdrop-filter:blur(14px);\n" +
        "  border-bottom:.5px solid rgba(0,0,0,.12);}\n" +
        ".bar img,.bar .ph{width:30px;height:30px;border-radius:50%;object-fit:cover;background:#C7C7CC;}\n" +
        ".bar .name{font-weight:600;font-size:15px;}\n" +
        ".list{flex:1;overflow-y:auto;padding:14px 12px 28px;display:flex;flex-direction:column;}\n" +
        ".row{display:flex;margin-top:6px;}\n" +
        ".row.out{justify-content:flex-end;}\n" +
        ".row.first{margin-top:14px;}\n" +
        ".bubble{max-width:78%;padding:9px 14px;border-radius:19px;white-space:pre-wrap;word-break:break-word;\n" +
        "  box-shadow:0 1px 1px rgba(0,0,0,.05);}\n" +
        ".row.in .bubble{background:var(--bubble-in);color:var(--text-in);border-bottom-left-radius:6px;}\n" +
        ".row.out .bubble{background:var(--bubble-out);color:var(--text-out);border-bottom-right-radius:6px;}\n" +
        ".time{text-align:center;color:#8E8E93;font-size:12px;margin:16px 0 2px;}\n" +
        ".empty{color:#8E8E93;text-align:center;margin-top:48px;font-size:14px;}\n" +
        "</style>\n" +
        "</head>\n" +
        "<body>\n" +
        "<div class=\"app\">\n" +
        "  <div class=\"bar\"><img id=\"avatar\" alt=\"\"><div class=\"name\" id=\"title\">DeepSeek</div></div>\n" +
        "  <div class=\"list\" id=\"list\"></div>\n" +
        "</div>\n" +
        "<script>\n" +
        "// 正文统一用 textContent 插入，天然转义，不需要拼 innerHTML\n" +
        "window.render=function(messages){\n" +
        "  var list=document.getElementById('list');list.innerHTML='';\n" +
        "  if(!messages||!messages.length){list.innerHTML='<div class=\"empty\">还没有消息</div>';return;}\n" +
        "  var head=document.createElement('div');head.className='time';head.textContent='聊天记录';\n" +
        "  list.appendChild(head);\n" +
        "  var lastRole=null;\n" +
        "  for(var i=0;i<messages.length;i++){\n" +
        "    var m=messages[i]||{};var role=(m.role==='user')?'out':'in';\n" +
        "    var row=document.createElement('div');row.className='row '+role+(role===lastRole?'':' first');\n" +
        "    var bubble=document.createElement('div');bubble.className='bubble';\n" +
        "    bubble.textContent=(m.text==null?'':m.text);\n" +
        "    row.appendChild(bubble);list.appendChild(row);lastRole=role;\n" +
        "  }\n" +
        "  list.scrollTop=list.scrollHeight;\n" +
        "};\n" +
        "(function(){\n" +
        "  var src='{{avatar}}';var img=document.getElementById('avatar');\n" +
        "  if(src){img.src=src;}else{img.className='ph';}\n" +
        "})();\n" +
        "</script>\n" +
        "</body>\n" +
        "</html>\n";

    private HtmlUiTemplate() { }
}