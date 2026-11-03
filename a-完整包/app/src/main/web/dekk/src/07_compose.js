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