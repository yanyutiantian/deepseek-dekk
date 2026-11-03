/* ===================== 00_header.js ===================== */
// ==UserScript==
// @name         DEKK
// @namespace    https://dekk.example.com/jsdekk
// @version      1.0.2-web
// @description  为 DeepSeek 网页版提供的 DEKK 功能模块（安卓 Xposed 模块 com.dekk.yanyu 的网页移植版）
// @author       烟萧雨
// @match        https://chat.deepseek.com/*
// @run-at       document-start
// @grant        none
// @noframes
// ==/UserScript==

/*
 * DEKK 网页版
 *
 * 这个脚本是安卓端 Xposed 模块（com.dekk.yanyu）的网页移植：
 *   - 菜单入口：登录后点头像弹出的菜单里，「系统设置」下面多一行「DEKK模块」；
 *   - 设置页：外观、分组、文案都对齐安卓端注入的「DEKK设置页面」；
 *   - 功能：设备状态提示词、补充上下文、一问多答、强开专家/识图、
 *           伪封号、AI控制封号、防撤回（本地存档）、聊天美化、历史对话全选，
 *           以及账号 / 人设市场 / 插件中心 / 消息中心（走 dekk.example.com 服务端）。
 *
 * 源码按职责拆在 src/ 下，由 build.sh 拼成单文件 dekk.js。
 */
(function () {
  'use strict';
  if (window.__DEKK__) return;

  var DEKK = {
    /** 脚本版本（本文件顶部的 @version 保持同步） */
    version: '1.0.2-web',
    build: '2026-10-03',
    /** 安卓端模块版本，设置页里显示用 */
    moduleVersion: '0.1.2-web',
    host: 'chat.deepseek.com',
    home: 'https://dekk.example.com/',
    download: 'https://dekk.example.com/jsdekk/dekk.js',
    /** DEKK 服务端接口前缀（与安卓端 PersonaApi.BASE 一致） */
    apiBase: 'https://dekk.example.com/API/?action=',
    /** DeepSeek 品牌蓝 */
    brand: '#4d6bfe',
    /** 运行时装配区：各模块往这里挂自己的东西 */
    modules: {},
    started: false,
  };

  window.__DEKK__ = DEKK;
})();
/* ===================== 01_util.js ===================== */
/*
 * 通用工具：日志、DOM、时间、存储、Toast、电池。
 * 全部挂在 DEKK.util 上，其它模块直接用 D.util.xxx。
 */
(function (D) {
  'use strict';
  if (!D) return;

  var PREFIX = '[DEKK]';

  var util = {
    /** 调试日志开关（设置页里可开，默认关，避免刷屏） */
    debug: false,

    log: function () {
      if (!util.debug) return;
      try { console.log.apply(console, [PREFIX].concat([].slice.call(arguments))); } catch (e) { }
    },
    info: function () {
      try { console.log.apply(console, [PREFIX].concat([].slice.call(arguments))); } catch (e) { }
    },
    warn: function () {
      try { console.warn.apply(console, [PREFIX].concat([].slice.call(arguments))); } catch (e) { }
    },
    error: function () {
      try { console.error.apply(console, [PREFIX].concat([].slice.call(arguments))); } catch (e) { }
    },

    sleep: function (ms) {
      return new Promise(function (r) { setTimeout(r, ms); });
    },

    uid: function () {
      return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
    },

    clamp: function (value, min, max) {
      return Math.max(min, Math.min(max, value));
    },

    esc: function (value) {
      // 注意：HTML 实体用字符串拼接写，别让编辑器/工具把 " 还原成引号
      var map = {
        '&': '&' + 'amp;',
        '<': '&' + 'lt;',
        '>': '&' + 'gt;',
        '"': '&' + 'quot;',
        "'": '&' + '#39;',
      };
      return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return map[c]; });
    },

    /** 纯文本转安全 HTML（存档、提示词预览用） */
    text2html: function (value) {
      return util.esc(value).replace(/\n/g, '<br>');
    },

    /** yyyy-MM-dd HH:mm:ss XXX（与安卓端设备快照格式一致） */
    fmtTime: function (date) {
      var d = date || new Date();
      var p = function (n) { return (n < 10 ? '0' : '') + n; };
      var off = -d.getTimezoneOffset();
      var sign = off >= 0 ? '+' : '-';
      var oh = p(Math.floor(Math.abs(off) / 60));
      var om = p(Math.abs(off) % 60);
      return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' '
        + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
        + ' ' + sign + oh + ':' + om;
    },

    fmtDate: function (date) {
      var d = date || new Date();
      var p = function (n) { return (n < 10 ? '0' : '') + n; };
      return d.getFullYear() + '年' + p(d.getMonth() + 1) + '月' + p(d.getDate()) + '日 '
        + p(d.getHours()) + ':' + p(d.getMinutes());
    },

    /** 剩余时间文案：3小时12分05秒（对齐 FakeBanStore.remainingText） */
    fmtRemain: function (ms) {
      var seconds = Math.max(0, Math.ceil(ms / 1000));
      var h = Math.floor(seconds / 3600);
      var m = Math.floor(seconds / 60) % 60;
      var s = seconds % 60;
      return h + '小时' + m + '分' + s + '秒';
    },

    /** 服务端时间字符串 → 相对时间 */
    shortTime: function (value) {
      if (!value) return '';
      var then = new Date(String(value).replace(' ', 'T'));
      if (isNaN(then.getTime())) return String(value);
      var diff = Date.now() - then.getTime();
      if (diff < 60000) return '刚刚';
      if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
      if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
      if (diff < 2592000000) return Math.floor(diff / 86400000) + ' 天前';
      return util.fmtDate(then);
    },

    $: function (selector, root) {
      return (root || document).querySelector(selector);
    },

    $$: function (selector, root) {
      return [].slice.call((root || document).querySelectorAll(selector));
    },

    /** 建元素：el('div', {className:'a', text:'x', attrs:{}, on:{click:fn}}, children) */
    el: function (tag, opts, children) {
      var node = document.createElement(tag);
      var o = opts || {};
      if (o.className) node.className = o.className;
      if (o.text != null) node.textContent = String(o.text);
      if (o.html != null) node.innerHTML = o.html;
      if (o.attrs) for (var k in o.attrs) if (o.attrs[k] != null) node.setAttribute(k, String(o.attrs[k]));
      if (o.style) for (var s in o.style) node.style[s] = o.style[s];
      if (o.on) for (var e in o.on) if (typeof o.on[e] === 'function') node.addEventListener(e, o.on[e]);
      var list = children == null ? [] : (Array.isArray(children) ? children : [children]);
      list.forEach(function (child) {
        if (child == null) return;
        node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
      });
      return node;
    },

    remove: function (node) {
      if (node && node.parentNode) node.parentNode.removeChild(node);
    },

    debounce: function (fn, ms) {
      var timer = null;
      return function () {
        var args = arguments, self = this;
        clearTimeout(timer);
        timer = setTimeout(function () { fn.apply(self, args); }, ms || 120);
      };
    },

    throttle: function (fn, ms) {
      var last = 0, timer = null, args = null;
      return function () {
        args = arguments;
        var now = Date.now();
        var wait = Math.max(0, (ms || 100) - (now - last));
        clearTimeout(timer);
        if (wait === 0) { last = now; fn.apply(this, args); }
        else timer = setTimeout(function () { last = Date.now(); fn.apply(null, args); }, wait);
      };
    },

    /** 等条件成立；返回 Promise<boolean> */
    waitFor: function (check, timeout, interval) {
      var limit = timeout == null ? 10000 : timeout;
      var step = interval == null ? 120 : interval;
      var start = Date.now();
      return new Promise(function (resolve) {
        (function tick() {
          var value = null;
          try { value = check(); } catch (e) { value = null; }
          if (value) return resolve(value);
          if (Date.now() - start >= limit) return resolve(null);
          setTimeout(tick, step);
        })();
      });
    },

    /** body 观察者：包含初始一次的调用 */
    observe: function (callback, options) {
      var observer = new MutationObserver(function (records) {
        try { callback(records); } catch (e) { util.error('观察回调异常', e); }
      });
      observer.observe(document.documentElement || document.body, options || { childList: true, subtree: true });
      try { callback([]); } catch (e) { util.error('观察初始回调异常', e); }
      return observer;
    },

    /** 剪贴板（失败退回 execCommand） */
    copy: function (text) {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          return navigator.clipboard.writeText(text).then(function () { return true; }, function () { return util.copyFallback(text); });
        }
      } catch (e) { }
      return Promise.resolve(util.copyFallback(text));
    },

    copyFallback: function (text) {
      try {
        var area = document.createElement('textarea');
        area.value = text;
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        var ok = document.execCommand('copy');
        document.body.removeChild(area);
        return ok;
      } catch (e) { return false; }
    },

    /** 命名空间化的本地存储（对应安卓端各个 SharedPreferences） */
    store: {
      read: function (ns, key, fallback) {
        try {
          var raw = localStorage.getItem('dekk:' + ns);
          if (!raw) return fallback;
          var data = JSON.parse(raw);
          return Object.prototype.hasOwnProperty.call(data, key) ? data[key] : fallback;
        } catch (e) { return fallback; }
      },
      write: function (ns, key, value) {
        try {
          var raw = localStorage.getItem('dekk:' + ns);
          var data = raw ? JSON.parse(raw) : {};
          data[key] = value;
          localStorage.setItem('dekk:' + ns, JSON.stringify(data));
          return true;
        } catch (e) {
          util.warn('本地存储写入失败', e && e.message);
          return false;
        }
      },
      remove: function (ns, key) {
        try {
          var raw = localStorage.getItem('dekk:' + ns);
          if (!raw) return;
          var data = JSON.parse(raw);
          delete data[key];
          localStorage.setItem('dekk:' + ns, JSON.stringify(data));
        } catch (e) { }
      },
      all: function (ns, fallback) {
        try {
          var raw = localStorage.getItem('dekk:' + ns);
          return raw ? JSON.parse(raw) : (fallback || {});
        } catch (e) { return fallback || {}; }
      },
      clear: function (ns) {
        try { localStorage.removeItem('dekk:' + ns); } catch (e) { }
      },
      /** 命名空间下的所有键值（存档、多会话数据用） */
      keys: function (ns) {
        try { return Object.keys(util.store.all(ns, {})); } catch (e) { return []; }
      },
    },

    /** 极简事件总线（设置变更通知用） */
    bus: (function () {
      var map = {};
      return {
        on: function (name, fn) {
          (map[name] = map[name] || []).push(fn);
          return function () { util.bus.off(name, fn); };
        },
        off: function (name, fn) {
          var list = map[name];
          if (!list) return;
          var i = list.indexOf(fn);
          if (i >= 0) list.splice(i, 1);
        },
        emit: function (name, payload) {
          var list = (map[name] || []).slice();
          list.forEach(function (fn) {
            try { fn(payload); } catch (e) { util.error('事件回调异常', name, e); }
          });
        },
      };
    })(),
  };

  /** 设备信息快照（设备状态提示词 / 识图开关文案里会用到） */
  util.device = {
    model: function () {
      var ua = navigator.userAgent || '';
      var patterns = [
        /Android[^;]*;\s*([^;)]+?)(?:\s+Build|\))/i,
        /\(([^)]+)\)/,
      ];
      for (var i = 0; i < patterns.length; i++) {
        var m = ua.match(patterns[i]);
        if (m && m[1]) return m[1].trim().slice(0, 48);
      }
      return navigator.platform || '未知设备';
    },
    system: function () {
      var ua = navigator.userAgent || '';
      var m = ua.match(/Android\s*[0-9.]+/i) || ua.match(/Windows NT\s*[0-9.]+/i)
        || ua.match(/Mac OS X\s*[0-9_.]+/i) || ua.match(/(iPhone|iPad)[^;]*OS\s*[0-9_]+/i);
      return m ? m[0] : (navigator.platform || '未知系统');
    },
    browser: function () {
      var ua = navigator.userAgent || '';
      var m = ua.match(/(Chrome|Firefox|Safari|Edg|MicroMessenger|Via)\/[0-9.]+/i);
      return m ? m[0] : '未知浏览器';
    },
    /** 电量：浏览器支持 Battery Status API 时才有 */
    battery: function () {
      return new Promise(function (resolve) {
        try {
          if (!navigator.getBattery) {
            resolve({ level: '未知', charging: '未知' });
            return;
          }
          navigator.getBattery().then(function (b) {
            var level = Math.round((b.level || 0) * 100) + '%';
            var charging = b.charging ? '正在充电' : '未充电';
            if (b.charging && (b.level || 0) >= 1) charging = '已充满';
            resolve({ level: level, charging: charging });
          }, function () { resolve({ level: '未知', charging: '未知' }); });
        } catch (e) {
          resolve({ level: '未知', charging: '未知' });
        }
      });
    },
    /** 网络类型（部分机型不支持，返回空串） */
    network: function () {
      try {
        var c = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
        if (!c) return '';
        return c.effectiveType ? String(c.effectiveType) : '';
      } catch (e) { return ''; }
    },
  };

  D.util = util;
})(window.__DEKK__);
/* ===================== 02_theme.js ===================== */
/*
 * 主题与样式。
 *
 * 外观照着 DeepSeek 网页版的「系统设置」弹窗做：
 *   - 半透明遮罩 + 居中圆角面板（窄屏铺满整屏，和站点自己的 dialog 行为一致）
 *   - 顶栏：返回箭头 / 标题 / 关闭按钮
 *   - 内容区是「分组标题 + 一行一项 + 分隔线」，行高 56px，左右 18px 内边距
 *   - 颜色优先用站点自己的设计变量（--dsw-alias-*），深浅色自动跟随
 * 兼容：老内核没有这些变量时退回 DEKK 自己的一套色值。
 */
