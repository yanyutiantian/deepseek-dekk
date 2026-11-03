/*
 * DEKK 服务端接口（对应安卓端的 PersonaApi，已升级到 V2 加密协议）。
 *
 * V2 的规矩（服务端 服务端 crypto.php、安卓端 ApiCrypto.java）：
 *   - 所有请求统一 POST，URL 只带 action 名 + &ver=v2，参数不再明文放 URL；
 *   - 参数 = action 里带的 query（如 "market&page=1&search=x" 的 page/search）+ 原来的 JSON body；
 *   - 参数 JSON 打成加密信封，body = {"v":2,"env":"<信封>"}；
 *   - 响应优先按信封解开（用本次请求的 master），没有信封就按明文解析（错误信息/灰度）。
 * 不放心的话可以对着 tools/test-crypto.js 跑一遍：它拿 node:crypto 当参照物逐项对拍。
 */
(function (D) {
  'use strict';
  if (!D || !D.util) return;

  var util = D.util;
  var store = D.store;
  var T = D.text;

  var FALLBACK_BASE = 'https://dekk.example.com/API/?action=';
  function apiBase() {
    return typeof D.apiBase === 'string' && D.apiBase ? D.apiBase : FALLBACK_BASE;
  }

  function baseAction(action) {
    var index = String(action).indexOf('&');
    return index >= 0 ? String(action).slice(0, index) : String(action);
  }

  /** "page=1&search=a%20b" → {page:"1", search:"a b"} */
  function parseQuery(query) {
    var params = {};
    String(query || '').split('&').forEach(function (pair) {
      if (!pair) return;
      var eq = pair.indexOf('=');
      var key = eq < 0 ? pair : pair.slice(0, eq);
      var value = eq < 0 ? '' : pair.slice(eq + 1);
      try { params[decodeURIComponent(key)] = decodeURIComponent(value); }
      catch (e) { params[key] = value; }
    });
    return params;
  }

  /** V2 请求：参数进信封、统一 POST */
  function request(action, body) {
    var name = baseAction(action);
    var query = String(action).length > name.length ? String(action).slice(name.length + 1) : '';
    var params = parseQuery(query);
    if (body) {
      Object.keys(body).forEach(function (key) { params[key] = body[key]; });
    }

    var sealed = null;
    try {
      sealed = D.crypto.seal(JSON.stringify(params));
    } catch (e) {
      util.error('请求加密失败', e);
      return Promise.reject(new Error('本地加密失败，请更新脚本'));
    }

    var headers = {
      Accept: 'application/json',
      'Content-Type': 'application/json; charset=utf-8',
    };
    var token = store.token();
    if (token) headers.Authorization = 'Bearer ' + token;

    var url = apiBase() + name + '&ver=v2';
    util.log('API', name, '(V2-POST)');

    return fetch(url, {
      method: 'POST',
      headers: headers,
      body: JSON.stringify({ v: 2, env: sealed.env }),
      credentials: 'omit',
      cache: 'no-store',
    }).then(function (response) {
      return response.text().then(function (text) {
        var plain = text;
        try {
          plain = D.crypto.open(text, sealed.master);
        } catch (e) {
          util.error('响应解密失败', e);
          throw new Error('响应校验失败，请更新脚本后重试');
        }
        var json = null;
        try { json = JSON.parse(plain); } catch (e) { json = null; }
        if (response.status === 401) store.clearAccount();
        if (response.status < 200 || response.status >= 300 || !json || !json.ok) {
          var message = json && json.message ? String(json.message) : '请求失败（HTTP ' + response.status + '）';
          throw new Error(message);
        }
        return json;
      });
    }, function () {
      throw new Error(T.common.networkFail);
    });
  }

  /** 头像地址：优先用服务端下发的 avatar_url；没有就按 id 拼；
   *  连 id 都没有时用服务端的默认头像（?default=1），保证不会出现裂图。
   *  avatar 是公开端点（白名单），不需要 V2 信封。 */
  function avatarUrl(profile) {
    var value = profile || store.profile();
    if (value && value.avatar_url) return String(value.avatar_url);
    var id = value && value.id ? parseInt(value.id, 10) : 0;
    if (id > 0) {
      return apiBase().replace('?action=', '?action=avatar&id=') + id + '&v=' + Date.now();
    }
    return apiBase().replace('?action=', '?action=avatar&id=0&default=1');
  }

  D.api = {
    request: request,

    /* ------------------------------------------------------------ 账号 */
    sendCode: function (email, purpose) {
      return request('send_code', { email: email, purpose: purpose });
    },
    login: function (email, password) {
      return request('login', { email: email, password: password });
    },
    register: function (email, password, code, nickname) {
      return request('register', { email: email, password: password, code: code, nickname: nickname || '' });
    },
    password: function (email, password, code) {
      return request('password', { email: email, password: password, code: code });
    },
    logout: function () { return request('logout', {}); },

    /* ---------------------------------------------------------- 资料 */
    profile: function () { return request('profile_get', {}); },
    saveNickname: function (nickname) { return request('nickname', { nickname: nickname }); },
    uploadAvatar: function (base64) { return request('profile_avatar', { image: base64 }); },

    /* ---------------------------------------------------------- 人设 */
    personaMarket: function (page, search) {
      return request('market', { page: page, search: search || '' });
    },
    personaMine: function (page, search) {
      return request('mine', { page: page, search: search || '' });
    },
    personaDownload: function (id) { return request('download', { id: id }); },

    /* ---------------------------------------------------------- 插件 */
    pluginMarket: function (page, search) {
      return request('plugin_market', { page: page, search: search || '' });
    },
    pluginMine: function (page, search) {
      return request('plugin_mine', { page: page, search: search || '' });
    },
    pluginDownload: function (id) { return request('plugin_download', { id: id }); },

    /* ---------------------------------------------------------- 消息 */
    notices: function () { return request('notice_list', {}); },
    noticeUnread: function () { return request('notice_unread', {}); },
    noticeRead: function (id) { return request('notice_read', { id: id }); },

    /* ---------------------------------------------------------- 条目 */
    item: function (kind, id) { return request('item_get', { kind: kind, id: id }); },

    avatarUrl: avatarUrl,
  };
})(window.__DEKK__);