"use strict";

// Thin, read-only dashboard over GET /api/v1/tenants/{tenantId}/top-categories.
// Same-origin as the API (served from Spring static/), so fetch URLs are relative.
// No business logic here: we render exactly what the API returns.

(function () {
  const $ = (id) => document.getElementById(id);

  const els = {
    form: $("controls"),
    tenantId: $("tenantId"),
    mode: $("mode"),
    window: $("window"),
    channel: $("channel"),
    k: $("k"),
    loadBtn: $("loadBtn"),
    meta: $("meta"),
    badge: $("badge"),
    scope: $("scope"),
    asOf: $("asOf"),
    insight: $("insight"),
    banner: $("banner"),
    loading: $("state-loading"),
    empty: $("state-empty"),
    error: $("state-error"),
    results: $("results"),
    table: $("table"),
    tbody: $("tbody"),
  };

  let chart = null;

  function showOnly(state) {
    // state: 'loading' | 'empty' | 'error' | 'results' | null
    els.loading.hidden = state !== "loading";
    els.empty.hidden = state !== "empty";
    els.error.hidden = state !== "error";
    els.results.hidden = state !== "results";
  }

  function formatCurrency(value) {
    const n = Number(value);
    if (!isFinite(n)) return String(value);
    return n.toLocaleString(undefined, { style: "currency", currency: "USD" });
  }

  function formatDelta(d) {
    const n = Number(d);
    if (!isFinite(n)) return "";
    const sign = n > 0 ? "+" : "";
    return sign + (n * 100).toFixed(1) + "%";
  }

  function setBadge(status) {
    const s = (status || "unknown").toLowerCase();
    els.badge.textContent = s;
    els.badge.className = "badge " + s; // .fresh / .stale / .pending / .degraded set the colour
  }

  const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

  // Render the ISO instant asOf as "as of 28 Jun 2026, 06:00" (in UTC, so the figure never shifts by
  // viewer timezone). Falls back to the raw string if it can't be parsed.
  function formatAsOf(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "as of " + iso;
    const hh = String(d.getUTCHours()).padStart(2, "0");
    const mm = String(d.getUTCMinutes()).padStart(2, "0");
    return (
      "as of " + d.getUTCDate() + " " + MONTHS[d.getUTCMonth()] + " " + d.getUTCFullYear() +
      ", " + hh + ":" + mm
    );
  }

  // Honest notice above results when the API can't serve a genuine forecast.
  // degraded => loud warning (on-the-fly estimate); pending => soft note (actuals stand-in).
  function renderBanner(status) {
    const s = (status || "").toLowerCase();
    if (s === "degraded") {
      els.banner.textContent =
        "⚠ Forecasts unavailable — showing an on-the-fly seasonal-naive estimate (low confidence).";
      els.banner.className = "banner banner-degraded";
      els.banner.hidden = false;
    } else if (s === "pending") {
      els.banner.textContent = "Forecast not yet computed — showing recent actuals.";
      els.banner.className = "banner banner-pending";
      els.banner.hidden = false;
    } else {
      els.banner.hidden = true;
    }
  }

  function cap(s) {
    return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
  }

  // Format a "YYYY-MM-DD" date string without going through Date() (avoids browser-timezone shifts).
  function fmtDate(s, withYear) {
    if (!s) return "";
    const [y, m, d] = s.split("-").map(Number);
    return MONTHS[m - 1] + " " + d + (withYear ? ", " + y : "");
  }

  // "Actuals · Month · All — May 30 – Jun 28, 2026" from the response's own fields.
  function scopeLabel(body) {
    const parts = [cap(body.mode), cap(body.window), cap(body.channel)].filter(Boolean).join(" · ");
    const range =
      body.windowFrom && body.windowTo
        ? fmtDate(body.windowFrom, false) + " – " + fmtDate(body.windowTo, true)
        : "";
    return range ? parts + " — " + range : parts;
  }

  // Whether any item carries the forecast-only fields. Drives column visibility.
  function present(items, key) {
    return items.some((it) => it[key] !== undefined && it[key] !== null);
  }

  function setColVisibility(show, cls) {
    document.querySelectorAll("." + cls).forEach((el) => {
      el.hidden = !show;
    });
  }

  function renderTable(items, flags) {
    els.tbody.innerHTML = "";
    for (const it of items) {
      const tr = document.createElement("tr");

      const rank = document.createElement("td");
      rank.className = "num";
      rank.textContent = it.rank;
      tr.appendChild(rank);

      const cat = document.createElement("td");
      cat.textContent = it.category; // textContent => category names stay untrusted/escaped
      tr.appendChild(cat);

      const val = document.createElement("td");
      val.className = "num";
      val.textContent = formatCurrency(it.value);
      tr.appendChild(val);

      const delta = document.createElement("td");
      delta.className = "num col-delta";
      delta.hidden = !flags.delta;
      if (it.deltaVsPrior !== undefined && it.deltaVsPrior !== null) {
        const n = Number(it.deltaVsPrior);
        delta.textContent = formatDelta(it.deltaVsPrior);
        delta.classList.add(n >= 0 ? "delta-up" : "delta-down");
      }
      tr.appendChild(delta);

      const conf = document.createElement("td");
      conf.className = "col-conf";
      conf.hidden = !flags.conf;
      if (it.confidence) {
        // Render confidence as a small colour-coded chip (HIGH=green, MEDIUM=amber, LOW=grey).
        const chip = document.createElement("span");
        chip.className = "chip conf-" + it.confidence;
        chip.textContent = it.confidence;
        conf.appendChild(chip);
      }
      tr.appendChild(conf);

      const interval = document.createElement("td");
      interval.className = "col-interval";
      interval.hidden = !flags.interval;
      if (it.interval && it.interval.low !== undefined && it.interval.high !== undefined) {
        interval.textContent =
          formatCurrency(it.interval.low) + " – " + formatCurrency(it.interval.high);
      }
      tr.appendChild(interval);

      els.tbody.appendChild(tr);
    }
  }

  // Chart.js v4 has no built-in error bars, so this tiny plugin draws the prediction interval as a
  // vertical whisker (low→high, with end caps) centred on each bar. The bands live on the dataset as
  // `intervals` (one {low,high} or null per bar); when none are present the plugin no-ops and we get
  // plain bars. Drawn in afterDatasetsDraw so the whiskers sit on top of the bars.
  const errorBarsPlugin = {
    id: "intervalWhiskers",
    afterDatasetsDraw(c) {
      const ds = c.data.datasets[0];
      const intervals = ds && ds.intervals;
      if (!intervals) return;
      const ctx = c.ctx;
      const yScale = c.scales.y;
      const bars = c.getDatasetMeta(0).data;
      const cap = 5; // half-width of the end caps, in px
      ctx.save();
      ctx.strokeStyle = "#e7e9f3";
      ctx.lineWidth = 1.5;
      bars.forEach((bar, i) => {
        const iv = intervals[i];
        if (!iv) return;
        const x = bar.x;
        const yLow = yScale.getPixelForValue(iv.low);
        const yHigh = yScale.getPixelForValue(iv.high);
        ctx.beginPath();
        ctx.moveTo(x, yLow);
        ctx.lineTo(x, yHigh); // the whisker
        ctx.moveTo(x - cap, yLow);
        ctx.lineTo(x + cap, yLow); // bottom cap
        ctx.moveTo(x - cap, yHigh);
        ctx.lineTo(x + cap, yHigh); // top cap
        ctx.stroke();
      });
      ctx.restore();
    },
  };

  function renderChart(items) {
    const ctx = $("chart").getContext("2d");
    const labels = items.map((it) => it.category);
    const data = items.map((it) => Number(it.value));

    // Only build the interval band when genuine forecast items carry one (absent for actuals/pending/
    // degraded). null entries leave that bar whisker-less.
    const hasInterval = (it) =>
      it.interval && it.interval.low !== undefined && it.interval.high !== undefined &&
      it.interval.low !== null && it.interval.high !== null;
    const intervals = items.some(hasInterval)
      ? items.map((it) => (hasInterval(it) ? { low: Number(it.interval.low), high: Number(it.interval.high) } : null))
      : null;

    // Stretch the value axis so the highest whisker isn't clipped at the top of a bar.
    const maxHigh = intervals
      ? intervals.reduce((m, iv) => (iv ? Math.max(m, iv.high) : m), 0)
      : 0;

    if (chart) chart.destroy();
    chart = new Chart(ctx, {
      type: "bar",
      data: {
        labels,
        datasets: [{ label: "Value", data, intervals, backgroundColor: "#6c8cff", borderRadius: 4 }],
      },
      plugins: [errorBarsPlugin],
      options: {
        responsive: true,
        plugins: { legend: { display: false } },
        scales: {
          x: { ticks: { color: "#9aa0b8" }, grid: { display: false } },
          y: {
            beginAtZero: true,
            suggestedMax: maxHigh ? maxHigh * 1.05 : undefined,
            ticks: { color: "#9aa0b8", callback: (v) => formatCurrency(v) },
            grid: { color: "#2a3050" },
          },
        },
      },
    });
  }

  function renderResponse(body) {
    setBadge(body.status);
    els.scope.textContent = scopeLabel(body);
    els.asOf.textContent = formatAsOf(body.asOf);
    els.meta.hidden = false;

    renderBanner(body.status);

    if (body.insight) {
      els.insight.textContent = body.insight;
      els.insight.hidden = false;
    } else {
      els.insight.hidden = true;
    }

    const items = Array.isArray(body.items) ? body.items : [];
    if (items.length === 0) {
      showOnly("empty");
      return;
    }

    const flags = {
      delta: present(items, "deltaVsPrior"),
      conf: present(items, "confidence"),
      interval: present(items, "interval"),
    };
    // Header cells use the same column classes; hide them when data is absent.
    setColVisibility(flags.delta, "col-delta");
    setColVisibility(flags.conf, "col-conf");
    setColVisibility(flags.interval, "col-interval");

    renderTable(items, flags);
    renderChart(items);
    showOnly("results");
  }

  function renderError(message) {
    els.meta.hidden = true;
    els.insight.hidden = true;
    els.banner.hidden = true;
    els.error.textContent = message;
    showOnly("error");
  }

  async function load() {
    const tenant = els.tenantId.value.trim();
    if (!tenant) {
      renderError("Tenant id is required.");
      return;
    }
    const mode = els.mode.value;
    const window = els.window.value;
    const channel = els.channel.value;
    const k = els.k.value;

    const url =
      "/api/v1/tenants/" +
      encodeURIComponent(tenant) +
      "/top-categories?mode=" +
      encodeURIComponent(mode) +
      "&window=" +
      encodeURIComponent(window) +
      "&channel=" +
      encodeURIComponent(channel) +
      "&k=" +
      encodeURIComponent(k);

    els.loadBtn.disabled = true;
    els.meta.hidden = true;
    els.insight.hidden = true;
    els.banner.hidden = true;
    showOnly("loading");

    try {
      const res = await fetch(url, {
        headers: {
          // Dev stand-in for auth; mirrors the path tenant.
          "X-Tenant-Id": tenant,
          Accept: "application/json",
        },
      });

      const text = await res.text();
      let body = null;
      try {
        body = text ? JSON.parse(text) : null;
      } catch (_) {
        // non-JSON body
      }

      if (!res.ok) {
        // RFC 7807 problem+json: prefer title/detail when present.
        let msg = "Request failed (HTTP " + res.status + ").";
        if (body && (body.title || body.detail)) {
          msg =
            (body.title || "Error") +
            (body.detail ? "\n" + body.detail : "") +
            "\n(HTTP " + res.status + ")";
        } else if (text) {
          msg += "\n" + text;
        }
        renderError(msg);
        return;
      }

      if (!body) {
        renderError("Empty or invalid response body.");
        return;
      }

      renderResponse(body);
    } catch (err) {
      renderError("Could not reach the API.\n" + (err && err.message ? err.message : err));
    } finally {
      els.loadBtn.disabled = false;
    }
  }

  function setTenantOptions(ids) {
    els.tenantId.replaceChildren();
    for (const id of ids) {
      const opt = document.createElement("option");
      opt.value = id;
      opt.textContent = id;
      els.tenantId.appendChild(opt);
    }
  }

  // Used when GET /api/v1/config can't be reached, so the demo still renders honest controls.
  const FALLBACK_CONFIG = {
    kOptions: [5, 7, 10],
    kDefault: 10,
    windowOptions: ["week", "month", "year"],
    windowDefault: "month",
    channelOptions: ["all", "online", "offline"],
    channelDefault: "all",
  };

  // Build a <select>'s <option>s via createElement + textContent (never innerHTML) — option labels
  // are config-controlled, but we keep the untrusted-input rule uniform across the dashboard.
  function setSelectOptions(selectEl, values, selected) {
    selectEl.replaceChildren();
    for (const v of values) {
      const opt = document.createElement("option");
      opt.value = String(v);
      opt.textContent = String(v);
      if (String(v) === String(selected)) opt.selected = true;
      selectEl.appendChild(opt);
    }
  }

  function applyConfig(cfg) {
    setSelectOptions(els.k, cfg.kOptions, cfg.kDefault);
    setSelectOptions(els.window, cfg.windowOptions, cfg.windowDefault);
    setSelectOptions(els.channel, cfg.channelOptions, cfg.channelDefault);
  }

  // Build the k / window / channel controls from GET /api/v1/config (central TopsalesProperties),
  // falling back to sane defaults so the demo always renders.
  async function populateConfig() {
    try {
      const res = await fetch("/api/v1/config", { headers: { Accept: "application/json" } });
      const body = res.ok ? await res.json() : null;
      applyConfig(body && Array.isArray(body.kOptions) ? body : FALLBACK_CONFIG);
    } catch (_) {
      applyConfig(FALLBACK_CONFIG);
    }
  }

  // Populate the tenant picker from GET /api/v1/tenants, then load. Falls back to a single
  // tenant_a option if the catalog can't be reached, so the demo still renders.
  async function populateTenants() {
    try {
      const res = await fetch("/api/v1/tenants", { headers: { Accept: "application/json" } });
      const body = res.ok ? await res.json() : null;
      const ids = body && Array.isArray(body.tenants) && body.tenants.length ? body.tenants : ["tenant_a"];
      setTenantOptions(ids);
    } catch (_) {
      setTenantOptions(["tenant_a"]);
    }
  }

  els.form.addEventListener("submit", (e) => {
    e.preventDefault();
    load();
  });

  // Reload immediately when the tenant changes — convenient for flipping between demo tenants.
  els.tenantId.addEventListener("change", load);

  // Build the config-driven controls and the tenant dropdown, then auto-load once so the demo shows
  // data immediately.
  Promise.all([populateTenants(), populateConfig()]).then(load);
})();