(function (D) {
  'use strict';
  if (!D || !D.util) return;

  var util = D.util;
  var STYLE_ID = 'dekk-style';
  var THEME_ATTR = 'data-dekk-theme';

  function isDark() {
    try {
      if (document.documentElement.classList.contains('ds-dark-theme')) return true;
      if (document.body && document.body.classList.contains('ds-dark-theme')) return true;
      var attr = document.documentElement.getAttribute('data-theme');
      if (attr) return String(attr).toLowerCase().indexOf('dark') >= 0;
      return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    } catch (e) { return false; }
  }

  var CSS = [
    /* ------------------------------------------------------------ 基础变量 */
    '#dekk-root{--dekk-brand:var(--dsw-alias-brand-primary,#4d6bfe);}',
    '[data-dekk-theme="light"]{--dekk-bg:var(--dsw-alias-bg-base,#ffffff);'
    + '--dekk-text:var(--dsw-alias-label-primary,#1a1a1a);--dekk-sub:var(--dsw-alias-label-secondary,#757575);'
    + '--dekk-line:var(--dsw-alias-border-l1,rgba(0,0,0,.08));--dekk-fill:rgba(0,0,0,.04);'
    + '--dekk-danger:#e53935;--dekk-mask:rgba(0,0,0,.45);--dekk-hover:var(--dsw-alias-interactive-bg-hover,rgba(0,0,0,.05));}',
    '[data-dekk-theme="dark"]{--dekk-bg:var(--dsw-alias-bg-base,#1b1b1c);'
    + '--dekk-text:var(--dsw-alias-label-primary,#e8e8e8);--dekk-sub:var(--dsw-alias-label-secondary,#8f8f8f);'
    + '--dekk-line:var(--dsw-alias-border-l1,rgba(255,255,255,.12));--dekk-fill:rgba(255,255,255,.06);'
    + '--dekk-danger:#ff6b6b;--dekk-mask:rgba(0,0,0,.6);--dekk-hover:var(--dsw-alias-interactive-bg-hover,rgba(255,255,255,.07));}',

    /* ------------------------------------------------------------ 面板本体 */
    // 注意：这里刻意不用 CSS `inset` / flex `gap` —— iOS 14.5 以下不支持，
    // 会导致整个面板错位（iOS 上「脚本用不了」多半就是这个原因）。
    '#dekk-root{position:fixed;top:0;right:0;bottom:0;left:0;z-index:2147483000;display:none;}',
    '#dekk-root[data-open="1"]{display:block;}',
    '.dekk-mask{position:absolute;top:0;right:0;bottom:0;left:0;background:var(--dekk-mask);'
    + '-webkit-backdrop-filter:blur(2px);backdrop-filter:blur(2px);}',
    '.dekk-panel{position:absolute;left:50%;top:50%;-webkit-transform:translate(-50%,-50%);'
    + 'transform:translate(-50%,-50%);width:92%;max-width:560px;max-height:86vh;display:flex;'
    + 'flex-direction:column;overflow:hidden;'
    + 'background:var(--dekk-bg);color:var(--dekk-text);border-radius:24px;'
    + 'box-shadow:0 18px 60px rgba(0,0,0,.28);font-size:16px;line-height:1.45;'
    + '-webkit-tap-highlight-color:transparent;}',
    '@media (max-width:520px){.dekk-panel{left:0;top:0;-webkit-transform:none;transform:none;'
    + 'width:100%;max-width:none;height:100%;max-height:100%;border-radius:0;}}',

    /* 顶栏 */
    '.dekk-panel__bar{position:relative;flex:0 0 auto;height:58px;display:flex;align-items:center;'
    + 'padding:0 14px;background:var(--dekk-bg);}',
    '.dekk-panel__title{flex:1 1 auto;min-width:0;font-size:17px;font-weight:500;'
    + 'margin-right:6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
    '.dekk-iconbtn{flex:0 0 auto;width:36px;height:36px;border-radius:50%;display:flex;'
    + 'align-items:center;justify-content:center;cursor:pointer;color:var(--dekk-text);}',
    '.dekk-iconbtn+.dekk-iconbtn{margin-left:6px;}',
    '.dekk-iconbtn:active{background:var(--dekk-hover);}',
    '.dekk-iconbtn svg{width:18px;height:18px;stroke:currentColor;fill:none;stroke-width:1.9;'
    + 'stroke-linecap:round;stroke-linejoin:round;}',
    '.dekk-iconbtn--text{font-size:22px;line-height:1;font-weight:300;}',

    /* 内容区（可上下滑） */
    '.dekk-panel__body{flex:1 1 auto;min-height:0;overflow-y:auto;overflow-x:hidden;'
    + 'padding:2px 18px 28px;-webkit-overflow-scrolling:touch;overscroll-behavior:contain;}',

    /* ------------------------------------------------------------ 列表行 */
    '.dekk-section{font-size:12.5px;color:var(--dekk-sub);padding:18px 2px 8px;}',
    '.dekk-card{display:block;margin:0;padding:0;background:transparent;}',
    '.dekk-row{display:flex;align-items:center;justify-content:space-between;'
    + 'min-height:56px;padding:10px 2px;cursor:default;}',
    '.dekk-row>*+*{margin-left:12px;}',
    '.dekk-row+.dekk-row{border-top:1px solid var(--dekk-line);}',
    '.dekk-row--tap{cursor:pointer;}',
    '.dekk-row--tap:active{background:var(--dekk-hover);}',
    '.dekk-row__main{flex:1 1 auto;min-width:0;}',
    '.dekk-row__title{font-size:16px;overflow:hidden;text-overflow:ellipsis;}',
    '.dekk-row__desc{font-size:13px;color:var(--dekk-sub);margin-top:6px;line-height:1.5;}',
    '.dekk-row__value{flex:0 0 auto;font-size:14px;color:var(--dekk-sub);max-width:46%;'
    + 'text-align:right;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
    '.dekk-arrow{flex:0 0 auto;color:var(--dekk-sub);font-size:15px;}',
    '.dekk-note{font-size:13px;color:var(--dekk-sub);line-height:1.6;padding:2px 2px 14px;white-space:pre-wrap;}',

    /* 开关（对齐站点开关的比例） */
    '.dekk-switch{flex:0 0 auto;width:46px;height:26px;border-radius:13px;background:rgba(128,128,128,.35);'
    + 'position:relative;cursor:pointer;transition:background .18s ease;}',
    '.dekk-switch[data-on="1"]{background:var(--dekk-brand);}',
    '.dekk-switch::after{content:"";position:absolute;top:3px;left:3px;width:20px;height:20px;'
    + 'border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.3);transition:transform .18s ease;}',
    '.dekk-switch[data-on="1"]::after{transform:translateX(20px);}',
    '.dekk-switch[data-disabled="1"]{opacity:.45;pointer-events:none;}',

    /* 输入控件 */
    '.dekk-field{padding:12px 0 2px;}',
    '.dekk-field__label{font-size:12.5px;color:var(--dekk-sub);padding:0 2px 8px;}',
    '.dekk-input{width:100%;box-sizing:border-box;background:var(--dekk-fill);color:var(--dekk-text);'
    + 'border:none;border-radius:12px;padding:12px 14px;font-size:16px;outline:none;'
    + 'font-family:inherit;-webkit-appearance:none;}',
    'textarea.dekk-input{min-height:150px;resize:vertical;line-height:1.5;}',
    '.dekk-input::placeholder{color:var(--dekk-sub);}',

    /* 按钮 */
    '.dekk-btn{border:none;border-radius:22px;padding:13px 18px;font-size:16px;font-family:inherit;'
    + 'background:var(--dekk-brand);color:#fff;width:100%;cursor:pointer;}',
    '.dekk-btn[data-busy="1"]{opacity:.6;pointer-events:none;}',
    '.dekk-btn--ghost{background:var(--dekk-fill);color:var(--dekk-text);}',
    '.dekk-btn--danger{background:var(--dekk-danger);}',
    '.dekk-btn--sm{width:auto;padding:9px 16px;font-size:14px;border-radius:16px;}',
    '.dekk-actions{display:flex;padding:14px 0 4px;flex-wrap:wrap;}',
    '.dekk-actions>*{margin:0 10px 8px 0;}',
    '.dekk-actions--right{justify-content:flex-end;}',

    /* 列表卡片（人设 / 插件 / 消息） */
    '.dekk-item{padding:14px 2px;border-bottom:1px solid var(--dekk-line);}',
    '.dekk-item__title{font-size:16px;font-weight:600;}',
    '.dekk-item__desc{font-size:14px;color:var(--dekk-sub);margin-top:8px;white-space:pre-wrap;'
    + 'display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;}',
    '.dekk-item__author{font-size:12px;color:var(--dekk-sub);margin-top:8px;text-align:right;}',
    '.dekk-item__foot{display:flex;justify-content:flex-end;margin-top:10px;flex-wrap:wrap;}',
    '.dekk-item__foot>*{margin:0 0 8px 10px;}',
    '.dekk-avatar-img{width:38px;height:38px;border-radius:50%;object-fit:cover;background:var(--dekk-fill);'
    + 'flex:0 0 auto;}',
    '.dekk-notice{display:flex;align-items:flex-start;padding:12px 2px;cursor:pointer;}',
    '.dekk-notice>*+*{margin-left:10px;}',
    '.dekk-notice+.dekk-notice{border-top:1px solid var(--dekk-line);}',
    '.dekk-notice__title{font-size:14px;}',
    '.dekk-notice__body{font-size:14px;color:var(--dekk-sub);margin-top:4px;white-space:pre-wrap;}',
    '.dekk-notice__time{font-size:11px;color:var(--dekk-sub);margin-top:4px;}',
    '.dekk-badge{background:var(--dekk-danger);color:#fff;font-size:11px;border-radius:14px;'
    + 'padding:2px 7px;min-width:10px;text-align:center;flex:0 0 auto;}',
    '.dekk-empty{text-align:center;color:var(--dekk-sub);font-size:14px;padding:32px 0;}',
    '.dekk-loading{text-align:center;color:var(--dekk-sub);font-size:13px;padding:14px 0;}',
    '.dekk-tabs{display:flex;padding:8px 0 14px;flex-wrap:wrap;}',
    '.dekk-tabs>*{margin:0 8px 8px 0;}',
    '.dekk-tab{padding:8px 14px;border-radius:16px;background:var(--dekk-fill);color:var(--dekk-sub);'
    + 'font-size:14px;cursor:pointer;}',
    '.dekk-tab[data-active="1"]{background:var(--dekk-brand);color:#fff;}',
    '.dekk-preview{width:100%;height:220px;border-radius:16px;object-fit:cover;background:var(--dekk-fill);'
    + 'display:block;margin:6px 0 14px;}',
    '.dekk-hero{padding:2px 2px 8px;}',
    '.dekk-hero__title{font-size:17px;font-weight:600;}',
    '.dekk-hero__sub{font-size:13px;color:var(--dekk-sub);margin-top:8px;line-height:1.6;white-space:pre-wrap;}',

    /* Toast / 提示条 */
    '#dekk-toast{position:fixed;left:50%;bottom:88px;transform:translateX(-50%);z-index:2147483600;'
    + 'background:rgba(0,0,0,.82);color:#fff;font-size:14px;line-height:1.5;padding:11px 18px;'
    + 'border-radius:14px;max-width:76vw;text-align:center;pointer-events:none;opacity:0;'
    + 'transition:opacity .16s ease;white-space:pre-wrap;}',
    '#dekk-toast[data-show="1"]{opacity:1;}',
    '#dekk-archivechip{position:fixed;left:50%;transform:translateX(-50%);bottom:150px;'
    + 'z-index:2147483100;display:none;background:rgba(0,0,0,.78);color:#fff;font-size:12.5px;'
    + 'padding:8px 14px;border-radius:16px;cursor:pointer;}',
    '#dekk-archivechip[data-show="1"]{display:block;}',
    '#dekk-float{position:fixed;left:10px;bottom:120px;z-index:2147483100;width:38px;height:38px;'
    + 'border-radius:50%;background:rgba(77,107,254,.9);color:#fff;font-size:11px;'
    + 'display:none;align-items:center;justify-content:center;flex-direction:column;cursor:pointer;'
    + 'box-shadow:0 4px 14px rgba(0,0,0,.25);user-select:none;}',
    '#dekk-float[data-show="1"]{display:flex;}',

    /* ---------------------------------------------------- 聊天美化（宿主页面） */
    '[data-dekk-role]{position:relative;}',
    '[data-dekk-role="ai"].dekk-bubble>[data-dekk-bubble="ai"]{background:var(--dekk-ai-bg,#ffffff)!important;'
    + 'border-radius:var(--dekk-radius,18px)!important;padding:10px 14px!important;display:inline-block;'
    + 'max-width:100%;}',
    '[data-dekk-role="user"].dekk-bubble>[data-dekk-bubble="user"]{background:var(--dekk-user-bg,#95ec69)!important;'
    + 'border-radius:var(--dekk-radius,18px)!important;padding:10px 14px!important;color:var(--dekk-user-fg,#111)!important;'
    + 'display:inline-block;max-width:100%;}',
    '[data-dekk-role="user"].dekk-bubble>[data-dekk-bubble="user"] *{color:inherit!important;}',
    '[data-dekk-role="user"].dekk-bubble{text-align:right;}',
    '[data-dekk-think="1"] .ds-think-content{border-radius:14px;background:rgba(128,128,128,.10);padding:8px 12px;}',
  ].join('\n');

  var theme = {
    isDark: isDark,
    label: function () { return isDark() ? 'dark' : 'light'; },

    /** 样式只注入一次 */
    inject: function () {
      if (document.getElementById(STYLE_ID)) return;
      var style = document.createElement('style');
      style.id = STYLE_ID;
      style.textContent = CSS;
      (document.head || document.documentElement).appendChild(style);
    },

    /** 把当前明暗写到 html / #dekk-root 上，供 CSS 变量使用 */
    apply: function () {
      var value = theme.label();
      document.documentElement.setAttribute(THEME_ATTR, value);
      var root = document.getElementById('dekk-root');
      if (root) root.setAttribute(THEME_ATTR, value);
      // 宿主的主题变量挂在 ds-dark-theme 上，#dekk-root 在 body 里能直接继承
    },

    /** 跟随宿主主题变化 */
    watch: function () {
      var onChange = util.debounce(function () {
        theme.apply();
        util.bus.emit('theme', theme.label());
      }, 120);
      try {
        var observer = new MutationObserver(onChange);
        observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme', 'style'] });
        if (document.body) observer.observe(document.body, { attributes: true, attributeFilter: ['class', 'data-theme', 'style'] });
      } catch (e) { }
      try {
        if (window.matchMedia) {
          var mq = window.matchMedia('(prefers-color-scheme: dark)');
          if (mq.addEventListener) mq.addEventListener('change', onChange);
        }
      } catch (e) { }
    },
  };

  D.theme = theme;
  D.css = CSS;
})(window.__DEKK__);
/* ===================== 03_texts.js ===================== */
/*
 * 文案表：尽量照抄安卓端注入页里的字符串，保证两端看起来一致。
 * 只保留现在页面里真正会出现的那些，删掉功能后不再留死文案。
 */
(function (D) {
  'use strict';
  if (!D) return;

  D.text = {
    /* ---------------------------------------------------------------- 首页 */
    settingsPageTitle: 'DEKK设置页面',
    menuEntry: 'DEKK模块',
    sectionAccount: '账号与人设中心',
    sectionPlugin: '插件与美化',
    sectionFeature: '功能',
    sectionAbout: '关于',

    /* ------------------------------------------------------------ 功能文案 */
    antiRecall: {
      title: '防撤回（本地存档）',
      desc: '还在测试中，不保证正确',
      chip: '检测到 %s 条消息已消失 · 查看本地存档',
      pageTitle: '本地存档',
      empty: '当前会话还没有存档',
      clearOne: '清空本会话存档',
      cleared: '已清空',
    },

    /* ------------------------------------------------------------ 聊天美化 */
    beautify: {
      title: '聊天美化',
      sectionBubble: '消息气泡',
      bubbleTitle: '微信对话气泡',
      bubbleDesc: '用户与AI独立气泡，自适应消息宽度；思考区保留独立折叠。切换后立即生效。',
      userColor: '用户气泡颜色',
      aiColor: 'AI气泡颜色',
      radius: '气泡圆角',
      radiusPicker: '气泡圆角',
      colorPicker: '选择颜色',
      saved: '已保存，立即生效',
    },

    /* -------------------------------------------------------- 服务端模块 */
    account: {
      loginEntry: '登录 / 注册',
      center: '个人中心',
      login: '登录',
      register: '注册',
      password: '修改密码',
      email: 'QQ邮箱',
      passwordField: '密码',
      passwordFieldNew: '密码（8—12字符）',
      nickname: '昵称',
      code: '6位验证码',
      sendCode: '发送验证码',
      resend: '重新发送（%s秒）',
      codeSent: '验证码已发送，5分钟内有效',
      submitLogin: '登录',
      submitRegister: '注册',
      submitPassword: '确认修改',
      needEmailPassword: '请填写邮箱和密码',
      passwordLength: '新密码应为8—12个字符',
      loginOk: '登录成功',
      registerOk: '注册成功，请登录',
      passwordOk: '密码已修改，旧登录已失效',
      registerAccount: '注册账号  ›',
      forgot: '忘记密码  ›',
      logout: '退出登录',
      logoutDone: '已退出登录',
      sectionAccountService: '账号服务',
      sectionAccountInfo: '账号',
      nicknameHint: '点击昵称可直接修改',
      nicknameTitle: '修改昵称',
      nicknameEmpty: '昵称不能为空',
      nicknameLong: '昵称最多 30 个字',
      nicknameSaved: '昵称已更新',
      changeAvatar: '点击更换头像',
      uploading: '正在压缩并上传头像…',
      avatarFail: '头像上传失败：',
      avatarOk: '头像已更新',
      statsFollows: '关注',
      statsFans: '粉丝',
      statsPersonas: '人设',
      statsLikes: '点赞',
    },

    persona: {
      market: '人设市场',
      mine: '我上传的',
      local: '已下载',
      mineEntry: '我的上传  ›',
      localEntry: '已下载  ›',
      marketEntry: '浏览人设市场  ›',
      searchHint: '搜索人设名称',
      search: '搜索',
      more: '加载更多',
      loading: '加载中…',
      retry: '重试加载',
      allLoaded: '已加载全部',
      emptyMarket: '没有找到匹配的人设',
      emptyLocal: '暂无匹配的本地人设',
      download: '下载',
      downloaded: '已下载',
      use: '使用',
      useBound: '已选择人设，下一次文本请求将附加',
      public: '公开',
      private: '私密',
      manage: '编辑管理  ›',
      saved: '已保存，可在聊天里使用',
      deleteLocal: '删除本地副本',
    },

    plugin: {
      market: '插件市场',
      mine: '我上传的',
      local: '已下载',
      searchHint: '搜索插件名称',
      emptyMarket: '没有找到匹配的插件',
      emptyLocal: '暂无匹配的本地插件',
      useBound: '已选择插件，下一次文本请求将附加',
    },

    about: {
      title: '关于 DEKK',
      version: '脚本版本',
      moduleVersion: '对应安卓模块版本',
      home: '官网 / 安卓版下载  ›',
      script: '复制脚本下载链接  ›',
      scriptCopied: '下载链接已复制',
      reset: '清空本脚本的全部本地数据',
      resetDesc: '设置、本地存档、登录态都会被清掉',
      resetDone: '已清空',
      resetConfirm: '确认清空本脚本的全部本地数据？',
      disclaimer: '本脚本只在你自己的浏览器里改页面，不改服务端数据；'
        + '附加到请求里的说明会真的发给 DeepSeek，请自行判断是否开启。',
    },

    common: {
      save: '保存',
      cancel: '取消',
      confirm: '确认',
      back: '返回',
      close: '关闭',
      loading: '加载中…',
      networkFail: '网络请求失败，请检查网络后重试',
      floatEntry: '悬浮入口',
      floatEntryDesc: '菜单入口注入失败时的兜底：屏幕左下角的小圆点也能打开 DEKK 设置页',
    },
  };
})(window.__DEKK__);
/* ===================== 04_store.js ===================== */
/*
 * 本地数据层：命名空间、键名都对齐安卓端的 SharedPreferences，
 * 便于两端对照排错（例如 dekk_prefs.device_context_enabled）。
 */
(function (D) {
  'use strict';
  if (!D || !D.util) return;

  var util = D.util;
  var NS = {
    prefs: 'dekk_prefs',
    beautify: 'dekk_beautify',
    fakeBan: 'dekk_fake_ban',
    account: 'dekk_account',
    profile: 'dekk_profile',
    personaLocal: 'dekk_persona_local',
    pluginLocal: 'dekk_plugin_local',
    personaSession: 'dekk_persona_session',
    pluginSession: 'dekk_plugin_session',
    prevQ: 'dekk_prev_q',
    archive: 'dekk_archive',
    ui: 'dekk_ui',
  };

  function pick(ns, key, fallback) { return util.store.read(ns, key, fallback); }
  function put(ns, key, value, event) {
    var ok = util.store.write(ns, key, value);
    if (ok && event) util.bus.emit(event, value);
    return ok;
  }

  var store = {
    NS: NS,

    /* ------------------------------------------------------------ 功能开关 */
    flag: function (key, fallback) { return !!pick(NS.prefs, key, fallback === undefined ? false : fallback); },
    setFlag: function (key, value, event) { return put(NS.prefs, key, !!value, event); },

    /* ------------------------------------------------------------ 防撤回 */
    antiRecall: function () { return store.flag('anti_recall'); },
    setAntiRecall: function (v) { return store.setFlag('anti_recall', v, 'recall'); },

    floatEntry: function () { return store.flag('float_entry', true); },
    setFloatEntry: function (v) { return store.setFlag('float_entry', v, 'float'); },

    debug: function () { return store.flag('debug_log'); },
    setDebug: function (v) { return store.setFlag('debug_log', v, 'debug'); },

    /* -------------------------------------------------------------- 账号 */
    account: function () { return util.store.all(NS.account, {}); },
    token: function () {
      var data = store.account();
      if (!data.token) return '';
      if (data.expires && Date.now() >= data.expires) { store.clearAccount(); return ''; }
      return String(data.token);
    },
    user: function () { return store.account().user || {}; },
    saveAccount: function (response) {
      var data = {
        token: String(response.token || ''),
        expires: Date.now() + (parseInt(response.expires_in, 10) || 0) * 1000,
        user: response.user || {},
      };
      util.store.write(NS.account, 'token', data.token);
      util.store.write(NS.account, 'expires', data.expires);
      util.store.write(NS.account, 'user', data.user);
      util.bus.emit('account', data);
      return data;
    },
    updateUser: function (user) {
      util.store.write(NS.account, 'user', user || {});
      util.bus.emit('account', store.account());
    },
    clearAccount: function () {
      util.store.clear(NS.account);
      util.bus.emit('account', {});
    },

    /* --------------------------------------------------- 个人资料缓存 */
    /** 资料本体（命名空间里存的是 {profile:{...}}，这里取里层） */
    profile: function () {
      var value = util.store.read(NS.profile, 'profile', null);
      return value && typeof value === 'object' ? value : {};
    },
    saveProfile: function (profile) {
      util.store.write(NS.profile, 'profile', profile || {});
      util.bus.emit('profile', profile || {});
    },
    nickname: function () {
      var name = store.profile().nickname;
      if (!name) name = store.user().nickname;
      return name ? String(name) : '未设置昵称';
    },

    /* ------------------------------------------------- 人设 / 插件（本地） */
    personaList: function () { return store.localList(NS.personaLocal); },
    pluginList: function () { return store.localList(NS.pluginLocal); },
    localList: function (ns) {
      var data = util.store.all(ns, {});
      return Object.keys(data).map(function (key) { return data[key]; })
        .filter(function (item) { return item && typeof item === 'object'; });
    },
    localSave: function (ns, item) {
      var id = String(item && item.id != null ? item.id : util.uid());
      util.store.write(ns, id, item);
      util.bus.emit('local', { ns: ns, id: id });
      return item;
    },
    localDelete: function (ns, id) {
      util.store.remove(ns, String(id));
      util.bus.emit('local', { ns: ns, id: String(id), deleted: true });
    },
    personaSave: function (item) { return store.localSave(NS.personaLocal, item); },
    pluginSave: function (item) { return store.localSave(NS.pluginLocal, item); },

    /* --------------------------------------------- 人设 / 插件一次性绑定 */
    bindSession: function (ns, item) {
      util.store.write(ns, 'name', item && item.name ? item.name : '');
      util.store.write(ns, 'prompt', item && item.prompt ? item.prompt : '');
      util.store.write(ns, 'pending', true);
      util.bus.emit('session-bind', { ns: ns, name: item && item.name });
    },
    pendingSession: function (ns) {
      if (!util.store.read(ns, 'pending', false)) return '';
      return String(util.store.read(ns, 'prompt', '') || '');
    },
    pendingSessionName: function (ns) { return String(util.store.read(ns, 'name', '') || ''); },
    consumeSession: function (ns) {
      util.store.write(ns, 'pending', false);
      util.store.remove(ns, 'prompt');
    },
    isPending: function (ns) { return store.pendingSession(ns).length > 0; },

    /* ---------------------------------------------------- 上一轮提问 */
    prevQuestion: function (sessionId) {
      if (!sessionId) return '';
      return String(util.store.read(NS.prevQ, sessionId, '') || '');
    },
    rememberQuestion: function (sessionId, text) {
      if (!sessionId || !text) return;
      util.store.write(NS.prevQ, sessionId, String(text));
    },
    clearPrevQuestions: function () { util.store.clear(NS.prevQ); },

    /* ------------------------------------------------------ 本地存档 */
    archiveGet: function (sessionId) {
      if (!sessionId) return null;
      return util.store.read(NS.archive, sessionId, null);
    },
    archiveSessions: function () { return util.store.keys(NS.archive); },
    archiveSave: function (sessionId, entry) {
      if (!sessionId || !entry) return;
      util.store.write(NS.archive, sessionId, entry);
    },
    archiveClear: function (sessionId) {
      util.store.remove(NS.archive, sessionId);
      util.bus.emit('archive', sessionId);
    },

    /* ------------------------------------------------------------ 美化 */
    beautify: {
      get: function (key, fallback) { return pick(NS.beautify, key, fallback); },
      set: function (key, value) { return put(NS.beautify, key, value, 'beautify'); },
      bubbles: function () { return !!pick(NS.beautify, 'wechat_bubbles', false); },
      userColor: function () { return String(pick(NS.beautify, 'bubble_user_color', '#95ec69') || '#95ec69'); },
      aiColor: function () { return String(pick(NS.beautify, 'bubble_assistant_color', '#ffffff') || '#ffffff'); },
      radius: function () { return util.clamp(parseInt(pick(NS.beautify, 'bubble_radius', 18), 10) || 18, 0, 30); },
      avatarEnabled: function () { return !!pick(NS.beautify, 'avatars_enabled', false); },
      avatarUser: function () { return String(pick(NS.beautify, 'avatar_user', '') || ''); },
      avatarAi: function () { return String(pick(NS.beautify, 'avatar_ai', '') || ''); },
      avatarSize: function () { return util.clamp(parseInt(pick(NS.beautify, 'avatar_size', 40), 10) || 40, 24, 56); },
      animations: function () { return !!pick(NS.beautify, 'animations', false); },
      composerStyle: function () { return util.clamp(parseInt(pick(NS.beautify, 'composer_style', 0), 10) || 0, 0, 2); },
    },

    /* --------------------------------------------------------- UI 状态 */
    ui: {
      get: function (key, fallback) { return pick(NS.ui, key, fallback); },
      set: function (key, value) { return put(NS.ui, key, value, 'ui'); },
      floatMoved: function () { return pick(NS.ui, 'float_left', true); },
    },

    /** 一键清空（关于页用） */
    resetAll: function () {
      Object.keys(NS).forEach(function (key) { util.store.clear(NS[key]); });
      util.bus.emit('reset');
    },
  };

  D.store = store;
})(window.__DEKK__);
/* ===================== 05_page.js ===================== */
/*
 * 页面框架 + 通用 UI 组件。
 *
 * 结构照 DeepSeek 网页版的「系统设置」弹窗：
 *   #dekk-root（遮罩 + 面板）
 *     .dekk-panel
 *       .dekk-panel__bar     ← 返回箭头 / 标题 / 关闭
 *       .dekk-panel__body    ← 可上下滑的内容区
 *
 * 子页面 = 路由（D.page.define），用栈管理；浏览器/系统返回键可以逐层退出。
 */
