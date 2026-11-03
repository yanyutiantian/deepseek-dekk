/*
 * 文案表：尽量照抄安卓端注入页里的字符串，保证两端看起来一致。
 * 只保留现在页面里真正会出现的那些，删掉功能后不再留死文案。
 */
(function (D) {
  'use strict';
  if (!D) return;

  D.text = {
    /* ---------------------------------------------------------------- 首页 */
    settingsPageTitle: 'DEKK设置页面',
    menuEntry: 'DEKK模块',
    sectionAccount: '账号与人设中心',
    sectionPlugin: '插件与美化',
    sectionFeature: '功能',
    sectionAbout: '关于',

    /* ------------------------------------------------------------ 功能文案 */
    antiRecall: {
      title: '防撤回（本地存档）',
      desc: '还在测试中，不保证正确',
      chip: '检测到 %s 条消息已消失 · 查看本地存档',
      pageTitle: '本地存档',
      empty: '当前会话还没有存档',
      clearOne: '清空本会话存档',
      cleared: '已清空',
    },

    /* ------------------------------------------------------------ 聊天美化 */
    beautify: {
      title: '聊天美化',
      sectionBubble: '消息气泡',
      bubbleTitle: '微信对话气泡',
      bubbleDesc: '用户与AI独立气泡，自适应消息宽度；思考区保留独立折叠。切换后立即生效。',
      userColor: '用户气泡颜色',
      aiColor: 'AI气泡颜色',
      radius: '气泡圆角',
      radiusPicker: '气泡圆角',
      colorPicker: '选择颜色',
      saved: '已保存，立即生效',
    },

    /* -------------------------------------------------------- 服务端模块 */
    account: {
      loginEntry: '登录 / 注册',
      center: '个人中心',
      login: '登录',
      register: '注册',
      password: '修改密码',
      email: 'QQ邮箱',
      passwordField: '密码',
      passwordFieldNew: '密码（8—12字符）',
      nickname: '昵称',
      code: '6位验证码',
      sendCode: '发送验证码',
      resend: '重新发送（%s秒）',
      codeSent: '验证码已发送，5分钟内有效',
      submitLogin: '登录',
      submitRegister: '注册',
      submitPassword: '确认修改',
      needEmailPassword: '请填写邮箱和密码',
      passwordLength: '新密码应为8—12个字符',
      loginOk: '登录成功',
      registerOk: '注册成功，请登录',
      passwordOk: '密码已修改，旧登录已失效',
      registerAccount: '注册账号  ›',
      forgot: '忘记密码  ›',
      logout: '退出登录',
      logoutDone: '已退出登录',
      sectionAccountService: '账号服务',
      sectionAccountInfo: '账号',
      nicknameHint: '点击昵称可直接修改',
      nicknameTitle: '修改昵称',
      nicknameEmpty: '昵称不能为空',
      nicknameLong: '昵称最多 30 个字',
      nicknameSaved: '昵称已更新',
      changeAvatar: '点击更换头像',
      uploading: '正在压缩并上传头像…',
      avatarFail: '头像上传失败：',
      avatarOk: '头像已更新',
      statsFollows: '关注',
      statsFans: '粉丝',
      statsPersonas: '人设',
      statsLikes: '点赞',
    },

    persona: {
      market: '人设市场',
      mine: '我上传的',
      local: '已下载',
      mineEntry: '我的上传  ›',
      localEntry: '已下载  ›',
      marketEntry: '浏览人设市场  ›',
      searchHint: '搜索人设名称',
      search: '搜索',
      more: '加载更多',
      loading: '加载中…',
      retry: '重试加载',
      allLoaded: '已加载全部',
      emptyMarket: '没有找到匹配的人设',
      emptyLocal: '暂无匹配的本地人设',
      download: '下载',
      downloaded: '已下载',
      use: '使用',
      useBound: '已选择人设，下一次文本请求将附加',
      public: '公开',
      private: '私密',
      manage: '编辑管理  ›',
      saved: '已保存，可在聊天里使用',
      deleteLocal: '删除本地副本',
    },

    plugin: {
      market: '插件市场',
      mine: '我上传的',
      local: '已下载',
      searchHint: '搜索插件名称',
      emptyMarket: '没有找到匹配的插件',
      emptyLocal: '暂无匹配的本地插件',
      useBound: '已选择插件，下一次文本请求将附加',
    },

    about: {
      title: '关于 DEKK',
      version: '脚本版本',
      moduleVersion: '对应安卓模块版本',
      home: '官网 / 安卓版下载  ›',
      script: '复制脚本下载链接  ›',
      scriptCopied: '下载链接已复制',
      reset: '清空本脚本的全部本地数据',
      resetDesc: '设置、本地存档、登录态都会被清掉',
      resetDone: '已清空',
      resetConfirm: '确认清空本脚本的全部本地数据？',
      disclaimer: '本脚本只在你自己的浏览器里改页面，不改服务端数据；'
        + '附加到请求里的说明会真的发给 DeepSeek，请自行判断是否开启。',
    },

    common: {
      save: '保存',
      cancel: '取消',
      confirm: '确认',
      back: '返回',
      close: '关闭',
      loading: '加载中…',
      networkFail: '网络请求失败，请检查网络后重试',
      floatEntry: '悬浮入口',
      floatEntryDesc: '菜单入口注入失败时的兜底：屏幕左下角的小圆点也能打开 DEKK 设置页',
    },
  };
})(window.__DEKK__);