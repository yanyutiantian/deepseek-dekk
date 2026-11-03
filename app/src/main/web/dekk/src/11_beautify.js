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