(function (D) {
  'use strict';
  if (!D || !D.util) return;

  var util = D.util;
  var ROOT_ID = 'dekk-root';
  var TOAST_ID = 'dekk-toast';

  var root = null;
  var stack = [];
  var routes = {};
  var pushedHistory = false;

  function ensureRoot() {
    if (root && root.parentNode) return root;
    root = document.getElementById(ROOT_ID);
    if (!root) {
      root = util.el('div', { attrs: { id: ROOT_ID } });
      (document.body || document.documentElement).appendChild(root);
    }
    D.theme && D.theme.apply();
    return root;
  }

  function updateOpen() {
    if (!root) return;
    root.setAttribute('data-open', stack.length > 0 ? '1' : '0');
  }

  function iconButton(kind, onClick, label) {
    var node = util.el('div', {
      className: 'dekk-iconbtn' + (kind === 'close' ? ' dekk-iconbtn--text' : ''),
      attrs: { role: 'button', 'aria-label': label },
      on: { click: onClick },
    });
    if (kind === 'back') {
      node.innerHTML = '<svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg>';
    } else {
      node.textContent = '✕';
    }
    return node;
  }

  function createView(def, params) {
    var mask = util.el('div', { className: 'dekk-mask' });
    var panel = util.el('div', { className: 'dekk-panel', attrs: { role: 'dialog', 'aria-modal': 'true' } });
    var bar = util.el('div', { className: 'dekk-panel__bar' });
    var title = util.el('div', { className: 'dekk-panel__title', text: def.title || '' });
    var body = util.el('div', { className: 'dekk-panel__body' });
    panel.appendChild(bar);
    panel.appendChild(body);

    var el = util.el('div', { className: 'dekk-page' }, [mask, panel]);
    mask.addEventListener('click', function () { D.page.close(); });

    return { el: el, mask: mask, panel: panel, bar: bar, title: title, body: body, def: def, params: params || {} };
  }

  function mountTop() {
    ensureRoot();
    root.innerHTML = '';
    var top = stack[stack.length - 1];
    if (!top) { updateOpen(); return; }
    var view = top.view;

    // 顶栏：子页面给返回箭头，最外层给关闭按钮（和宿主设置弹窗一致）
    view.bar.innerHTML = '';
    if (stack.length > 1) {
      view.bar.appendChild(iconButton('back', function () { D.page.back(); }, D.text.common.back));
    }
    view.bar.appendChild(view.title);
    view.bar.appendChild(iconButton('close', function () { D.page.close(); }, D.text.common.close));

    root.appendChild(view.el);
    updateOpen();

    // 关键：重绘前先清空内容并复位标题。
    // 不清空的话，从子页面返回时会再渲染一遍，页面内容会一层层叠上去。
    view.body.innerHTML = '';
    view.title.textContent = view.def.title || '';

    try {
      view.def.render(view.body, {
        params: view.params,
        title: function (text) { view.title.textContent = text; },
        refresh: function () { D.page.refresh(); },
        page: view,
      });
    } catch (e) {
      util.error('页面渲染失败', top.name, e);
      view.body.appendChild(util.el('div', { className: 'dekk-empty', text: '页面渲染失败：' + (e && e.message) }));
    }
  }

  var page = {
    define: function (name, def) { routes[name] = def; },

    open: function (name, params) {
      var def = routes[name];
      if (!def) { util.warn('未定义的路由', name); return; }
      stack.push({ name: name, view: createView(def, params) });
      if (!pushedHistory) {
        pushedHistory = true;
        try { history.pushState({ dekkPage: stack.length }, ''); } catch (e) { }
      }
      mountTop();
      return stack[stack.length - 1].view;
    },

    /** 清空栈只留一层（首页 / 登录这类入口用） */
    reset: function (name, params) {
      stack = [];
      pushedHistory = false;
      page.open(name, params);
    },

    replace: function (name, params) {
      if (stack.length) stack.pop();
      page.open(name, params);
    },

    refresh: function () {
      if (stack.length) mountTop();
    },

    back: function () {
      if (!stack.length) return;
      stack.pop();
      if (stack.length > 0) { mountTop(); return; }
      updateOpen();
      if (pushedHistory) {
        pushedHistory = false;
        try { if (history.state && history.state.dekkPage) history.back(); } catch (e) { }
      }
    },

    close: function () {
      stack = [];
      updateOpen();
      if (pushedHistory) {
        pushedHistory = false;
        try { if (history.state && history.state.dekkPage) history.back(); } catch (e) { }
      }
    },

    isOpen: function () { return stack.length > 0; },
    current: function () { return stack.length ? stack[stack.length - 1].name : ''; },

    /** 拦截系统返回键 / 浏览器返回 / ESC */
    installHistoryGuard: function () {
      window.addEventListener('popstate', function () {
        if (!stack.length) return;
        stack.pop();
        pushedHistory = false;
        if (stack.length) {
          mountTop();
          pushedHistory = true;
          try { history.pushState({ dekkPage: stack.length }, ''); } catch (e) { }
        } else updateOpen();
      });
      document.addEventListener('keydown', function (event) {
        if (event.key !== 'Escape' || !stack.length) return;
        event.stopPropagation();
        D.page.close();
      }, true);
    },
  };

  /* ------------------------------------------------------------------ Toast */
  var toastTimer = null;
  function toast(text, ms) {
    try {
      var node = document.getElementById(TOAST_ID);
      if (!node) {
        node = util.el('div', { attrs: { id: TOAST_ID } });
        (document.body || document.documentElement).appendChild(node);
      }
      node.textContent = String(text == null ? '' : text);
      node.setAttribute('data-show', '1');
      clearTimeout(toastTimer);
      toastTimer = setTimeout(function () { node.setAttribute('data-show', '0'); }, ms || 2200);
    } catch (e) { }
  }

  /* ---------------------------------------------------------------- 通用 UI */
  var ui = {
    section: function (parent, title) {
      var node = util.el('div', { className: 'dekk-section', text: title });
      parent.appendChild(node);
      return node;
    },

    card: function (parent) {
      var node = util.el('div', { className: 'dekk-card' });
      parent.appendChild(node);
      return node;
    },

    /** 普通行：{title, desc, value, arrow, onClick, right} */
    row: function (card, options) {
      var o = options || {};
      var main = util.el('div', { className: 'dekk-row__main' });
      if (o.title != null) main.appendChild(util.el('div', { className: 'dekk-row__title', text: o.title }));
      if (o.desc) main.appendChild(util.el('div', { className: 'dekk-row__desc', text: o.desc }));
      var row = util.el('div', { className: 'dekk-row' + (o.onClick ? ' dekk-row--tap' : '') }, [main]);
      if (o.value != null) row.appendChild(util.el('div', { className: 'dekk-row__value', text: o.value }));
      if (o.right) row.appendChild(o.right);
      if (o.arrow) row.appendChild(util.el('div', { className: 'dekk-arrow', text: '›' }));
      if (o.onClick) row.addEventListener('click', o.onClick);
      card.appendChild(row);
      return row;
    },

    /** 开关行：返回 {row, toggle, set(checked, silent)} */
    switchRow: function (card, options) {
      var o = options || {};
      var toggle = util.el('div', {
        className: 'dekk-switch',
        attrs: { role: 'switch', 'aria-checked': o.checked ? 'true' : 'false' },
      });
      function set(checked, silent) {
        var on = !!checked;
        toggle.setAttribute('data-on', on ? '1' : '0');
        toggle.setAttribute('aria-checked', on ? 'true' : 'false');
        if (!silent && typeof o.onChange === 'function') o.onChange(on);
      }
      var row = ui.row(card, {
        title: o.title,
        desc: o.desc,
        right: toggle,
        onClick: function () { set(toggle.getAttribute('data-on') !== '1'); },
      });
      set(!!o.checked, true);
      if (o.disabled) toggle.setAttribute('data-disabled', '1');
      return { row: row, toggle: toggle, set: set };
    },

    note: function (parent, text) {
      var node = util.el('div', { className: 'dekk-note', text: text });
      parent.appendChild(node);
      return node;
    },

    field: function (parent, options) {
      var o = options || {};
      var box = util.el('div', { className: 'dekk-field' });
      if (o.label) box.appendChild(util.el('div', { className: 'dekk-field__label', text: o.label }));
      var input = o.multiline ? util.el('textarea', { className: 'dekk-input' }) : util.el('input', { className: 'dekk-input' });
      if (o.type) input.type = o.type;
      if (o.placeholder) input.placeholder = o.placeholder;
      if (o.value != null) input.value = o.value;
      if (o.maxLength) input.maxLength = o.maxLength;
      if (o.onInput) input.addEventListener('input', function () { o.onInput(input.value); });
      box.appendChild(input);
      parent.appendChild(box);
      return input;
    },

    button: function (parent, options) {
      var o = options || {};
      var btn = util.el('button', {
        className: 'dekk-btn' + (o.kind === 'ghost' ? ' dekk-btn--ghost' : '')
          + (o.kind === 'danger' ? ' dekk-btn--danger' : '')
          + (o.small ? ' dekk-btn--sm' : ''),
        text: o.title,
        attrs: { type: 'button' },
      });
      btn.addEventListener('click', function () {
        if (btn.getAttribute('data-busy') === '1') return;
        if (typeof o.onClick !== 'function') return;
        var result = o.onClick(btn);
        if (result && typeof result.then === 'function' && o.busy) {
          btn.setAttribute('data-busy', '1');
          result.then(function () { btn.removeAttribute('data-busy'); }, function () { btn.removeAttribute('data-busy'); });
        }
      });
      if (o.block === false && parent) {
        var wrap = util.el('div', { className: 'dekk-actions' + (o.right ? ' dekk-actions--right' : '') }, [btn]);
        parent.appendChild(wrap);
        return btn;
      }
      if (parent) parent.appendChild(btn);
      return btn;
    },

    actions: function (parent, options) {
      var node = util.el('div', { className: 'dekk-actions' + (options && options.right ? ' dekk-actions--right' : '') });
      parent.appendChild(node);
      return node;
    },

    empty: function (parent, text) {
      var node = util.el('div', { className: 'dekk-empty', text: text });
      parent.appendChild(node);
      return node;
    },

    loading: function (parent, text) {
      var node = util.el('div', { className: 'dekk-loading', text: text || D.text.common.loading });
      parent.appendChild(node);
      return node;
    },

    item: function (parent) {
      var node = util.el('div', { className: 'dekk-item' });
      parent.appendChild(node);
      return node;
    },

    tabs: function (parent, items, active, onPick) {
      var box = util.el('div', { className: 'dekk-tabs' });
      items.forEach(function (item) {
        var tab = util.el('div', {
          className: 'dekk-tab',
          text: item.label,
          attrs: { 'data-active': item.value === active ? '1' : '0' },
        });
        tab.addEventListener('click', function () { onPick(item.value); });
        box.appendChild(tab);
      });
      parent.appendChild(box);
      return box;
    },

    preview: function (parent, src) {
      var img = util.el('img', { className: 'dekk-preview', attrs: { src: src, alt: '' } });
      parent.appendChild(img);
      return img;
    },

    /** 底部弹层选项 */
    choose: function (title, options) {
      return new Promise(function (resolve) {
        var overlay = util.el('div', {
          style: {
            position: 'fixed', top: '0', right: '0', bottom: '0', left: '0', zIndex: '2147483500',
            background: 'var(--dekk-mask,rgba(0,0,0,.45))',
            display: 'flex', alignItems: 'flex-end', justifyContent: 'center',
          },
        });
        var sheet = util.el('div', {
          style: {
            width: '100%', maxWidth: '560px', background: 'var(--dekk-bg,#fff)', color: 'var(--dekk-text,#1a1a1a)',
            borderRadius: '22px 22px 0 0', padding: '18px 18px 22px', boxSizing: 'border-box',
            maxHeight: '70vh', overflowY: 'auto',
          },
        });
        sheet.appendChild(util.el('div', { style: { fontWeight: '600', marginBottom: '12px' }, text: title || '' }));
        options.forEach(function (item) {
          var row = util.el('div', {
            className: 'dekk-row dekk-row--tap',
            text: item.label,
            style: { borderTop: '1px solid var(--dekk-line,rgba(0,0,0,.08))' },
          });
          row.addEventListener('click', function () { cleanup(); resolve(item.value); });
          sheet.appendChild(row);
        });
        var cancel = util.el('button', { className: 'dekk-btn dekk-btn--ghost', style: { marginTop: '16px' }, text: D.text.common.cancel });
        cancel.addEventListener('click', function () { cleanup(); resolve(null); });
        sheet.appendChild(cancel);
        overlay.appendChild(sheet);
        overlay.addEventListener('click', function (e) { if (e.target === overlay) { cleanup(); resolve(null); } });
        ensureRoot();
        root.appendChild(overlay);
        function cleanup() { util.remove(overlay); }
      });
    },

    /** 弹窗：{title, message, input?, placeholder?, confirmText?, cancelText?} → Promise<值|true|false> */
    dialog: function (config) {
      var o = config || {};
      return new Promise(function (resolve) {
        var overlay = util.el('div', {
          style: {
            position: 'fixed', top: '0', right: '0', bottom: '0', left: '0', zIndex: '2147483500',
            background: 'var(--dekk-mask,rgba(0,0,0,.45))',
            display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '22px',
          },
        });
        var box = util.el('div', {
          style: {
            width: '100%', maxWidth: '440px', background: 'var(--dekk-bg,#fff)', color: 'var(--dekk-text,#1a1a1a)',
            borderRadius: '22px', padding: '20px', boxSizing: 'border-box',
          },
        });
        if (o.title) box.appendChild(util.el('div', { style: { fontSize: '17px', fontWeight: '600' }, text: o.title }));
        if (o.message) {
          box.appendChild(util.el('div', {
            style: { marginTop: '10px', fontSize: '14px', color: 'var(--dekk-sub,#757575)', lineHeight: '1.6', whiteSpace: 'pre-wrap' },
            text: o.message,
          }));
        }
        var input = null;
        if (o.input) {
          input = util.el('input', { className: 'dekk-input', style: { marginTop: '14px' } });
          input.type = o.type || 'text';
          input.placeholder = o.placeholder || '';
          input.value = o.value || '';
          box.appendChild(input);
        }
        var actions = util.el('div', { className: 'dekk-actions dekk-actions--right', style: { marginTop: '18px' } });
        var cancel = util.el('button', { className: 'dekk-btn dekk-btn--ghost dekk-btn--sm', text: o.cancelText || D.text.common.cancel });
        var confirm = util.el('button', { className: 'dekk-btn dekk-btn--sm', text: o.confirmText || D.text.common.confirm });
        cancel.addEventListener('click', function () { cleanup(); resolve(o.input ? null : false); });
        confirm.addEventListener('click', function () { var value = input ? input.value : true; cleanup(); resolve(value); });
        actions.appendChild(cancel);
        actions.appendChild(confirm);
        box.appendChild(actions);
        overlay.appendChild(box);
        ensureRoot();
        root.appendChild(overlay);
        if (input) setTimeout(function () { try { input.focus(); } catch (e) { } }, 30);
        function cleanup() { util.remove(overlay); }
      });
    },
  };

  D.page = page;
  D.ui = ui;
  D.toast = toast;
})(window.__DEKK__);
/* ===================== 05b_dom.js ===================== */
/*
 * 宿主页面 DOM 适配层。
 *
 * 安卓端有 Xposed 可以精确 hook 组件，网页端只能按 DOM 找：
 *   - 输入框：textarea.ds-textarea__textarea（站点自己的类名）
 *   - 消息条目：.ds-message / .ds-assistant-message-main-content
 *   - 虚拟列表：每条的 [data-virtual-list-item-key="<消息id>"]
 * 这里把「找元素 + 模拟输入 + 模拟发送」都收在一处，宿主改版时只改这个文件。
 */
