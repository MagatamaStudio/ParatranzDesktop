'use strict'

const { contextBridge, ipcRenderer } = require('electron')

// 引擎由主进程注入到主世界，这里只做一件事：把页面上「屏蔽 / 临时显示」按钮的点击
// 转成 IPC 请求。请求内容不可信（页面脚本也能 postMessage），所以真正的名单变更
// 一定还要过主进程的原生确认框。
window.addEventListener('message', (e) => {
  if (e.source !== window || !e.data || e.data.channel !== 'pt-shield-intent') return
  ipcRenderer.send('shield:intent', {
    type: e.data.type,
    payload: e.data.payload,
    href: location.href
  })
})

contextBridge.exposeInMainWorld('ptShieldPage', { version: 1 })

// ---------- 窗口顶部工具条 ----------
// 原生菜单栏收起后，这条工具条占据窗口最上方：后退 / 前进 / 刷新 / 返回主页，
// 右端是应用菜单（和 Alt 唤出的原生菜单同一份）。页面整体下移让出这条的高度，
// 所以它是界面上的一条栏，不是浮在内容上的浮层。
const NAV_HEIGHT = 34

const NAV_CSS = `
html body{padding-top:${NAV_HEIGHT}px !important}
main{min-height:calc(100vh - ${NAV_HEIGHT}px) !important}
.sticky-top,.sticky{top:${NAV_HEIGHT}px !important}
.b-sidebar{top:${NAV_HEIGHT}px !important;height:calc(100vh - ${NAV_HEIGHT}px) !important}
#pt-nav{position:fixed;left:0;right:0;top:0;height:${NAV_HEIGHT}px;z-index:2147483601;display:flex;
  align-items:center;gap:2px;padding:0 6px;background:#f4f5f7;border-bottom:1px solid rgba(0,0,0,.14);
  font:0/0 a;user-select:none;-webkit-user-select:none;color:#333}
#pt-nav button{all:unset;box-sizing:border-box;width:28px;height:24px;display:flex;align-items:center;
  justify-content:center;border-radius:5px;cursor:pointer;color:#333}
#pt-nav button:hover{background:rgba(0,0,0,.09)}
#pt-nav button:active{background:rgba(0,0,0,.16)}
#pt-nav button[disabled]{opacity:.3;cursor:default;background:transparent}
#pt-nav svg{width:15px;height:15px;display:block}
#pt-nav .pt-nav-sep{width:1px;height:16px;margin:0 3px;background:rgba(0,0,0,.16)}
#pt-nav .pt-nav-menu{margin-left:auto;margin-right:2px}
@media print{html body{padding-top:0 !important}#pt-nav{display:none}}
`

function navIcon(body) {
  return '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + body + '</svg>'
}

const NAV_BUTTONS = [
  ['back', '后退', navIcon('<path d="M13 8H3"/><path d="M7.5 3.5 3 8l4.5 4.5"/>')],
  ['forward', '前进', navIcon('<path d="M3 8h10"/><path d="M8.5 3.5 13 8l-4.5 4.5"/>')],
  ['reload', '刷新', navIcon('<path d="M13 8a5 5 0 1 1-1.5-3.6"/><path d="M13 3.2v3.3H9.7"/>')],
  ['home', '返回主页', navIcon('<path d="M3 7.2 8 3l5 4.2"/><path d="M4.5 6.5V13h7V6.5"/>' +
    '<path d="M6.8 13V9.8h2.4V13"/>')]
]

const MENU_BUTTON = ['menu', '菜单', navIcon('<path d="M4 5h8"/><path d="M4 8h8"/><path d="M4 11h8"/>')]

let navState = { canGoBack: false, canGoForward: false }

function applyNavState() {
  const bar = document.getElementById('pt-nav')
  if (!bar) return
  const back = bar.querySelector('[data-act="back"]')
  const forward = bar.querySelector('[data-act="forward"]')
  if (back) back.disabled = !navState.canGoBack
  if (forward) forward.disabled = !navState.canGoForward
}

// SPA 的 pushState / popstate 不会生成 Electron 的导航记录，用 Navigation API
// 自己维护按钮可用状态；跨文档加载时主进程还会再推一次兜底。
function syncFromNavigation() {
  const nav = window.navigation
  if (!nav) return
  navState = { canGoBack: !!nav.canGoBack, canGoForward: !!nav.canGoForward }
  applyNavState()
}

try {
  if (window.navigation) {
    window.navigation.addEventListener('currententrychange', syncFromNavigation)
    window.navigation.addEventListener('navigatesuccess', syncFromNavigation)
  }
} catch (e) {}

function addNavButton(bar, [act, label, icon], className) {
  const btn = document.createElement('button')
  btn.type = 'button'
  btn.dataset.act = act
  btn.title = label
  btn.setAttribute('aria-label', label)
  if (className) btn.className = className
  btn.innerHTML = icon
  btn.addEventListener('click', (e) => {
    e.preventDefault()
    e.stopPropagation()
    ipcRenderer.send(act === 'menu' ? 'app:menu' : 'nav:action', act)
  }, true)
  bar.appendChild(btn)
  return btn
}

function installNav() {
  if (window.top !== window) return
  if (document.getElementById('pt-nav')) return
  const style = document.createElement('style')
  style.id = 'pt-nav-style'
  style.textContent = NAV_CSS
  const bar = document.createElement('div')
  bar.id = 'pt-nav'
  bar.setAttribute('role', 'toolbar')
  bar.setAttribute('aria-label', '页面导航')
  for (const item of NAV_BUTTONS) {
    if (item[0] === 'home') {
      const sep = document.createElement('span')
      sep.className = 'pt-nav-sep'
      bar.appendChild(sep)
    }
    addNavButton(bar, item)
  }
  addNavButton(bar, MENU_BUTTON, 'pt-nav-menu')
  document.documentElement.appendChild(style)
  document.documentElement.appendChild(bar)
  applyNavState()
  syncFromNavigation()
}

ipcRenderer.on('nav:state', (e, state) => {
  if (!state || typeof state !== 'object') return
  navState = { canGoBack: !!state.canGoBack, canGoForward: !!state.canGoForward }
  applyNavState()
})

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', installNav, { once: true })
} else {
  installNav()
}
