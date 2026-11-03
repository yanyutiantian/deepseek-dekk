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