(function (D) {
  'use strict';
  if (!D || !D.util) return;

  var util = D.util;

  /* ------------------------------------------------------------- 消息区 */
  var chat = {
    /** 所有消息容器（虚拟列表只渲染可见部分） */
    items: function () { return util.$$('.ds-message'); },

    lastAssistantText: function () {
      var nodes = util.$$('.ds-assistant-message-main-content');
      if (!nodes.length) return '';
      return nodes[nodes.length - 1].innerText || '';
    },

    /** 当前会话 id：优先看地址栏 /a/chat/s/<id>，其次看网络层记录 */
    sessionId: function () {
      try {
        var match = location.pathname.match(/\/s\/([0-9a-f-]{8,})/i);
        if (match) return match[1];
      } catch (e) { }
      return D.net ? D.net.sessionId : '';
    },

    /** 会话切换 / 新消息时回调（节流） */
    watch: function (callback) {
      var run = util.throttle(function () {
        try { callback(); } catch (e) { util.error('消息区回调异常', e); }
      }, 400);
      var scheduled = util.debounce(run, 220);
      var observer = new MutationObserver(scheduled);
      observer.observe(document.documentElement, { childList: true, subtree: true });
      return observer;
    },
  };

  /* -------------------------------------------------------------- 输入框 */
  function textarea() {
    return util.$('textarea.ds-textarea__textarea') || util.$('textarea');
  }

  function setValue(node, text) {
    try {
      var descriptor = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
      if (descriptor && descriptor.set) descriptor.set.call(node, text);
      else node.value = text;
      node.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    } catch (e) {
      util.error('写入输入框失败', e);
      return false;
    }
  }

  /** 发送按钮：从输入框往上找容器，取右下方那个带 svg 的小方块 */
  function sendButton() {
    var node = textarea();
    if (!node) return null;
    var rect = node.getBoundingClientRect();
    var parent = node;
    for (var depth = 0; depth < 10 && parent; depth++) {
      parent = parent.parentElement;
      if (!parent) break;
      var candidates = util.$$('button, [role="button"]', parent).filter(function (el) {
        var box = el.getBoundingClientRect();
        if (box.width < 16 || box.width > 140 || box.height < 16 || box.height > 90) return false;
        if (!el.querySelector('svg')) return false;
        var toRight = box.left >= rect.right - 48;
        var below = box.top >= rect.top;
        return toRight && below;
      });
      if (candidates.length) {
        candidates.sort(function (a, b) {
          var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
          if (Math.abs(rb.left - ra.left) > 4) return rb.left - ra.left;
          return rb.top - ra.top;
        });
        return candidates[0];
      }
    }
    return null;
  }

  /** 宿主是否正在生成（生成中主按钮会变成「停止生成」） */
  function busy() {
    try {
      var button = sendButton();
      if (!button) return false;
      var tip = button.getAttribute('aria-label') || button.getAttribute('title') || '';
      if (tip.indexOf('停止') >= 0 || /stop/i.test(tip)) return true;
      return false;
    } catch (e) { return false; }
  }

  function pressEnter(node) {
    var event = new KeyboardEvent('keydown', {
      key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
      bubbles: true, cancelable: true, composed: true,
    });
    try { node.dispatchEvent(event); } catch (e) { return false; }
    return true;
  }

  /**
   * 模拟用户把 text 发出去（一问多答 / 自检用）。
   * 返回是否「看起来发出去了」——判据是宿主把输入框清了。
   */
  function submit(text) {
    var node = textarea();
    if (!node) return Promise.resolve(false);
    try { node.focus(); } catch (e) { }
    setValue(node, text);
    var step = util.sleep(80).then(function () {
      if (node.value !== text) {
        // React 没接住，重试一次
        setValue(node, text);
        return util.sleep(80);
      }
      return null;
    }).then(function () {
      pressEnter(node);
      return util.sleep(320);
    }).then(function () {
      if (node.value.trim() === '') return true;
      var button = sendButton();
      if (!button) return false;
      try { button.click(); } catch (e) { return false; }
      return util.sleep(420).then(function () { return node.value.trim() === ''; });
    });

    return step.then(function (ok) {
      if (!ok) util.warn('模拟发送失败：宿主没有清空输入框，可能需要手动补发');
      return ok;
    }, function () { return false; });
  }

  /* ---------------------------------------------- 历史对话多选（全选99） */
  var batch = {
    /** 多选状态栏（顶栏写着「选择对话 / 已选择 N 个对话」） */
    header: function () {
      var nodes = util.$$('div, span');
      for (var i = 0; i < nodes.length; i++) {
        var text = (nodes[i].textContent || '').trim();
        if (!text || text.length > 24) continue;
        if (text === '选择对话' || text.indexOf('已选择') === 0 || text === 'Select Chats' || /selected$/i.test(text)) {
          return nodes[i];
        }
      }
      return null;
    },

    /** 已选中数量（读顶栏文案） */
    selectedCount: function () {
      var header = batch.header();
      if (!header) return 0;
      var match = (header.textContent || '').match(/(\d+)/);
      return match ? parseInt(match[1], 10) : 0;
    },

    active: function () { return !!batch.header(); },

    /** 会话列表容器（虚拟列表） */
    list: function () {
      var header = batch.header();
      if (!header) return null;
      var node = header;
      for (var i = 0; i < 12 && node; i++) {
        node = node.parentElement;
        if (!node) break;
        if (node.scrollHeight > node.clientHeight + 40) return node;
      }
      return document.querySelector('.ds-scroll-area') || null;
    },

    /** 一屏内的会话行（多选态下每行都有 checkbox） */
    rows: function () {
      var boxes = util.$$('input[type="checkbox"]');
      var rows = [];
      boxes.forEach(function (box) {
        var row = box.closest('div[class]') || box.closest('div') || box.parentElement;
        if (!row) return;
        var rect = row.getBoundingClientRect();
        if (rect.width < 100 || rect.height < 24 || rect.height > 140) return;
        rows.push({ row: row, box: box, top: rect.top });
      });
      rows.sort(function (a, b) { return a.top - b.top; });
      return rows;
    },

    /** 点一下某行的勾选（点在 checkbox 上最稳） */
    toggle: function (item) {
      try {
        if (item.box.checked) return false;
        item.box.click();
        return true;
      } catch (e) { return false; }
    },
  };

  D.dom = {
    chat: chat,
    textarea: textarea,
    setValue: setValue,
    submit: submit,
    sendButton: sendButton,
    busy: busy,
    batch: batch,
    /** 按文本找元素（用于注入菜单项 / 全选按钮） */
    findByText: function (text, selector, root) {
      var nodes = util.$$(selector || '*', root);
      for (var i = 0; i < nodes.length; i++) {
        if ((nodes[i].textContent || '').trim() === text) return nodes[i];
      }
      return null;
    },
  };
})(window.__DEKK__);
/* ===================== 06_net.js ===================== */
/*
 * 网络拦截中枢。
 *
 * 安卓端靠 Xposed 挂在宿主的「请求序列化」和「流式事件」上；网页端有两条路：
 *   1) fetch —— 脚本自己的 API 调用、部分接口；
 *   2) XHR    —— **宿主的发送消息、历史消息其实都走这条**（实测 /api/v0/chat/completion
 *                与 /api/v0/chat/create_pow_challenge 都是 XMLHttpRequest）。
 * 所以两边都要挂：
 *   - 发请求前：允许改请求体（补充上下文 / 人设 / 插件靠这里真正注入）；
 *   - 拿响应时：把注入内容从宿主读到的内容里抹掉（JSON 递归洗、SSE 逐事件洗），
 *              同时把这些 SSE 事件喂给内部钩子（防撤回存档要用）。
 *
 * DeepSeek 网页端的关键接口：
 *   POST /api/v0/chat/completion        发送（body: {chat_session_id, parent_message_id, prompt, ...}）
 *   GET  /api/v0/chat/history_messages  历史消息
 *   POST /api/v0/chat/continue          继续生成
 */
(function (D) {
  'use strict';
  if (!D || !D.util) return;

  var util = D.util;
  var hooks = {
    request: [],
    json: [],
    stream: [],
    raw: [],
    /** 需要改写宿主读到的响应体（例如把附加到 prompt 里的内容从历史里抹掉） */
    rewrite: [],
  };

  var PATCH_FLAG = '__dekk_fetch_patched';

  var net = {
    /** 最近一次见到的会话 id（存档、上一轮提问、全选都要用） */
    sessionId: '',
    /** 事件名常量 */
    EVENTS: { delta: 'delta', ready: 'ready', close: 'close', finish: 'finish', hint: 'hint', toast: 'toast' },

    onRequest: function (fn) { hooks.request.push(fn); return function () { util.removeFrom(hooks.request, fn); }; },
    onJson: function (fn) { hooks.json.push(fn); return function () { util.removeFrom(hooks.json, fn); }; },
    onStream: function (fn) { hooks.stream.push(fn); return function () { util.removeFrom(hooks.stream, fn); }; },
    onRaw: function (fn) { hooks.raw.push(fn); return function () { util.removeFrom(hooks.raw, fn); }; },

    /**
     * 注册响应改写：matcher(url) 返回 true 才会读响应体；apply({url, json}) 改了返回 true。
     * 只有注册过改写、且 matcher 命中的响应才会被缓存下来（避免影响流式与大响应）。
     */
    addRewrite: function (matcher, apply) {
      hooks.rewrite.push({ matcher: matcher, apply: apply });
    },

    /** 是否有人关心这个 URL 的响应体 */
    needsRewrite: function (url) {
      for (var i = 0; i < hooks.rewrite.length; i++) {
        try { if (hooks.rewrite[i].matcher(url)) return true; } catch (e) { }
      }
      return false;
    },

    applyRewrite: function (url, json) {
      var changed = false;
      hooks.rewrite.forEach(function (item) {
        try {
          if (item.matcher(url) && item.apply({ url: url, json: json })) changed = true;
        } catch (e) { util.error('响应改写异常', e); }
      });
      return changed;
    },

    /** 是否命中某个接口 */
    is: function (url, suffix) { return String(url || '').indexOf(suffix) >= 0; },
    isCompletion: function (url) { return net.is(url, '/api/v0/chat/completion'); },
    isHistory: function (url) { return net.is(url, '/api/v0/chat/history_messages'); },

    /** 文本清洗：各功能注册自己的去块规则，本地显示时把附加内容藏起来 */
    strippers: [],
    addStripper: function (fn) { net.strippers.push(fn); },
    sanitize: function (text) {
      if (typeof text !== 'string' || !text) return text;
      var value = text;
      net.strippers.forEach(function (fn) {
        try { value = fn(value); } catch (e) { }
      });
      return value;
    },

    install: function () {
      if (window[PATCH_FLAG]) return;
      window[PATCH_FLAG] = true;
      net.installFetch();
      net.installXhr();
    },

    /** 宿主整套请求都是 fetch 时走这里（脚本自身的 API 调用也走 fetch） */
    installFetch: function () {
      var nativeFetch = window.fetch;
      if (typeof nativeFetch !== 'function') {
        util.warn('当前环境没有 fetch，功能会被限制');
        return;
      }

      window.fetch = function (input, init) {
        var url = '';
        var method = 'GET';
        try {
          if (typeof input === 'string') {
            url = input;
            method = (init && init.method) || 'GET';
          } else if (input && input.url) {
            url = input.url;
            method = (init && init.method) || input.method || 'GET';
          }
        } catch (e) { }

        var ctx = {
          url: url,
          method: String(method).toUpperCase(),
          body: null,
          bodyChanged: false,
          aborted: false,
          reason: '',
          init: init || null,
        };

        // 只在 JSON 字符串体上动手，FormData / Blob / ReadableStream 一律不动
        if (init && typeof init.body === 'string' && ctx.method !== 'GET') {
          try {
            var parsed = JSON.parse(init.body);
            if (parsed && typeof parsed === 'object') ctx.body = parsed;
          } catch (e) { /* 不是 JSON，保持原样 */ }
        }

        if (ctx.body && ctx.body.chat_session_id) net.sessionId = String(ctx.body.chat_session_id);

        for (var i = 0; i < hooks.request.length; i++) {
          try { hooks.request[i](ctx); } catch (e) { util.error('请求钩子异常', e); }
        }

        if (ctx.aborted) {
          util.log('请求被 DEKK 拦下：', ctx.reason || url);
          return Promise.reject(new Error('DEKK_BLOCKED:' + (ctx.reason || 'blocked')));
        }

        var finalInput = input;
        var finalInit = init;
        if (ctx.bodyChanged && ctx.body) {
          finalInit = {};
          for (var key in (init || {})) finalInit[key] = init[key];
          try { finalInit.body = JSON.stringify(ctx.body); } catch (e) { ctx.bodyChanged = false; }
        }

        var result;
        try {
          result = nativeFetch.call(this, finalInput, finalInit);
        } catch (e) {
          return Promise.reject(e);
        }

        return result.then(function (response) {
          try { return inspect(url, response); } catch (e) { util.error('响应解析异常', e); return response; }
        });
      };

      util.log('网络拦截已安装');
    },

    /**
     * 宿主的聊天请求其实走 XHR（不是 fetch），所以这里必须也挂上：
     *   - 发送前：改写请求体 —— 人设/插件/补充上下文靠这一步才能真正注入；
     *   - 读取时：把注入内容从 responseText / response 里抹掉，
     *            并把增量 SSE 事件喂给内部钩子（防撤回存档要用）。
     */
    installXhr: function () {
      var proto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
      if (!proto || proto.__dekkPatched) return;
      proto.__dekkPatched = true;

      var nativeOpen = proto.open;
      var nativeSend = proto.send;

      proto.open = function (method, url) {
        try {
          this.__dekk = {
            url: String(url || ''),
            method: String(method || 'GET').toUpperCase(),
            parsed: 0,
          };
        } catch (e) { }
        return nativeOpen.apply(this, arguments);
      };

      proto.send = function (body) {
        var info = this.__dekk;
        if (!info) return nativeSend.apply(this, arguments);

        var ctx = {
          url: info.url,
          method: info.method,
          body: null,
          bodyChanged: false,
          aborted: false,
          reason: '',
          xhr: true,
        };

        if (typeof body === 'string' && ctx.method !== 'GET') {
          try {
            var parsed = JSON.parse(body);
            if (parsed && typeof parsed === 'object') ctx.body = parsed;
          } catch (e) { /* 不是 JSON，保持原样 */ }
        }

        if (ctx.body && ctx.body.chat_session_id) net.sessionId = String(ctx.body.chat_session_id);

        for (var i = 0; i < hooks.request.length; i++) {
          try { hooks.request[i](ctx); } catch (e) { util.error('请求钩子异常(XHR)', e); }
        }

        if (shouldSanitize(info.url)) guardXhr(this, info);

        if (ctx.aborted) {
          util.log('XHR 请求被 DEKK 拦下：', ctx.reason || info.url);
          try { this.abort(); } catch (e) { }
          return;
        }

        var finalBody = body;
        if (ctx.body && ctx.bodyChanged) {
          try { finalBody = JSON.stringify(ctx.body); } catch (e) { finalBody = body; }
        }
        return nativeSend.call(this, finalBody);
      };

      util.log('XHR 拦截已安装');
    },
  };

  /** 取消注入：成对的标记，本地显示时整段抹掉（07_compose 直接用这份表） */
  net.markers = [
    ['[DEKK 系统提示词：设备状态（客户端附加上下文，非 system 角色）]', '[/DEKK 设备状态]'],
    ['[DEKK 上下文补充（客户端附加，非 system 角色）]', '[/DEKK 上下文补充]'],
    ['[DEKK 人设提示词（普通用户上下文）]', '[/DEKK 人设提示词]'],
    ['[DEKK 插件提示词（普通用户上下文）]', '[/DEKK 插件提示词]'],
    ['[DEKK AI禁言控制（普通用户上下文）]', '[/DEKK AI禁言控制]'],
  ];
  /** 附加内容后面跟着的正文标记 */
  net.userMark = '[用户消息]';

  /** 需要的接口才做响应体清洗（避免给所有请求都加一层缓冲） */
  function shouldSanitize(url) {
    return net.is(url, '/api/v0/chat/') || net.is(url, '/api/v0/share/');
  }

  /** 递归清洗 JSON 里的所有字符串 */
  function sanitizeValue(value) {
    if (typeof value === 'string') return net.sanitize(value);
    if (Array.isArray(value)) {
      for (var i = 0; i < value.length; i++) {
        var next = sanitizeValue(value[i]);
        if (next !== value[i]) value[i] = next;
      }
      return value;
    }
    if (value && typeof value === 'object') {
      for (var key in value) {
        if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
        var child = sanitizeValue(value[key]);
        if (child !== value[key]) value[key] = child;
      }
      return value;
    }
    return value;
  }

  /** 只处理 data: 行，事件名/空行原样保留 */
  function sanitizeSseChunk(chunk) {
    if (!chunk || chunk.indexOf('data:') < 0) return chunk;
    return chunk.split('\n').map(function (line) {
      if (line.indexOf('data:') !== 0) return line;
      var raw = line.slice(5).trim();
      if (!raw || raw === '[DONE]') return line;
      var json = null;
      try { json = JSON.parse(raw); } catch (e) { return line; }
      return 'data: ' + JSON.stringify(sanitizeValue(json));
    }).join('\n');
  }

  /** 把 SSE 流转成「清洗过」的流，宿主读到的东西里不会再有 DEKK 附加内容 */
  function sanitizeSse(response) {
    var reader = response.body.getReader();
    var decoder = new TextDecoder('utf-8');
    var encoder = new TextEncoder();
    var buffer = '';
    return new ReadableStream({
      pull: function (controller) {
        return reader.read().then(function (result) {
          if (result.done) {
            if (buffer) { controller.enqueue(encoder.encode(sanitizeSseChunk(buffer))); buffer = ''; }
            controller.close();
            return;
          }
          buffer += decoder.decode(result.value, { stream: true });
          var out = '';
          var index;
          while ((index = buffer.indexOf('\n\n')) >= 0) {
            out += sanitizeSseChunk(buffer.slice(0, index + 2));
            buffer = buffer.slice(index + 2);
          }
          if (out) controller.enqueue(encoder.encode(out));
        }, function (error) { controller.error(error); });
      },
      cancel: function () { try { reader.cancel(); } catch (e) { } },
    });
  }

  /** 返回【宿主真正读到的】那个 response（可能被我们改写） */
  function inspect(url, response) {
    if (!response || !url) return response;
    var type = '';
    try { type = response.headers.get('content-type') || ''; } catch (e) { }

    if (type.indexOf('text/event-stream') >= 0 || net.isCompletion(url)) {
      if (!response.body || !response.body.getReader) {
        parseStream(url, response.clone());
        return response;
      }
      parseStream(url, response.clone());
      try {
        return cloneWith(response, sanitizeSse(response));
      } catch (e) {
        util.error('流式内容清洗失败，放行原始数据', e);
        return response;
      }
    }

    if (type.indexOf('json') < 0) return response;
    if (!shouldSanitize(url) && !net.needsRewrite(url)) return response;

    return response.text().then(function (text) {
      var json = null;
      try { json = JSON.parse(text); } catch (e) { json = null; }
      if (!json) return cloneWith(response, text);
      var changed = net.applyRewrite(url, json);
      var before = JSON.stringify(json);
      sanitizeValue(json);
      if (before !== JSON.stringify(json)) changed = true;
      notifyJson(url, json);
      notifyRaw(url, JSON.stringify(json));
      return cloneWith(response, changed ? JSON.stringify(json) : text);
    }, function () { return response; });
  }

  /** 用同一份响应头和状态码，换一个 body 重建 Response */
  function cloneWith(response, text) {
    try {
      return new Response(text, {
        status: response.status,
        statusText: response.statusText,
        headers: response.headers,
      });
    } catch (e) {
      return response;
    }
  }

  function notifyRaw(url, text) {
    hooks.raw.forEach(function (fn) {
      try { fn({ url: url, text: text }); } catch (e) { util.error('raw 钩子异常', e); }
    });
  }

  function notifyJson(url, json) {
    hooks.json.forEach(function (fn) {
      try { fn({ url: url, json: json }); } catch (e) { util.error('json 钩子异常', e); }
    });
  }

  /** SSE：DeepSeek 用 event: xxx + data: {...} 的形式 */
  function parseStream(url, response) {
    var body = response.body;
    if (!body || !body.getReader) {
      // 老内核没有 ReadableStream：退化成整体文本
      response.text().then(function (text) {
        if (text) parseChunks(url, text);
      }, function () { });
      return;
    }
    var reader = body.getReader();
    var decoder = new TextDecoder('utf-8');
    var buffer = '';
    (function pump() {
      reader.read().then(function (result) {
        if (result.done) {
          if (buffer.trim()) parseChunks(url, buffer);
          return;
        }
        buffer += decoder.decode(result.value, { stream: true });
        var index;
        while ((index = buffer.indexOf('\n\n')) >= 0) {
          var chunk = buffer.slice(0, index);
          buffer = buffer.slice(index + 2);
          parseChunks(url, chunk);
        }
        pump();
      }, function () { /* 流被中断，忽略 */ });
    })();
  }

  /** 把 XHR 读到的东西清洗掉，并把增量 SSE 事件喂给内部钩子。
   *  两条支路：流式(SSE) → 洗文本 + 派发事件；普通 JSON（历史消息等）→ 过改写钩子 + 洗字符串。 */
  function guardXhr(xhr, info) {
    var textDesc = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
    var respDesc = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'response');

    var lastRaw = '';
    var lastClean = '';

    function contentType() {
      try { return String(xhr.getResponseHeader('content-type') || '').toLowerCase(); } catch (e) { return ''; }
    }

    function isSse() {
      if (contentType().indexOf('event-stream') >= 0) return true;
      return net.isCompletion(info.url)
        || net.is(info.url, '/api/v0/chat/continue')
        || net.is(info.url, '/api/v0/chat/regenerate')
        || net.is(info.url, '/api/v0/chat/resume_stream');
    }

    /** 增量很小时先复用上次结果，避免每个 progress 事件都全量重扫 */
    function clean(raw) {
      if (raw === lastRaw) return lastClean;
      if (lastRaw && raw.length > lastRaw.length && raw.indexOf(lastRaw) === 0
        && raw.length - lastRaw.length < 512) return lastClean;
      lastRaw = raw;
      lastClean = net.sanitize(raw);
      return lastClean;
    }

    /** 普通 JSON：先让改写钩子跑（防撤回就在这一步归档），再把附加内容洗掉 */
    function cleanJson(raw) {
      if (raw === lastRaw) return lastClean;
      lastRaw = raw;
      var json = null;
      try { json = JSON.parse(raw); } catch (e) { lastClean = net.sanitize(raw); return lastClean; }
      var before = JSON.stringify(json);
      try {
        net.applyRewrite(info.url, json);
        sanitizeValue(json);
      } catch (e) { util.error('XHR JSON 处理异常', e); }
      notifyJson(info.url, json);
      var after = JSON.stringify(json);
      lastClean = before === after ? raw : after;
      return lastClean;
    }

    /** responseType=json 时拿到的是对象，就地清洗 */
    function processObject(value) {
      try {
        var before = JSON.stringify(value);
        net.applyRewrite(info.url, value);
        sanitizeValue(value);
        notifyJson(info.url, value);
        if (JSON.stringify(value) !== before) return value;
      } catch (e) { }
      return value;
    }

    /** 只解析新增的完整 SSE 事件（按 \n\n 切） */
    function feed(raw) {
      if (raw.length <= info.parsed) return;
      var chunk = raw.slice(info.parsed);
      var cut = chunk.lastIndexOf('\n\n');
      if (cut < 0) return;
      var complete = chunk.slice(0, cut);
      info.parsed += cut + 2;
      complete.split('\n\n').forEach(function (part) {
        if (part && part.trim()) parseChunks(info.url, part);
      });
    }

    function readText() {
      var raw = textDesc ? textDesc.get.call(xhr) : xhr.responseText;
      if (typeof raw !== 'string') return raw;
      if (isSse()) {
        feed(raw);
        return clean(raw);
      }
      return cleanJson(raw);
    }

    function read() {
      try { return readText(); } catch (e) { return ''; }
    }

    try {
      if (textDesc) Object.defineProperty(xhr, 'responseText', { configurable: true, get: read });
      if (respDesc) {
        Object.defineProperty(xhr, 'response', {
          configurable: true,
          get: function () {
            var value = respDesc.get.call(xhr);
            if (value && typeof value === 'object') return processObject(value);
            return typeof value === 'string' ? read() : value;
          },
        });
      }
    } catch (e) {
      util.warn('XHR 响应清洗挂载失败', e);
    }
  }

  function parseChunks(url, chunk) {
    if (!chunk) return;
    var event = 'message';
    var dataLines = [];
    chunk.split(/\r?\n/).forEach(function (line) {
      if (line.indexOf('event:') === 0) event = line.slice(6).trim();
      else if (line.indexOf('data:') === 0) dataLines.push(line.slice(5).trim());
    });
    if (!dataLines.length) return;
    var raw = dataLines.join('\n');
    if (raw === '[DONE]') return;
    var data = null;
    try { data = JSON.parse(raw); } catch (e) { data = null; }
    net.emitStream(url, event, data, raw);
  }

  /** 把一次 SSE 事件派发给内部钩子（fetch 与 XHR 两条路共用） */
  net.emitStream = function (url, event, data, raw) {
    hooks.stream.forEach(function (fn) {
      try { fn({ url: url, event: event, data: data, raw: raw }); } catch (e) { util.error('stream 钩子异常', e); }
    });
  };

  // util 里补一个小工具，供钩子注销使用
  util.removeFrom = function (list, item) {
    var index = list.indexOf(item);
    if (index >= 0) list.splice(index, 1);
  };

  D.net = net;
})(window.__DEKK__);
/* ===================== 07_compose.js ===================== */
/*
 * 请求组装 + 附加内容的本地清洗。
 *
 * 附加内容只进「发给服务端的请求体」；宿主读到的任何东西（历史 JSON、流式 SSE）
 * 都会被 06_net 按 D.net.markers 清洗一遍，所以气泡里永远只有用户自己的话。
 *
 * 现在会附加的只有三样：
 *   补充上下文 → 人设 → 插件 →「[用户消息]」+ 原话
 */
