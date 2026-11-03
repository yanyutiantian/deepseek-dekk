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