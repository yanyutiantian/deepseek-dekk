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