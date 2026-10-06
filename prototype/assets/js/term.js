/* ============================================================
   FlowOps 原型 · 远程操作组件 V2（SSH 终端 + SFTP 文件浏览）
   两种形态：
     FlowTerm.open(opt)  → 居中大弹窗（右下角可拖拽缩放 + 新页面全屏入口）
     FlowTerm.mount(el, opt) → 嵌入任意容器（terminal.html 全屏页使用）
   真实实现：xterm.js / SFTP ↔ WSS ↔ executor-client(SSH Shell + SFTP 子系统) ↔ 节点
   安全：凭据平台注入明文不出库 · asciicast 录制进审计 · 单次授权绑定 任务/步骤/用户
   ============================================================ */
(function () {
  /* ---- 仿真文件系统（节点侧）---- */
  var FS = {
    '/': [{ n: 'opt', d: true }, { n: 'data', d: true }, { n: 'etc', d: true }],
    '/opt': [{ n: 'flowops', d: true }, { n: 'spark-3.5', d: true }],
    '/opt/flowops': [{ n: 'work', d: true }, { n: 'logs', d: true }, { n: 'conf.yaml', s: '2.1 KB' }, { n: 'clean-job-3.1.0.jar', s: '48.2 MB' }],
    '/opt/flowops/logs': [{ n: 'score-20431.log', s: '12.4 MB' }, { n: 'clean-0917.log', s: '86.2 MB' }, { n: 'feature-0923.log', s: '4.8 MB' }],
    '/opt/flowops/work': [{ n: 'TASK-20260925-0042', d: true }, { n: 'TASK-20260925-0035', d: true }],
    '/opt/flowops/work/TASK-20260925-0042': [
      { n: '数据接入', d: true }, { n: '数据清洗', d: true }, { n: '特征计算', d: true }, { n: 'output_vars.json', s: '1.2 KB' }, { n: 'resolved_command.sh', s: '0.4 KB' }
    ],
    '/opt/flowops/work/TASK-20260925-0042/特征计算': [{ n: 'feature.log', s: '1.8 MB' }, { n: 'part-0000.parquet', s: '204 MB' }, { n: '_meta.json', s: '0.3 KB' }],
    '/opt/flowops/work/TASK-20260925-0042/数据清洗': [{ n: 'clean.log', s: '42.6 MB' }],
    '/opt/flowops/work/TASK-20260925-0035': [{ n: '模型评分', d: true }, { n: 'hs_err_pid20431.log', s: '2.2 MB' }],
    '/data': [{ n: 'ecom', d: true }],
    '/data/ecom': [{ n: '20260925', d: true }, { n: 'rules', d: true }],
    '/data/ecom/20260925': [{ n: 'order_feature', d: true }, { n: 'order_clean', d: true }],
    '/data/ecom/20260925/order_feature': [{ n: 'part-0000.parquet', s: '204 MB' }, { n: 'part-0001.parquet', s: '198 MB' }],
    '/data/ecom/rules': [{ n: 'order_rules_v12.json', s: '18 KB' }],
    '/etc': [{ n: 'hosts', s: '0.2 KB' }]
  };
  var FILE_DEMO = {
    'conf.yaml': 'workflow: order-sync\nschedule: "0 2 * * *"\nnotify: [pm-ecom, biz-wang]',
    'output_vars.json': '{\n  "cleanedFilePath": "/data/dwd/order_clean/dt=2026-09-25",\n  "rowCount": 1284729\n}',
    'resolved_command.sh': '#!/bin/bash\n/usr/bin/spark-submit --master yarn --executor-memory 22g … --output /data/dwd/order_clean/dt=2026-09-25',
    '_meta.json': '{ "featureDim": 286, "sparse": 0.624, "window": ["7d","30d","90d"] }',
    'order_rules_v12.json': '{ "rules": 42, "required": ["order_id","user_id","amount","pay_time"], "threshold": 0.998 }',
    'hosts': '127.0.0.1 localhost\n10.20.31.14 prod-node-014\n10.20.44.203 gpu-node-003'
  };

  /* ---------------- 嵌入式挂载 ---------------- */
  function mount(root, opt) {
    opt = opt || {};
    var nodeName = opt.nodeName || 'node';
    var ip = opt.ip || '0.0.0.0';
    var credFp = opt.credFp || '****0000';
    var startPath = opt.startPath && FS[opt.startPath] ? opt.startPath : '/opt/flowops';
    var fill = opt.termHeight === 'fill';

    root.innerHTML = ''
      + '<div class="banner pri" style="margin-bottom:10px"><span data-ic="link"></span><div>'
      + (opt.ctxTitle ? '<b>' + opt.ctxTitle + '</b>　' + (opt.ctxSub || '') + '<br>' : '')
      + '单次授权（TTL 10 分钟）· 全程录制进审计 · 凭据 ' + credFp + ' 平台注入，明文不下发浏览器 · 前端仿真</div></div>'
      + '<div class="tabs" style="margin-bottom:10px">'
      + '<button class="tab-btn on" data-t="ssh">终端（SSH）</button>'
      + '<button class="tab-btn" data-t="sftp">文件（SFTP）</button></div>'
      + (!fill ? '<div class="row" style="margin-bottom:6px;flex:none"><span class="tiny faint">拖拽右下角 ↘ 调整大小，或</span><span class="spacer"></span><button class="btn xs" data-fs>全屏</button></div>' : '')
      + '<div id="paneSsh" style="' + (fill ? 'flex:1;min-height:0;display:flex;flex-direction:column;' : '') + '"></div>'
      + '<div id="paneSftp" class="hide" style="' + (fill ? 'flex:1;min-height:0;display:flex;flex-direction:column;' : '') + '"></div>';

    /* ================= Tab 1：SSH ================= */
    var paneSsh = root.querySelector('#paneSsh');
    paneSsh.innerHTML = '<div id="termOut" style="' + (fill ? 'flex:1;min-height:0;' : 'height:250px;') + 'overflow-y:auto;white-space:pre-wrap;word-break:break-all;background:#05080E;border:1px solid var(--line-faint);border-radius:var(--r-sm);padding:10px 12px;font-family:var(--mono);font-size:11.5px;line-height:1.7;color:#B6C2D4"></div>'
      + '<div class="row mt-2" style="gap:0;background:#05080E;border:1px solid var(--line-faint);border-radius:var(--r-sm);padding:6px 10px;overflow-x:auto;flex:none">'
      + '<span id="prompt" class="mono" style="color:var(--ok);font-size:11.5px;flex:none"></span>'
      + '<input id="termIn" class="mono" autocomplete="off" style="flex:1;background:transparent;border:0;outline:none;color:#E8EEF9;font-size:11.5px" placeholder="输入命令（help 查看可用命令）">'
      + '</div>'
      + '<div class="tiny faint mt-2" style="flex:none">支持：ls · cd · pwd · cat · tail -f · ps · df · whoami · date · exit —— 会话开始/结束与逐条命令均写审计</div>';
    var out = paneSsh.querySelector('#termOut');
    var input = paneSsh.querySelector('#termIn');
    var promptEl = paneSsh.querySelector('#prompt');
    var cwd = startPath;
    function setPrompt() { promptEl.innerHTML = 'etl@' + nodeName + ':' + cwd + '$&nbsp;'; }
    function w(html) { out.insertAdjacentHTML('beforeend', html); out.scrollTop = out.scrollHeight; }
    function resolve(dir) {
      if (dir === '' || dir === '.') return cwd;
      if (dir === '..') return cwd.split('/').slice(0, -1).join('/') || '/';
      if (dir === '/') return '/';
      if (dir.charAt(0) === '/') return dir;
      return (cwd === '/' ? '' : cwd) + '/' + dir;
    }
    setPrompt();
    w('<span style="color:var(--info)">Connecting to ' + ip + ' … 22</span>\n');
    setTimeout(function () { w('<span style="color:var(--ok)">SSH-2.0-OpenSSH_8.9 · 主机指纹已校验（白名单）</span>\n'); }, 300);
    setTimeout(function () { w('<span style="color:var(--ok)">Welcome to Ubuntu 22.04 (etl@' + nodeName + ') · 会话已录制（SESSION_START 已写审计）</span>\n\n'); }, 650);
    var CMDS = {
      help: function () { w('<span style="color:var(--t3)">可用命令：ls · cd &lt;dir&gt; · pwd · cat &lt;file&gt; · tail -f · ps · df · whoami · date · exit\n（真实实现中任意命令经 SSH 通道执行并实时回显）</span>\n'); },
      ls: function (arg) {
        var dir = resolve(arg || cwd);
        var items = FS[dir];
        if (!items) { w('ls: cannot access ' + FO.esc(dir) + ': No such file or directory\n'); return; }
        w(items.map(function (it) {
          return it.d ? '<span style="color:var(--info);font-weight:600">' + it.n + '/</span>' : it.n + (it.s ? ' <span style="color:var(--t4)">' + it.s + '</span>' : '');
        }).join('  ') + '\n');
      },
      cd: function (arg) {
        if (!arg) { cwd = '/'; setPrompt(); return; }
        var dir = resolve(arg);
        if (FS[dir]) { cwd = dir; setPrompt(); return; }
        w('cd: ' + FO.esc(arg) + ': No such file or directory\n');
      },
      pwd: function () { w(cwd + '\n'); },
      cat: function (arg) {
        var p = resolve(arg || '');
        var name = p.split('/').pop();
        if (FILE_DEMO[name]) w('<span style="color:var(--cyan)">' + FO.esc(FILE_DEMO[name]) + '\n</span>');
        else w('cat: ' + FO.esc(arg) + ': No such file\n');
      },
      'tail -f /opt/flowops/logs/*.log': function () {
        var i = 0;
        var t = setInterval(function () {
          if (!document.body.contains(input) || i >= 5) { clearInterval(t); return; }
          i++;
          w('<span style="color:var(--t4)">15:3' + (2 + i) + ':0' + i + '</span> <span style="color:var(--info)">INFO</span>  ScoreJob - batch ' + (47 + i) + '/79 · GPU 80%\n');
        }, 600);
        w('<span style="color:var(--t3)">（tail 持续输出中 = 「远程实时控制台」效果 · 5s 后演示停止）</span>\n');
      },
      'ps -ef | grep python': function () { w('etl   20431  19822  2 15:28 ?  00:01:12 python3 score.py --model risk_v37.pkl\netl   20488  19822  0 15:31 ?  00:00:03 python3 feature_calc.py\n'); },
      df: function () { w('Filesystem      Size  Used Avail Use%  Mounted on\n/dev/sda1       4.0T  2.1T  1.9T  53%  /data\n'); },
      whoami: function () { w('etl\n'); },
      date: function () { w('Fri Sep 25 15:33:12 CST 2026\n'); },
      exit: function () { w('<span style="color:var(--warn)">logout · SESSION_END 已写审计（会话回放 asciicast-8842.cast）</span>\n'); input.disabled = true; }
    };
    input.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      var line = input.value.trim();
      w('<span style="color:var(--ok)">etl@' + nodeName + ':' + cwd + '$</span> ' + FO.esc(line) + '\n');
      input.value = '';
      if (!line) return;
      var sp = line.indexOf(' ');
      var cmd = sp < 0 ? line : line.slice(0, sp);
      var arg = sp < 0 ? '' : line.slice(sp + 1).trim();
      (CMDS[cmd] || function () { w('bash: ' + FO.esc(cmd) + ': command found in demo whitelist only（真实实现不限命令）\n'); })(arg);
    });
    setTimeout(function () { input.focus(); }, 400);

    /* ================= Tab 2：SFTP ================= */
    var paneSftp = root.querySelector('#paneSftp');
    var sftpPath = startPath;
    function renderSftp() {
      var items = FS[sftpPath] || [];
      var parts = sftpPath.split('/').filter(Boolean);
      var crumb = '<span class="mono clickable" id="sftpRoot" style="cursor:pointer;color:var(--pri-hi)">/</span>'
        + parts.map(function (p, i) {
            return ' <span class="sep">/</span> <a class="mono clickable js-crumb" data-i="' + (i + 1) + '" style="cursor:pointer;word-break:break-all">' + p + '</a>';
          }).join('');
      paneSftp.innerHTML = '<div class="row small mb-2" style="background:var(--bg-inset);border:1px solid var(--line-faint);border-radius:var(--r-sm);padding:6px 10px;flex-wrap:wrap;row-gap:2px">'
        + crumb + '<span class="spacer"></span><span class="tiny faint">SFTP 会话已审计</span></div>'
        + '<div style="flex:1;min-height:0;overflow-y:auto;border:1px solid var(--line-faint);border-radius:var(--r-sm)">'
        + '<table class="tbl"><thead><tr><th style="width:34px"></th><th>名称</th><th style="width:80px">类型</th><th style="width:90px">大小</th><th style="text-align:right">操作</th></tr></thead><tbody>'
        + items.map(function (it) {
            var full = (sftpPath === '/' ? '' : sftpPath) + '/' + it.n;
            return it.d
              ? '<tr class="clickable js-dir" data-p="' + full + '"><td>' + FO_ICONS.get('db').replace('<svg', '<svg width=14 height=14 style="color:var(--info)"') + '</td>'
                + '<td><b class="small" style="color:var(--info)">' + it.n + '/</b></td><td class="tiny faint">目录</td><td class="tiny faint">—</td><td></td></tr>'
              : '<tr><td>' + FO_ICONS.get('file').replace('<svg', '<svg width=14 height=14 style="color:var(--t3)"') + '</td>'
                + '<td class="small">' + it.n + '</td><td class="tiny faint">' + (it.n.match(/\.log$/) ? '日志' : it.n.match(/\.jar$/) ? 'Jar' : it.n.match(/\.(parquet)$/) ? 'Parquet' : '文件') + '</td>'
                + '<td class="mono tiny">' + (it.s || '—') + '</td>'
                + '<td><div class="actions">'
                + '<button class="btn xs icon-only" title="预览内容" onclick="FlowTerm.preview(\'' + it.n.replace(/'/g, '') + '\')">' + FO_ICONS.get('eye') + '</button>'
                + '<button class="btn xs icon-only" title="下载（审批白名单 + 审计）" onclick="FO.toast(\'SFTP 下载需走审批白名单，DL_REQUEST 已写审计（演示）\',\'warn\')">' + FO_ICONS.get('download') + '</button>'
                + '</div></td></tr>';
          }).join('')
        + '</tbody></table></div>'
        + '<div class="tiny faint mt-2" style="flex:none">真实实现复用同一条 SSH 连接的 SFTP 子系统；下载与删除操作逐条写审计（DL_REQUEST / RM）</div>';
      paneSftp.querySelectorAll('.js-dir').forEach(function (row) {
        row.addEventListener('click', function () { sftpPath = row.dataset.p; renderSftp(); });
      });
      paneSftp.querySelectorAll('.js-crumb').forEach(function (a) {
        a.addEventListener('click', function () { sftpPath = '/' + parts.slice(0, +a.dataset.i).join('/'); renderSftp(); });
      });
      var rootBtn = paneSftp.querySelector('#sftpRoot');
      if (rootBtn) rootBtn.addEventListener('click', function () { sftpPath = '/'; renderSftp(); });
    }
    renderSftp();

    root.querySelectorAll('.tab-btn').forEach(function (b) {
      b.addEventListener('click', function () {
        root.querySelectorAll('.tab-btn').forEach(function (x) { x.classList.remove('on'); });
        b.classList.add('on');
        paneSsh.classList.toggle('hide', b.dataset.t !== 'ssh');
        paneSftp.classList.toggle('hide', b.dataset.t !== 'sftp');
        if (b.dataset.t === 'ssh') input.focus();
      });
    });
    FO.applyPerms(root);
  }

  /* ---------------- 大弹窗形态（可拖拽缩放 + 新窗口打开） ---------------- */
  function open(opt) {
    opt = opt || {};
    var pageUrl = 'terminal.html?n=' + encodeURIComponent(opt.nodeName || '')
      + '&i=' + encodeURIComponent(opt.ip || '')
      + '&c=' + encodeURIComponent(opt.credFp || '')
      + '&t=' + encodeURIComponent(opt.ctxTitle || '')
      + '&s=' + encodeURIComponent(opt.ctxSub || '')
      + '&p=' + encodeURIComponent(opt.startPath || '');
    var m = FO.modal({
      title: '远程操作 · ' + (opt.nodeName || '') + '（' + (opt.ip || '') + '）',
      width: 900,
      body: '<div class="term-shell" style="overflow:hidden;display:flex;flex-direction:column;position:relative;'
        + 'height:620px;min-width:640px;min-height:420px;'
        + 'border:1px solid var(--line-faint);border-radius:var(--r-sm);padding:12px">'
        + '<div class="term-mount" style="flex:1;min-height:0;display:flex;flex-direction:column"></div>'
        + '<div class="rs-grip" title="拖拽调整大小" style="position:absolute;right:2px;bottom:2px;width:16px;height:16px;cursor:nwse-resize;z-index:9;'
        + 'background:linear-gradient(135deg, transparent 46%, var(--line-strong) 46%, var(--line-strong) 52%, transparent 52%,'
        + ' transparent 60%, var(--line-strong) 60%, var(--line-strong) 66%, transparent 66%,'
        + ' transparent 74%, var(--line-strong) 74%, var(--line-strong) 80%, transparent 80%)"></div></div>'
        + '<div class="tiny faint mt-2">右下角可拖拽调整弹窗大小 · '
        + '<a href="' + pageUrl + '" target="_blank">在新页面全屏打开 →</a>（独立标签页，适合长时间排障）</div>',
      footer: '<button class="btn" data-act="cancel">断开（写审计）</button>'
    });
    mount(m.root.querySelector('.term-mount'), opt);
    /* 自研缩放：右下角把手拖拽（替代原生 resize:both） */
    var shell = m.root.querySelector('.term-shell');
    var modalEl = m.root.querySelector('.modal');
    var grip = m.root.querySelector('.rs-grip');
    var resizing = null;
    shell.style.height = Math.min(620, Math.round(window.innerHeight * 0.8)) + 'px';
    grip.addEventListener('pointerdown', function (e) {
      resizing = { x: e.clientX, y: e.clientY, w: shell.offsetWidth, h: shell.offsetHeight };
      grip.setPointerCapture(e.pointerId);
      e.preventDefault();
    });
    grip.addEventListener('pointermove', function (e) {
      if (!resizing) return;
      var w = Math.max(640, Math.min(window.innerWidth * 0.94 - 40, resizing.w + (e.clientX - resizing.x)));
      var h = Math.max(420, Math.min(window.innerHeight * 0.9 - 40, resizing.h + (e.clientY - resizing.y)));
      shell.style.width = w + 'px';
      shell.style.height = h + 'px';
    });
    grip.addEventListener('pointerup', function () { resizing = null; });
    /* 全屏 / 还原 */
    var fsBtn = m.root.querySelector('[data-fs]');
    if (fsBtn) {
      var fsState = false, prev = null;
      fsBtn.addEventListener('click', function () {
        if (!fsState) {
          prev = { w: shell.style.width, h: shell.style.height };
          modalEl.style.width = '96vw';
          shell.style.width = '100%';
          shell.style.height = '84vh';
          fsBtn.textContent = '还原';
          fsState = true;
        } else {
          modalEl.style.width = '900px';
          shell.style.width = prev.w;
          shell.style.height = prev.h;
          fsBtn.textContent = '全屏';
          fsState = false;
        }
      });
    }
    FO.applyPerms(m.root);
    return m;
  }

  function preview(name) {
    var content = FILE_DEMO[name];
    FO.modal({
      title: '文件预览 · ' + name, width: 560, okText: '关闭',
      body: (content
        ? '<div class="code" style="max-height:320px;overflow-y:auto">' + FO.esc(content) + '</div>'
        : '<div class="empty">' + FO_ICONS.get('file') + '<p>二进制文件不支持在线预览</p><p class="tiny faint">' + name + ' · 可申请下载（审批白名单 + 审计）</p></div>')
        + '<div class="tiny faint mt-3">预览大文件自动截断前 64KB；预览动作写审计（VIEW_FILE）</div>',
      footer: '<button class="btn" data-act="cancel">关闭</button>'
    });
  }

  window.FlowTerm = { open: open, mount: mount, preview: preview, FS: FS };
})();
