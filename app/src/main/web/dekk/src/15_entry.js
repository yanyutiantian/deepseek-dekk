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