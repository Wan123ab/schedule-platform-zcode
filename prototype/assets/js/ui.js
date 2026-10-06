/* ============================================================
   FlowOps 原型 · 共享 UI 框架 V0.2（vanilla JS，无框架依赖）
   提供：应用壳（渐变品牌标/导航/运维横幅/环境卡）、状态徽标、
        弹窗/抽屉/Toast、权限演示（按钮显隐 + 数据行过滤）、
        格式化、Cron 预览、DAG 分层布局
   ============================================================ */
(function () {
  var ICONS = window.FO_ICONS;
  var M = window.MOCK;

  var FO = {};

  /* ---------------- 主题 ---------------- */
  FO.theme = localStorage.getItem('flowops.theme') || 'dark';
  FO.toggleTheme = function () {
    FO.theme = FO.theme === 'dark' ? 'light' : 'dark';
    document.body.setAttribute('data-theme', FO.theme);
    localStorage.setItem('flowops.theme', FO.theme);
  };
  document.body.setAttribute('data-theme', FO.theme);

  /* ---------------- 演示角色与权限 ---------------- */
  FO.ROLES = M.roles;
  FO.role = localStorage.getItem('flowops.role') || 'PLATFORM_ADMIN';
  FO.setRole = function (code) {
    FO.role = code;
    localStorage.setItem('flowops.role', code);
    FO.applyPerms(document);
    document.dispatchEvent(new CustomEvent('rolechange', { detail: code }));
  };
  FO.can = function (perm) {
    var perms = M.PERM_DEMO[FO.role] || [];
    return perms.indexOf('*') >= 0 || perms.indexOf(perm) >= 0;
  };
  /* DataScope（D-19 的数据半边）：当前角色可见的项目集合；null = 全部 */
  FO.visibleProjects = function () {
    var s = M.SCOPE_DEMO[FO.role];
    return s === 'ALL' ? null : s;
  };
  /* 扫描 data-perm 属性：无权限的按钮直接移除（工程约定：不灰掉） */
  FO.applyPerms = function (root) {
    (root || document).querySelectorAll('[data-perm]').forEach(function (el) {
      var ok = el.getAttribute('data-perm') === 'opsView'
        ? FO.can('opsView')
        : FO.can(el.getAttribute('data-perm'));
      el.setAttribute('data-perm-hidden', ok ? '0' : '1');
    });
    var sw = document.querySelector('.view-switch');
    if (sw) sw.classList.toggle('disabled', !FO.can('opsView'));
  };

  /* ---------------- 视图模式（业务/运维） ---------------- */
  FO.viewMode = localStorage.getItem('flowops.viewMode') || 'business';
  FO.setViewMode = function (m) {
    FO.viewMode = m;
    document.body.setAttribute('data-view-mode', m);
    localStorage.setItem('flowops.viewMode', m);
    document.dispatchEvent(new CustomEvent('viewmodechange', { detail: m }));
  };
  document.body.setAttribute('data-view-mode', FO.viewMode);
  document.addEventListener('viewmodechange', function () {
    document.querySelectorAll('.view-switch button').forEach(function (b) {
      b.classList.toggle('on', b.dataset.vm === FO.viewMode);
    });
  });

  /* ---------------- 应用壳 ---------------- */
  var NAV = [
    { group: null, label: '工作台', icon: 'dashboard', href: 'index.html', key: 'dashboard' },
    { group: '任务管理', label: '任务列表', icon: 'tasks', href: 'task-list.html', key: 'taskList' },
    { group: '任务管理', label: '回填补数', icon: 'backfill', href: 'backfill.html', key: 'backfill' },
    { group: '工作流管理', label: '工作流列表', icon: 'workflow', href: 'workflow-list.html', key: 'workflowList' },
    { group: '集群管理', label: '集群列表', icon: 'cluster', href: 'cluster-list.html', key: 'clusterList' },
    { group: '算子管理', label: '算子列表', icon: 'operator', href: 'operator-list.html', key: 'operatorList' },
    { group: '系统管理', label: '项目空间', icon: 'users', href: 'project-list.html', key: 'projectList' },
    { group: '系统管理', label: '凭据管理', icon: 'credential', href: 'credential-list.html', key: 'credentialList' },
    { group: '系统管理', label: '告警配置', icon: 'alert', href: 'alert-config.html', key: 'alertConfig' },
    { group: '系统管理', label: '审计日志', icon: 'audit', href: 'audit-log.html', key: 'auditLog' },
    { group: '系统管理', label: '平台健康度', icon: 'health', href: 'platform-health.html', key: 'platformHealth' },
    { group: '系统管理', label: 'API 接入', icon: 'link', href: 'api-trigger.html', key: 'apiTrigger' }
  ];

  FO.mountShell = function (opt) {
    opt = opt || {};
    var html = '<div class="app">'
      + '<aside class="sidebar">' + sidebarHtml(opt.active) + '</aside>'
      + '<div class="main">'
      + topbarHtml(opt)
      + '<div class="view-banner"><span class="vt-dot"></span>当前为 <b>运维视图</b> · 任务参数与日志默认脱敏，查看原文需申请授权并记入审计</div>'
      + '<div class="content">' + (opt.content || '') + '</div>'
      + '</div></div>'
      + '<div class="toasts" id="toasts"></div>';
    document.body.insertAdjacentHTML('beforeend', html);
    wireTopbar();
    FO.applyPerms(document);
    document.querySelectorAll('[data-ic]').forEach(function (el) { el.innerHTML = ICONS.get(el.getAttribute('data-ic')); });
  };

  function sidebarHtml(active) {
    var out = '<div class="brand"><span class="brand-mark">' + ICONS.get('brand') + '</span>'
      + '<div class="brand-txt"><div class="brand-name">FlowOps</div><div class="brand-sub">WORKFLOW OPS</div></div></div>'
      + '<nav class="nav">';
    var lastGroup = null;
    NAV.forEach(function (n) {
      if (n.group !== lastGroup) {
        out += '<div class="nav-title">' + (n.group || '总览').toUpperCase() + '</div>';
        lastGroup = n.group;
      }
      out += '<a class="nav-item' + (n.key === active ? ' active' : '') + '" href="' + n.href + '">'
        + ICONS.get(n.icon) + '<span>' + n.label + '</span>'
        + (n.key === 'taskList' ? '<span class="nav-badge">6</span>' : '')
        + '</a>';
    });
    out += '</nav><div class="sidebar-foot"><div class="env-card"><span class="env-dot"></span>'
      + '<div class="env-txt"><b>调度链路正常</b><span class="tiny">leader scheduler-1 · 99.97%</span></div></div>'
      + '<div class="row tiny faint" style="margin-top:9px;justify-content:space-between">'
      + '<span>原型 V0.2 · <a href="pages.html">评审索引</a></span><span>20 页</span></div></div>';
    return out;
  }

  function topbarHtml(opt) {
    var crumb = (opt.crumb || []).map(function (c) {
      return c.href ? '<a href="' + c.href + '">' + c.label + '</a>' : '<span>' + c.label + '</span>';
    }).join('<span class="sep">/</span>');
    return '<header class="topbar">'
      + '<div class="crumb">' + crumb + (crumb ? '<span class="sep">/</span>' : '') + '<b>' + (opt.title || '') + '</b></div>'
      + '<div class="topbar-right">'
      + '<div class="view-switch" title="业务/运维视图切换（PRD §11.3）">'
      +   '<button data-vm="business" class="' + (FO.viewMode === 'business' ? 'on' : '') + '">业务视图</button>'
      +   '<button data-vm="ops" class="' + (FO.viewMode === 'ops' ? 'on' : '') + '">运维视图</button>'
      + '</div>'
      + '<span class="role-chip">' + ICONS.get('user') + '演示角色 <select id="demoRole" class="select" style="width:auto;height:24px;padding:0 22px 0 6px;font-size:11px">'
      +   Object.keys(M.roles).map(function (k) {
            return '<option value="' + k + '"' + (k === FO.role ? ' selected' : '') + '>' + M.roles[k].name + '</option>';
          }).join('')
      + '</select></span>'
      + '<button class="icon-btn" id="themeBtn" title="明暗主题切换">' + ICONS.get(FO.theme === 'dark' ? 'sun' : 'moon') + '</button>'
      + '<span class="row" style="gap:7px"><span class="live-dot on"></span><span class="small">' + M.roles[FO.role].user + '</span></span>'
      + '</div></header>';
  }

  function wireTopbar() {
    document.querySelectorAll('.view-switch button').forEach(function (b) {
      b.addEventListener('click', function () { FO.setViewMode(b.dataset.vm); });
    });
    var roleSel = document.getElementById('demoRole');
    if (roleSel) roleSel.addEventListener('change', function () {
      FO.setRole(roleSel.value);
      FO.toast('已切换为「' + M.roles[FO.role].name + '」：按钮按 47 权限点收敛' +
        (FO.visibleProjects() ? '，数据按 DataScope 过滤为 ' + FO.visibleProjects().join(' / ') : '，可见全平台数据'), 'info');
    });
    var tb = document.getElementById('themeBtn');
    if (tb) tb.addEventListener('click', function () {
      FO.toggleTheme();
      tb.innerHTML = ICONS.get(FO.theme === 'dark' ? 'sun' : 'moon');
    });
  }

  /* ---------------- 状态语义表（tone + marker 双区分） ---------------- */
  var ST = {
    // 任务 9 态
    PENDING:            { l: '等待中',  tone: 'idle', mk: 'dot' },
    SCHEDULING:         { l: '调度中',  tone: 'info', mk: 'pulse' },
    RUNNING:            { l: '运行中',  tone: 'info', mk: 'spin' },
    STOPPING:           { l: '停止中',  tone: 'warn', mk: 'pulse' },
    SUCCESS:            { l: '成功',    tone: 'ok',   mk: 'check' },
    FAILED:             { l: '失败',    tone: 'fail', mk: 'cross' },
    TIMEOUT:            { l: '超时',    tone: 'warn', mk: 'clock' },
    STOPPED:            { l: '已停止',  tone: 'idle', mk: 'square' },
    PARTIAL:            { l: '部分成功', tone: 'warn', mk: 'dot' },
    // 步骤 11 态
    NOT_STARTED:        { l: '未开始',    tone: 'idle', mk: 'dot' },
    WAITING_DEPENDENCY: { l: '等待依赖',  tone: 'info', mk: 'clock' },
    WAITING_RESOURCE:   { l: '等待资源',  tone: 'warn', mk: 'dot' },
    RETRYING:           { l: '重试中',    tone: 'warn', mk: 'spin' },
    SKIPPED:            { l: '已跳过',    tone: 'idle', mk: 'square' }
  };
  var MK_SVG = {
    dot:    '<svg viewBox="0 0 12 12" class="mk"><circle cx="6" cy="6" r="3" fill="currentColor"/></svg>',
    pulse:  '<svg viewBox="0 0 12 12" class="mk mk-pulse"><circle cx="6" cy="6" r="3" fill="currentColor"/></svg>',
    spin:   '<svg viewBox="0 0 12 12" class="mk mk-spin"><path d="M6 1.2a4.8 4.8 0 1 1-4.8 4.8" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
    check:  '<svg viewBox="0 0 12 12" class="mk"><path d="M2 6.5 5 9.5 10 3" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    cross:  '<svg viewBox="0 0 12 12" class="mk"><path d="M3 3l6 6M9 3 3 9" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
    clock:  '<svg viewBox="0 0 12 12" class="mk"><circle cx="6" cy="6" r="4.6" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M6 3.4V6l1.8 1.2" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>',
    square: '<svg viewBox="0 0 12 12" class="mk"><rect x="3" y="3" width="6" height="6" rx="1" fill="currentColor"/></svg>'
  };
  FO.statusTag = function (code, extra) {
    var s = ST[code] || { l: code, tone: 'idle', mk: 'dot' };
    var t = s.tone === 'idle' ? '' : ' t-' + s.tone;
    return '<span class="st' + t + '" title="' + (extra || s.l) + '">' + (MK_SVG[s.mk] || MK_SVG.dot) + '<span>' + s.l + '</span></span>';
  };
  FO.STEP_L = { NOT_STARTED: '未开始', WAITING_DEPENDENCY: '等待依赖', WAITING_RESOURCE: '等待资源', SCHEDULING: '调度中',
    RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败', RETRYING: '重试中', SKIPPED: '已跳过', STOPPED: '已停止', TIMEOUT: '超时' };

  /* 通用标签字典 */
  FO.L = {
    CLUSTER_STATUS: { NORMAL: ['正常', 'ok'], PARTIAL_ABNORMAL: ['部分异常', 'warn'], UNAVAILABLE: ['不可用', 'fail'], MAINTENANCE: ['维护中', 'info'] },
    NODE_ONLINE: { ONLINE: ['在线', 'ok'], OFFLINE: ['离线', 'fail'], UNKNOWN: ['未知', 'warn'] },
    WF_STATUS: { DRAFT: ['草稿', 'warn'], PUBLISHED: ['已发布', 'ok'], DISABLED: ['已停用', 'idle'], ARCHIVED: ['已归档', 'idle'] },
    OP_STATUS: { ENABLED: ['启用', 'ok'], DISABLED: ['停用', 'idle'] },
    OPV_STATUS: { DRAFT: ['草稿', 'warn'], PUBLISHED: ['已发布', 'ok'], OFFLINE: ['已下线', 'idle'] },
    CRED_STATUS: { VALID: ['正常', 'ok'], EXPIRING: ['即将过期', 'warn'], EXPIRED: ['已失效', 'fail'], REVOKED: ['已吊销', 'fail'] },
    TRIGGER_TYPE: { MANUAL: '手动', CRON: '定时', API: 'API', EVENT: '事件', BACKFILL: '回填' },
    LEVEL: { 严重: 'CRITICAL', 警告: 'WARN', 提示: 'INFO', CRITICAL: ['严重', 'fail'], WARN: ['警告', 'warn'], INFO: ['提示', 'info'] },
    ALERT_RESULT: { SENT: ['已送达', 'ok'], PARTIAL: ['部分送达', 'warn'], FAILED: ['送达失败', 'fail'], SUPPRESSED: ['已抑制', 'idle'] },
    BF_STATUS: { RUNNING: ['进行中', 'info'], PAUSED: ['已暂停', 'warn'], DONE: ['已完成', 'ok'], FAILED: ['失败', 'fail'], CANCELLED: ['已取消', 'idle'] },
    OS: { LINUX: 'Linux', WINDOWS: 'Windows' },
    OPTYPE: { JAR: 'Jar', PYTHON: 'Python', SHELL: 'Shell', BAT: 'Bat', EXE: 'Exe', CUSTOM: '自定义' },
    CONC_POLICY: { FORBID: '跳过本次触发', ALLOW: '并行执行', QUEUE: '排队等待' },
    STRATEGY: { TERMINATE: '失败终止工作流', RETRY: '失败重试当前步骤' },
    DECISION: { DISPATCHED: ['派发', 'ok'], DEFERRED: ['暂缓', 'info'], BLOCKED: ['阻塞', 'fail'], SKIPPED: ['跳过', 'idle'] }
  };
  FO.tag = function (dict, key) {
    var it = (FO.L[dict] || {})[key];
    if (!it) return '<span class="tag">' + key + '</span>';
    if (Array.isArray(it)) return '<span class="tag ' + it[1] + '">' + it[0] + '</span>';
    return '<span class="tag">' + it + '</span>';
  };
  FO.levelTag = function (zh) {
    var key = FO.L.LEVEL[zh];
    return FO.tag('LEVEL', key);
  };

  /* ---------------- Toast / Modal / Drawer / Confirm ---------------- */
  FO.toast = function (msg, type, title) {
    var wrap = document.getElementById('toasts');
    if (!wrap) { wrap = document.createElement('div'); wrap.className = 'toasts'; wrap.id = 'toasts'; document.body.appendChild(wrap); }
    var t = document.createElement('div');
    t.className = 'toast ' + (type || 'info');
    var ic = { ok: 'check', fail: 'cross', warn: 'alert', info: 'info' }[type || 'info'];
    t.innerHTML = ICONS.get(ic) + '<div>' + (title ? '<b>' + title + '</b>' : '') + '<span class="' + (title ? 'tt-sub' : '') + '">' + msg + '</span></div>'
      + '<button class="icon-btn x">' + ICONS.get('close') + '</button>';
    t.querySelector('.x').onclick = function () { t.remove(); };
    wrap.appendChild(t);
    setTimeout(function () { if (t.parentNode) t.remove(); }, 4600);
  };

  FO.modal = function (opt) {
    var ov = document.createElement('div');
    ov.className = 'overlay';
    ov.innerHTML = '<div class="modal" style="width:' + (opt.width || 520) + 'px">'
      + '<div class="modal-h"><b>' + opt.title + '</b><button class="icon-btn x">' + ICONS.get('close') + '</button></div>'
      + '<div class="modal-b">' + (opt.body || '') + '</div>'
      + (opt.footer !== null ? '<div class="modal-f">' + (opt.footer || '<button class="btn" data-act="cancel">取消</button><button class="btn primary" data-act="ok">' + (opt.okText || '确定') + '</button>') + '</div>' : '')
      + '</div>';
    document.body.appendChild(ov);
    function close() { ov.remove(); document.removeEventListener('keydown', esc); }
    function esc(e) { if (e.key === 'Escape') close(); }
    document.addEventListener('keydown', esc);
    ov.addEventListener('click', function (e) { if (e.target === ov) close(); });
    ov.querySelector('.x').onclick = close;
    ov.querySelectorAll('[data-act="cancel"]').forEach(function (b) { b.onclick = close; });
    if (opt.onOk) ov.querySelector('[data-act="ok"]').onclick = function () { opt.onOk(ov, close); };
    return { root: ov, close: close };
  };

  FO.confirm = function (opt) {
    return FO.modal({
      title: opt.title || '操作确认', width: opt.width || 440,
      body: '<div class="small" style="line-height:1.7">' + (opt.message || '') + '</div>'
        + (opt.requireReason
          ? '<div class="field mt-4"><label>原因 <span class="req">*</span></label><textarea class="textarea js-reason" placeholder="请填写操作原因（至少 5 个字符）"></textarea><div class="field-err hide js-reason-err">原因需至少 5 个字符</div></div>'
          : ''),
      okText: opt.okText || '确定',
      onOk: function (ov, close) {
        if (opt.requireReason) {
          var r = ov.querySelector('.js-reason').value.trim();
          if (r.length < 5) { ov.querySelector('.js-reason-err').classList.remove('hide'); return; }
        }
        var done = opt.onOk ? opt.onOk(opt.requireReason ? ov.querySelector('.js-reason').value.trim() : undefined) : true;
        if (done !== false) close();
      }
    });
  };

  FO.drawer = function (opt) {
    var mask = document.createElement('div'); mask.className = 'drawer-mask';
    var d = document.createElement('div'); d.className = 'drawer';
    if (opt.width) d.style.width = opt.width + 'px';
    d.innerHTML = '<div class="drawer-h"><b>' + opt.title + '</b><button class="icon-btn x">' + ICONS.get('close') + '</button></div>'
      + '<div class="drawer-b">' + (opt.body || '') + '</div>'
      + (opt.footer ? '<div class="drawer-f">' + opt.footer + '</div>' : '');
    document.body.appendChild(mask); document.body.appendChild(d);
    function close() { mask.remove(); d.remove(); }
    mask.onclick = close; d.querySelector('.x').onclick = close;
    return { root: d, close: close };
  };

  /* ---------------- 格式化 ---------------- */
  FO.fmt = {
    num: function (n) { return n == null ? '—' : String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ','); },
    bytes: function (mb) {
      if (mb == null) return '—';
      if (mb >= 1048576) return (mb / 1048576).toFixed(1) + ' TB';
      if (mb >= 1024) return (mb / 1024).toFixed(1) + ' GB';
      return mb + ' MB';
    }
  };
  FO.fmtDur = function (sec) {
    if (sec == null) return '—';
    if (sec < 60) return sec + 's';
    var m = Math.floor(sec / 60), s = sec % 60;
    if (m < 60) return m + 'm ' + (s ? s + 's' : '');
    var h = Math.floor(m / 60);
    return h + 'h ' + (m % 60) + 'm';
  };

  /* ---------------- SVG 迷你趋势（渐变面积） ---------------- */
  FO.sparkline = function (series, opt) {
    opt = opt || {};
    var w = opt.w || 240, h = opt.h || 56, colors = opt.colors || ['var(--pri)', 'var(--fail)'];
    var all = series.data.reduce(function (a, b) { return a.concat(b); }, []);
    var max = Math.max.apply(null, all) * 1.15 || 1;
    var gid = 'g' + Math.random().toString(36).slice(2, 7);
    var out = '<svg class="spark" viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none" style="width:100%;height:' + h + 'px">'
      + '<defs><linearGradient id="' + gid + '" x1="0" y1="0" x2="0" y2="1">'
      + '<stop class="grad-a" offset="0" stop-opacity=".5"/><stop offset="1" stop-opacity="0"/></linearGradient></defs>';
    [0.33, 0.66].forEach(function (f) {
      out += '<line class="grid-l" x1="0" x2="' + w + '" y1="' + (h * f) + '" y2="' + (h * f) + '"/>';
    });
    series.data.forEach(function (arr, si) {
      var pts = arr.map(function (v, i2) {
        return [(i2 / (arr.length - 1)) * w, h - (v / max) * (h - 6) - 3];
      });
      var d = 'M' + pts.map(function (p) { return p[0].toFixed(1) + ' ' + p[1].toFixed(1); }).join(' L');
      var c = colors[si % colors.length];
      out += '<path class="area" d="' + d + ' L' + w + ' ' + h + ' L0 ' + h + ' Z" fill="url(#' + gid + ')" stroke="none" opacity="' + (si === 0 ? 1 : .4) + '"/>';
      out += '<path class="ln" d="' + d + '" stroke="' + c + '"/>';
    });
    return out + '</svg>';
  };

  /* ---------------- 资源条 ---------------- */
  FO.resBar = function (label, used, total, unitFn, extraCls) {
    var pct = total > 0 ? (used / total) * 100 : 0;
    var cls = extraCls || (pct >= 90 ? 'hot' : pct >= 70 ? 'warm' : 'oklv');
    var fmt = unitFn || function (v) { return v; };
    return '<div class="rbar"><div class="rb-top"><span>' + label + '</span>'
      + '<span class="num">' + fmt(used) + ' / ' + fmt(total) + '</span></div>'
      + '<div class="rb-track"><div class="rb-fill ' + cls + '" style="width:' + pct.toFixed(1) + '%"></div></div></div>';
  };

  /* ---------------- 成功率环形图 ---------------- */
  FO.ring = function (pct, size, label) {
    size = size || 88;
    var r = (size - 12) / 2, c = 2 * Math.PI * r;
    var off = c * (1 - pct / 100);
    var col = pct >= 99 ? 'var(--ok)' : pct >= 95 ? 'var(--info)' : 'var(--warn)';
    return '<div class="ring" style="width:' + size + 'px;height:' + size + 'px">'
      + '<svg width="' + size + '" height="' + size + '">'
      + '<circle cx="' + size / 2 + '" cy="' + size / 2 + '" r="' + r + '" fill="none" stroke="var(--bg-inset)" stroke-width="8"/>'
      + '<circle cx="' + size / 2 + '" cy="' + size / 2 + '" r="' + r + '" fill="none" stroke="' + col + '" stroke-width="8" stroke-linecap="round"'
      + ' stroke-dasharray="' + c.toFixed(1) + '" stroke-dashoffset="' + off.toFixed(1) + '" transform="rotate(-90 ' + size / 2 + ' ' + size / 2 + ')"/>'
      + '</svg><span class="ring-val" style="color:' + col + '">' + pct + '<small>%</small></span></div>'
      + (label ? '<div class="tiny faint tc" style="margin-top:4px">' + label + '</div>' : '');
  };

  /* ---------------- 简单 5 段 Cron 解析（预览未来 5 次） ---------------- */
  FO.cronNext5 = function (expr, from) {
    function field(p, min, max) {
      if (p === '*' || p === undefined) return null;
      var set = [];
      p.split(',').forEach(function (part) {
        var step = 1, rng = part;
        if (part.indexOf('/') >= 0) { var pp = part.split('/'); rng = pp[0]; step = parseInt(pp[1], 10) || 1; }
        var a = min, b = max;
        if (rng !== '*') {
          if (rng.indexOf('-') >= 0) { var rr = rng.split('-'); a = +rr[0]; b = +rr[1]; }
          else { a = b = +rng; }
        }
        for (var v = a; v <= b; v += step) if (v >= min && v <= max) set.push(v);
      });
      return set.length ? set : null;
    }
    var f = expr.trim().split(/\s+/);
    if (f.length !== 5) return null;
    var mm = field(f[0], 0, 59), hh = field(f[1], 0, 23),
        dom = field(f[2], 1, 31), mon = field(f[3], 1, 12), dow = field(f[4], 0, 6);
    var out = [], t = new Date((from || new Date(M.TODAY + 'T15:40:00')).getTime());
    t.setSeconds(0, 0);
    for (var guard = 0; guard < 500000 && out.length < 5; guard++) {
      t = new Date(t.getTime() + 60000);
      if (mm && mm.indexOf(t.getMinutes()) < 0) continue;
      if (hh && hh.indexOf(t.getHours()) < 0) continue;
      if (mon && mon.indexOf(t.getMonth() + 1) < 0) continue;
      if (dom && dom.indexOf(t.getDate()) < 0) continue;
      if (dow && dow.indexOf(t.getDay()) < 0) continue;
      out.push(new Date(t.getTime()));
    }
    return out;
  };

  /* ---------------- DAG 分层布局（只读视图/整理布局共用） ---------------- */
  FO.dagLayout = function (steps, edges) {
    var ids = steps.filter(function (s) { return s.type !== 'NOTE'; }).map(function (s) { return s.id; });
    var level = {};
    ids.forEach(function (id) { level[id] = 0; });
    var changed = true, guard = 0;
    while (changed && guard++ < 100) {
      changed = false;
      edges.forEach(function (e) {
        if (level[e.from] != null && level[e.to] != null && level[e.to] < level[e.from] + 1) {
          level[e.to] = level[e.from] + 1;
          changed = true;
        }
      });
    }
    var byLevel = {};
    ids.forEach(function (id) {
      var l = level[id] || 0;
      (byLevel[l] = byLevel[l] || []).push(id);
    });
    var pos = {};
    Object.keys(byLevel).forEach(function (l) {
      byLevel[l].forEach(function (id, i) {
        pos[id] = { x: 60 + l * 250, y: 70 + i * 140 };
      });
    });
    return pos;
  };

  /* ---------------- 杂项 ---------------- */
  FO.uid = function (p) { return (p || 'id') + '-' + Math.random().toString(36).slice(2, 8); };
  FO.esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); };
  FO.qs = function (k) { return new URLSearchParams(location.search).get(k); };

  /* ---------------- 复制到剪贴板（列表页通用） ---------------- */
  FO.copyText = function (text, tip) {
    function done() { FO.toast((tip || '已复制') + '：' + text, 'ok'); }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { fallback(); });
    } else fallback();
    function fallback() {
      var ta = document.createElement('textarea');
      ta.value = text; document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); done(); } catch (e) { FO.toast('复制失败，请手动复制', 'fail'); }
      ta.remove();
    }
  };

  /* ---------------- 图标动作按钮（列表操作项统一用图标） ---------------- */
  FO.iconAct = function (icon, title, onclick, cls, perm) {
    return '<button class="btn xs icon-only' + (cls ? ' ' + cls : '') + '" title="' + title + '"'
      + (perm ? ' data-perm="' + perm + '"' : '')
      + ' onclick="' + onclick + '">' + FO_ICONS.get(icon) + '</button>';
  };

  /* ---------------- 列表页指标条 ---------------- */
  FO.metricsStrip = function (items) {
    return '<div class="grid mb-4" style="grid-template-columns:repeat(' + items.length + ',1fr)">'
      + items.map(function (it) {
          var tone = it.tone ? 'style="color:var(--' + it.tone + ')"' : '';
          return '<div class="panel metric"><div class="m-label">' + (it.icon ? FO_ICONS.get(it.icon) : '') + it.label + '</div>'
            + '<div class="m-value"' + tone + '>' + it.value + '</div>'
            + (it.foot ? '<div class="m-foot">' + it.foot + '</div>' : '') + '</div>';
        }).join('') + '</div>';
  };

  /* ---------------- 实时跳动（运行时长/计数器） ---------------- */
  FO.liveTicker = function (fn, ms) {
    var t = setInterval(fn, ms || 1000);
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) clearInterval(t);
      else { clearInterval(t); t = setInterval(fn, ms || 1000); }
    });
    return t;
  };

  /* ---------------- 四态演示（loading/empty/error/noperm） ---------------- */
  FO.fourState = function (targetSel, normalHtml, opt) {
    opt = opt || {};
    var el = document.querySelector(targetSel);
    if (!el) return;
    var view = localStorage.getItem('flowops.statedemo') || 'normal';
    el.setAttribute('data-stateview', view);
    if (view === 'normal') { el.innerHTML = normalHtml; return; }
    if (view === 'loading') {
      el.innerHTML = '<div class="panel-b">' + [1, 2, 3, 4].map(function () {
        return '<div class="skel" style="height:34px;margin-bottom:9px"></div>';
      }).join('') + '</div>';
      return;
    }
    if (view === 'empty') {
      el.innerHTML = '<div class="empty">' + FO_ICONS.get('search') + '<p>没有匹配的数据</p><p class="tiny faint">尝试清空筛选条件，或切换演示状态「正常」</p></div>';
      return;
    }
    if (view === 'error') {
      el.innerHTML = '<div class="empty">' + FO_ICONS.get('alert') + '<p>加载失败：50000 服务内部错误</p><p class="tiny faint">trace_id 0af7651916cd43dd8448eb211c80319c（演示）</p><button class="btn sm primary" onclick="location.reload()">重试</button></div>';
      return;
    }
    if (view === 'noperm') {
      el.innerHTML = '<div class="empty">' + FO_ICONS.get('lock') + '<p>40301：超出数据范围</p><p class="tiny faint">当前角色无权访问该项目数据——切换顶栏演示角色可对比</p></div>';
      return;
    }
    el.innerHTML = normalHtml;
  };
  /* 四态切换控件（挂在筛选栏尾部） */
  FO.stateDemoControl = function () {
    var cur = localStorage.getItem('flowops.statedemo') || 'normal';
    return '<div class="seg" id="stateDemo" title="演示表格四态：正常/加载/空/错误/无权限（工程要求四态全覆盖）">'
      + [['normal', '正常'], ['loading', '加载'], ['empty', '空'], ['error', '错误'], ['noperm', '无权限']].map(function (s) {
          return '<button data-v="' + s[0] + '" class="' + (cur === s[0] ? 'on' : '') + '">' + s[1] + '</button>';
        }).join('') + '</div>';
  };
  FO.bindStateDemo = function (targetSel, normalHtml) {
    document.addEventListener('click', function (e) {
      var b = e.target.closest && e.target.closest('#stateDemo button');
      if (!b) return;
      localStorage.setItem('flowops.statedemo', b.dataset.v);
      document.querySelectorAll('#stateDemo button').forEach(function (x) { x.classList.toggle('on', x === b); });
      FO.fourState(targetSel, normalHtml);
    });
  };

  /* ---------------- 列表页 URL 状态同步 ---------------- */
  FO.urlState = {
    read: function () {
      var o = {};
      new URLSearchParams(location.search).forEach(function (v, k) { o[k] = v; });
      return o;
    },
    write: function (state, defaults) {
      var p = new URLSearchParams();
      Object.keys(state).forEach(function (k) {
        var d = defaults ? defaults[k] : undefined;
        if (state[k] != null && state[k] !== '' && state[k] !== d) p.set(k, state[k]);
      });
      var s = p.toString();
      history.replaceState(null, '', location.pathname + (s ? '?' + s : ''));
    }
  };

  window.FO = FO;
})();
