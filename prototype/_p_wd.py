# -*- coding: utf-8 -*-
import io

p = 'workflow-detail.html'
s = io.open(p, encoding='utf-8').read()

def rep(old, new, tag):
    global s
    assert old in s, 'ANCHOR MISS: ' + tag
    s = s.replace(old, new, 1)

# 1) 触发器 Tab：追加 API 触发卡片
old = """      }).join('') + '<div class="panel-b"><button class="btn" data-perm="schedule:trigger:write" onclick="FO.toast(\\'新建触发器（演示）：Cron/周期 + 版本绑定 + 时区 + 补跑策略 + 运行参数 + 目标队列\\',\\'info\\')">' + FO_ICONS.get('plus') + '新建触发器</button></div>';"""
new = """      }).join('') + apiCardHtml(w) + '<div class="panel-b"><button class="btn" data-perm="schedule:trigger:write" onclick="FO.toast(\\'新建触发器（演示）：Cron/周期 + 版本绑定 + 时区 + 补跑策略 + 运行参数 + 目标队列\\',\\'info\\')">' + FO_ICONS.get('plus') + '新建触发器</button></div>';"""
rep(old, new, 'trg-api')

# 2) apiCardHtml 助手（挂在 TABS 定义前）
old = "  var TABS = {\n    ver: function () {"
new = """  /* API 触发卡片：展示绑定该工作流的 Key 与调用量（PRD §10.9 API 触发 · 提前纳入一期） */
  function apiCardHtml(w) {
    var keys = (MOCK.apiKeys || []).filter(function (k) { return k.scopeWfs.indexOf(w.id) >= 0; });
    if (!keys.length) return '';
    var calls = keys.reduce(function (sum, k) { return sum + (k.calls24h || 0); }, 0);
    return '<div class="panel-b" style="border-bottom:1px solid var(--line-faint)">'
      + '<div class="row mb-3"><b>API 触发</b><span class="tag mut">开放接口</span>'
      + '<span class="spacer"></span><a class="tiny" href="api-trigger.html">API 接入管理 →</a></div>'
      + '<div class="row" style="gap:26px">'
      + '<div><div class="tiny faint">绑定 Key</div><b class="mono">' + keys.length + '</b>'
      + '<div class="tiny faint">' + keys.map(function (k) { return k.name; }).join('、') + '</div></div>'
      + '<div><div class="tiny faint">今日调用</div><b class="mono">' + calls + '</b></div>'
      + '<div><div class="tiny faint">鉴权</div><span class="tiny">X-API-Key + 作用域白名单 + 限流</span></div>'
      + '<div><div class="tiny faint">状态通知</div><span class="tiny">Webhook（HMAC-SHA256 签名）</span></div>'
      + '</div>'
      + '<div class="tiny faint mt-3">第三方经 <span class="mono">POST /openapi/v1/tasks</span> 创建任务（trigger_type=API），复用统一并发闸门（ConcurrencyGuard）与幂等键；支持创建 / 查详情 / 终止 / 重试四个端点</div>'
      + '</div>';
  }

  var TABS = {
    ver: function () {"""
rep(old, new, 'api-card')

# 3) mini DAG v2：与编辑器同渲染（foreignObject 节点 + 类型图标 + 四向锚点贝塞尔 + 配置节点虚线）
start_marker = '  /* ---------- 迷你 DAG 预览 ---------- */'
end_marker = """    if (cfgCount) document.getElementById('dagPreviewPanel').querySelector('.hint').textContent += ' · 另有 ' + cfgCount + ' 个配置节点（连线注入）';
  })();"""