(function (D) {
  'use strict';
  if (!D || !D.util || !D.net) return;

  var util = D.util;
  var store = D.store;
  var T = D.text;

  /* ---------------------------------------------------------- 文本清洗 */
  /** 删掉 start..end 之间的块（最多 8 次，和安卓端一致） */
  function stripBlock(text, start, end) {
    if (!text || text.indexOf(start) < 0) return text;
    var value = text;
    for (var i = 0; i < 8; i++) {
      var a = value.indexOf(start);
      if (a < 0) break;
      var b = value.indexOf(end, a + start.length);
      if (b < 0) break;
      value = value.slice(0, a) + value.slice(b + end.length);
    }
    return value;
  }

  /** 本地可见文本（等价安卓端 LocalMessageText.visible） */
  function visibleText(text) {
    if (typeof text !== 'string' || !text) return text;
    var value = text;

    // 老格式：设备状态块 + [用户消息] + 原话
    var deviceStart = D.net.markers[0][0];
    var deviceEnd = D.net.markers[0][1];
    for (var i = 0; i < 8 && value.indexOf(deviceStart) === 0; i++) {
      var end = value.indexOf(deviceEnd, deviceStart.length);
      if (end < 0) break;
      var pos = end + deviceEnd.length;
      while (pos < value.length && /\s/.test(value.charAt(pos))) pos++;
      if (value.indexOf(D.net.userMark, pos) !== pos) break;
      pos += D.net.userMark.length;
      if (value.slice(pos, pos + 2) === '\r\n') pos += 2;
      else if (value.charAt(pos) === '\n') pos += 1;
      value = value.slice(pos);
    }

    // 成对标记整段抹掉
    D.net.markers.forEach(function (pair) {
      value = stripBlock(value, pair[0], pair[1]);
    });

    // 剩下的 [用户消息] 之后才是原话
    var userPos = value.indexOf(D.net.userMark);
    if (userPos >= 0) {
      var cut = userPos + D.net.userMark.length;
      while (cut < value.length && /\s/.test(value.charAt(cut))) cut++;
      value = value.slice(cut);
    }
    return value;
  }

  /* -------------------------------------------------------------- 组装器 */
  var compose = {
    contributors: [],
    lastTurn: null,

    /** 注册一个内容来源：fn(turn) → 要附加的文本（空串表示不附加） */
    add: function (fn) { compose.contributors.push(fn); },

    install: function () {
      /* 1) 发送/编辑消息时把附加内容塞进 prompt */
      D.net.onRequest(function (ctx) {
        if (!ctx.body || typeof ctx.body.prompt !== 'string') return;
        if (!D.net.isCompletion(ctx.url) && !D.net.is(ctx.url, '/api/v0/chat/edit_message')) return;

        var original = ctx.body.prompt;
        if (!original.trim()) return;

        var sessionId = ctx.body.chat_session_id ? String(ctx.body.chat_session_id) : D.net.sessionId;
        var turn = { sessionId: sessionId, text: original, previous: '', parts: [] };
        var prefix = '';
        compose.contributors.forEach(function (fn) {
          var block = '';
          try { block = fn(turn) || ''; } catch (e) { util.error('附加内容生成失败', e); }
          if (!block) return;
          turn.parts.push(block);
          prefix += block;
        });

        compose.lastTurn = turn;
        if (!prefix) return;

        ctx.body.prompt = prefix + D.net.userMark + '\n' + original;
        ctx.bodyChanged = true;
        util.log('本轮附加内容长度：', prefix.length, '会话：', sessionId);

        // 人设 / 插件是一次性的：请求发出去就消耗掉
        if (store.isPending(store.NS.personaSession)) store.consumeSession(store.NS.personaSession);
        if (store.isPending(store.NS.pluginSession)) store.consumeSession(store.NS.pluginSession);
      });

      /* 2) 本地显示清洗（响应体的清洗由 06_net 统一做） */
      D.net.addStripper(visibleText);
    },

    visibleText: visibleText,
    stripBlock: stripBlock,
  };

  /* ------------------------------------------------------------ 各内容来源 */

  /** 人设 / 插件（一次性，绑定后下一条文本请求带上） */
  compose.add(function () {
    var output = '';
    var persona = store.pendingSession(store.NS.personaSession);
    if (persona) output += '[DEKK 人设提示词（普通用户上下文）]\n' + persona + '\n[/DEKK 人设提示词]\n\n';
    var plugin = store.pendingSession(store.NS.pluginSession);
    if (plugin) output += '[DEKK 插件提示词（普通用户上下文）]\n' + plugin + '\n[/DEKK 插件提示词]\n\n';
    return output;
  });

  D.compose = compose;
})(window.__DEKK__);
/* ===================== 10_recall.js ===================== */
/*
 * 防撤回 / 本地存档。
 *
 * 安卓端的实现是 hook 消息合并流程，把被标成撤回的快照顶回去；网页端没有这层，
 * 换成「本地留档」这条路：
 *   1) 每次拉历史消息时，把服务端返回的消息按 id 存进本地存档；
 *   2) 回答流式过程中，把正文片段累积下来，流结束就落一条存档
 *      （这样即使服务端随后把这条删了，本地也还有原文）；
 *   3) 服务端返回整表（cache_control=REPLACE）时，比对存档里曾经出现过的 id，
 *      发现有消失的就在聊天页挂个提示条，点开就是完整的存档。
 *
 * 默认关闭（安卓端也是强制关闭状态），打开后数据只存在这台设备的浏览器里。
 */
(function (D) {
  'use strict';
  if (!D || !D.util || !D.net) return;

  var util = D.util;
  var store = D.store;
  var T = D.text;

  var CHIP_ID = 'dekk-archivechip';
  var LIMIT = 400;

  var live = { text: '', sessionId: '' };
  var missing = {};

  function chip() {
    var node = document.getElementById(CHIP_ID);
    if (!node) {
      node = util.el('div', { attrs: { id: CHIP_ID } });
      node.addEventListener('click', function () {
        D.page.open('archive', { sessionId: D.dom ? D.dom.chat.sessionId() : D.net.sessionId });
      });
      (document.body || document.documentElement).appendChild(node);
    }
    return node;
  }

  function refreshChip() {
    var node = chip();
    var sessionId = D.dom ? D.dom.chat.sessionId() : D.net.sessionId;
    var count = missing[sessionId] || 0;
    if (!store.antiRecall() || !count) {
      node.setAttribute('data-show', '0');
      return;
    }
    node.textContent = T.antiRecall.chip.replace('%s', String(count));
    node.setAttribute('data-show', '1');
  }

  /* ------------------------------------------------------------ 存档读写 */
  function entryFor(sessionId) {
    var value = store.archiveGet(sessionId);
    if (!value || typeof value !== 'object') value = { messages: [], seen: [], updatedAt: 0 };
    if (!Array.isArray(value.messages)) value.messages = [];
    if (!Array.isArray(value.seen)) value.seen = [];
    return value;
  }

  function archiveMessage(sessionId, message) {
    if (!sessionId || !message || !message.content) return;
    var entry = entryFor(sessionId);
    var id = String(message.id || util.uid());
    var index = -1;
    for (var i = 0; i < entry.messages.length; i++) {
      if (entry.messages[i].id === id) { index = i; break; }
    }
    var record = { id: id, role: message.role || '', content: message.content, at: message.at || Date.now() };
    if (index >= 0) entry.messages[index] = record;
    else entry.messages.push(record);
    if (entry.seen.indexOf(id) < 0) entry.seen.push(id);
    if (entry.messages.length > LIMIT) entry.messages = entry.messages.slice(-LIMIT);
    entry.updatedAt = Date.now();
    store.archiveSave(sessionId, entry);
  }

  /** 解析流式 patch：{p:[路径], o:操作, v:值}，只关心正文片段 */
  function collect(data) {
    if (!data) return;
    if (Array.isArray(data)) {
      data.forEach(collect);
      return;
    }
    if (Array.isArray(data.v) && data.o === 'BATCH') {
      data.v.forEach(collect);
      return;
    }
    var path = data.p;
    var text = data.v;
    if (typeof text !== 'string') return;
    var isContent = false;
    if (Array.isArray(path)) {
      isContent = path.indexOf('content') >= 0 || path.indexOf('fragments') >= 0;
    }
    if (!isContent) return;
    if (data.o === 'SET') live.text = text;
    else live.text += text;
  }

  /* --------------------------------------------------------------- 安装 */
  var recall = {
    install: function () {
      /* 1) 历史消息：存档 + 比对消失的消息（在 compose 的清洗之后再跑） */
      D.net.addRewrite(function (url) { return D.net.isHistory(url); }, function (payload) {
        if (!store.antiRecall()) return false;
        var data = payload.json && payload.json.data && payload.json.data.biz_data;
        if (!data || !Array.isArray(data.chat_messages)) return false;
        var sessionId = (data.chat_session && data.chat_session.id) || D.net.sessionId;
        if (!sessionId) return false;
        var entry = entryFor(sessionId);

        if (data.cache_control === 'REPLACE') {
          var present = {};
          data.chat_messages.forEach(function (message) { present[String(message.message_id)] = true; });
          var gone = entry.seen.filter(function (id) { return !present[id] && id.indexOf('stream-') !== 0; });
          missing[sessionId] = gone.length;
          setTimeout(refreshChip, 0);
        }

        data.chat_messages.forEach(function (message) {
          archiveMessage(sessionId, {
            id: message.message_id,
            role: message.role,
            content: message.content,
            at: message.inserted_at ? Date.parse(message.inserted_at) : Date.now(),
          });
        });
        return false; // 不改响应体，归档是本地行为
      });

      /* 2) 流式回答：累积正文，结束时落一条存档 */
      D.net.onStream(function (payload) {
        if (!store.antiRecall()) return;
        if (payload.url && !D.net.isCompletion(payload.url)) return;
        var sessionId = D.dom ? D.dom.chat.sessionId() : D.net.sessionId;
        if (!live.sessionId || live.sessionId !== sessionId) {
          live.sessionId = sessionId;
          live.text = '';
        }
        if (payload.event === 'delta') collect(payload.data);
        if (payload.event === 'close' || payload.event === 'finish') {
          var text = live.text;
          live.text = '';
          if (text.trim()) {
            archiveMessage(sessionId, { id: 'stream-' + Date.now(), role: 'ASSISTANT', content: text, at: Date.now() });
          }
        }
      });

      /* 3) 换会话时刷新提示条 */
      if (D.dom) D.dom.chat.watch(function () { refreshChip(); });

      /* 4) 存档页 */
      D.page.define('archive', {
        title: T.antiRecall.pageTitle,
        render: function (body, ctx) {
          var sessionId = (ctx.params && ctx.params.sessionId) || (D.dom ? D.dom.chat.sessionId() : D.net.sessionId);
          var entry = sessionId ? store.archiveGet(sessionId) : null;
          if (!entry || !entry.messages || !entry.messages.length) {
            D.ui.empty(body, T.antiRecall.empty);
            return;
          }
          D.ui.section(body, '会话 ' + sessionId.slice(0, 12) + '…');
          var card = D.ui.card(body);
          var messages = entry.messages.slice().reverse().slice(0, 60);
          messages.forEach(function (message) {
            var isUser = String(message.role).toUpperCase().indexOf('USER') >= 0;
            var item = util.el('div', { style: { padding: '12px 0', borderTop: '1px solid var(--dekk-line)' } }, [
              util.el('div', {
                style: { fontSize: '12px', color: 'var(--dekk-sub)' },
                text: (isUser ? '我' : 'AI') + ' · ' + util.fmtDate(new Date(message.at || Date.now())),
              }),
              util.el('div', {
                style: { marginTop: '6px', fontSize: '14px', whiteSpace: 'pre-wrap', wordBreak: 'break-word' },
                text: message.content,
              }),
            ]);
            card.appendChild(item);
          });
          D.ui.button(body, {
            title: T.antiRecall.clearOne,
            kind: 'danger',
            onClick: function () {
              D.ui.dialog({ title: T.antiRecall.clearOne, message: '存档只在本机，清掉就找不回来了。' }).then(function (ok) {
                if (!ok) return;
                store.archiveClear(sessionId);
                missing[sessionId] = 0;
                refreshChip();
                D.toast(T.antiRecall.cleared);
                ctx.refresh();
              });
            },
          });
        },
      });

      setInterval(refreshChip, 3000);
    },

    refreshChip: refreshChip,
  };

  D.recall = recall;
})(window.__DEKK__);
/* ===================== 11_beautify.js ===================== */
/*
 * 聊天美化（对应安卓端 ChatBubbleHook）。
 *
 * 只保留「微信对话气泡」这一项：
 *   - 给 .ds-message 打 data-dekk-role，再用属性选择器改气泡颜色/圆角；
 *   - 颜色、圆角都写在 html 的 CSS 变量上，改完即时生效。
 * 聊天头像、消息动画、输入框毛玻璃/液态玻璃都已按需求移除。
 */
