/* ==========================================================================
   Claude Design System — PokeClaw mockup interaction layer
   --------------------------------------------------------------------------
   Vanilla port of the reference project's React hooks:
     useClaudeScrollPinning.tsx  →  scroll pinning
     ChatComposer.tsx            →  auto-grow + CJK IME guard
     ModelSwitcherWithTokenGauge →  model popover

   Design values come from cds.css (MIT, © Caho1). Behaviour here is written
   from scratch — no build step, no dependencies.
   ========================================================================== */
(function () {
  'use strict';

  /* ---------------------------------------------------------------- 主题 */
  var root = document.documentElement;
  var THEME_KEY = 'cds-mockup-theme';
  var ACCENT_KEY = 'cds-mockup-accent';
  var BASE_KEY = 'cds-mockup-base';

  /* localStorage 在 file://（不透明来源）下会抛错。原先全部静默 catch，
     导致「能切主题但存不住」看起来像渲染 bug，排查方向完全跑偏。
     失败必须出声 —— 在 WebView console 里能直接看到。 */
  function store(key, value) {
    try { localStorage.setItem(key, value); return true; }
    catch (e) { console.warn('[cds] localStorage.setItem(' + key + ') failed:', e); return false; }
  }

  function read(key) {
    try { return localStorage.getItem(key); }
    catch (e) { console.warn('[cds] localStorage.getItem(' + key + ') failed:', e); return null; }
  }

  function applyTheme(mode) {
    root.setAttribute('data-mode', mode);
    store(THEME_KEY, mode);
    var btn = document.getElementById('themeToggle');
    if (btn) btn.setAttribute('aria-label', mode === 'dark' ? '切换到浅色' : '切换到深色');
    qaRefresh();
  }

  function initTheme() {
    var saved = read(THEME_KEY);
    if (saved) { applyTheme(saved); return; }
    applyTheme(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  }

  /* ------------------------------------------------- 配色方案（强调色）
     与 [data-mode] 正交：换配色不动深浅，换深浅不动配色。 */
  function applyAccent(key) {
    // 写入前把关：无效的 key 不进 localStorage。否则存下去的是一个
    // 永远不会被应用的垃圾值，下次读回还要再判一次无效。
    if (!validAccent(key)) key = 'clay';
    root.setAttribute('data-theme', key);
    store(ACCENT_KEY, key);
    var rows = document.querySelectorAll('.swatch-row');
    rows.forEach(function (row) {
      row.setAttribute('aria-checked', String(row.dataset.themeKey === key));
    });
    syncThemeLabel();
    qaRefresh();
  }

  /* ------------------------------------------------- 底色方案
     第三维，与强调色、深浅三者互不影响。 */
  function applyBase(key) {
    if (!validBase(key)) key = 'clay';
    root.setAttribute('data-base', key);
    store(BASE_KEY, key);
    var rows = document.querySelectorAll('.base-row');
    rows.forEach(function (row) {
      row.setAttribute('aria-checked', String(row.dataset.baseKey === key));
    });
    syncThemeLabel();
    qaRefresh();
  }

  /* ------------------------------------------------- 存量值校验
     两种失效，症状完全一样（页面静默回落成 clay），所以分开处理：

       A. 方案本身被删了  → querySelector 找不到行，走老用户兼容
       B. 方案还在、但 CSS 变量被删了 → 行还在，querySelector 照样通过，
          页面回落成默认色，看上去像「存不住」

     B 是真正咬人的那个：只查 DOM 会放它过去。所以两关都要过 ——
     存进 localStorage 之前问「有没有对应规则」，读回时再问一次。
     规则缺失不静默放过，直接 console.warn 并回落，让 logcat 有话说。 */
  function hasKey(selector, attr, saved) {
    return !!saved && !!document.querySelector(selector + '[' + attr + '="' + saved + '"]');
  }

  // CSS 规则真的存在吗：把属性打到 <html> 上，读回解析后的变量值。
  // 只查 DOM 查不出「行还在、变量没了」，只有读计算值能查出来。
  function cssResolves(attr, key, probeVar) {
    var prev = root.getAttribute(attr);
    root.setAttribute(attr, key);
    // 强制重算，否则读到的是上一次的缓存值
    void root.offsetWidth;
    var v = getComputedStyle(root).getPropertyValue(probeVar).trim();
    if (prev === null) root.removeAttribute(attr); else root.setAttribute(attr, prev);
    void root.offsetWidth;
    return v.length > 0;
  }

  function validAccent(saved) {
    if (!hasKey('.swatch-row', 'data-theme-key', saved)) return false;
    if (cssResolves('data-theme', saved, '--theme-accent')) return true;
    console.warn('[cds] 配色 ' + saved + ' 有色块但没有 [data-theme] 规则，已回落默认');
    return false;
  }

  function validBase(saved) {
    if (!hasKey('.base-row', 'data-base-key', saved)) return false;
    // 探针变量必须是 cds.css 里真实存在的名字。写错名字不会「校验失败」，
    // 而是对所有 key 都返回空串 —— 全部误判为无效，整套底色直接瘫掉。
    if (cssResolves('data-base', saved, '--surface-0')) return true;
    console.warn('[cds] 底色 ' + saved + ' 有色块但没有 [data-base] 规则，已回落默认');
    return false;
  }

  function syncThemeLabel() {
    var b = document.querySelector('.base-row[aria-checked="true"] .swatch-name');
    var a = document.querySelector('.swatch-row[aria-checked="true"] .swatch-name');
    var inline = document.getElementById('themeNameInline');
    if (inline && b && a) inline.textContent = b.textContent + ' ' + a.textContent;
  }

  function initAccent() {
    var saved = read(ACCENT_KEY);
    applyAccent(validAccent(saved) ? saved : 'clay');
  }

  function initBase() {
    var saved = read(BASE_KEY);
    applyBase(validBase(saved) ? saved : 'clay');
  }

  /* -------------------------------------------------------- Composer 行为 */
  var ta = document.getElementById('composerInput');
  var sendBtn = document.getElementById('sendBtn');

  // 输入法合成守卫：CJK 输入过程中 Enter 是「选词」，不是「发送」。
  // 必须同时看 isComposing 和 keyCode 229 —— 部分 IMEs 合成结束才派发 keyup，
  // 只判断 isComposing 会漏掉一部分。
  var composing = false;

  function syncAutoGrow() {
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 220) + 'px';
    sendBtn.disabled = ta.value.trim().length === 0;
  }

  ta.addEventListener('input', syncAutoGrow);
  ta.addEventListener('compositionstart', function () { composing = true; });
  ta.addEventListener('compositionend', function () { composing = false; syncAutoGrow(); });

  ta.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    if (e.shiftKey) return;
    if (composing || e.isComposing || e.keyCode === 229) return; // 合成中 → 不发送
    e.preventDefault();
    submit();
  });

  sendBtn.addEventListener('click', submit);

  function submit() {
    var text = ta.value.trim();
    if (!text) return;
    ta.value = '';
    syncAutoGrow();
    appendUser(text);
    runAssistant(text);
  }

  /* ------------------------------------------------------- 滚动底部钉住
     抄 useClaudeScrollPinning.ts。四个机制，缺一不可：
       1. 40px 底部容差
       2. 150ms 用户输入冷却窗口 —— 用户刚滚过就别抢视口
       3. programmaticScroll 守卫 —— 程序滚动触发的事件不能再当作用户滚动
       4. 离开底部 >120px 出现「跳至最新」 */
  var feed = document.getElementById('feed');
  var jumpBtn = document.getElementById('jumpLatest');
  var pinned = true;
  var showJump = false;
  var lastUserInputAt = 0;
  var programmaticScroll = false;
  var PIN_TOLERANCE = 40;
  var JUMP_THRESHOLD = 120;
  var COOLDOWN_MS = 150;

  // 用户主动滚动（滚轮 / 触摸 / 键盘）立刻释放锁定。
  // 不只监听 scroll —— scroll 事件滞后一帧，用户快速滚动时会误判。
  ['wheel', 'touchstart', 'keydown'].forEach(function (evt) {
    feed.addEventListener(evt, function () {
      lastUserInputAt = performance.now();
      pinned = false;
    }, { passive: true });
  });

  feed.addEventListener('scroll', function () {
    if (programmaticScroll) { programmaticScroll = false; return; }
    var slack = feed.scrollHeight - feed.scrollTop - feed.clientHeight;
    pinned = slack <= PIN_TOLERANCE;
    showJump = slack > JUMP_THRESHOLD;
    syncJump();
  }, { passive: true });

  function syncJump() {
    if (!jumpBtn) return;
    jumpBtn.setAttribute('data-show', showJump ? 'true' : 'false');
  }

  function toBottom(smooth) {
    programmaticScroll = true;
    feed.scrollTo({ top: feed.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    pinned = true;
    showJump = false;
    syncJump();
  }

  if (jumpBtn) {
    jumpBtn.addEventListener('click', function () {
      lastUserInputAt = 0;          // 主动点击 = 放弃冷却，立即贴底
      toBottom(true);
    });
  }

  // token 流式追加时的重锚定：只有「没被用户接管」才自动跟随
  function repin() {
    if (performance.now() - lastUserInputAt <= COOLDOWN_MS) return;
    if (pinned) toBottom(false);
  }

  /* ---------------------------------------------------- 限流退避倒计时
     抄 useRateLimitBackoff.ts：
       delay = min(max(base,5000) * 2^(n-1) * jitter, 600000)，jitter ±15%
       连续 3 次自动重试后停止自动重试，强制用户手动点
     演示时把 base 压到 3s，否则没人愿意等 10 分钟。 */
  var backoff = { waiting: false, at: 0, left: 0, runs: 0, manualOnly: false, timer: null };

  function renderBackoff() {
    var banner = document.getElementById('rateLimit');
    if (!banner) return;
    if (!backoff.waiting) { banner.setAttribute('data-show', 'false'); return; }
    banner.setAttribute('data-show', 'true');
    banner.querySelector('.rl-text').textContent = backoff.manualOnly
      ? '已连续退避 ' + backoff.runs + ' 次，需要你手动重试。'
      : '模型限流，' + backoff.left + ' 秒后重试（第 ' + backoff.runs + ' 次）';
    var retry = banner.querySelector('.rl-retry');
    if (retry) retry.style.display = backoff.manualOnly ? '' : 'none';
  }

  function tickBackoff() {
    backoff.left = Math.max(0, Math.ceil((backoff.at - Date.now()) / 1000));
    if (backoff.left <= 0) {
      clearInterval(backoff.timer);
      backoff.timer = null;
      backoff.waiting = false;
      renderBackoff();
      appendUser('（手动重试）');
      runAssistant('retry');
      return;
    }
    renderBackoff();
  }

  function triggerBackoff() {
    backoff.runs += 1;
    if (backoff.runs > 3) {                       // 第 4 次：不再自动重试
      backoff.waiting = true; backoff.manualOnly = true; backoff.left = 0;
      renderBackoff();
      return;
    }
    var base = 3000;                              // 演示用，生产是 5000
    var jitter = 0.85 + Math.random() * 0.3;
    var delay = Math.min(Math.max(base, 5000) * Math.pow(2, backoff.runs - 1) * jitter, 600000);
    backoff.waiting = true; backoff.manualOnly = false;
    backoff.at = Date.now() + delay;
    backoff.left = Math.ceil(delay / 1000);
    clearInterval(backoff.timer);
    backoff.timer = setInterval(tickBackoff, 250);
    renderBackoff();
  }

  var rlBanner = document.getElementById('rateLimit');
  if (rlBanner) {
    rlBanner.querySelector('.rl-close').addEventListener('click', function () {
      clearInterval(backoff.timer);
      backoff.timer = null;
      backoff.waiting = false; backoff.manualOnly = false; backoff.runs = 0;
      renderBackoff();
    });
    rlBanner.querySelector('.rl-retry').addEventListener('click', function () {
      clearInterval(backoff.timer);
      backoff.timer = null;
      backoff.waiting = false; backoff.manualOnly = false;
      backoff.runs = 0;
      renderBackoff();
      appendUser('（手动重试）');
      runAssistant('retry');
    });
  }

  /* ------------------------------------------------------------- 漫游焦点
     抄 useClaudeRovingFocus.ts：方向键在组内移动，Home/End 首尾，
     Tab 立即交还全局焦点流（防键盘死循环），tabIndex 动态 0 / -1。 */
  function rovingFocus(container, itemSelector) {
    var items = Array.prototype.slice.call(container.querySelectorAll(itemSelector));
    if (!items.length) return;
    var cur = 0;

    items.forEach(function (el, i) {
      el.tabIndex = i === 0 ? 0 : -1;
      el.addEventListener('click', function () { cur = i; apply(); });
    });

    function apply() {
      items.forEach(function (el, i) { el.tabIndex = i === cur ? 0 : -1; });
      items[cur].focus({ preventScroll: true });
    }

    container.addEventListener('keydown', function (e) {
      if (e.key === 'Tab') return;               // 关键：不拦截 Tab
      var i = items.indexOf(document.activeElement);
      if (i < 0) return;
      var next = -1;
      switch (e.key) {
        case 'ArrowDown': case 'ArrowRight': next = i + 1; break;
        case 'ArrowUp':   case 'ArrowLeft':  next = i - 1; break;
        case 'Home': case 'PageUp':            next = 0; break;
        case 'End':  case 'PageDown':          next = items.length - 1; break;
        default: return;
      }
      e.preventDefault();
      cur = (next + items.length) % items.length; // loop 回绕
      apply();
    });
  }

  rovingFocus(document.querySelector('.sessions'), '.session');
  rovingFocus(document.querySelector('.composer-bar'), '.pill');
  var popWrap = document.querySelector('.pop-wrap');
  var pop = popWrap.querySelector('.pop');
  var popTrigger = popWrap.querySelector('.pill');

  popTrigger.addEventListener('click', function (e) {
    e.stopPropagation();
    pop.setAttribute('data-open', pop.getAttribute('data-open') === 'true' ? 'false' : 'true');
  });
  document.addEventListener('click', function (e) {
    if (!popWrap.contains(e.target)) pop.setAttribute('data-open', 'false');
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') pop.setAttribute('data-open', 'false');
  });

  pop.addEventListener('click', function (e) {
    var item = e.target.closest('.pop-item');
    if (!item) return;
    pop.querySelectorAll('.pop-item').forEach(function (b) {
      b.setAttribute('aria-checked', String(b === item));
      b.style.background = b === item ? 'var(--cds-fill-ghost-selected)' : '';
    });
    popTrigger.querySelector('.pill-label').textContent = item.dataset.label;
  });

  /* ------------------------------------------------------------ 消息渲染 */
  function turn(cls, inner) {
    var t = document.createElement('div');
    t.className = 'turn ' + cls;
    t.innerHTML = inner;
    feed.appendChild(t);
    repin();
    return t;
  }

  function esc(s) {
    return s.replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function appendUser(text) {
    var t = turn('turn-user', '<div class="bubble"></div>');
    t.querySelector('.bubble').textContent = text;
  }

  // 模拟助手回合：思考状态 → 工具调用 → 正文。仅为演示动效，不连后端。
  function runAssistant(prompt) {
    var status = turn('turn-assistant',
      '<div class="status-row"><span class="timeline-quiet-dots">' +
      '<span></span><span></span><span></span></span><span class="status-text">正在思考…</span></div>');

    setTimeout(function () {
      status.remove();
      turn('turn-assistant',
        '<div class="body"><p>好的，我来处理。</p></div>');

      var card = turn('turn-assistant',
        '<div class="tool-card">' +
        '<div class="tool-head">' +
        '<span class="status-dot" data-kind="ready"></span>' +
        '<span class="tool-name">accessibility.perform_action</span>' +
        '<span class="tool-kind">无障碍 · 点击</span>' +
        '</div>' +
        '<div class="tool-body">NODE_ID=btn_send · ACTION=click</div>' +
        '</div>');

      setTimeout(function () {
        card.style.opacity = '0.42';
        turn('turn-assistant',
          '<div class="body"><p>已经点了发送。截图如下：</p></div>' +
          '<div class="code">' +
          '<div class="code-head">screenshot.png</div>' +
          '<pre>' + esc('$ adb exec-out screencap -p > shot.png\nbytes: 284,112 · device: vivo X300 Pro') + '</pre>' +
          '</div>');
        repin();
      }, 420);
    }, 900);
  }

  /* --------------------------------------------- 配色方案 popover 交互 */
  var accentWrap = document.querySelector('.theme-pop-wrap');
  var accentPop = document.getElementById('themePop');
  var accentBtn = document.getElementById('themePickerBtn');

  if (accentBtn && accentPop) {
    accentBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = accentPop.getAttribute('data-open') === 'true';
      accentPop.setAttribute('data-open', String(!open));
      accentBtn.setAttribute('aria-expanded', String(!open));
    });
    // 与模型切换 popover 互斥：两个同时开会叠在一起
    document.addEventListener('click', function (e) {
      if (!accentWrap.contains(e.target)) {
        accentPop.setAttribute('data-open', 'false');
        accentBtn.setAttribute('aria-expanded', 'false');
      }
      if (popWrap.contains(e.target) === false && !e.target.closest('.pop-wrap')) {
        pop.setAttribute('data-open', 'false');
      }
    });
    accentPop.addEventListener('click', function (e) {
      var row = e.target.closest('.swatch-row');
      if (row) { applyAccent(row.dataset.themeKey); return; }
      var base = e.target.closest('.base-row');
      if (base) applyBase(base.dataset.baseKey);
    });
  }

  /* ==================================================== QA 自检面板
     为什么把诊断画进页面，而不是只靠 logcat：
       §18.7  uiautomator 看不见 WebView 内部，截图才是可靠 ground truth
       §18.13 postDelayed 探针 3 次只触发 1 次，取证全是噪音
     画进页面之后，一张截图同时回答四个互斥的可能：

       loads 是否增长          → 存储跨进程存活吗
       自检 写/读/一致          → localStorage 本身能读写吗
       applied 与 stored 是否同 → 存下来了但没读回来？
       spAccent 与 stored.accent → 上次原生记住的配色 vs 本地存的
         同 → localStorage 被清空了（原生记得，WebView 不记得）
         不同 → cds.js 的读写路径断了

     第四条是关键：SharedPreferences 一定持久，所以它是唯一的
     「已知为真的对照组」。没有对照组就无法区分「没写」和「写丢了」。
     ==================================================== */
  var QA_LOADS_KEY = 'cds-qa-loads';
  var QA_TOKEN_KEY = 'cds-qa-token';
  var qaPanel = null;
  var qaLaunchAt = new Date();

  // 本次页面加载序号。它每次 +1 并落盘，所以它是「存储跨重启是否存活」
  // 的唯一可靠读数：恒为 1 = 每次启动都从空存储开始。
  var qaLoads = (function () {
    var n = parseInt(read(QA_LOADS_KEY) || '0', 10);
    if (isNaN(n)) n = 0;
    n += 1;
    store(QA_LOADS_KEY, String(n));
    return n;
  }());

  // 写-读-比对三列，能区分「setItem 抛错」「setItem 静默失败」
  // 和「写进去了」，这三种在页面外观上完全一样。
  function qaRoundTrip() {
    var token = 't' + Date.now() + '-' + Math.floor(Math.random() * 1e6);
    var out = { w: '-', r: '-', ok: false };
    try { localStorage.setItem(QA_TOKEN_KEY, token); out.w = 'OK'; }
    catch (e) { out.w = 'THREW'; return out; }
    try { out.r = String(localStorage.getItem(QA_TOKEN_KEY)); } catch (e) { out.r = 'THREW'; }
    out.ok = out.r === token;
    return out;
  }

  function qaState() {
    var rt = qaRoundTrip();
    var all = {};
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        all[k] = String(localStorage.getItem(k));
      }
    } catch (e) { all = { ERROR: String(e) }; }
    return {
      loads: qaLoads,
      launchAt: qaLaunchAt.toISOString().slice(11, 19),
      origin: String(location.origin),
      protocol: String(location.protocol),
      ua: String(navigator.userAgent).replace(/^.*Chrome\//, 'Chrome/').replace(/ Mobile.*/, ''),
      applied: {
        mode: root.getAttribute('data-mode'),
        accent: root.getAttribute('data-theme'),
        base: root.getAttribute('data-base')
      },
      stored: {
        theme: read(THEME_KEY),
        accent: read(ACCENT_KEY),
        base: read(BASE_KEY),
        len: Object.keys(all).length
      },
      roundTrip: rt
    };
  }

  function qaReport() {
    var s = qaState();
    if (window.PokeClawQA) {
      // 顺序要紧：先读原生记住的上次值，再写本次值。反过来会读到刚写的
      // 自己，对照组就退化成 localStorage 自己，等于什么都没验。
      try {
        var last = window.PokeClawQA.lastState();
        if (last) {
          var parts = String(last).split('|');
          s.spAccent = parts[0];
          s.spMode = parts[1];
        }
      } catch (e) { s.spAccent = 'THREW'; }
      try {
        window.PokeClawQA.report(
          JSON.stringify(s), String(s.applied.accent), String(s.applied.mode)
        );
      } catch (e) { /* 无桥：只留页面内证据 */ }
    }
    console.log('[cdsQA] ' + JSON.stringify(s));
    return s;
  }

  function qaRender(s) {
    if (!qaPanel) return;
    qaPanel.querySelector('#qaLoads').textContent =
      'loads=' + s.loads + ' @' + s.launchAt;
    qaPanel.querySelector('#qaOrigin').textContent =
      s.origin + ' · ' + s.protocol + ' · ' + s.ua;
    qaPanel.querySelector('#qaApplied').textContent =
      'applied mode=' + s.applied.mode + ' accent=' + s.applied.accent + ' base=' + s.applied.base;
    qaPanel.querySelector('#qaStored').textContent =
      'stored theme=' + s.stored.theme + ' accent=' + s.stored.accent +
      ' base=' + s.stored.base + ' len=' + s.stored.len;
    qaPanel.querySelector('#qaRT').textContent =
      '自检 写=' + s.roundTrip.w + ' 读=' + s.roundTrip.r + ' 一致=' + s.roundTrip.ok +
      ' | 原生记住 accent=' + (s.spAccent || '—') + ' mode=' + (s.spMode || '—');
  }

  function qaRefresh() { qaRender(qaReport()); }

  function qaBuildPanel() {
    var el = document.createElement('div');
    el.id = 'cdsQaPanel';
    // 只读诊断条，不再持有任何切换按钮 —— 配色切换一律走 index.html 里
    // 那个 .theme-pop 色板浮层，与网页预览完全一致。之前加循环按钮是为了
    // 绕开不可靠的坐标盲 tap，验收通过后已按要求撤回。
    el.style.cssText = [
      'position:fixed', 'z-index:2147483647', 'left:0', 'right:0', 'top:0',
      'background:var(--cds-fill-secondary)', 'color:var(--cds-text-primary)',
      'border-bottom:1px solid var(--cds-border-strong)',
      'font:11px/1.5 ui-monospace,Consolas,monospace',
      'padding:4px 8px', 'pointer-events:none'
    ].join(';');
    el.innerHTML =
      '<b>QA</b>' +
      '<span id="qaLoads"></span>' +
      '<span id="qaOrigin"></span>' +
      '<span id="qaApplied"></span>' +
      '<span id="qaStored"></span>' +
      '<span id="qaRT"></span>';
    document.body.appendChild(el);
    qaPanel = el;
  }

  /* ------------------------------------------------------------------ 启动 */
  qaBuildPanel();
  initTheme();
  initAccent();
  initBase();
  document.getElementById('themeToggle').addEventListener('click', function () {
    applyTheme(root.getAttribute('data-mode') === 'dark' ? 'light' : 'dark');
  });
  syncAutoGrow();
  requestAnimationFrame(function () { toBottom(false); });

  // 演示入口：首次打开时演示一次限流退避，之后不再打扰。
  // 真实接入时由请求层在收到 429 时调用，UI 不该自己判断限流。
  try {
    if (!localStorage.getItem('cds-mockup-rl-demo')) {
      localStorage.setItem('cds-mockup-rl-demo', '1');
      setTimeout(triggerBackoff, 1200);
    }
  } catch (e) { /* file:// 下 localStorage 可能不可用 */ }

  // 暴露给调试 / 自动化验证
  window.__cds = {
    rateLimit: triggerBackoff,
    resetRateLimit: function () {
      clearInterval(backoff.timer); backoff.timer = null;
      backoff.waiting = false; backoff.manualOnly = false; backoff.runs = 0;
      renderBackoff();
    },
    setAccent: applyAccent,
    setBase: applyBase,
    qa: qaReport,
    state: function () {
      return { pinned: pinned, showJump: showJump, backoff: backoff,
               mode: root.getAttribute('data-mode'),
               accent: root.getAttribute('data-theme'),
               base: root.getAttribute('data-base') };
    }
  };
})();