i0 = s.index(start_marker)
i1 = s.index(end_marker) + len(end_marker)
new_block = """  /* ---------- 迷你 DAG 预览（与编辑器同渲染：foreignObject 节点 + 类型图标 + 四向贝塞尔 + 配置节点） ---------- */
  (function renderMiniDag() {
    if (!w.steps || !w.steps.length) { document.getElementById('dagPreviewPanel').classList.add('hide'); return; }
    var svg = document.getElementById('miniDag');
    var NW = 188, NH = 78, CFGW = 184, CFGH = 78;
    function nsize(st) { return st.type === 'CONFIG' ? [CFGW, CFGH] : [NW, NH]; }
    var OP_ICON = { SHELL: 'bolt', JAR: 'cluster', PYTHON: 'diag', BAT: 'file', EXE: 'play', CUSTOM: 'settings' };
    var OP_COLOR = { SHELL: 'var(--cyan)', JAR: 'var(--info)', PYTHON: 'var(--mut)', BAT: 'var(--warn)', EXE: 'var(--ok)', CUSTOM: 'var(--t2)' };
    var OP_TINT = { SHELL: 'rgba(34,211,238,.14)', JAR: 'rgba(59,155,255,.14)', PYTHON: 'rgba(167,139,250,.14)', BAT: 'rgba(245,165,36,.14)', EXE: 'rgba(40,199,111,.14)', CUSTOM: 'rgba(130,150,174,.14)' };
    var tasks = w.steps.filter(function (st) { return st.type === 'TASK'; });
    var cfgs = w.steps.filter(function (st) { return st.type === 'CONFIG'; });
    var pos = FO.dagLayout(tasks, w.edges);
    cfgs.forEach(function (c) { if (pos[c.id] == null) pos[c.id] = { x: (c.x || 880), y: (c.y || -80) }; else { pos[c.id].x = c.x; pos[c.id].y = c.y; } });
    var nodes = w.steps.filter(function (st) { return st.type !== 'NOTE'; });

    var minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
    nodes.forEach(function (st) {
      var p = pos[st.id]; if (!p) return;
      var S = nsize(st);
      minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
      maxX = Math.max(maxX, p.x + S[0]); maxY = Math.max(maxY, p.y + S[1]);
    });
    minX -= 46; minY -= 46; maxX += 66; maxY += 56;

    function anchor(a, b) {
      var aS = nsize(a), bS = nsize(b);
      var dx = (b.x + bS[0] / 2) - (a.x + aS[0] / 2), dy = (b.y + bS[1] / 2) - (a.y + aS[1] / 2);
      var sA, sB;
      if (Math.abs(dx) >= Math.abs(dy)) { sA = dx >= 0 ? 'r' : 'l'; sB = dx >= 0 ? 'l' : 'r'; }
      else { sA = dy >= 0 ? 'b' : 't'; sB = dy >= 0 ? 't' : 'b'; }
      function pt(n, sd, S) {
        if (sd === 'r') return [n.x + S[0], n.y + S[1] / 2];
        if (sd === 'l') return [n.x, n.y + S[1] / 2];
        if (sd === 'b') return [n.x + S[0] / 2, n.y + S[1]];
        return [n.x + S[0] / 2, n.y];
      }
      var p1 = pt(a, sA, aS), p2 = pt(b, sB, bS);
      var dist = Math.max(30, Math.hypot(p2[0] - p1[0], p2[1] - p1[1]) / 2);
      var c1, c2;
      if (sA === 'r') c1 = [p1[0] + dist, p1[1]]; else if (sA === 'l') c1 = [p1[0] - dist, p1[1]];
      else if (sA === 'b') c1 = [p1[0], p1[1] + dist]; else c1 = [p1[0], p1[1] - dist];
      if (sB === 'l') c2 = [p2[0] - dist, p2[1]]; else if (sB === 'r') c2 = [p2[0] + dist, p2[1]];
      else if (sB === 't') c2 = [p2[0], p2[1] - dist]; else c2 = [p2[0], p2[1] + dist];
      return 'M' + p1[0] + ' ' + p1[1] + ' C' + c1[0] + ' ' + c1[1] + ' ' + c2[0] + ' ' + c2[1] + ' ' + (p2[0] - 6) + ' ' + p2[1];
    }

    var s = '<defs>'
      + '<marker id="pvarr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="var(--pri)"/></marker>'
      + '<marker id="pvmut" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="var(--mut)"/></marker></defs>';

    w.edges.forEach(function (e) {
      var a = nodes.filter(function (n) { return n.id === e.from; })[0];
      var b = nodes.filter(function (n) { return n.id === e.to; })[0];
      if (!a || !b || pos[a.id] == null || pos[b.id] == null) return;
      var isCfg = a.type === 'CONFIG';
      s += '<path d="' + anchor(a, b) + '" fill="none" marker-end="url(#' + (isCfg ? 'pvmut' : 'pvarr') + ')"'
        + (isCfg ? ' stroke="rgba(167,139,250,.55)" stroke-width="1.3" stroke-dasharray="6 5"' : ' stroke="var(--pri-line)" stroke-width="1.8"') + '/>';
    });

    nodes.forEach(function (st) {
      var p = pos[st.id]; if (!p) return;
      var S = nsize(st);
      var op = st.type === 'TASK' ? MOCK.operators.filter(function (o) { return o.id === st.op; })[0] : null;
      var chips = [];
      if (st.type === 'CONFIG') {
        (MW ? [] : []).forEach(function () {});
      }
      if (st.type === 'CONFIG') {
        var mwMap = { 'PostgreSQL': ['host', 'port', 'db'], 'MySQL': ['host', 'port', 'db'], 'Redis': ['host', 'port'], 'RocketMQ': ['namesrv', 'topic'], 'Kafka': ['bootstrap', 'topic'], 'MQTT': ['broker', 'topic'], 'MinIO/S3': ['endpoint', 'access_key', 'bucket'], 'NAS(NFS/SMB)': ['server', 'path'] };
        (mwMap[st.mw] || []).slice(0, 3).forEach(function (k) { chips.push(k); });
      } else {
        if (st.res && st.res.cpu) chips.push('CPU ' + st.res.cpu);
        if (st.res && st.res.gpu) chips.push('GPU ' + st.res.gpu);
        if (st.mutex) chips.push('🔒' + st.mutex);
      }
      var icon, color, tint;
      if (st.type === 'CONFIG') { icon = 'db'; color = 'var(--mut)'; tint = 'var(--mut-dim)'; }
      else {
        icon = op ? (OP_ICON[op.type] || 'operator') : 'operator';
        color = op ? (OP_COLOR[op.type] || 'var(--t2)') : 'var(--t2)';
        tint = op ? (OP_TINT[op.type] || 'var(--idle-dim)') : 'var(--idle-dim)';
      }
      var typeCls = st.type === 'CONFIG' ? ' cfg' : '';
      s += '<g class="pv-g" transform="translate(' + p.x + ',' + p.y + ')">'
        + '<foreignObject width="' + S[0] + '" height="' + S[1] + '">'
        + '<div xmlns="http://www.w3.org/1999/xhtml" class="pv-node' + typeCls + '" title="点击进入编辑器">'
        + '<div class="pv-nm"><span class="pv-ic" style="color:' + color + ';background:' + tint + '">' + FO_ICONS.get(icon) + '</span>' + FO.esc(st.name) + '</div>'
        + (st.type === 'CONFIG'
          ? '<div class="pv-op">' + (st.mw || '') + ' · 配置注入</div>'
          : '<div class="pv-op">' + (op ? op.name + ' · ' + st.opVer : '—') + '</div>')
        + '<div class="pv-meta">' + chips.slice(0, 3).map(function (c) { return '<span class="pv-chip">' + c + '</span>'; }).join('') + '</div>'
        + '</div></foreignObject></g>';
    });

    svg.setAttribute('viewBox', minX + ' ' + minY + ' ' + (maxX - minX) + ' ' + (maxY - minY));
    svg.innerHTML = s;
    svg.style.cursor = 'pointer';
    svg.title = '点击进入编辑器';
    svg.addEventListener('click', function () { location.href = 'workflow-editor.html?id=' + w.id; });
    if (cfgs.length) document.getElementById('dagPreviewPanel').querySelector('.hint').textContent = '与编辑器同渲染 · 另有 ' + cfgs.length + ' 个配置节点（虚线注入）· 点击进入编辑器';
  })();"""
