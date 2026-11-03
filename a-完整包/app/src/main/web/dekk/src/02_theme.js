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