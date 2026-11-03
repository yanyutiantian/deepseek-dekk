<?php
declare(strict_types=1);

/**
 * 临时管理面板页面（宝塔风格，已适配手机端）。
 *
 * 鉴权：URL 带 ?t=<48位token>，1 小时有效；过期后接口返回 401，页面提示失效。
 * 左上角显示申请面板的 KOOK 账号名；右上角（手机端为「三条杠」菜单）是功能导航。
 */
require __DIR__ . '/lib.php';

$panel = panel_token(true);
$actor = panel_actor($panel);
$token = (string) ($_GET['t'] ?? '');
$expiresAt = (string) $panel['expires_at'];
$serverNow = date('Y-m-d H:i:s');
$remain = max(0, strtotime($expiresAt) - strtotime($serverNow));
?>
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>临时管理面板 · DEKK</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#f0f2f5;color:#2b2f38;font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}

/* ── 顶栏 ── */
.top{position:sticky;top:0;z-index:20;background:#23262e;color:#e8eaed}
.top-inner{display:flex;align-items:center;gap:10px;padding:0 14px;height:54px}
.burger{display:none;background:transparent;border:1px solid #3a3f4b;color:#e8eaed;border-radius:8px;
    width:38px;height:38px;font-size:17px;line-height:1;cursor:pointer;flex:0 0 auto}
.logo{font-weight:700;font-size:15px;letter-spacing:.5px;color:#20a53a;white-space:nowrap}
.who{display:flex;align-items:center;gap:6px;background:#2e323c;border:1px solid #3a3f4b;border-radius:14px;
    padding:3px 12px;font-size:12.5px;color:#c8cdd6;white-space:nowrap;max-width:300px;overflow:hidden;text-overflow:ellipsis}
.who b{color:#fff;font-weight:600}
.spacer{flex:1 1 auto}
.nav-wrap{display:flex;align-items:center;gap:6px}
.nav{background:transparent;border:1px solid #3a3f4b;color:#c8cdd6;border-radius:8px;padding:7px 14px;
    cursor:pointer;font-size:13px;transition:.15s;white-space:nowrap}
.nav:hover{background:#2e323c;color:#fff}
.nav.on{background:#20a53a;border-color:#20a53a;color:#fff}

/* ── 主体 ── */
.wrap{max-width:1180px;margin:18px auto;padding:0 16px}
.card{background:#fff;border-radius:10px;box-shadow:0 2px 10px rgba(0,0,0,.06);padding:16px}
.bar{display:flex;align-items:center;gap:10px;margin-bottom:12px;flex-wrap:wrap}
input[type=text],input[type=password],select{border:1px solid #d5d9e0;border-radius:8px;padding:9px 12px;
    font-size:14px;outline:none;background:#fff;min-width:220px;font-family:inherit}
input:focus,select:focus{border-color:#20a53a;box-shadow:0 0 0 2px rgba(32,165,58,.12)}
.btn{background:#20a53a;border:1px solid #20a53a;color:#fff;border-radius:8px;padding:9px 16px;
    cursor:pointer;font-size:13px;font-family:inherit}
.btn:hover{filter:brightness(1.05)}
.btn.ghost{background:#fff;color:#4a5260;border-color:#d5d9e0}
.btn.danger{background:#e34d59;border-color:#e34d59}
.btn:disabled{opacity:.55;cursor:not-allowed}

.table-scroll{overflow-x:auto;-webkit-overflow-scrolling:touch}
table{width:100%;border-collapse:collapse;font-size:13px;min-width:640px}
th,td{padding:10px 12px;text-align:left;border-bottom:1px solid #eef0f4;vertical-align:middle}
th{background:#fafbfc;color:#6b7280;font-weight:600;font-size:12.5px;white-space:nowrap}
tr.row{cursor:pointer}
tr.row:hover{background:#f6fbf7}
.tag{display:inline-block;padding:1px 8px;border-radius:10px;font-size:11.5px;border:1px solid;white-space:nowrap}
.tag.sp{color:#b8860b;border-color:#f0dcae;background:#fffaf0}
.tag.ban{color:#e34d59;border-color:#f7c9cd;background:#fef3f4}
.tag.gray{color:#6b7280;border-color:#e0e3e9;background:#f7f8fa}
.pager{display:flex;align-items:center;gap:10px;margin-top:12px;font-size:13px;color:#6b7280;flex-wrap:wrap}

/* ── 抽屉 ── */
.mask{position:fixed;inset:0;background:rgba(20,22,26,.45);display:none;z-index:30}
.mask.on{display:block}
.drawer{position:fixed;top:0;right:0;height:100%;width:min(560px,94vw);background:#fff;
    box-shadow:-6px 0 24px rgba(0,0,0,.16);transform:translateX(100%);transition:.22s;z-index:31;overflow:auto}
.drawer.on{transform:none}
.drawer .hd{position:sticky;top:0;background:#fff;border-bottom:1px solid #eef0f4;padding:14px 18px;
    display:flex;justify-content:space-between;align-items:center;gap:10px}
.drawer .hd h3{margin:0;font-size:15px}
.drawer .bd{padding:16px 18px 40px}
.kv{margin-bottom:14px}
.kv .k{color:#6b7280;font-size:12.5px;margin-bottom:4px}
.kv .v{font-size:13.5px;word-break:break-word;white-space:pre-wrap}
details{border:1px solid #eef0f4;border-radius:8px;padding:8px 12px;background:#fafbfc}
details summary{cursor:pointer;color:#4a5260;font-size:13px;outline:none}
details .txt{margin-top:8px;max-height:320px;overflow:auto;font-size:13px;white-space:pre-wrap;color:#333}
.hr{height:1px;background:#eef0f4;margin:16px 0}
.act{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.note{color:#6b7280;font-size:12.5px}
.toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:#23262e;color:#fff;
    padding:10px 18px;border-radius:8px;font-size:13px;opacity:0;transition:.25s;z-index:60;max-width:92vw;text-align:center}
.toast.on{opacity:1}
.warn{color:#e34d59}

/* ── 评论 ── */
.cmt{border:1px solid #eef0f4;border-radius:8px;padding:10px 12px;margin-bottom:8px;background:#fff}
.cmt.pin{border-color:#f0dcae;background:#fffdf7}
.cmt-hd{display:flex;align-items:center;gap:6px;flex-wrap:wrap;font-size:13px}
.cmt-tx{font-size:13.5px;margin:3px 0 6px;white-space:pre-wrap;word-break:break-word}
.cmt-rep{margin-top:6px;padding-left:10px;border-left:2px solid #eef0f4}
.cmt-rep-item{font-size:12.5px;color:#4a5260;margin-bottom:5px;white-space:pre-wrap;word-break:break-word}
.cmt .act{margin-top:6px}
.cmt .btn{padding:4px 10px;font-size:12.5px;width:auto}

/* ── 手机端 ── */
@media (max-width:768px){
    .burger{display:block}
    .top-inner{height:auto;flex-wrap:wrap;padding:8px 12px;gap:8px}
    .who{max-width:none;flex:1 1 auto;order:3;width:100%;justify-content:space-between}
    .logo{font-size:14px;flex:1 1 auto}
    /* 导航折叠进汉堡菜单 */
    .nav-wrap{display:none;order:4;width:100%;flex-direction:column;align-items:stretch;gap:8px;padding-bottom:8px}
    .nav-wrap.open{display:flex}
    .nav{width:100%;text-align:left;padding:11px 14px;font-size:14px}
    .wrap{margin:10px auto;padding:0 10px}
    .card{padding:12px;border-radius:8px}
    .bar{flex-direction:column;align-items:stretch;gap:8px}
    input[type=text],input[type=password],select{min-width:0;width:100%;font-size:15px}
    .btn{width:100%;padding:11px}
    .pager .btn{width:auto}
    table{min-width:560px;font-size:12.5px}
    th,td{padding:8px 10px}
    .drawer{width:100%;max-width:100%}
    .act{flex-direction:column;align-items:stretch}
}
</style>
</head>
<body>

<div class="top">
    <div class="top-inner">
        <button class="burger" id="burger" aria-label="菜单">☰</button>
        <span class="logo">DEKK 临时面板</span>
        <span class="spacer"></span>
        <span class="who">👤 <b><?= htmlspecialchars($actor, ENT_QUOTES, 'UTF-8') ?></b>
            <span class="note" id="clock">剩余 --:--</span></span>
        <div class="nav-wrap" id="navWrap">
            <button class="nav on" data-tab="personas">人设管理</button>
            <button class="nav" data-tab="plugins">插件管理</button>
            <button class="nav" data-tab="users">用户管理</button>
            <button class="nav" data-tab="create">账号创建</button>
            <button class="nav" id="reload">刷新</button>
        </div>
    </div>
</div>

<div class="wrap">
    <div class="card" id="mainCard">
        <div class="bar">
            <input type="text" id="q" placeholder="输入关键字实时搜索…">
            <span class="note" id="hint"></span>
        </div>
        <div class="table-scroll"><div id="tableBox"></div></div>
        <div class="pager" id="pagerBox"></div>
    </div>

    <div class="card" id="createCard" style="display:none">
        <div class="bar"><b>创建新账号</b><span class="note">直接写入数据库（邮箱仅支持数字 QQ 邮箱）</span></div>
        <div class="bar"><input type="text" id="cEmail" placeholder="邮箱，例如 123456@qq.com"></div>
        <div class="bar"><input type="text" id="cNick" placeholder="昵称"></div>
        <div class="bar"><input type="password" id="cPwd" placeholder="密码（8~12 位）"></div>
        <div class="bar"><button class="btn" id="doCreate">创建账号</button></div>
    </div>
</div>

<div class="mask" id="mask"></div>
<div class="drawer" id="drawer">
    <div class="hd"><h3 id="dTitle">详情</h3><button class="btn ghost" id="dClose" style="width:auto">关闭</button></div>
    <div class="bd" id="dBody"></div>
</div>
<div class="toast" id="toast"></div>

<script>
var T = <?= json_encode($token) ?>;
var REMAIN = <?= (int) $remain ?>;
var tab = 'personas', page = 1, kw = '', items = [];

function toast(msg){
    var el = document.getElementById('toast');
    el.textContent = msg; el.classList.add('on');
    clearTimeout(el._t); el._t = setTimeout(function(){ el.classList.remove('on'); }, 2200);
}
function esc(s){
    return String(s === null || s === undefined ? '' : s)
        .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
        .replace(/"/g,'"').replace(/'/g,'&#39;');
}
function api(action, params, cb){
    var fd = new FormData();
    fd.append('t', T); fd.append('action', action); fd.append('page', page); fd.append('q', kw);
    for (var k in (params||{})) fd.append(k, params[k]);
    fetch('api.php', {method:'POST', body: fd})
        .then(function(r){ return r.json(); })
        .then(function(d){
            if(!d.ok){
                toast(d.message || '请求失败');
                if(d.message && d.message.indexOf('过期')>=0){
                    document.body.innerHTML = '<div class="wrap"><div class="card"><h3 class="warn">面板已失效</h3>'
                        + '<p class="note">临时面板有效期为 1 小时，请回到 KOOK 重新发送 /panel。</p></div></div>';
                }
                return;
            }
            cb(d);
        })
        .catch(function(){ toast('网络错误'); });
}

function tagRow(){
    if(tab==='users') return '<tr><th>ID</th><th>邮箱</th><th>昵称</th><th>状态</th><th>人设/插件</th><th>注册时间</th><th></th></tr>';
    return '<tr><th>ID</th><th>名称</th><th>简介</th><th>作者</th><th>用户ID</th><th>更新时间</th><th></th></tr>';
}
function render(d){
    items = d.items || [];
    var html = '<table><thead>' + tagRow() + '</thead><tbody>';
    if(!items.length){
        html += '<tr><td colspan="7" class="note">没有数据</td></tr>';
    }
    items.forEach(function(it){
        if(tab==='users'){
            var st = '';
            if(it.banned_until) st += '<span class="tag ban">已封禁</span> ';
            if(it.sponsor==1 || it.sponsor==='1') st += '<span class="tag sp">赞助用户</span> ';
            if(!it.banned_until && !(it.sponsor==1||it.sponsor==='1')) st += '<span class="tag gray">正常</span>';
            html += '<tr class="row" onclick="detail(\'user\','+it.id+')">'
                + '<td>'+esc(it.id)+'</td><td>'+esc(it.email)+'</td><td>'+esc(it.nickname)+'</td>'
                + '<td>'+st+'</td><td>'+esc(it.personas)+' / '+esc(it.plugins)+'</td>'
                + '<td class="note">'+esc(it.created_at)+'</td><td class="note">查看 ›</td></tr>';
        }else{
            html += '<tr class="row" onclick="detail(\''+(tab==='personas'?'persona':'plugin')+'\','+it.id+')">'
                + '<td>'+esc(it.id)+'</td><td>'+esc(it.name)+'</td>'
                + '<td>'+esc((it.description||'').slice(0,40))+'</td>'
                + '<td>'+esc(it.nickname||'')+'</td><td>'+esc(it.user_id)+'</td>'
                + '<td class="note">'+esc(it.updated_at||'')+'</td><td class="note">查看 ›</td></tr>';
        }
    });
    html += '</tbody></table>';
    document.getElementById('tableBox').innerHTML = html;
    document.getElementById('hint').textContent = '共 ' + d.total + ' 条 · 每页 ' + d.size + ' 条';

    var pages = Math.max(1, Math.ceil(d.total / d.size));
    document.getElementById('pagerBox').innerHTML =
        '<button class="btn ghost" id="prev"'+(page<=1?' disabled':'')+'>上一页</button>'
        + ' <span>第 ' + page + ' / ' + pages + ' 页</span> '
        + '<button class="btn ghost" id="next"'+(page>=pages?' disabled':'')+'>下一页</button>';
    var p = document.getElementById('prev'), n = document.getElementById('next');
    if(p) p.onclick = function(){ if(page>1){ page--; load(); } };
    if(n) n.onclick = function(){ if(page<pages){ page++; load(); } };
}
function load(){
    if(tab==='create') return;
    api(tab, {}, render);
}

function kv(k, v){
    return '<div class="kv"><div class="k">'+esc(k)+'</div><div class="v">'+esc(v)+'</div></div>';
}
function detail(kind, id){
    api(kind, {id:id}, function(d){
        var it = d.item, h = '';
        if(kind==='persona' || kind==='plugin'){
            h += kv('用户名称', it.nickname || '—');
            h += kv('用户邮箱', it.email || '—');
            h += kv('用户ID', it.user_id);
            h += kv((kind==='persona'?'人设名称':'插件名称'), it.name);
            h += kv('简介', it.description || '—');
            h += '<div class="kv"><div class="k">提示词（默认折叠）</div>'
               + '<details><summary>点击展开查看提示词</summary><div class="txt">'+esc(it.prompt||'')+'</div></details></div>';
            h += kv('可见性', it.visibility === 'public' ? '公开' : '私密');
            h += '<div class="kv"><div class="k">下架（设为私密，不再公开显示；下架后本列表不再列出）</div><div class="act">'
               + '<button class="btn danger" onclick="setVisibility(\''+kind+'\','+it.id+',\'private\')">下架</button></div></div>';
            h += '<div class="hr"></div>';
            h += '<div class="kv"><div class="k">评论（默认折叠，仅可置顶/删除，不支持回复）</div>'
               + '<div id="cmtBox"><span class="note">正在加载评论…</span></div></div>';
            h += kv('创建 / 更新时间', (it.created_at||'') + ' / ' + (it.updated_at||''));
            document.getElementById('dTitle').textContent = (kind==='persona'?'人设详情':'插件详情') + ' #' + it.id;
        }else{
            var isSp = (it.sponsor==1 || it.sponsor==='1');
            h += kv('用户ID', it.id);
            h += kv('邮箱', it.email);
            h += kv('昵称', it.nickname);
            h += kv('账号创建时间', it.created_at);
            h += kv('是否赞助用户', isSp ? ('是（'+(it.sponsor_at||'')+'）') : '否');
            h += kv('KOOK 绑定', (it.kook_user_id ? ('已绑定：'+(it.kook_name||'')+'（ID '+(it.kook_user_id)+'）'+(it.kook_bound_at?(' · '+it.kook_bound_at):'')) : '未绑定'));
            h += kv('人设 / 插件数量', it.personas + ' / ' + it.plugins);
            h += kv('当前有效登录 Token', it.tokens);
            h += kv('封禁状态', it.banned_until ? ('已封禁至 ' + it.banned_until + (it.ban_reason?(' · '+it.ban_reason):'')) : '未封禁');
            h += '<div class="hr"></div>';
            h += '<div class="kv"><div class="k">赞助身份</div><div class="act">'
               + '<button class="btn" onclick="setSponsor('+it.id+',1)">设为赞助用户</button>'
               + '<button class="btn ghost" onclick="setSponsor('+it.id+',0)">取消赞助</button></div></div>';
            h += '<div class="hr"></div>';
            h += '<div class="kv"><div class="k">封禁账号（封禁后立刻清除该账号所有 Token，并拒绝登录）</div>'
               + '<div class="act"><select id="banDur">'
               + '<option value="12h">12 小时</option><option value="24h">24 小时</option>'
               + '<option value="7d">7 天</option><option value="30d">30 天</option>'
               + '<option value="forever">永久</option></select>'
               + '<input type="text" id="banReason" placeholder="备注（可选）">'
               + '<button class="btn danger" onclick="doBan('+it.id+')">封禁</button>'
               + '<button class="btn ghost" onclick="doUnban('+it.id+')">解封</button></div></div>';
            h += '<div class="hr"></div>';
            h += '<div class="kv"><div class="k">修改密码（8~12 位，改后旧登录全部失效）</div>'
               + '<div class="act"><input type="password" id="newPwd" placeholder="新密码">'
               + '<button class="btn" onclick="doPwd('+it.id+')">修改密码</button></div></div>';
            document.getElementById('dTitle').textContent = '用户详情 #' + it.id;
        }
        document.getElementById('dBody').innerHTML = h;
        document.getElementById('mask').classList.add('on');
        document.getElementById('drawer').classList.add('on');
        // 人设/插件详情：顺手加载评论区
        if(kind==='persona' || kind==='plugin') loadComments(kind, it.id);
    });
}
function setSponsor(id, flag){
    var txt = flag===1 ? '设为赞助用户' : '取消赞助';
    if(!confirm('确认' + txt + '？')) return;
    api('set_sponsor', {id:id, sponsor:flag}, function(){
        toast('已' + txt);
        closeDrawer(); load();
    });
}
function doBan(id){
    var dur = document.getElementById('banDur').value;
    var reason = document.getElementById('banReason').value;
    if(!confirm('确认封禁该账号（' + dur + '）？封禁后其所有 Token 会被立刻清除。')) return;
    api('ban', {id:id, duration:dur, reason:reason}, function(d){
        toast('已封禁至 ' + d.until + '（清除 Token ' + d.tokens_cleared + ' 个）');
        closeDrawer(); load();
    });
}
function doUnban(id){
    api('unban', {id:id}, function(){ toast('已解封'); closeDrawer(); load(); });
}
function doPwd(id){
    var pw = document.getElementById('newPwd').value;
    if(pw.length<8 || pw.length>12){ toast('密码需 8~12 位'); return; }
    api('set_password', {id:id, password:pw}, function(d){
        toast('密码已修改（旧 Token 已清除 ' + d.tokens_cleared + ' 个）');
    });
}
function closeDrawer(){
    document.getElementById('mask').classList.remove('on');
    document.getElementById('drawer').classList.remove('on');
}

// ── 下架 ──
function setVisibility(kind, id, vis){
    if(!confirm('确认下架？下架后该' + (kind==='persona'?'人设':'插件') + '变为私密，不再公开显示。')) return;
    api('set_visibility', {kind:kind, id:id, visibility:vis}, function(){
        toast('已下架');
        closeDrawer(); load();
    });
}

// ── 评论 ──
var cmtKind = '', cmtId = 0;
function loadComments(kind, id){
    cmtKind = kind; cmtId = id;
    api('comments', {kind:kind, id:id}, function(d){
        renderComments(d);
    });
}
function renderComments(d){
    var box = document.getElementById('cmtBox');
    if(!box) return;
    var items = d.items || [];
    if(!items.length){
        box.innerHTML = '<span class="note">暂无评论</span>';
        return;
    }
    var h = '<div class="note" style="margin-bottom:6px">共 ' + d.total + ' 条 · 已置顶 '
        + d.pinned_count + '/' + d.max_pin + '（最多置顶 ' + d.max_pin + ' 条；只能置顶/删除，不能回复）</div>';
    items.forEach(function(c){
        h += '<div class="cmt'+(c.pinned?' pin':'')+'">'
            + '<div class="cmt-hd"><b>' + esc(c.nickname || '（已注销）') + '</b>'
            + (c.pinned ? ' <span class="tag sp">已置顶</span>' : '')
            + '<span class="note">' + esc(c.created_at) + '</span></div>'
            + '<div class="cmt-tx">' + esc(c.content) + '</div>'
            + '<div class="act">'
            + '<button class="btn ghost" onclick="pinComment(' + c.id + ',' + (c.pinned?0:1) + ')">'
            + (c.pinned ? '取消置顶' : '置顶') + '</button>'
            + '<button class="btn danger" onclick="delComment(' + c.id + ')">删除</button></div>';
        if(c.replies && c.replies.length){
            h += '<div class="cmt-rep">';
            c.replies.forEach(function(r){
                h += '<div class="cmt-rep-item">' + esc(r.nickname || '（已注销）') + '：' + esc(r.content)
                    + ' <button class="btn ghost" onclick="delComment(' + r.id + ')">删除</button></div>';
            });
            h += '</div>';
        }
        h += '</div>';
    });
    box.innerHTML = h;
}
function pinComment(cid, on){
    api('comment_pin', {id:cid, on:on}, function(){
        toast(on===1 ? '已置顶' : '已取消置顶');
        loadComments(cmtKind, cmtId);
    });
}
function delComment(cid){
    if(!confirm('确认删除这条评论？（若是顶层评论，它的回复也会一起删除）')) return;
    api('comment_delete', {id:cid}, function(d){
        toast('已删除（含回复共 ' + d.deleted + ' 条）');
        loadComments(cmtKind, cmtId);
    });
}

document.getElementById('mask').onclick = closeDrawer;
document.getElementById('dClose').onclick = closeDrawer;
document.getElementById('reload').onclick = function(){ load(); toast('已刷新'); };

// ── 汉堡菜单（手机端）──
var burger = document.getElementById('burger');
var navWrap = document.getElementById('navWrap');
burger.onclick = function(){ navWrap.classList.toggle('open'); };

Array.prototype.forEach.call(document.querySelectorAll('.nav[data-tab]'), function(btn){
    btn.onclick = function(){
        Array.prototype.forEach.call(document.querySelectorAll('.nav[data-tab]'), function(b){ b.classList.remove('on'); });
        btn.classList.add('on');
        tab = btn.getAttribute('data-tab'); page = 1; kw = '';
        document.getElementById('q').value = '';
        document.getElementById('mainCard').style.display = (tab==='create') ? 'none' : '';
        document.getElementById('createCard').style.display = (tab==='create') ? '' : 'none';
        navWrap.classList.remove('open');   // 选完自动收起菜单
        load();
    };
});
var qtimer = null;
document.getElementById('q').oninput = function(){
    clearTimeout(qtimer);
    var v = this.value.trim();
    qtimer = setTimeout(function(){ kw = v; page = 1; load(); }, 300);
};
document.getElementById('doCreate').onclick = function(){
    var email = document.getElementById('cEmail').value.trim();
    var nick = document.getElementById('cNick').value.trim();
    var pwd = document.getElementById('cPwd').value;
    if(!email || !nick || !pwd){ toast('请填完整'); return; }
    api('create_user', {email:email, nickname:nick, password:pwd}, function(d){
        toast('已创建账号 #' + d.id);
        document.getElementById('cEmail').value = '';
        document.getElementById('cNick').value = '';
        document.getElementById('cPwd').value = '';
    });
};

// 剩余时间倒计时
(function(){
    var left = REMAIN, el = document.getElementById('clock');
    function tick(){
        if(left <= 0){ el.textContent = '已过期'; el.className = 'warn'; return; }
        var m = Math.floor(left/60), s = left % 60;
        el.textContent = '剩余 ' + (m<10?'0':'') + m + ':' + (s<10?'0':'') + s;
        left--; setTimeout(tick, 1000);
    }
    tick();
})();

load();
</script>
</body>
</html>