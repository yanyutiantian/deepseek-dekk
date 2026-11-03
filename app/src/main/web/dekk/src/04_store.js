/*
 * 本地数据层：命名空间、键名都对齐安卓端的 SharedPreferences，
 * 便于两端对照排错（例如 dekk_prefs.device_context_enabled）。
 */
(function (D) {
  'use strict';
  if (!D || !D.util) return;

  var util = D.util;
  var NS = {
    prefs: 'dekk_prefs',
    beautify: 'dekk_beautify',
    fakeBan: 'dekk_fake_ban',
    account: 'dekk_account',
    profile: 'dekk_profile',
    personaLocal: 'dekk_persona_local',
    pluginLocal: 'dekk_plugin_local',
    personaSession: 'dekk_persona_session',
    pluginSession: 'dekk_plugin_session',
    prevQ: 'dekk_prev_q',
    archive: 'dekk_archive',
    ui: 'dekk_ui',
  };

  function pick(ns, key, fallback) { return util.store.read(ns, key, fallback); }
  function put(ns, key, value, event) {
    var ok = util.store.write(ns, key, value);
    if (ok && event) util.bus.emit(event, value);
    return ok;
  }

  var store = {
    NS: NS,

    /* ------------------------------------------------------------ 功能开关 */
    flag: function (key, fallback) { return !!pick(NS.prefs, key, fallback === undefined ? false : fallback); },
    setFlag: function (key, value, event) { return put(NS.prefs, key, !!value, event); },

    /* ------------------------------------------------------------ 防撤回 */
    antiRecall: function () { return store.flag('anti_recall'); },
    setAntiRecall: function (v) { return store.setFlag('anti_recall', v, 'recall'); },

    floatEntry: function () { return store.flag('float_entry', true); },
    setFloatEntry: function (v) { return store.setFlag('float_entry', v, 'float'); },

    debug: function () { return store.flag('debug_log'); },
    setDebug: function (v) { return store.setFlag('debug_log', v, 'debug'); },

    /* -------------------------------------------------------------- 账号 */
    account: function () { return util.store.all(NS.account, {}); },
    token: function () {
      var data = store.account();
      if (!data.token) return '';
      if (data.expires && Date.now() >= data.expires) { store.clearAccount(); return ''; }
      return String(data.token);
    },
    user: function () { return store.account().user || {}; },
    saveAccount: function (response) {
      var data = {
        token: String(response.token || ''),
        expires: Date.now() + (parseInt(response.expires_in, 10) || 0) * 1000,
        user: response.user || {},
      };
      util.store.write(NS.account, 'token', data.token);
      util.store.write(NS.account, 'expires', data.expires);
      util.store.write(NS.account, 'user', data.user);
      util.bus.emit('account', data);
      return data;
    },
    updateUser: function (user) {
      util.store.write(NS.account, 'user', user || {});
      util.bus.emit('account', store.account());
    },
    clearAccount: function () {
      util.store.clear(NS.account);
      util.bus.emit('account', {});
    },

    /* --------------------------------------------------- 个人资料缓存 */
    /** 资料本体（命名空间里存的是 {profile:{...}}，这里取里层） */
    profile: function () {
      var value = util.store.read(NS.profile, 'profile', null);
      return value && typeof value === 'object' ? value : {};
    },
    saveProfile: function (profile) {
      util.store.write(NS.profile, 'profile', profile || {});
      util.bus.emit('profile', profile || {});
    },
    nickname: function () {
      var name = store.profile().nickname;
      if (!name) name = store.user().nickname;
      return name ? String(name) : '未设置昵称';
    },

    /* ------------------------------------------------- 人设 / 插件（本地） */
    personaList: function () { return store.localList(NS.personaLocal); },
    pluginList: function () { return store.localList(NS.pluginLocal); },
    localList: function (ns) {
      var data = util.store.all(ns, {});
      return Object.keys(data).map(function (key) { return data[key]; })
        .filter(function (item) { return item && typeof item === 'object'; });
    },
    localSave: function (ns, item) {
      var id = String(item && item.id != null ? item.id : util.uid());
      util.store.write(ns, id, item);
      util.bus.emit('local', { ns: ns, id: id });
      return item;
    },
    localDelete: function (ns, id) {
      util.store.remove(ns, String(id));
      util.bus.emit('local', { ns: ns, id: String(id), deleted: true });
    },
    personaSave: function (item) { return store.localSave(NS.personaLocal, item); },
    pluginSave: function (item) { return store.localSave(NS.pluginLocal, item); },

    /* --------------------------------------------- 人设 / 插件一次性绑定 */
    bindSession: function (ns, item) {
      util.store.write(ns, 'name', item && item.name ? item.name : '');
      util.store.write(ns, 'prompt', item && item.prompt ? item.prompt : '');
      util.store.write(ns, 'pending', true);
      util.bus.emit('session-bind', { ns: ns, name: item && item.name });
    },
    pendingSession: function (ns) {
      if (!util.store.read(ns, 'pending', false)) return '';
      return String(util.store.read(ns, 'prompt', '') || '');
    },
    pendingSessionName: function (ns) { return String(util.store.read(ns, 'name', '') || ''); },
    consumeSession: function (ns) {
      util.store.write(ns, 'pending', false);
      util.store.remove(ns, 'prompt');
    },
    isPending: function (ns) { return store.pendingSession(ns).length > 0; },

    /* ---------------------------------------------------- 上一轮提问 */
    prevQuestion: function (sessionId) {
      if (!sessionId) return '';
      return String(util.store.read(NS.prevQ, sessionId, '') || '');
    },
    rememberQuestion: function (sessionId, text) {
      if (!sessionId || !text) return;
      util.store.write(NS.prevQ, sessionId, String(text));
    },
    clearPrevQuestions: function () { util.store.clear(NS.prevQ); },

    /* ------------------------------------------------------ 本地存档 */
    archiveGet: function (sessionId) {
      if (!sessionId) return null;
      return util.store.read(NS.archive, sessionId, null);
    },
    archiveSessions: function () { return util.store.keys(NS.archive); },
    archiveSave: function (sessionId, entry) {
      if (!sessionId || !entry) return;
      util.store.write(NS.archive, sessionId, entry);
    },
    archiveClear: function (sessionId) {
      util.store.remove(NS.archive, sessionId);
      util.bus.emit('archive', sessionId);
    },

    /* ------------------------------------------------------------ 美化 */
    beautify: {
      get: function (key, fallback) { return pick(NS.beautify, key, fallback); },
      set: function (key, value) { return put(NS.beautify, key, value, 'beautify'); },
      bubbles: function () { return !!pick(NS.beautify, 'wechat_bubbles', false); },
      userColor: function () { return String(pick(NS.beautify, 'bubble_user_color', '#95ec69') || '#95ec69'); },
      aiColor: function () { return String(pick(NS.beautify, 'bubble_assistant_color', '#ffffff') || '#ffffff'); },
      radius: function () { return util.clamp(parseInt(pick(NS.beautify, 'bubble_radius', 18), 10) || 18, 0, 30); },
      avatarEnabled: function () { return !!pick(NS.beautify, 'avatars_enabled', false); },
      avatarUser: function () { return String(pick(NS.beautify, 'avatar_user', '') || ''); },
      avatarAi: function () { return String(pick(NS.beautify, 'avatar_ai', '') || ''); },
      avatarSize: function () { return util.clamp(parseInt(pick(NS.beautify, 'avatar_size', 40), 10) || 40, 24, 56); },
      animations: function () { return !!pick(NS.beautify, 'animations', false); },
      composerStyle: function () { return util.clamp(parseInt(pick(NS.beautify, 'composer_style', 0), 10) || 0, 0, 2); },
    },

    /* --------------------------------------------------------- UI 状态 */
    ui: {
      get: function (key, fallback) { return pick(NS.ui, key, fallback); },
      set: function (key, value) { return put(NS.ui, key, value, 'ui'); },
      floatMoved: function () { return pick(NS.ui, 'float_left', true); },
    },

    /** 一键清空（关于页用） */
    resetAll: function () {
      Object.keys(NS).forEach(function (key) { util.store.clear(NS[key]); });
      util.bus.emit('reset');
    },
  };

  D.store = store;
})(window.__DEKK__);