<?php
/**
 * 挑战页模板（由 gate.php require，不能直接访问）。
 *
 * 入参：$c（挑战串）、$bits（PoW 位数）、$toH（转义后的跳转目标）、$mode（s/w/c）
 *
 * 三种模式的统一约定：
 *   ① PoW    —— 后台静默算完，三种模式都必须带上 n
 *   ② JS 挑战 —— 页面算出 j = sha256("dekk-js|"+C)，三种模式都必须带上 j
 *   ③ 模式专属：滑块拖到底 / 等够 5 秒 / 填 4 位数字
 *
 * 注意：本文件里的 JS **不使用 // 行注释**。服务端 gate_obfuscate() 会把整页压成
 * 一行输出，// 注释一旦出现就会把后面的代码全部吃掉 —— 那是这类处理最经典的翻车。
 */
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<title>访问验证 · DEKK</title>
<style>
  :root{ --bg:#f5f6f7; --fg:#1d2126; --sub:#6b7280; --line:#dfe3e8; --accent:#2563eb; --err:#b91c1c; }
  @media (prefers-color-scheme: dark){ :root{ --bg:#14171a; --fg:#e6e8ea; --sub:#9aa3ad; --line:#2a2f35; --accent:#4f8cf7; --err:#f87171; } }
  *{ box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  body{ margin:0; min-height:100vh; background:var(--bg); color:var(--fg);
        font:15px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
        display:flex; align-items:center; justify-content:center; padding:20px; }
  .box{ width:100%; max-width:340px; }
  h1{ font-size:17px; font-weight:600; margin:0 0 4px; }
  .sub{ color:var(--sub); font-size:13px; margin:0 0 16px; }
  #track{ position:relative; height:44px; border-radius:6px; background:var(--line);
          overflow:hidden; touch-action:none; }
  #fill{ position:absolute; top:0; bottom:0; left:0; width:44px; background:rgba(37,99,235,.18); }
  #knob{ position:absolute; top:0; left:0; width:44px; height:44px; border-radius:6px;
         background:var(--accent); color:#fff; display:flex; align-items:center; justify-content:center;
         font-size:14px; font-weight:600; cursor:grab; user-select:none; transition:left .12s ease-out; }
  #shield{ height:6px; border-radius:3px; background:var(--line); overflow:hidden; }
  #sbar{ height:100%; width:0; background:var(--accent); transition:width .1s linear; }
  .hint{ font-size:13px; color:var(--sub); margin:10px 0 0; }
  #cap{ display:block; width:132px; height:48px; border:1px solid var(--line); border-radius:6px;
        cursor:pointer; background:#fff; }
  #ans{ width:132px; height:44px; margin-top:10px; padding:0 12px; border:1px solid var(--line);
        border-radius:6px; background:transparent; color:var(--fg); font-size:19px;
        letter-spacing:.35em; text-align:center; }
  #ans:focus{ outline:none; border-color:var(--accent); }
  #go{ width:132px; height:44px; margin-top:10px; border:0; border-radius:6px; background:var(--accent);
       color:#fff; font-size:15px; font-weight:600; cursor:pointer; }
  #go:active{ opacity:.85; }
  #tip{ font-size:13px; color:var(--sub); margin-top:10px; min-height:20px; }
  #tip.err{ color:var(--err); }
  .foot{ font-size:12px; color:var(--sub); margin-top:16px; }
</style>
</head>
<body>
<div class="box">
  <h1>访问验证</h1>

<?php if ($mode === 's'): ?>
  <p class="sub">把滑块拖到最右边即可进入</p>
  <div id="track"><div id="fill"></div><div id="knob">›››</div></div>
<?php elseif ($mode === 'w'): ?>
  <p class="sub">正在检查浏览器环境</p>
  <div id="shield"><div id="sbar"></div></div>
  <p class="hint"><span id="snum">5</span> 秒后自动进入</p>
<?php else: ?>
  <p class="sub">请输入图中的 4 位数字</p>
  <img id="cap" alt="验证码" src="/_gate/?r=captcha&amp;c=<?php echo $cUrl; ?>">
  <input id="ans" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="4" autocomplete="off" placeholder="····">
  <button id="go" type="button">进入</button>
<?php endif; ?>

  <div id="tip"></div>
  <div class="foot">通过后 1 小时内无需重复验证 · dekk.example.com</div>
</div>

<script>
(function(){
  /* 关键值在服务端被随机拆成多段再拼接（纯字符串拼接，功能完全不受影响，
     但直接看源码得到的是碎片，需要人工还原才能读懂）。 */
  var C    = <?php echo gate_split_js($c); ?>;
  var BITS = <?php echo (int)$bits; ?>;
  var TO   = <?php echo gate_split_js($toH, 2); ?>;
  var MODE = '<?php echo $mode; ?>';

  /* 紧凑 SHA-256（纯 JS，兼容微信内核，不依赖 crypto.subtle） */
  function sha256(ascii){
    function rr(v,a){ return (v>>>a)|(v<<(32-a)); }
    var mp=Math.pow, mw=mp(2,32), out='', w=[], len=ascii.length*8;
    var h=sha256.h=sha256.h||[], k=sha256.k=sha256.k||[], pc=k.length, comp={};
    for(var c=2; pc<64; c++){ if(!comp[c]){
      for(var i=0;i<313;i+=c) comp[i]=c;
      h[pc]=(mp(c,.5)*mw)|0; k[pc++]=(mp(c,1/3)*mw)|0;
    }}
    ascii+='\x80'; while(ascii.length%64-56) ascii+='\x00';
    for(i=0;i<ascii.length;i++){ var jj=ascii.charCodeAt(i); if(jj>>8) return null; w[i>>2]|=jj<<((3-i)%4)*8; }
    w[w.length]=(len/mw)|0; w[w.length]=len;
    for(var j=0;j<w.length;){
      var blk=w.slice(j,j+=16), old=h;
      h=h.slice(0,8);
      for(i=0;i<64;i++){
        var w15=blk[i-15], w2=blk[i-2], a=h[0], e=h[4];
        var t1=h[7]+(rr(e,6)^rr(e,11)^rr(e,25))+((e&h[5])^((~e)&h[6]))+k[i]
              +(blk[i]=(i<16)?blk[i]:(blk[i-16]+(rr(w15,7)^rr(w15,18)^(w15>>>3))+blk[i-7]+(rr(w2,17)^rr(w2,19)^(w2>>>10)))|0);
        var t2=(rr(a,2)^rr(a,13)^rr(a,22))+((a&h[1])^(a&h[2])^(h[1]&h[2]));
        h=[(t1+t2)|0].concat(h); h[4]=(h[4]+t1)|0;
      }
      for(i=0;i<8;i++) h[i]=(h[i]+old[i])|0;
    }
    for(i=0;i<8;i++) for(j=3;j+1;j--){ var b=(h[i]>>(j*8))&255; out+=((b<16)?0:'')+b.toString(16); }
    return out;
  }

  /* 所有模式共用的 JS 令牌：证明本页真的执行了 JS */
  var JS_TOK = sha256('dekk-js|' + C);
  var T0 = Date.now();

  /* PoW：静默后台算完（放进 setTimeout，先让页面渲染出来，避免阻塞首屏） */
  var POW_N = null;
  function runPow(){
    var n = 0, z = new Array(BITS+1).join('0');
    for (var guard = 0; guard < 5000000; guard++){
      var h = sha256(C + n);
      if (h && h.slice(0, BITS) === z){ POW_N = n; return; }
      n++;
    }
  }
  setTimeout(runPow, 0);

  var tip = document.getElementById('tip');
  var busy = false;

  function fail(j){
    busy = false;
    tip.className = 'err';
    tip.textContent = (j && j.msg) ? j.msg : '校验失败，请重试';
    if (typeof reset === 'function') reset();
  }

  function send(extra){
    if (busy) return;
    if (POW_N === null){ setTimeout(function(){ send(extra); }, 20); return; }
    busy = true;
    tip.className = '';
    tip.textContent = '正在进入…';
    var body = 'c=' + encodeURIComponent(C)
             + '&n=' + POW_N
             + '&j=' + JS_TOK
             + '&e=' + (Date.now() - T0)
             + extra;
    fetch('/_gate/?r=verify', {
      method:'POST',
      headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body: body,
      credentials:'same-origin'
    }).then(function(r){ return r.json(); }).then(function(j){
      if (j.ok){ location.replace(TO); return; }
      fail(j);
    }).catch(function(){
      fail(null);
    });
  }

  if (MODE === 's'){
    /* ── 滑块：滑到底即过；没到底松手会自动吸附到最右 ── */
    var track = document.getElementById('track'),
        fill  = document.getElementById('fill'),
        knob  = document.getElementById('knob');
    var dragging=false, curX=0, max=0, trace=[], t0=0;

    function layout(){ max = track.clientWidth - 44; setX(0); }
    function setX(x){
      x = Math.max(0, Math.min(max, x));
      curX = x;
      knob.style.left = x + 'px';
      fill.style.width = (x + 44) + 'px';
    }
    function start(e){
      if (busy) return;
      dragging = true; trace = []; t0 = Date.now(); setX(0);
      if (e && e.cancelable) e.preventDefault();
    }
    function move(e){
      if (!dragging) return;
      var rect = track.getBoundingClientRect();
      var pt = (e.touches && e.touches[0]) ? e.touches[0] : e;
      setX(pt.clientX - rect.left - 22);
      if (trace.length < 80) trace.push([Date.now() - t0, Math.round(curX)]);
      if (e.cancelable) e.preventDefault();
    }
    function end(e){
      if (!dragging) return;
      dragging = false;
      var pct = max > 0 ? Math.round(curX / max * 100) : 0;
      if (pct < 95){
        setX(max);
        if (trace.length < 2) trace.push([Date.now() - t0 + 40, Math.round(max)]);
      }
      send('&x=100&t=' + encodeURIComponent(JSON.stringify(trace)) + '&d=' + (Date.now() - t0 + 40));
      if (e && e.cancelable) e.preventDefault();
    }
    var reset = function(){ setX(0); };

    track.addEventListener('mousedown', start);
    track.addEventListener('touchstart', start, {passive:false});
    document.addEventListener('mousemove', move);
    document.addEventListener('touchmove', move, {passive:false});
    document.addEventListener('mouseup', end);
    document.addEventListener('touchend', end);
    window.addEventListener('resize', layout);
    layout();

  } else if (MODE === 'w'){
    /* ── 5 秒盾：等够 5 秒自动提交（服务端按 challenge 时间戳核，客户端改不动）── */
    var sbar = document.getElementById('sbar'),
        snum = document.getElementById('snum');
    var TOTAL = 5400, STEP = 100, el = 0;
    var iv = setInterval(function(){
      el += STEP;
      var p = Math.min(1, el / TOTAL);
      sbar.style.width = (p * 100) + '%';
      snum.textContent = Math.max(0, Math.ceil((TOTAL - el) / 1000));
      if (el >= TOTAL){ clearInterval(iv); send(''); }
    }, STEP);
    var reset = function(){ setTimeout(function(){ location.reload(); }, 1500); };

  } else {
    /* ── 图片验证码：4 位纯数字，答案只在服务端 ── */
    var ans = document.getElementById('ans'),
        cap = document.getElementById('cap'),
        go  = document.getElementById('go');
    var reset = function(){ busy = false; ans.value = ''; };
    cap.onclick = function(){ location.reload(); };
    go.onclick = function(){
      var v = (ans.value || '').replace(/\D/g, '');
      if (v.length !== 4){ tip.className = 'err'; tip.textContent = '请输入 4 位数字'; return; }
      ans.value = v;
      if (Date.now() - T0 < 1200){
        setTimeout(function(){ go.onclick(); }, 1200 - (Date.now() - T0));
        return;
      }
      send('&a=' + encodeURIComponent(v));
    };
    ans.onkeydown = function(e){
      if ((e.keyCode || e.which) === 13) go.onclick();
    };
    ans.focus();
  }
})();
</script>
</body>
</html>