(function (D) {
  'use strict';
  if (!D || !D.util || !D.dom) return;

  var util = D.util;
  var store = D.store;
  var T = D.text;

  /* --------------------------------------------------------- 变量下发 */
  function applyVars() {
    var b = store.beautify;
    var root = document.documentElement;
    root.style.setProperty('--dekk-user-bg', b.userColor());
    root.style.setProperty('--dekk-ai-bg', b.aiColor());
    root.style.setProperty('--dekk-radius', b.radius() + 'px');
    root.setAttribute('data-dekk-think', b.bubbles() ? '1' : '0');
  }

  /* ------------------------------------------------------------- 气泡 */
  function findUserBubble(node) {
    var candidates = util.$$('div', node);
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      try {
        var style = window.getComputedStyle(el);
        var match = (style.backgroundColor || '').match(/rgba?\(([^)]+)\)/);
        if (!match) continue;
        var parts = match[1].split(',').map(function (v) { return parseFloat(v); });
        var alpha = parts.length > 3 ? parts[3] : 1;
        if (alpha <= 0.05) continue;
        if (style.borderTopLeftRadius === '0px' && style.borderTopRightRadius === '0px') continue;
        var rect = el.getBoundingClientRect();
        if (rect.width < 24 || rect.height < 18) continue;
        return el;
      } catch (e) { }
    }
    return null;
  }

  function tagMessages() {
    var on = store.beautify.bubbles();
    D.dom.chat.items().forEach(function (node) {
      try {
        var isAi = !!node.querySelector('.ds-assistant-message-main-content');
        var role = isAi ? 'ai' : 'user';
        if (node.getAttribute('data-dekk-role') !== role) node.setAttribute('data-dekk-role', role);

        var bubble = isAi
          ? node.querySelector('.ds-assistant-message-main-content')
          : (node.querySelector('[data-dekk-bubble="user"]') || findUserBubble(node));
        if (bubble && bubble !== node) bubble.setAttribute('data-dekk-bubble', role);

        if (on) node.classList.add('dekk-bubble');
        else node.classList.remove('dekk-bubble');
      } catch (e) { util.warn('气泡标注失败', e); }
    });
  }

  var beautify = {
    apply: function () {
      applyVars();
      tagMessages();
    },

    install: function () {
      applyVars();
      var observer = D.dom.chat.watch(function () { beautify.apply(); });
      util.bus.on('beautify', function () { beautify.apply(); });
      setTimeout(function () { beautify.apply(); }, 1500);
      return observer;
    },
  };

  /* ------------------------------------------------------------ 设置页 */
  beautify.installPages = function () {
    D.page.define('beautify', {
      title: T.beautify.title,
      render: function (body) {
        D.ui.section(body, T.beautify.sectionBubble);
        var card = D.ui.card(body);
        D.ui.switchRow(card, {
          title: T.beautify.bubbleTitle,
          desc: T.beautify.bubbleDesc,
          checked: store.beautify.bubbles(),
          onChange: function (on) {
            store.beautify.set('wechat_bubbles', on);
            beautify.apply();
          },
        });
        D.ui.row(card, {
          title: T.beautify.userColor,
          arrow: true,
          onClick: function () { D.page.open('bubbleColor', { key: 'bubble_user_color' }); },
        });
        D.ui.row(card, {
          title: T.beautify.aiColor,
          arrow: true,
          onClick: function () { D.page.open('bubbleColor', { key: 'bubble_assistant_color' }); },
        });
        D.ui.row(card, {
          title: T.beautify.radius,
          value: store.beautify.radius() + 'px',
          arrow: true,
          onClick: function () { D.page.open('bubbleRadius'); },
        });
      },
    });

    D.page.define('bubbleColor', {
      title: T.beautify.colorPicker,
      render: function (body, ctx) {
        var key = (ctx.params && ctx.params.key) || 'bubble_user_color';
        var isUser = key === 'bubble_user_color';
        var card = D.ui.card(body);
        var input = D.ui.field(card, {
          label: isUser ? T.beautify.userColor : T.beautify.aiColor,
          type: 'color',
          value: store.beautify.get(key, isUser ? '#95ec69' : '#ffffff'),
        });
        D.ui.button(body, {
          title: T.common.save,
          onClick: function () {
            store.beautify.set(key, input.value);
            beautify.apply();
            D.toast(T.beautify.saved);
            D.page.back();
          },
        });
      },
    });

    D.page.define('bubbleRadius', {
      title: T.beautify.radiusPicker,
      render: function (body) {
        var card = D.ui.card(body);
        var input = D.ui.field(card, { label: '0 - 30', type: 'range', value: store.beautify.radius() });
        input.min = '0';
        input.max = '30';
        var label = util.el('div', { className: 'dekk-note', text: store.beautify.radius() + 'px' });
        card.appendChild(label);
        input.addEventListener('input', function () { label.textContent = input.value + 'px'; });
        D.ui.button(body, {
          title: T.common.save,
          onClick: function () {
            store.beautify.set('bubble_radius', parseInt(input.value, 10) || 0);
            beautify.apply();
            D.toast(T.beautify.saved);
            D.page.back();
          },
        });
      },
    });
  };

  D.beautify = beautify;
})(window.__DEKK__);
/* ===================== 12_crypto.js ===================== */
/*
 * DEKK API V2 协议加密（服务端 服务端 crypto.php，安卓端 ApiCrypto.java）。
 *
 * 信封：
 *   master  = 32 随机字节（本次请求的会话密钥）
 *   encKey  = SHA256(master || "enc")        macKey = SHA256(master || "mac")
 *   iv      = 16 随机字节
 *   data    = AES-256-CBC/PKCS7(encKey, iv, 明文JSON)
 *   mac     = base64(HMAC-SHA256(macKey, iv || data))
 *   env     = base64(JSON{v,ts,nonce,key,iv,data,mac})，key = base64(RSA-PKCS1v1.5(master))
 * 请求：POST {action=名字&ver=v2}，body = {"v":2,"env":env}
 * 响应：JSON{v,iv,data,mac}，用同一把 master 解开；没有这些字段就是明文（错误信息/灰度）。
 *
 * 为什么要纯 JS 手写：宿主发消息走 XHR，响应是同步读 responseText 的，
 * WebCrypto 只有异步接口，会赶不上读取时机，所以这里全部用同步实现（RSA 用 BigInt）。
 */
(function (D) {
  'use strict';
  if (!D) return;

  /* ================================================================ 工具 */

  function utf8Bytes(text) {
    if (typeof TextEncoder === 'function') return new TextEncoder().encode(text);
    var escaped = unescape(encodeURIComponent(text)); // eslint-disable-line
    var out = new Uint8Array(escaped.length);
    for (var i = 0; i < escaped.length; i++) out[i] = escaped.charCodeAt(i) & 0xFF;
    return out;
  }

  function bytesUtf8(bytes) {
    if (typeof TextDecoder === 'function') return new TextDecoder('utf-8').decode(bytes);
    var binary = '';
    for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    try { return decodeURIComponent(escape(binary)); } // eslint-disable-line
    catch (e) { return binary; }
  }

  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  /** 反查表：解码时避免逐字符 indexOf（包体可能很大） */
  var B64_INDEX = (function () {
    var table = new Array(256);
    for (var i = 0; i < 256; i++) table[i] = -1;
    for (i = 0; i < B64.length; i++) table[B64.charCodeAt(i)] = i;
    return table;
  })();

  function base64Encode(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i += 3) {
      var b0 = bytes[i];
      var b1 = i + 1 < bytes.length ? bytes[i + 1] : NaN;
      var b2 = i + 2 < bytes.length ? bytes[i + 2] : NaN;
      out += B64.charAt(b0 >> 2);
      out += B64.charAt(((b0 & 3) << 4) | (isNaN(b1) ? 0 : b1 >> 4));
      out += isNaN(b1) ? '=' : B64.charAt(((b1 & 15) << 2) | (isNaN(b2) ? 0 : b2 >> 6));
      out += isNaN(b2) ? '=' : B64.charAt(b2 & 63);
    }
    return out;
  }

  function base64Decode(text) {
    var clean = String(text == null ? '' : text).replace(/[^A-Za-z0-9+/=]/g, '');
    var length = clean.length;
    while (length > 0 && clean.charAt(length - 1) === '=') length--;
    var out = new Uint8Array(Math.floor((length * 6) / 8));
    var buffer = 0;
    var bits = 0;
    var index = 0;
    for (var i = 0; i < length; i++) {
      var value = B64_INDEX[clean.charCodeAt(i) & 0xFF];
      if (value < 0) continue;
      buffer = (buffer << 6) | value;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        out[index++] = (buffer >> bits) & 0xFF;
        // 关键：把已取出的位丢掉，否则长字符串会超出 32 位精度、丢字节
        buffer &= (1 << bits) - 1;
      }
    }
    return index === out.length ? out : out.slice(0, index);
  }

  function concat(a, b) {
    var out = new Uint8Array(a.length + b.length);
    out.set(a, 0);
    out.set(b, a.length);
    return out;
  }

  function randomBytes(count) {
    var out = new Uint8Array(count);
    if (D.cryptoGetRandom) return D.cryptoGetRandom(out);
    if (window.crypto && window.crypto.getRandomValues) return window.crypto.getRandomValues(out);
    for (var i = 0; i < count; i++) out[i] = Math.floor(Math.random() * 256);
    return out;
  }

  function randomHex(byteCount) {
    var bytes = randomBytes(byteCount);
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      out += ('0' + bytes[i].toString(16)).slice(-2);
    }
    return out;
  }

  /* ============================================================= SHA-256 */

  var K256 = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ];

  function sha256(bytes) {
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var length = bytes.length;
    var withPadding = new Uint8Array((((length + 9) >> 6) + 1) << 6);
    withPadding.set(bytes, 0);
    withPadding[length] = 0x80;
    var bitLength = length * 8;
    // 末尾 8 字节写位长度（大端）；JS 数值够用（< 2^53）
    for (var i = 0; i < 8; i++) {
      withPadding[withPadding.length - 1 - i] = Math.floor(bitLength / Math.pow(2, 8 * i)) & 0xFF;
    }

    var W = new Array(64);
    for (var offset = 0; offset < withPadding.length; offset += 64) {
      for (var t = 0; t < 16; t++) {
        var j = offset + t * 4;
        W[t] = ((withPadding[j] << 24) | (withPadding[j + 1] << 16) | (withPadding[j + 2] << 8) | withPadding[j + 3]) >>> 0;
      }
      for (t = 16; t < 64; t++) {
        var w15 = W[t - 15];
        var w2 = W[t - 2];
        var s0 = (rotr(w15, 7) ^ rotr(w15, 18) ^ (w15 >>> 3)) >>> 0;
        var s1 = (rotr(w2, 17) ^ rotr(w2, 19) ^ (w2 >>> 10)) >>> 0;
        W[t] = (W[t - 16] + s0 + W[t - 7] + s1) >>> 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (t = 0; t < 64; t++) {
        var S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
        var ch = ((e & f) ^ (~e & g)) >>> 0;
        var temp1 = (h + S1 + ch + K256[t] + W[t]) >>> 0;
        var S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
        var maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
        var temp2 = (S0 + maj) >>> 0;
        h = g; g = f; f = e;
        e = (d + temp1) >>> 0;
        d = c; c = b; b = a;
        a = (temp1 + temp2) >>> 0;
      }
      H[0] = (H[0] + a) >>> 0; H[1] = (H[1] + b) >>> 0; H[2] = (H[2] + c) >>> 0; H[3] = (H[3] + d) >>> 0;
      H[4] = (H[4] + e) >>> 0; H[5] = (H[5] + f) >>> 0; H[6] = (H[6] + g) >>> 0; H[7] = (H[7] + h) >>> 0;
    }

    var out = new Uint8Array(32);
    for (var i2 = 0; i2 < 8; i2++) {
      out[i2 * 4] = (H[i2] >>> 24) & 0xFF;
      out[i2 * 4 + 1] = (H[i2] >>> 16) & 0xFF;
      out[i2 * 4 + 2] = (H[i2] >>> 8) & 0xFF;
      out[i2 * 4 + 3] = H[i2] & 0xFF;
    }
    return out;
  }

  function rotr(value, bits) {
    return ((value >>> bits) | (value << (32 - bits))) >>> 0;
  }

  function hmacSha256(keyBytes, data) {
    var blockSize = 64;
    var key = keyBytes.length > blockSize ? sha256(keyBytes) : keyBytes;
    var padded = new Uint8Array(blockSize);
    padded.set(key, 0);
    var oKey = new Uint8Array(blockSize);
    var iKey = new Uint8Array(blockSize);
    for (var i = 0; i < blockSize; i++) {
      oKey[i] = padded[i] ^ 0x5C;
      iKey[i] = padded[i] ^ 0x36;
    }
    return sha256(concat(oKey, sha256(concat(iKey, data))));
  }

  /* ========================================================== AES-CBC */

  var SBOX = null;
  var RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36, 0x6C, 0xD8, 0xAB, 0x4D];

  function buildSbox() {
    var sbox = new Uint8Array(256);
    var p = 1;
    var q = 1;
    do {
      p = (p ^ ((p << 1) & 0xFF) ^ ((p & 0x80) ? 0x1B : 0)) & 0xFF;
      q ^= (q << 1) & 0xFF;
      q ^= (q << 2) & 0xFF;
      q ^= (q << 4) & 0xFF;
      q &= 0xFF;
      if (q & 0x80) q ^= 0x09;
      var x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4));
      sbox[p] = (x ^ 0x63) & 0xFF;
    } while (p !== 1);
    sbox[0] = 0x63;
    return sbox;
  }

  function gmul(a, b) {
    var p = 0;
    for (var i = 0; i < 8; i++) {
      if (b & 1) p ^= a;
      var hi = a & 0x80;
      a = (a << 1) & 0xFF;
      if (hi) a ^= 0x1B;
      b >>= 1;
    }
    return p & 0xFF;
  }

  function expandKey(key) {
    var nk = key.length / 4;
    var rounds = nk + 6;
    var total = 4 * (rounds + 1);
    var w = new Uint8Array(total * 4);
    w.set(key, 0);
    for (var i = nk; i < total; i++) {
      var off = i * 4;
      var temp = [w[(i - 1) * 4], w[(i - 1) * 4 + 1], w[(i - 1) * 4 + 2], w[(i - 1) * 4 + 3]];
      if (i % nk === 0) {
        temp = [SBOX[temp[1]] ^ RCON[i / nk - 1], SBOX[temp[2]], SBOX[temp[3]], SBOX[temp[0]]];
      } else if (nk > 6 && i % nk === 4) {
        temp = [SBOX[temp[0]], SBOX[temp[1]], SBOX[temp[2]], SBOX[temp[3]]];
      }
      for (var j = 0; j < 4; j++) w[off + j] = w[(i - nk) * 4 + j] ^ temp[j];
    }
    return { words: w, rounds: rounds };
  }

  function encryptBlock(key, input, out, offset) {
    var state = new Uint8Array(16);
    state.set(input);
    addRoundKey(key, state, 0);
    for (var round = 1; round < key.rounds; round++) {
      subBytes(state);
      shiftRows(state);
      mixColumns(state);
      addRoundKey(key, state, round);
    }
    subBytes(state);
    shiftRows(state);
    addRoundKey(key, state, key.rounds);
    out.set(state, offset);
  }

  function decryptBlock(key, input, out, offset) {
    var state = new Uint8Array(16);
    state.set(input);
    addRoundKey(key, state, key.rounds);
    for (var round = key.rounds - 1; round > 0; round--) {
      invShiftRows(state);
      invSubBytes(state);
      addRoundKey(key, state, round);
      invMixColumns(state);
    }
    invShiftRows(state);
    invSubBytes(state);
    addRoundKey(key, state, 0);
    out.set(state, offset);
  }

  function addRoundKey(key, state, round) {
    var w = key.words;
    for (var i = 0; i < 16; i++) state[i] ^= w[round * 16 + i];
  }

  function subBytes(state) {
    for (var i = 0; i < 16; i++) state[i] = SBOX[state[i]];
  }

  function invSubBytes(state) {
    for (var i = 0; i < 16; i++) state[i] = INV_SBOX[state[i]];
  }

  function shiftRows(state) {
    var t = new Uint8Array(16);
    t[0] = state[0]; t[4] = state[4]; t[8] = state[8]; t[12] = state[12];
    t[1] = state[5]; t[5] = state[9]; t[9] = state[13]; t[13] = state[1];
    t[2] = state[10]; t[6] = state[14]; t[10] = state[2]; t[14] = state[6];
    t[3] = state[15]; t[7] = state[3]; t[11] = state[7]; t[15] = state[11];
    state.set(t);
  }

  function invShiftRows(state) {
    var t = new Uint8Array(16);
    t[0] = state[0]; t[4] = state[4]; t[8] = state[8]; t[12] = state[12];
    t[5] = state[1]; t[9] = state[5]; t[13] = state[9]; t[1] = state[13];
    t[10] = state[2]; t[14] = state[6]; t[2] = state[10]; t[6] = state[14];
    t[15] = state[3]; t[3] = state[7]; t[7] = state[11]; t[11] = state[15];
    state.set(t);
  }

  function mixColumns(state) {
    for (var c = 0; c < 4; c++) {
      var i = c * 4;
      var a0 = state[i], a1 = state[i + 1], a2 = state[i + 2], a3 = state[i + 3];
      state[i] = gmul(a0, 2) ^ gmul(a1, 3) ^ a2 ^ a3;
      state[i + 1] = a0 ^ gmul(a1, 2) ^ gmul(a2, 3) ^ a3;
      state[i + 2] = a0 ^ a1 ^ gmul(a2, 2) ^ gmul(a3, 3);
      state[i + 3] = gmul(a0, 3) ^ a1 ^ a2 ^ gmul(a3, 2);
    }
  }

  function invMixColumns(state) {
    for (var c = 0; c < 4; c++) {
      var i = c * 4;
      var a0 = state[i], a1 = state[i + 1], a2 = state[i + 2], a3 = state[i + 3];
      state[i] = gmul(a0, 14) ^ gmul(a1, 11) ^ gmul(a2, 13) ^ gmul(a3, 9);
      state[i + 1] = gmul(a0, 9) ^ gmul(a1, 14) ^ gmul(a2, 11) ^ gmul(a3, 13);
      state[i + 2] = gmul(a0, 13) ^ gmul(a1, 9) ^ gmul(a2, 14) ^ gmul(a3, 11);
      state[i + 3] = gmul(a0, 11) ^ gmul(a1, 13) ^ gmul(a2, 9) ^ gmul(a3, 14);
    }
  }

  var INV_SBOX = null;

  function initTables() {
    if (SBOX) return;
    SBOX = buildSbox();
    INV_SBOX = new Uint8Array(256);
    for (var i = 0; i < 256; i++) INV_SBOX[SBOX[i]] = i;
  }

  function pkcs7Pad(data) {
    var pad = 16 - (data.length % 16);
    var out = new Uint8Array(data.length + pad);
    out.set(data, 0);
    for (var i = data.length; i < out.length; i++) out[i] = pad;
    return out;
  }

  function pkcs7Unpad(data) {
    if (!data.length || data.length % 16 !== 0) return data;
    var pad = data[data.length - 1];
    if (pad < 1 || pad > 16 || pad > data.length) return data;
    for (var i = data.length - pad; i < data.length; i++) {
      if (data[i] !== pad) return data;
    }
    return data.slice(0, data.length - pad);
  }

  function aesCbcEncrypt(keyBytes, iv, plain) {
    initTables();
    var key = expandKey(keyBytes);
    var data = pkcs7Pad(plain);
    var out = new Uint8Array(data.length);
    var previous = iv;
    for (var offset = 0; offset < data.length; offset += 16) {
      var block = new Uint8Array(16);
      for (var i = 0; i < 16; i++) block[i] = data[offset + i] ^ previous[i];
      encryptBlock(key, block, out, offset);
      previous = out.slice(offset, offset + 16);
    }
    return out;
  }

  function aesCbcDecrypt(keyBytes, iv, cipher) {
    initTables();
    var key = expandKey(keyBytes);
    if (cipher.length % 16 !== 0) throw new Error('密文长度不合法');
    var out = new Uint8Array(cipher.length);
    var previous = iv;
    for (var offset = 0; offset < cipher.length; offset += 16) {
      var block = cipher.slice(offset, offset + 16);
      var plain = new Uint8Array(16);
      decryptBlock(key, block, plain, 0);
      for (var i = 0; i < 16; i++) out[offset + i] = plain[i] ^ previous[i];
      previous = block;
    }
    return pkcs7Unpad(out);
  }

  /* ==================================================== RSA（PKCS1 v1.5） */

  var PUBLIC_KEY_BASE64 =
    'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxSdKjVUftbGZ3+vOQKPbDiaBwUUtEl7AwyhnpdZxEMbGDKsG9oNPkJDjtdVcB1PFB6Hj7R63GQFjJkGuPTcHrf4fcp3nRyKr1kz/s76LH6eRp+ldEXygX20B3JlTTB0aSM/NKLNa9Wzp2ml2INsjbtzzCllwS9boCd/p7vnRSTKM4owqbY1VMq6cigj+4Ceg1rtF53n/W1q3AiptemzbViz0vUh7KXHL1ijnNWOih0q4cZi1wJ4hle6PiQW76ofWjwXIMAX9JhHbdozayYeX5GlOKXvqTJ9zVcIhK/NSfXPWhyDqsxD+meKMWFYb8nDjfpU7uSbMH1xRxY2bX/yiBQIDAQAB';

  /** 极简 DER 解析：从 SPKI 里取模数 n 和指数 e */
  function parsePublicKey(base64) {
    var der = base64Decode(base64);
    var index = 0;
    function readLength() {
      var first = der[index++];
      if (first < 0x80) return first;
      var count = first & 0x7F;
      var value = 0;
      for (var i = 0; i < count; i++) value = (value << 8) | der[index++];
      return value;
    }
    function readTag() { return der[index++]; }
    // SEQUENCE
    readTag();
    readLength();
    // 内层 SEQUENCE（算法标识）
    readTag();
    var algLength = readLength();
    index += algLength;
    // BIT STRING
    readTag();
    readLength();
    index++; // 未使用位数
    // 内层 SEQUENCE：INTEGER n, INTEGER e
    readTag();
    readLength();
    function readInteger() {
      readTag();
      var length = readLength();
      var start = index;
      index += length;
      var value = 0n;
      for (var i = start; i < start + length; i++) value = (value << 8n) | BigInt(der[i]);
      return value;
    }
    var n = readInteger();
    var e = readInteger();
    return { n: n, e: e, size: (n.toString(2).length + 7) >> 3 };
  }

  var cachedKey = null;

  function modPow(base, exponent, modulus) {
    var result = 1n;
    var b = base % modulus;
    var exp = exponent;
    while (exp > 0n) {
      if (exp & 1n) result = (result * b) % modulus;
      b = (b * b) % modulus;
      exp >>= 1n;
    }
    return result;
  }

  function rsaEncrypt(bytes) {
    if (!cachedKey) cachedKey = parsePublicKey(PUBLIC_KEY_BASE64);
    var key = cachedKey;
    var k = key.size;
    if (bytes.length > k - 11) throw new Error('RSA 明文过长');
    var psLength = k - 3 - bytes.length;
    var em = new Uint8Array(k);
    em[0] = 0x00;
    em[1] = 0x02;
    var ps = randomBytes(psLength);
    for (var i = 0; i < psLength; i++) em[2 + i] = ps[i] === 0 ? 1 : ps[i];
    em[2 + psLength] = 0x00;
    em.set(bytes, 3 + psLength);
    var m = 0n;
    for (i = 0; i < k; i++) m = (m << 8n) | BigInt(em[i]);
    var c = modPow(m, key.e, key.n);
    var out = new Uint8Array(k);
    for (i = k - 1; i >= 0; i--) {
      out[i] = Number(c & 0xFFn);
      c >>= 8n;
    }
    return out;
  }

  /* ============================================================== 信封 */

  function derive(master, label) {
    return sha256(concat(master, utf8Bytes(label)));
  }

  /**
   * 把明文打成信封。
   * 返回 { env: base64(信封JSON), master: Uint8Array(32) } ——响应要用同一把 master 解。
   */
  function seal(plaintext) {
    initTables();
    var master = randomBytes(32);
    var encKey = derive(master, 'enc');
    var macKey = derive(master, 'mac');
    var iv = randomBytes(16);
    var cipher = aesCbcEncrypt(encKey, iv, utf8Bytes(plaintext));
    var mac = base64Encode(hmacSha256(macKey, concat(iv, cipher)));
    var envelope = JSON.stringify({
      v: 2,
      ts: Math.floor(Date.now() / 1000),
      nonce: randomHex(16),
      key: base64Encode(rsaEncrypt(master)),
      iv: base64Encode(iv),
      data: base64Encode(cipher),
      mac: mac,
    });
    return { env: base64Encode(utf8Bytes(envelope)), master: master };
  }

  /** 解开响应；不是加密信封（明文/错误信息）时原样返回 */
  function open(text, master) {
    if (typeof text !== 'string' || !text) return text;
    var trimmed = text.trim();
    if (!trimmed || trimmed.charAt(0) !== '{') return text;
    var outer = null;
    try { outer = JSON.parse(trimmed); } catch (e) { return text; }
    if (!outer || !outer.data || !outer.iv || !outer.mac) return text;
    if (!master) throw new Error('缺少会话密钥');
    var encKey = derive(master, 'enc');
    var macKey = derive(master, 'mac');
    var iv = base64Decode(outer.iv);
    var cipher = base64Decode(outer.data);
    var expect = base64Encode(hmacSha256(macKey, concat(iv, cipher)));
    if (expect !== String(outer.mac)) throw new Error('响应校验失败');
    return bytesUtf8(aesCbcDecrypt(encKey, iv, cipher));
  }

  D.crypto = {
    seal: seal,
    open: open,
    base64Encode: base64Encode,
    base64Decode: base64Decode,
    sha256: sha256,
    hmacSha256: hmacSha256,
    aesCbcEncrypt: aesCbcEncrypt,
    aesCbcDecrypt: aesCbcDecrypt,
    randomBytes: randomBytes,
    /** 仅供本地自检使用 */
    _internals: { expandKey: expandKey, parsePublicKey: parsePublicKey, initTables: initTables },
  };
})(window.__DEKK__);
/* ===================== 13_api.js ===================== */
/*
 * DEKK 服务端接口（对应安卓端的 PersonaApi，已升级到 V2 加密协议）。
 *
 * V2 的规矩（服务端 服务端 crypto.php、安卓端 ApiCrypto.java）：
 *   - 所有请求统一 POST，URL 只带 action 名 + &ver=v2，参数不再明文放 URL；
 *   - 参数 = action 里带的 query（如 "market&page=1&search=x" 的 page/search）+ 原来的 JSON body；
 *   - 参数 JSON 打成加密信封，body = {"v":2,"env":"<信封>"}；
 *   - 响应优先按信封解开（用本次请求的 master），没有信封就按明文解析（错误信息/灰度）。
 * 不放心的话可以对着 tools/test-crypto.js 跑一遍：它拿 node:crypto 当参照物逐项对拍。
 */
