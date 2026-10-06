/* ============================================================
   FlowOps 原型 · 内联 SVG 图标库（零外链、零图标字体）
   用法：FO.icon('play') → '<svg …>…</svg>'
   ============================================================ */
(function () {
  var P = {}; // path fragments

  function i(d, extra) { return '<path d="' + d + '"' + (extra || ' fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"') + '/>'; }

  P.dashboard = i('M3 3h7v7H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 14h7v7H3z');
  P.tasks     = i('M4 6h2M4 12h2M4 18h2M9 6h11M9 12h11M9 18h11');
  P.workflow  = i('M5 4h4v4H5zM15 16h4v4h-4zM7 8v6a2 2 0 0 0 2 2h6') + '<circle cx="7" cy="4" r="0"/>';
  P.cluster   = i('M4 5h16v6H4zM4 13h16v6H4zM7 8h.01M7 16h.01');
  P.node      = i('M9 3h6v5H9zM3 16h6v5H3zM15 16h6v5h-6zM12 8v3M6 16v-2a3 3 0 0 1 3-3h6a3 3 0 0 1 3 3v2');
  P.operator  = i('M12 2 3 7v10l9 5 9-5V7zM12 12 3 7M12 12l9-5M12 12v10');
  P.credential= i('M6 10V7a6 6 0 0 1 12 0v3M5 10h14v11H5zM12 14v3');
  P.alert     = i('M12 3 2 20h20zM12 9v5M12 17h.01');
  P.audit     = i('M12 3a9 9 0 1 0 9 9M21 3l-9 9M15 3h6v6');
  P.health    = i('M3 12h4l2-6 4 12 2-6h6');
  P.calendar  = i('M4 6h16v15H4zM4 10h16M8 3v4M16 3v4');
  P.users     = i('M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21a7 7 0 0 1 14 0M17 7a4 4 0 0 1 0 8M22 21a7 7 0 0 0-5-6.7');
  P.user      = i('M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0');
  P.play      = i('M7 4.5 19 12 7 19.5z');
  P.stop      = i('M6 6h12v12H6z');
  P.retry     = i('M21 12a9 9 0 1 1-2.6-6.3M21 3v6h-6');
  P.plus      = i('M12 5v14M5 12h14');
  P.edit      = i('M4 20h4L20 8l-4-4L4 16zM14 6l4 4');
  P.trash     = i('M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 11v6M14 11v6');
  P.search    = i('M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3');
  P.filter    = i('M3 5h18l-7 8v6l-4-2v-4z');
  P.close     = i('M6 6l12 12M18 6 6 18');
  P.check     = i('M4 12.5 10 18 20 6');
  P.cross     = i('M6 6l12 12M18 6 6 18');
  P.clock     = i('M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3.5 2');
  P.info      = i('M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 8h.01M12 11v6');
  P.chevD     = i('M6 9l6 6 6-6');
  P.chevR     = i('M9 6l6 6-6 6');
  P.chevL     = i('M15 6l-6 6 6 6');
  P.external  = i('M14 4h6v6M20 4l-9 9M18 13v7H4V6h7');
  P.copy      = i('M9 9h11v11H9zM5 15H4V4h11v1');
  P.download  = i('M12 3v12M7 11l5 5 5-5M4 21h16');
  P.upload    = i('M12 15V3M7 7l5-5 5 5M4 21h16');
  P.eye       = i('M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12zM12 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z');
  P.eyeOff    = i('M3 3l18 18M10.6 5.1A10 10 0 0 1 22 12a15 15 0 0 1-3 3.5M6.6 6.6A15 15 0 0 0 2 12s3.5 6.5 10 6.5a10 10 0 0 0 4-.8M9.9 9.9a2.5 2.5 0 0 0 3.5 3.5');
  P.lock      = i('M6 11h12v10H6zM9 11V7a3 3 0 0 1 6 0v4');
  P.key       = i('M14 10a4 4 0 1 0-4 4L3 21v-3h3v-3h3l1-1a4 4 0 0 1 4-4zM17.5 6.5h.01');
  P.db        = i('M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3');
  P.server    = i('M4 4h16v7H4zM4 13h16v7H4zM7.5 7.5h.01M7.5 16.5h.01');
  P.queue     = i('M4 5h10M4 10h16M4 15h10M4 20h16');
  P.bolt      = i('M13 2 4 14h6l-1 8 9-12h-6z');
  P.sun       = i('M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4');
  P.moon      = i('M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z');
  P.logout    = i('M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9');
  P.brand     = '<rect x="3" y="3" width="18" height="18" rx="4" fill="none" stroke="currentColor" stroke-width="1.7"/><path d="M7 13l2.5 3L17 7" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/>';
  P.diag      = i('M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1') + '<circle cx="12" cy="12" r="3.2" fill="none" stroke="currentColor" stroke-width="1.7"/>';
  P.backfill  = i('M3 12a9 9 0 1 0 3-6.7M3 3v6h6M12 7v5l3.5 2');
  P.link      = i('M9 15l6-6M8 12l-2.5 2.5a3.5 3.5 0 0 0 5 5L13 17M16 12l2.5-2.5a3.5 3.5 0 0 0-5-5L11 7');
  P.file      = i('M6 2h8l4 4v16H6zM14 2v5h5');
  P.settings  = i('M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z') + '<path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3h.1a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9v.1a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" fill="none" stroke="currentColor" stroke-width="1.7"/>';
  P.flag      = i('M5 21V4M5 4h13l-2.5 4L18 12H5');
  P.shield    = i('M12 2 4 5v6c0 5.2 3.4 9 8 11 4.6-2 8-5.8 8-11V5zM9 12l2 2 4-4.5');
  P.refresh   = i('M21 12a9 9 0 1 1-2.6-6.3M21 3v6h-6');
  P.maximize  = i('M8 3H3v5M16 3h5v5M8 21H3v-5M16 21h5v-5');
  P.hand      = i('M18 11V6.5a1.5 1.5 0 0 0-3 0V11m0-1V4.5a1.5 1.5 0 0 0-3 0V11m0-5.5a1.5 1.5 0 0 0-3 0V13l-1.8-2a1.6 1.6 0 0 0-2.4 2.1L9 19a6 6 0 0 0 5 3h1a6 6 0 0 0 6-6v-5');
  P.zoomIn    = i('M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3M11 8v6M8 11h6');
  P.minus     = i('M5 12h14');

  var CACHE = {};
  window.FO_ICONS = {
    get: function (name) {
      if (CACHE[name]) return CACHE[name];
      var body = P[name] || P.info;
      var svg = '<svg viewBox="0 0 24 24" aria-hidden="true">' + body + '</svg>';
      CACHE[name] = svg;
      return svg;
    },
    has: function (n) { return !!P[n]; }
  };
})();
