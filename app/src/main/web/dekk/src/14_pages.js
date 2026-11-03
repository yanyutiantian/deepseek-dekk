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