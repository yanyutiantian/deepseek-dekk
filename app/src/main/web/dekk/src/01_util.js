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