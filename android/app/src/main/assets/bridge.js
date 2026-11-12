(function () {
  'use strict'

  var native = window.__ptNative
  var listeners = []
  var pending = {}
  var seq = 0

  function parse(text) {
    if (text === undefined || text === null || text === '') return null
    try { return JSON.parse(text) } catch (e) { return null }
  }

  function callSync(name, arg) {
    if (!native || !native.call) return Promise.resolve(null)
    try {
      return Promise.resolve(parse(native.call(name, arg === undefined ? '' : JSON.stringify(arg), '')))
    } catch (e) {
      return Promise.resolve({ ok: false, error: String(e) })
    }
  }

  function callAsync(name, arg) {
    return new Promise(function (resolve) {
      if (!native || !native.call) { resolve(null); return }
      var id = 'r' + (++seq)
      pending[id] = resolve
      try {
        native.call(name, arg === undefined ? '' : JSON.stringify(arg), id)
      } catch (e) {
        delete pending[id]
        resolve({ ok: false, error: String(e) })
      }
    })
  }

  window.__ptReply = function (id, json) {
    var fn = pending[id]
    if (!fn) return
    delete pending[id]
    fn(parse(json))
  }

  window.__ptManageChanged = function (json) {
    var snap = parse(json)
    if (!snap) return
    for (var i = 0; i < listeners.length; i++) {
      try { listeners[i](snap) } catch (e) {}
    }
  }

  window.ptManage = {
    get: function () { return callSync('get') },
    addProject: function (entry) { return callSync('addProject', entry) },
    removeProject: function (id) { return callSync('removeProject', id) },
    addUser: function (entry) { return callSync('addUser', entry) },
    removeUser: function (key) { return callSync('removeUser', key) },
    revealProject: function (id) { return callSync('revealProject', id) },
    revealUser: function (key) { return callSync('revealUser', key) },
    setRevealAll: function (on) { return callSync('setRevealAll', !!on) },
    setSettings: function (patch) { return callSync('setSettings', patch) },
    currentTarget: function () { return callSync('currentTarget') },
    blockCurrent: function () { return callAsync('blockCurrent') },
    openSite: function (opts) { return callSync('openSite', opts || {}) },
    accountAdd: function (name) { return callSync('accountAdd', name) },
    accountRename: function (msg) { return callSync('accountRename', msg) },
    accountRemove: function (id) { return callSync('accountRemove', id) },
    exportList: function () { return callAsync('exportList') },
    importList: function () { return callAsync('importList') },
    loginWithToken: function (msg) { return callAsync('loginWithToken', msg) },
    whoami: function (account) { return callAsync('whoami', account) },
    openTokenPage: function () { return callSync('openTokenPage') },
    plugins: function () { return callSync('plugins') },
    togglePlugin: function (msg) { return callSync('togglePlugin', msg) },
    openPluginFolder: function () { return callSync('importPlugins') },
    onChanged: function (fn) { listeners.push(fn) }
  }

  // Android 适配：调低桌面专属条目，避免用户去找不存在的托盘和多账号窗口。
  function hideSection(title) {
    var sections = document.querySelectorAll('section')
    for (var i = 0; i < sections.length; i++) {
      var h = sections[i].querySelector('h2')
      if (h && h.textContent.trim() === title) sections[i].style.display = 'none'
    }
  }

  function adapt() {
    hideSection('账号窗口')
    var tray = document.getElementById('closeToTray')
    if (tray && tray.closest) {
      var row = tray.closest('label')
      if (row) row.style.display = 'none'
    }
    var pick = document.getElementById('openPluginFolder')
    if (pick) pick.textContent = '导入插件文件'
    var pluginHint = document.querySelector('section h2')
    var sections = document.querySelectorAll('section')
    for (var i = 0; i < sections.length; i++) {
      var h = sections[i].querySelector('h2')
      if (!h || h.textContent.trim() !== '插件') continue
      var hint = sections[i].querySelector('.hint')
      if (hint) hint.textContent = '从文件管理器选择 .user.js / .js / .css 导入；插件在屏蔽引擎之后运行，绕不过屏蔽。改完要重载站点窗口才生效。'
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', adapt)
  } else {
    adapt()
  }
})();