(function (D) {
  'use strict';
  if (!D || !D.util) return;

  var util = D.util;
  var store = D.store;
  var T = D.text;

  var FALLBACK_BASE = 'https://dekk.example.com/API/?action=';
  function apiBase() {
    return typeof D.apiBase === 'string' && D.apiBase ? D.apiBase : FALLBACK_BASE;
  }

  function baseAction(action) {
    var index = String(action).indexOf('&');
    return index >= 0 ? String(action).slice(0, index) : String(action);
  }

  /** "page=1&search=a%20b" → {page:"1", search:"a b"} */
  function parseQuery(query) {
    var params = {};
    String(query || '').split('&').forEach(function (pair) {
      if (!pair) return;
      var eq = pair.indexOf('=');
      var key = eq < 0 ? pair : pair.slice(0, eq);
      var value = eq < 0 ? '' : pair.slice(eq + 1);
      try { params[decodeURIComponent(key)] = decodeURIComponent(value); }
      catch (e) { params[key] = value; }
    });
    return params;
  }

  /** V2 请求：参数进信封、统一 POST */
  function request(action, body) {
    var name = baseAction(action);
    var query = String(action).length > name.length ? String(action).slice(name.length + 1) : '';
    var params = parseQuery(query);
    if (body) {
      Object.keys(body).forEach(function (key) { params[key] = body[key]; });
    }

    var sealed = null;
    try {
      sealed = D.crypto.seal(JSON.stringify(params));
    } catch (e) {
      util.error('请求加密失败', e);
      return Promise.reject(new Error('本地加密失败，请更新脚本'));
    }

    var headers = {
      Accept: 'application/json',
      'Content-Type': 'application/json; charset=utf-8',
    };
    var token = store.token();
    if (token) headers.Authorization = 'Bearer ' + token;

    var url = apiBase() + name + '&ver=v2';
    util.log('API', name, '(V2-POST)');

    return fetch(url, {
      method: 'POST',
      headers: headers,
      body: JSON.stringify({ v: 2, env: sealed.env }),
      credentials: 'omit',
      cache: 'no-store',
    }).then(function (response) {
      return response.text().then(function (text) {
        var plain = text;
        try {
          plain = D.crypto.open(text, sealed.master);
        } catch (e) {
          util.error('响应解密失败', e);
          throw new Error('响应校验失败，请更新脚本后重试');
        }
        var json = null;
        try { json = JSON.parse(plain); } catch (e) { json = null; }
        if (response.status === 401) store.clearAccount();
        if (response.status < 200 || response.status >= 300 || !json || !json.ok) {
          var message = json && json.message ? String(json.message) : '请求失败（HTTP ' + response.status + '）';
          throw new Error(message);
        }
        return json;
      });
    }, function () {
      throw new Error(T.common.networkFail);
    });
  }

  /** 头像地址：优先用服务端下发的 avatar_url；没有就按 id 拼；
   *  连 id 都没有时用服务端的默认头像（?default=1），保证不会出现裂图。
   *  avatar 是公开端点（白名单），不需要 V2 信封。 */
  function avatarUrl(profile) {
    var value = profile || store.profile();
    if (value && value.avatar_url) return String(value.avatar_url);
    var id = value && value.id ? parseInt(value.id, 10) : 0;
    if (id > 0) {
      return apiBase().replace('?action=', '?action=avatar&id=') + id + '&v=' + Date.now();
    }
    return apiBase().replace('?action=', '?action=avatar&id=0&default=1');
  }

  D.api = {
    request: request,

    /* ------------------------------------------------------------ 账号 */
    sendCode: function (email, purpose) {
      return request('send_code', { email: email, purpose: purpose });
    },
    login: function (email, password) {
      return request('login', { email: email, password: password });
    },
    register: function (email, password, code, nickname) {
      return request('register', { email: email, password: password, code: code, nickname: nickname || '' });
    },
    password: function (email, password, code) {
      return request('password', { email: email, password: password, code: code });
    },
    logout: function () { return request('logout', {}); },

    /* ---------------------------------------------------------- 资料 */
    profile: function () { return request('profile_get', {}); },
    saveNickname: function (nickname) { return request('nickname', { nickname: nickname }); },
    uploadAvatar: function (base64) { return request('profile_avatar', { image: base64 }); },

    /* ---------------------------------------------------------- 人设 */
    personaMarket: function (page, search) {
      return request('market', { page: page, search: search || '' });
    },
    personaMine: function (page, search) {
      return request('mine', { page: page, search: search || '' });
    },
    personaDownload: function (id) { return request('download', { id: id }); },

    /* ---------------------------------------------------------- 插件 */
    pluginMarket: function (page, search) {
      return request('plugin_market', { page: page, search: search || '' });
    },
    pluginMine: function (page, search) {
      return request('plugin_mine', { page: page, search: search || '' });
    },
    pluginDownload: function (id) { return request('plugin_download', { id: id }); },

    /* ---------------------------------------------------------- 消息 */
    notices: function () { return request('notice_list', {}); },
    noticeUnread: function () { return request('notice_unread', {}); },
    noticeRead: function (id) { return request('notice_read', { id: id }); },

    /* ---------------------------------------------------------- 条目 */
    item: function (kind, id) { return request('item_get', { kind: kind, id: id }); },

    avatarUrl: avatarUrl,
  };
})(window.__DEKK__);
/* ===================== 14_pages.js ===================== */
/*
 * 设置页与各子页面。
 *
 * 布局对应安卓端的 DekkSettingsPage + CenterPage + AccountPages / UserCenterPage /
 * PersonaPages / PluginPages / NoticePage，分组、顺序、文案都照着来。
 */
