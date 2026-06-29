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
    asOf: $("asOf"),
    insight: $("insight"),
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
    els.badge.className = "badge " + s;
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
        conf.textContent = it.confidence;
        conf.classList.add("conf-" + it.confidence);
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

  function renderChart(items) {
    const ctx = $("chart").getContext("2d");
    const labels = items.map((it) => it.category);
    const data = items.map((it) => Number(it.value));

    if (chart) chart.destroy();
    chart = new Chart(ctx, {
      type: "bar",
      data: {
        labels,
        datasets: [{ label: "Value", data, backgroundColor: "#6c8cff", borderRadius: 4 }],
      },
      options: {
        responsive: true,
        plugins: { legend: { display: false } },
        scales: {
          x: { ticks: { color: "#9aa0b8" }, grid: { display: false } },
          y: {
            ticks: { color: "#9aa0b8", callback: (v) => formatCurrency(v) },
            grid: { color: "#2a3050" },
          },
        },
      },
    });
  }

  function renderResponse(body) {
    setBadge(body.status);
    els.asOf.textContent = body.asOf ? "as of " + body.asOf : "";
    els.meta.hidden = false;

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

  els.form.addEventListener("submit", (e) => {
    e.preventDefault();
    load();
  });

  // Auto-load once on first paint so the demo shows data immediately.
  load();
})();
