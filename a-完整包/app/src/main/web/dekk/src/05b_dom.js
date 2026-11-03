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