(function (D) {
  'use strict';
  if (!D || !D.util || !D.page) return;

  var util = D.util;
  var ui = D.ui;
  var store = D.store;
  var api = D.api;
  var T = D.text;

  /* ============================================================= 首页 */
  function renderHome(body) {
    /* 账号与人设中心 */
    ui.section(body, T.sectionAccount);
    var accountCard = ui.card(body);
    ui.row(accountCard, {
      title: store.token() ? T.account.center : T.account.loginEntry,
      arrow: true,
      onClick: function () {
        if (store.token()) D.page.open('profile');
        else D.page.open('account', { mode: 'login' });
      },
    });
    ui.row(accountCard, {
      title: '人设市场  ›',
      onClick: function () { D.page.open('persona', { mode: 'market' }); },
    });

    /* 插件与美化 */
    ui.section(body, T.sectionPlugin);
    var pluginCard = ui.card(body);
    ui.row(pluginCard, {
      title: '插件中心  ›',
      onClick: function () { D.page.open('plugin', { mode: 'market' }); },
    });
    ui.row(pluginCard, {
      title: '聊天美化  ›',
      onClick: function () { D.page.open('beautify'); },
    });

    /* 功能 */
    ui.section(body, T.sectionFeature);

    var recallCard = ui.card(body);
    ui.switchRow(recallCard, {
      title: T.antiRecall.title,
      checked: store.antiRecall(),
      onChange: function (on) { store.setAntiRecall(on); },
    });
    ui.note(recallCard, T.antiRecall.desc);

    /* 关于 */
    ui.section(body, T.sectionAbout);
    var aboutCard = ui.card(body);
    ui.row(aboutCard, { title: T.about.title + '  ›', onClick: function () { D.page.open('about'); } });
    ui.switchRow(aboutCard, {
      title: T.common.floatEntry,
      desc: T.common.floatEntryDesc,
      checked: store.floatEntry(),
      onChange: function (on) {
        store.setFloatEntry(on);
        util.bus.emit('float', on);
      },
    });
  }

  /* ============================================================ 账号页 */
  function renderAccount(body, ctx) {
    var mode = (ctx.params && ctx.params.mode) || 'login';
    var titles = { login: T.account.login, register: T.account.register, password: T.account.password };
    ctx.title(titles[mode] || T.account.login);

    var head = util.el('div', { style: { textAlign: 'center', padding: '6px 0 18px' } });
    head.appendChild(util.el('div', { style: { fontSize: '30px', fontWeight: '700', color: 'var(--dekk-brand)' }, text: 'DEKK' }));
    head.appendChild(util.el('div', {
      style: { fontSize: '14px', color: 'var(--dekk-sub)', marginTop: '10px' },
      text: mode === 'login' ? '登录账号，同步你的人设'
        : (mode === 'register' ? '创建账号，收藏与分享人设' : '验证邮箱，设置新密码'),
    }));
    body.appendChild(head);

    ui.tabs(body, [
      { label: T.account.login, value: 'login' },
      { label: T.account.register, value: 'register' },
      { label: T.account.password, value: 'password' },
    ], mode, function (value) { D.page.replace('account', { mode: value }); });

    var card = ui.card(body);
    var email = ui.field(card, { label: T.account.email, type: 'email', value: store.user().email || '' });
    ui.note(card, '服务端目前只接受数字 QQ 邮箱（例如 123456@qq.com）');
    var password = ui.field(card, {
      label: mode === 'login' ? T.account.passwordField : T.account.passwordFieldNew,
      type: 'password',
    });
    var nickname = mode === 'register' ? ui.field(card, { label: T.account.nickname }) : null;
    var code = mode === 'login' ? null : ui.field(card, { label: T.account.code });

    if (mode !== 'login') {
      var sendButton = ui.button(card, {
        title: T.account.sendCode,
        kind: 'ghost',
        onClick: function () {
          var address = email.value.trim();
          if (!address) { D.toast(T.account.needEmailPassword); return; }
          if (!/^\d+@qq\.com$/i.test(address)) { D.toast('仅支持数字QQ邮箱，例如 123456@qq.com'); return; }
          sendButton.setAttribute('data-busy', '1');
          api.sendCode(address, mode === 'register' ? 'register' : 'password').then(function () {
            D.toast(T.account.codeSent);
            var left = 60;
            sendButton.textContent = T.account.resend.replace('%s', String(left));
            var timer = setInterval(function () {
              left--;
              if (left <= 0) {
                clearInterval(timer);
                sendButton.removeAttribute('data-busy');
                sendButton.textContent = T.account.sendCode;
                return;
              }
              sendButton.textContent = T.account.resend.replace('%s', String(left));
            }, 1000);
          }, function (error) {
            sendButton.removeAttribute('data-busy');
            D.toast(error.message);
          });
        },
      });
    }

    var submit = ui.button(body, {
      title: mode === 'login' ? T.account.submitLogin
        : (mode === 'register' ? T.account.submitRegister : T.account.submitPassword),
      onClick: function () {
        var address = email.value.trim();
        var secret = password.value;
        if (!address || !secret) { D.toast(T.account.needEmailPassword); return; }
        if (!/^\d+@qq\.com$/i.test(address)) { D.toast('仅支持数字QQ邮箱，例如 123456@qq.com'); return; }
        if (mode !== 'login' && (secret.length < 8 || secret.length > 12)) {
          D.toast(T.account.passwordLength);
          return;
        }
        submit.setAttribute('data-busy', '1');
        var task = mode === 'login'
          ? api.login(address, secret)
          : (mode === 'register'
            ? api.register(address, secret, code ? code.value.trim() : '', nickname ? nickname.value.trim() : '')
            : api.password(address, secret, code ? code.value.trim() : ''));
        task.then(function (result) {
          submit.removeAttribute('data-busy');
          if (mode === 'login') {
            store.saveAccount(result);
            D.toast(T.account.loginOk);
            D.page.replace('profile');
            return;
          }
          if (mode === 'register') {
            D.toast(T.account.registerOk);
            D.page.replace('account', { mode: 'login' });
            return;
          }
          store.clearAccount();
          D.toast(T.account.passwordOk);
          D.page.replace('account', { mode: 'login' });
        }, function (error) {
          submit.removeAttribute('data-busy');
          D.toast(error.message);
        });
      },
    });

    if (mode === 'login') {
      ui.section(body, T.account.sectionAccountService);
      var links = ui.card(body);
      ui.row(links, { title: T.account.registerAccount, onClick: function () { D.page.replace('account', { mode: 'register' }); } });
      ui.row(links, { title: T.account.forgot, onClick: function () { D.page.replace('account', { mode: 'password' }); } });
    }
  }

  /* ======================================================== 个人中心页 */
  function renderProfile(body, ctx) {
    if (!store.token()) { D.page.replace('account', { mode: 'login' }); return; }
    var card = ui.card(body);
    card.style.textAlign = 'center';

    var avatar = util.el('img', {
      className: 'dekk-avatar-img',
      style: { width: '88px', height: '88px', margin: '6px auto 0' },
      attrs: { src: api.avatarUrl(), alt: '' },
    });
    // 加载失败就退到服务端默认头像，避免出现裂图
    avatar.addEventListener('error', function () {
      var fallback = api.avatarUrl({});
      if (avatar.getAttribute('src') !== fallback) avatar.setAttribute('src', fallback);
    });
    card.appendChild(avatar);
    card.appendChild(util.el('div', {
      style: { fontSize: '12px', color: 'var(--dekk-sub)', marginTop: '8px' },
      text: T.account.changeAvatar,
    }));
    avatar.addEventListener('click', function () { pickAndUploadAvatar(function () { ctx.refresh(); }); });

    var nickname = util.el('div', {
      style: { fontSize: '20px', fontWeight: '600', marginTop: '10px', textAlign: 'center' },
      text: store.nickname(),
    });
    card.appendChild(nickname);
    card.appendChild(util.el('div', {
      style: { fontSize: '12px', color: 'var(--dekk-sub)', marginTop: '4px', marginBottom: '12px' },
      text: T.account.nicknameHint,
    }));
    nickname.addEventListener('click', function () {
      ui.dialog({ title: T.account.nicknameTitle, input: true, value: store.nickname() }).then(function (value) {
        if (value == null) return;
        var name = String(value).trim();
        if (!name) { D.toast(T.account.nicknameEmpty); return; }
        if (name.length > 30) { D.toast(T.account.nicknameLong); return; }
        api.saveNickname(name).then(function () {
          var profile = store.profile();
          profile.nickname = name;
          store.saveProfile(profile);
          var user = store.user();
          user.nickname = name;
          store.updateUser(user);
          nickname.textContent = name;
          D.toast(T.account.nicknameSaved);
        }, function (error) { D.toast(error.message); });
      });
    });

    var stats = [
      ['follows', T.account.statsFollows], ['fans', T.account.statsFans],
      ['personas', T.account.statsPersonas], ['likes', T.account.statsLikes],
    ];
    var statsRow = util.el('div', { style: { display: 'flex', marginTop: '6px' } });
    var values = {};
    stats.forEach(function (pair) {
      var column = util.el('div', { style: { flex: '1', textAlign: 'center' } });
      var value = util.el('div', { style: { fontSize: '18px', fontWeight: '600' }, text: '0' });
      values[pair[0]] = value;
      column.appendChild(value);
      column.appendChild(util.el('div', { style: { fontSize: '13px', color: 'var(--dekk-sub)', marginTop: '4px' }, text: pair[1] }));
      statsRow.appendChild(column);
    });
    card.appendChild(statsRow);

    function bind() {
      var profile = store.profile();
      nickname.textContent = store.nickname();
      avatar.src = api.avatarUrl(profile);
      stats.forEach(function (pair) { values[pair[0]].textContent = String(profile[pair[0]] || 0); });
    }
    bind();

    ui.section(body, T.account.sectionAccountInfo);
    var accountCard = ui.card(body);
    ui.row(accountCard, { title: '账号：' + (store.user().email || '未知') });
    ui.row(accountCard, { title: T.account.logout, arrow: true, onClick: function () { logout(ctx); } });
    ui.note(accountCard, '登录态只保存在本机浏览器，退出会同时清掉本地缓存的资料。');

    api.profile().then(function (result) {
      if (result && result.profile) { store.saveProfile(result.profile); bind(); }
    }, function (error) {
      // 登录态失效：直接回登录页，不然页面上的数字/头像都还是旧的
      if (error && String(error.message).indexOf('登录') >= 0) D.page.replace('account', { mode: 'login' });
    });
  }

  function logout(ctx) {
    ui.dialog({ title: T.account.logout, message: '退出后需要重新登录才能同步人设和插件。' }).then(function (ok) {
      if (!ok) return;
      api.logout().then(function () { }, function () { });
      store.clearAccount();
      store.saveProfile({});
      D.toast(T.account.logoutDone);
      ctx.refresh();
    });
  }

  function pickAndUploadAvatar(done) {
    var input = util.el('input', { attrs: { type: 'file', accept: 'image/*' } });
    input.style.position = 'fixed';
    input.style.left = '-9999px';
    document.body.appendChild(input);
    input.addEventListener('change', function () {
      var file = input.files && input.files[0];
      util.remove(input);
      if (!file) return;
      D.toast(T.account.uploading);
      var reader = new FileReader();
      reader.onload = function () {
        compressToBase64(String(reader.result || '')).then(function (base64) {
          return api.uploadAvatar(base64);
        }).then(function () {
          D.toast(T.account.avatarOk);
          if (done) done();
        }, function (error) {
          D.toast(T.account.avatarFail + (error && error.message ? error.message : ''));
        });
      };
      reader.readAsDataURL(file);
    });
    input.click();
  }

  /** 头像压到最长边 512 的 JPEG（对齐安卓端 AvatarUi.compress） */
  function compressToBase64(dataUrl) {
    return new Promise(function (resolve) {
      var image = new Image();
      image.onload = function () {
        try {
          var max = 512;
          var scale = Math.min(1, max / Math.max(image.width, image.height));
          var canvas = document.createElement('canvas');
          canvas.width = Math.max(1, Math.round(image.width * scale));
          canvas.height = Math.max(1, Math.round(image.height * scale));
          canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
          var out = canvas.toDataURL('image/jpeg', 0.85);
          resolve(out.slice(out.indexOf(',') + 1));
        } catch (e) {
          resolve(String(dataUrl).slice(String(dataUrl).indexOf(',') + 1));
        }
      };
      image.onerror = function () { resolve(String(dataUrl).slice(String(dataUrl).indexOf(',') + 1)); };
      image.src = dataUrl;
    });
  }

  /* ================================================= 人设 / 插件共用列表 */
  function renderMarket(body, ctx, kind) {
    var isPersona = kind === 'persona';
    var tb = isPersona ? T.persona : T.plugin;
    var mode = (ctx.params && ctx.params.mode) || 'market';
    var state = { page: 1, query: '', busy: false, done: false };
    var titles = { market: tb.market, mine: tb.mine, local: tb.local };
    ctx.title(titles[mode] || tb.market);

    /* 导航 */
    var nav = ui.card(body);
    if (mode !== 'mine') {
      ui.row(nav, {
        title: tb.mineEntry || '我的上传  ›',
        onClick: function () {
          if (!store.token()) { D.page.open('account', { mode: 'login' }); return; }
          D.page.replace(kind, { mode: 'mine' });
        },
      });
    }
    if (mode !== 'local') {
      ui.row(nav, {
        title: tb.localEntry || '已下载  ›',
        onClick: function () { D.page.replace(kind, { mode: 'local' }); },
      });
    } else {
      ui.row(nav, {
        title: tb.marketEntry || '浏览市场  ›',
        onClick: function () { D.page.replace(kind, { mode: 'market' }); },
      });
    }

    /* 搜索 */
    var searchCard = ui.card(body);
    var search = ui.field(searchCard, { label: tb.searchHint });
    var searchRow = util.el('div', { className: 'dekk-actions dekk-actions--right' });
    var searchButton = util.el('button', { className: 'dekk-btn dekk-btn--sm', text: tb.search });
    searchRow.appendChild(searchButton);
    searchCard.appendChild(searchRow);
    searchButton.addEventListener('click', function () { reload(); });
    search.addEventListener('keydown', function (event) { if (event.key === 'Enter') reload(); });

    var results = util.el('div');
    body.appendChild(results);
    var more = ui.button(body, { title: tb.more || T.persona.more, kind: 'ghost' });
    more.addEventListener('click', function () { load(); });

    function empty(text) { ui.empty(results, text); }

    function reload() {
      var query = search.value.trim();
      if (query.length > 100) { D.toast('名称搜索最多100字'); return; }
      state = { page: 1, query: query, busy: false, done: false };
      results.innerHTML = '';
      more.style.display = 'none';
      if (mode === 'local') {
        var list = (isPersona ? store.personaList() : store.pluginList()).filter(function (item) {
          return String(item.name || '').toLowerCase().indexOf(query.toLowerCase()) >= 0;
        });
        if (!list.length) empty(isPersona ? tb.emptyLocal : tb.emptyLocal);
        list.forEach(function (item) { renderLocal(item); });
        return;
      }
      more.style.display = '';
      load();
    }

    function load() {
      if (state.busy || state.done) return;
      state.busy = true;
      more.textContent = T.persona.loading;
      var page = state.page;
      var task = isPersona
        ? (mode === 'mine' ? api.personaMine(page, state.query) : api.personaMarket(page, state.query))
        : (mode === 'mine' ? api.pluginMine(page, state.query) : api.pluginMarket(page, state.query));
      task.then(function (result) {
        state.busy = false;
        more.textContent = tb.more || T.persona.more;
        var items = result.items || [];
        if (page === 1 && !items.length) empty(tb.emptyMarket);
        else items.forEach(function (item) { renderRemote(item); });
        state.page = page + 1;
        if (items.length < 20) {
          state.done = true;
          more.textContent = T.persona.allLoaded;
          more.setAttribute('data-busy', '1');
        }
      }, function (error) {
        state.busy = false;
        more.textContent = T.persona.retry;
        D.toast(error.message);
      });
    }

    function renderLocal(item) {
      var card = ui.item(results);
      card.appendChild(util.el('div', { className: 'dekk-item__title', text: item.name || '未命名' }));
      if (item.description) card.appendChild(util.el('div', { className: 'dekk-item__desc', text: item.description }));
      if (item.author) card.appendChild(util.el('div', { className: 'dekk-item__author', text: '作者：' + item.author }));
      var foot = util.el('div', { className: 'dekk-item__foot' });

      var use = util.el('button', { className: 'dekk-btn dekk-btn--sm dekk-btn--ghost', text: T.persona.use });
      use.addEventListener('click', function () {
        store.bindSession(isPersona ? store.NS.personaSession : store.NS.pluginSession, item);
        D.toast(isPersona ? T.persona.useBound : T.plugin.useBound);
      });
      foot.appendChild(use);

      var remove = util.el('button', { className: 'dekk-btn dekk-btn--sm dekk-btn--danger', text: T.persona.deleteLocal });
      remove.addEventListener('click', function () {
        store.localDelete(isPersona ? store.NS.personaLocal : store.NS.pluginLocal, item.id);
        D.toast('已删除本地副本');
        reload();
      });
      foot.appendChild(remove);
      card.appendChild(foot);
    }

    function renderRemote(item) {
      var card = ui.item(results);
      card.appendChild(util.el('div', { className: 'dekk-item__title', text: item.name || '未命名' }));
      if (item.description) card.appendChild(util.el('div', { className: 'dekk-item__desc', text: item.description }));
      if (item.author) card.appendChild(util.el('div', { className: 'dekk-item__author', text: '作者：' + item.author }));
      var foot = util.el('div', { className: 'dekk-item__foot' });

      var download = util.el('button', { className: 'dekk-btn dekk-btn--sm', text: T.persona.download });
      download.addEventListener('click', function () {
        download.setAttribute('data-busy', '1');
        var task = isPersona ? api.personaDownload(item.id) : api.pluginDownload(item.id);
        task.then(function (result) {
          var payload = isPersona ? result.persona : result.plugin;
          if (!payload) { D.toast('响应格式错误'); return; }
          if (isPersona) store.personaSave(payload);
          else store.pluginSave(payload);
          download.textContent = T.persona.downloaded;
          D.toast(T.persona.saved);
        }, function (error) {
          download.removeAttribute('data-busy');
          D.toast(error.message);
        });
      });
      foot.appendChild(download);
      card.appendChild(foot);

      if (mode === 'mine' && item.visibility) {
        card.appendChild(util.el('div', {
          className: 'dekk-item__author',
          text: (item.visibility === 'public' ? T.persona.public : T.persona.private) + ' · ' + T.persona.manage,
        }));
      }

      card.addEventListener('click', function (event) {
        if (event.target && event.target.tagName === 'BUTTON') return;
        D.page.open('itemDetail', { kind: kind, id: item.id, name: item.name });
      });
    }

    reload();
  }

  /* ====================================================== 条目详情 / 消息 */
  function renderItemDetail(body, ctx) {
    var kind = (ctx.params && ctx.params.kind) || 'persona';
    var id = ctx.params && ctx.params.id;
    var loading = ui.loading(body, T.common.loading);
    api.item(kind, id).then(function (result) {
      util.remove(loading);
      var item = result.item || {};
      ctx.title(item.name || '详情');
      var card = ui.card(body);
      card.appendChild(util.el('div', { className: 'dekk-item__title', text: item.name || '未命名' }));
      if (item.description) card.appendChild(util.el('div', { className: 'dekk-item__desc', text: item.description }));
      if (item.author) card.appendChild(util.el('div', { className: 'dekk-item__author', text: '作者：' + item.author }));
      if (item.prompt) {
        ui.section(body, '提示词');
        var promptCard = ui.card(body);
        promptCard.appendChild(util.el('div', {
          style: { fontSize: '14px', whiteSpace: 'pre-wrap', wordBreak: 'break-word', padding: '6px 0' },
          text: item.prompt,
        }));
        ui.button(body, {
          title: '复制提示词',
          kind: 'ghost',
          onClick: function () { util.copy(item.prompt).then(function () { D.toast('已复制'); }); },
        });
      }
    }, function (error) {
      util.remove(loading);
      ui.empty(body, error.message);
    });
  }

  /* ============================================================ 关于页 */
  function renderAbout(body, ctx) {
    var card = ui.card(body);
    ui.row(card, { title: T.about.version, value: 'v' + D.version });
    ui.row(card, { title: T.about.moduleVersion, value: D.moduleVersion });
    ui.row(card, { title: T.about.home, onClick: function () { window.open(D.home, '_blank'); } });
    ui.row(card, {
      title: T.about.script,
      onClick: function () { util.copy(D.download).then(function () { D.toast(T.about.scriptCopied); }); },
    });
    ui.note(card, T.about.disclaimer);

    ui.section(body, '数据');
    var dataCard = ui.card(body);
    ui.row(dataCard, {
      title: T.about.reset,
      onClick: function () {
        ui.dialog({ title: T.about.reset, message: T.about.resetConfirm }).then(function (ok) {
          if (!ok) return;
          store.resetAll();
          D.toast(T.about.resetDone);
          ctx.refresh();
        });
      },
    });
    ui.note(dataCard, T.about.resetDesc);
  }

  D.pages = {
    install: function () {
      D.page.define('home', { title: T.settingsPageTitle, render: renderHome });
      D.page.define('account', { title: T.account.login, render: renderAccount });
      D.page.define('profile', { title: T.account.center, render: renderProfile });
      D.page.define('persona', {
        title: T.persona.market,
        render: function (body, ctx) { renderMarket(body, ctx, 'persona'); },
      });
      D.page.define('plugin', {
        title: T.plugin.market,
        render: function (body, ctx) { renderMarket(body, ctx, 'plugin'); },
      });
      D.page.define('itemDetail', { title: '详情', render: renderItemDetail });
      D.page.define('about', { title: T.about.title, render: renderAbout });
    },
  };
})(window.__DEKK__);
/* ===================== 15_entry.js ===================== */
/*
 * 启动与入口注入。
 *
 * 入口位置对齐安卓端：「系统设置」下面多一行「DEKK模块」。
 * 网页版的「系统设置」在头像下拉菜单里（div.ds-dropdown-menu 里的
 * div.ds-dropdown-menu-option），所以做法是——等这个菜单出现，
 * 把「系统设置」那一行整行复制一份，改文案、挂自己的点击，插在它下面。
 * 复制宿主自己的行 = 图标、字号、间距、深浅色全部天然一致。
 *
 * 万一站点改版找不到菜单，会在左下角显示一个 DEKK 小圆点兜底。
 */
(function (D) {
  'use strict';
  if (!D || !D.util) return;

  var util = D.util;
  var store = D.store;
  var T = D.text;

  var MENU_ITEM_ID = 'dekk-menu-item';
  var FLOAT_ID = 'dekk-float';
  var settingsLabels = ['系统设置', '设置', 'Settings'];
  var menuInjected = false;
  var floatShown = false;

  /* ------------------------------------------------------------ 菜单项 */
  function findSettingsOption() {
    var menus = util.$$('div.ds-dropdown-menu');
    for (var i = 0; i < menus.length; i++) {
      var options = util.$$('.ds-dropdown-menu-option', menus[i]);
      for (var j = 0; j < options.length; j++) {
        var label = options[j].querySelector('.ds-dropdown-menu-option__label');
        var text = (label ? label.textContent : options[j].textContent || '').trim();
        if (settingsLabels.indexOf(text) >= 0) return { menu: menus[i], option: options[j], label: label };
      }
    }
    return null;
  }

  function injectMenuItem() {
    if (document.getElementById(MENU_ITEM_ID)) { menuInjected = true; return; }
    var found = findSettingsOption();
    if (!found) return;

    var clone = found.option.cloneNode(true);
    clone.setAttribute('id', MENU_ITEM_ID);
    clone.setAttribute('data-dekk', 'menu');
    var label = clone.querySelector('.ds-dropdown-menu-option__label');
    if (label) label.textContent = T.menuEntry;
    else clone.textContent = T.menuEntry;

    clone.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      if (typeof event.stopImmediatePropagation === 'function') event.stopImmediatePropagation();
      D.page.reset('home');
    }, true);
    clone.addEventListener('mousedown', function (event) {
      event.stopPropagation();
    }, true);

    try {
      found.option.parentNode.insertBefore(clone, found.option.nextSibling);
      menuInjected = true;
      // 记一笔：证明过这个版本能注入，下次就不用再显示兜底小圆点了
      store.setFlag('menu_injected', true);
      util.log('菜单入口已注入');
      hideFloat();
    } catch (e) {
      util.warn('菜单入口注入失败', e);
    }
  }

  /* ---------------------------------------------------------- 悬浮入口 */
  function floatEntry() {
    var node = document.getElementById(FLOAT_ID);
    if (!node) {
      node = util.el('div', {
        attrs: { id: FLOAT_ID, title: T.menuEntry },
        html: '<span>DEKK</span>',
        on: {
          click: function (event) {
            event.preventDefault();
            event.stopPropagation();
            D.page.reset('home');
          },
        },
      });
      (document.body || document.documentElement).appendChild(node);
    }
    return node;
  }

  function showFloat() {
    if (floatShown || !store.floatEntry()) return;
    floatShown = true;
    floatEntry().setAttribute('data-show', '1');
  }

  function hideFloat() {
    var node = document.getElementById(FLOAT_ID);
    if (node) node.setAttribute('data-show', '0');
  }

  /* ------------------------------------------------------------- 启动 */
  function boot() {
    if (D.started) return;
    D.started = true;

    try {
      util.debug = store.debug();
      D.theme.inject();
      D.theme.apply();
      D.theme.watch();

      /* 网络与功能：顺序和安卓端 DekkEntry.step 的顺序保持一致 */
      var steps = [
        ['网络拦截', function () { D.net.install(); }],
        ['请求组装', function () { D.compose.install(); }],
        ['防撤回存档', function () { D.recall.install(); }],
        ['聊天美化', function () { D.beautify.install(); D.beautify.installPages(); }],
        ['设置页面', function () { D.pages.install(); }],
      ];
      steps.forEach(function (step) {
        try {
          step[1]();
        } catch (e) {
          util.error('模块安装失败：' + step[0], e);
        }
      });

      D.page.installHistoryGuard();

      /* 入口：菜单项 + 兜底小圆点 */
      util.observe(function () {
        try { injectMenuItem(); } catch (e) { util.error('菜单注入异常', e); }
      }, { childList: true, subtree: true });

      setTimeout(function () {
        // 头像菜单的 DOM 只有点开时才有，所以这里判断不了「以后能不能注入」；
        // 只要以前成功注入过就先不打扰，等到 30 秒还没注入再兜底。
        if (menuInjected) return;
        if (!store.flag('menu_injected')) {
          util.warn('没找到「系统设置」菜单，启用左下角悬浮入口');
          showFloat();
          return;
        }
        util.log('此前注入成功过，稍后再确认一次');
      }, 12000);

      setTimeout(function () {
        if (menuInjected) return;
        util.warn('仍未注入菜单入口，启用左下角悬浮入口');
        showFloat();
      }, 30000);

      util.bus.on('float', function (on) {
        if (on) showFloat();
        else hideFloat();
      });

      /* 会话切换时刷新一下与当前会话相关的 UI（存档提示条等） */
      if (D.dom && D.dom.chat) {
        D.dom.chat.watch(function () {
          if (D.recall && D.recall.refreshChip) D.recall.refreshChip();
        });
      }

      util.info('DEKK 网页版 v' + D.version + ' 已启动（模块 v' + D.moduleVersion + '）');
    } catch (e) {
      util.error('DEKK 启动失败', e);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }

  // 体量很小的样式和网络补丁尽早装好，避免漏掉首屏请求
  try {
    D.theme.inject();
  } catch (e) { }
  D.entry = {
    boot: boot,
    injected: function () { return menuInjected; },
    showFloat: showFloat,
  };
})(window.__DEKK__);