s = s[:i0] + new_block + s[i1:]
print('miniDag v2 OK')

# 4) 页面 CSS：pv 节点样式（编辑器同款视觉的语言子集）
old = "  .cron-preview { font-family: var(--font); font-size: 12px; line-height: 2; }"
new = """  .cron-preview { font-family: var(--font); font-size: 12px; line-height: 2; }
  /* pv：DAG 预览节点（与编辑器 .dag-node 同视觉语言） */
  #miniDag { cursor: pointer; }
  .pv-node {
    width: 100%; height: 100%; border-radius: 10px; border: 1.5px solid rgba(46,144,250,.4);
    background: linear-gradient(180deg, rgba(26,35,56,.95), rgba(13,20,32,.97));
    box-shadow: 0 8px 22px rgba(0,0,0,.42), inset 0 1px 0 rgba(255,255,255,.06);
    padding: 9px 12px; user-select: none;
    font-family: var(--font); font-size: 12.5px; color: var(--t1); line-height: 1.35;
  }
  body[data-theme="light"] .pv-node { background: linear-gradient(180deg, #FFFFFF, #F4F7FC); box-shadow: 0 6px 18px rgba(16,30,60,.1); }
  .pv-node.cfg { border-color: rgba(167,139,250,.55); background: linear-gradient(160deg, rgba(58,47,99,.92), rgba(24,20,48,.96)); }
  body[data-theme="light"] .pv-node.cfg { background: linear-gradient(160deg, #F3F0FC, #E9E4F8); }
  .pv-node.cfg .pv-nm { color: var(--mut); }
  .pv-g:hover .pv-node { border-color: var(--pri); box-shadow: 0 0 0 3px var(--pri-dim), 0 0 18px rgba(46,144,250,.25); }
  .pv-nm { font-weight: 600; display: flex; align-items: center; gap: 6px; }
  .pv-ic { width: 18px; height: 18px; border-radius: 4px; display: inline-flex; align-items: center; justify-content: center; flex: none; }
  .pv-ic svg { width: 11px; height: 11px; }
  .pv-op { font-size: 10.5px; color: var(--t3); margin-top: 2px; }
  .pv-meta { display: flex; gap: 4px; margin-top: 5px; flex-wrap: wrap; }
  .pv-chip { font-size: 9.5px; padding: 0 5px; border-radius: 3px; background: rgba(46,144,250,.12); border: 1px solid var(--pri-line); color: var(--pri-hi); }
  body[data-theme="light"] .pv-chip { background: var(--pri-dim); }"""
rep(old, new, 'pv-css')

io.open(p, 'w', encoding='utf-8').write(s)
print('workflow-detail OK')
