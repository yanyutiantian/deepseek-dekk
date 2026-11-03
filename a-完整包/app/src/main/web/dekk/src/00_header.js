// ==UserScript==
// @name         DEKK
// @namespace    https://dekk.example.com/jsdekk
// @version      1.0.2-web
// @description  为 DeepSeek 网页版提供的 DEKK 功能模块（安卓 Xposed 模块 com.dekk.yanyu 的网页移植版）
// @author       烟萧雨
// @match        https://chat.deepseek.com/*
// @run-at       document-start
// @grant        none
// @noframes
// ==/UserScript==

/*
 * DEKK 网页版
 *
 * 这个脚本是安卓端 Xposed 模块（com.dekk.yanyu）的网页移植：
 *   - 菜单入口：登录后点头像弹出的菜单里，「系统设置」下面多一行「DEKK模块」；
 *   - 设置页：外观、分组、文案都对齐安卓端注入的「DEKK设置页面」；
 *   - 功能：设备状态提示词、补充上下文、一问多答、强开专家/识图、
 *           伪封号、AI控制封号、防撤回（本地存档）、聊天美化、历史对话全选，
 *           以及账号 / 人设市场 / 插件中心 / 消息中心（走 dekk.example.com 服务端）。
 *
 * 源码按职责拆在 src/ 下，由 build.sh 拼成单文件 dekk.js。
 */
(function () {
  'use strict';
  if (window.__DEKK__) return;

  var DEKK = {
    /** 脚本版本（本文件顶部的 @version 保持同步） */
    version: '1.0.2-web',
    build: '2026-10-03',
    /** 安卓端模块版本，设置页里显示用 */
    moduleVersion: '0.1.2-web',
    host: 'chat.deepseek.com',
    home: 'https://dekk.example.com/',
    download: 'https://dekk.example.com/jsdekk/dekk.js',
    /** DEKK 服务端接口前缀（与安卓端 PersonaApi.BASE 一致） */
    apiBase: 'https://dekk.example.com/API/?action=',
    /** DeepSeek 品牌蓝 */
    brand: '#4d6bfe',
    /** 运行时装配区：各模块往这里挂自己的东西 */
    modules: {},
    started: false,
  };

  window.__DEKK__ = DEKK;
})();