/*
 * HookFlow demo console — no framework, no build step.
 *
 * Everything the page shows comes from GET /v1/demo/state, which reads the real
 * tables (delivery / event / outbox) plus the app's own Micrometer counters. The
 * buttons drive the real pipeline too: the simulated business systems send HTTP
 * requests with an API key, exactly like a customer would.
 *
 * Rates (per second) are computed here from counter deltas, so the page never
 * claims a throughput the platform did not report.
 */

const API = '/v1/demo';
const HISTORY = 60;

let previous = null;              // last state, for delta-based rates
let lastState = null;
const series = { delivered: [], backlog: [], shed: [] };
let rateSliderTouched = false;

// ---------------------------------------------------------------- transport

async function getState() {
  const response = await fetch(`${API}/state`, { cache: 'no-store' });
  if (!response.ok) {
    throw new Error(`state ${response.status}`);
  }
  return response.json();
}

async function post(path, params = {}) {
  const query = new URLSearchParams(params).toString();
  const response = await fetch(`${API}${path}${query ? `?${query}` : ''}`, { method: 'POST' });
  if (!response.ok) {
    throw new Error(`${path} ${response.status}`);
  }
  return response.json().catch(() => ({}));
}

function toast(message, bad = false) {
  const element = document.getElementById('toast');
  element.textContent = message;
  element.classList.toggle('bad', bad);
  element.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => element.classList.remove('show'), 3200);
}

// ------------------------------------------------------------------ helpers

function text(node, value) {
  node.textContent = value === null || value === undefined ? '' : String(value);
  return node;
}

function el(tag, className, value) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (value !== undefined && value !== null) node.textContent = String(value);
  return node;
}

function number(value) {
  return value === null || value === undefined ? '—' : String(value);
}

function perSecond(now, before, key) {
  if (!before) return 0;
  return Math.max(0, now - before);
}

function ms(value) {
  if (value === null || value === undefined) return '—';
  if (value >= 1000) return `${(value / 1000).toFixed(1)}s`;
  return `${value}ms`;
}

function bySlug(state, slug) {
  return (state.tenants || []).find(tenant => tenant.slug === slug);
}

// -------------------------------------------------------------------- render

function renderKpis(state, rates) {
  text(document.getElementById('kpi-delivered'), `${rates.delivered}/s`);
  text(document.getElementById('kpi-backlog'), number(state.platform.outboxBacklog));
  text(document.getElementById('kpi-shed'), `${rates.shed}/s`);

  const inflight = state.tenants.reduce((sum, tenant) => sum + tenant.delivering, 0);
  text(document.getElementById('kpi-inflight'), inflight);
  const retrying = state.tenants.reduce((sum, tenant) => sum + tenant.retryWait, 0);
  text(document.getElementById('kpi-retry'), retrying);

  const fairness = document.getElementById('chip-fairness');
  fairness.textContent = `公平调度：${state.platform.fairnessEnabled ? '开' : '关'}`;
  fairness.className = `chip ${state.platform.fairnessEnabled ? 'on' : 'off'}`;

  text(document.getElementById('chip-watermark'), `租户水位：${state.platform.tenantHighWatermark}`);
  document.getElementById('chip-jaeger').href = state.jaegerUrl || 'http://localhost:16686';
}

function renderStreams(state) {
  const container = document.getElementById('streams');
  container.replaceChildren();

  for (const stream of state.simulator.streams) {
    const tenant = bySlug(state, stream.slug) || {};
    const card = el('div', 'stream');
    const head = el('div', 'stream-head');
    head.append(el('span', 'stream-name', stream.tenantName || stream.slug),
        el('span', 'stream-slug', `预算 ${number(tenant.maxInFlight)} · 订阅 ${tenant.subscribedTypes || '-'}`));
    card.append(head);
    card.append(el('div', 'stream-sub', `速率 ${stream.ratePerSecond}/s · 未结束订单 ${stream.openOrders}`));

    const numbers = el('div', 'stream-numbers');
    const cells = [
      ['发送', stream.sent, false],
      ['接受', stream.accepted, false],
      ['被限流', stream.throttled, stream.throttled > 0],
      ['失败', stream.failed, stream.failed > 0],
    ];
    for (const [label, value, bad] of cells) {
      const cell = el('div');
      cell.append(el('b', bad ? 'bad' : '', value), document.createTextNode(label));
      numbers.append(cell);
    }
    card.append(numbers);
    if (stream.lastError) {
      card.append(el('div', 'stream-err', stream.lastError));
    }
    container.append(card);
  }

  if (!rateSliderTouched) {
    const slider = document.getElementById('rate');
    slider.value = state.simulator.ratePerSecond;
    text(document.getElementById('rate-out'), state.simulator.ratePerSecond);
  }
}

function renderDeliveries(state) {
  const body = document.querySelector('#deliveries tbody');
  body.replaceChildren();

  for (const delivery of state.deliveries) {
    const row = el('tr', delivery.status === 'DEAD' ? 'dead' : '');
    row.append(el('td', '', delivery.id));
    row.append(el('td', 'name', delivery.tenantName));
    row.append(el('td', '', delivery.eventId));
    row.append(el('td', 'name', delivery.eventType || '-'));
    const statusCell = el('td');
    statusCell.append(el('span', `tag ${delivery.status}`, delivery.status));
    row.append(statusCell);
    row.append(el('td', '', `${delivery.attemptCount}/${delivery.maxAttempts}`));
    row.append(el('td', '', ms(delivery.latencyMs)));
    row.append(el('td', 'name', delivery.lastErrorClass
        ? `${delivery.lastErrorClass}${delivery.lastStatusCode ? ` (${delivery.lastStatusCode})` : ''}`
        : ''));

    const actionCell = el('td');
    if (delivery.status === 'DEAD') {
      const button = el('button', 'replay', '重放');
      button.addEventListener('click', async () => {
        try {
          await post(`/deliveries/${delivery.id}/replay`);
          toast(`已重放 #${delivery.id}`);
          refresh();
        } catch (error) {
          toast(`重放失败：${error.message}`, true);
        }
      });
      actionCell.append(button);
    }
    row.append(actionCell);
    body.append(row);
  }

  if (state.deliveries.length === 0) {
    const row = el('tr');
    const cell = el('td', 'name', '还没有投递。点上面的场景或「让 quick 提交 10 条」。');
    cell.colSpan = 9;
    cell.style.color = 'var(--muted)';
    row.append(cell);
    body.append(row);
  }
}

function renderReceiver(state) {
  const receiver = state.receiver;
  const stats = document.getElementById('recv-stats');
  stats.replaceChildren();
  const cells = [
    ['收到 webhook', receiver.received, ''],
    ['签名通过', receiver.signatureOk, 'ok'],
    ['签名失败', receiver.signatureBad, receiver.signatureBad > 0 ? 'bad' : ''],
  ];
  for (const [label, value, cls] of cells) {
    const cell = el('div');
    cell.append(el('b', cls, value), document.createTextNode(label));
    stats.append(cell);
  }

  const modeLabel = {
    ok: receiver.delayMillis > 0 ? `正常，但每次慢 ${receiver.delayMillis}ms` : '正常返回 200',
    error: '每次都返回 500',
    slow: '睡 5s 再回答（超过 3s 读超时）',
    reject: '直接断开连接',
  }[receiver.mode] || receiver.mode;
  text(document.getElementById('recv-mode'), `当前行为：${modeLabel}`);

  document.querySelectorAll('[data-rec]').forEach(button => {
    const mode = button.dataset.rec.toLowerCase();
    const delay = Number(button.dataset.delay || 0);
    button.classList.toggle('active',
        mode === receiver.mode && (mode !== 'ok' || delay === receiver.delayMillis));
  });

  const hooks = document.getElementById('hooks');
  hooks.replaceChildren();
  for (const hook of receiver.recent.slice(0, 12)) {
    const item = el('li', hook.signatureValid ? '' : 'bad');
    const title = el('div');
    title.append(el('b', '', `#${hook.deliveryId}`), document.createTextNode(' '));
    title.append(el('span', '', hook.eventType || ''));
    title.append(document.createTextNode(` · ${hook.slug} · 第 ${hook.attempt} 次 · ${hook.bytes}B`));
    item.append(title);
    const verdict = el('div', '', hook.signatureValid ? '✓ HMAC 验签通过（接收方自己算的）' : '✗ 验签失败');
    item.append(verdict);
    if (hook.traceId) {
      item.append(el('div', 'trace', `trace ${hook.traceId}`));
    }
    hooks.append(item);
  }
  if (receiver.recent.length === 0) {
    hooks.append(el('li', '', '还没有收到任何 webhook'));
  }
}

function renderFairness(state) {
  const container = document.getElementById('fairness');
  container.replaceChildren();
  for (const tenant of state.tenants) {
    const row = el('div', 'fair-row');
    const head = el('div', 'fair-head');
    head.append(el('span', '', tenant.name));
    head.append(el('span', 'budget', `预算 ${tenant.maxInFlight === 0 ? '不限' : tenant.maxInFlight}`));
    row.append(head);

    const metrics = el('div', 'fair-metrics');
    const cells = [
      ['在途', tenant.delivering, false],
      ['排队', tenant.pending + tenant.retryWait, false],
      ['p50', ms(tenant.p50Ms), false],
      ['p99', ms(tenant.p99Ms), tenant.p99Ms > 2000],
    ];
    for (const [label, value, hot] of cells) {
      const cell = el('div');
      cell.append(el('b', hot ? 'hot' : '', value), document.createTextNode(label));
      metrics.append(cell);
    }
    row.append(metrics);
    container.append(row);
  }
}

function renderSparks() {
  drawSpark('spark-rate', series.delivered, 'var(--ok)');
  drawSpark('spark-backlog', series.backlog, 'var(--accent)');
  drawSpark('spark-shed', series.shed, 'var(--bad)');
}

function drawSpark(id, values, color) {
  const svg = document.getElementById(id);
  if (!values.length) return;
  const max = Math.max(1, ...values);
  const step = 200 / Math.max(1, HISTORY - 1);
  const offset = HISTORY - values.length;
  const points = values.map((value, index) => {
    const x = (index + offset) * step;
    const y = 46 - (value / max) * 42;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  const line = `M${points.join(' L')}`;
  const area = `${line} L200,48 L${((HISTORY - 1) * step).toFixed(1)},48 Z`;
  svg.innerHTML =
      `<path d="${area}" fill="${color}" opacity="0.13"></path>` +
      `<path d="${line}" fill="none" stroke="${color}" stroke-width="1.6"></path>`;
}

// -------------------------------------------------------------------- poll

async function refresh() {
  let state;
  try {
    state = await getState();
  } catch (error) {
    showDisabled(error);
    return;
  }

  const rates = {
    delivered: previous ? state.platform.succeeded - previous.platform.succeeded : 0,
    shed: previous
        ? (state.platform.shedTenant + state.platform.shedPlatform)
          - (previous.platform.shedTenant + previous.platform.shedPlatform)
        : 0,
  };

  series.delivered.push(rates.delivered);
  series.backlog.push(state.platform.outboxBacklog);
  series.shed.push(rates.shed);
  for (const key of Object.keys(series)) {
    if (series[key].length > HISTORY) series[key].shift();
  }

  previous = state;
  lastState = state;

  renderKpis(state, rates);
  renderStreams(state);
  renderDeliveries(state);
  renderReceiver(state);
  renderFairness(state);
  renderSparks();

  text(document.getElementById('sim-toggle'), state.simulator.running ? '暂停' : '继续');
  text(document.getElementById('state-line'),
      `published ${state.platform.published} · 降载(租户) ${state.platform.shedTenant} · ` +
      `降载(平台) ${state.platform.shedPlatform} · 死信 ${state.platform.dead} · ` +
      `更新 ${new Date(state.now).toLocaleTimeString()}`);
}

function showDisabled(error) {
  const main = document.querySelector('main');
  if (main && !document.getElementById('disabled-banner')) {
    const banner = el('div', 'card');
    banner.id = 'disabled-banner';
    banner.style.gridColumn = '1 / -1';
    banner.append(el('h2', '', '演示控制台未启用'));
    banner.append(el('p', 'hint',
        `GET /v1/demo/state 返回 ${error.message}。控制台只在 hookflow.demo.enabled=true 时存在，`
        + '也就是本地的 all profile（./mvnw spring-boot:run）。部署环境里这些接口是 404，不是藏在密码后面。'));
    main.prepend(banner);
  }
}

// ----------------------------------------------------------------- actions

function bindActions() {
  document.getElementById('sim-toggle').addEventListener('click', async () => {
    const running = lastState ? !lastState.simulator.running : true;
    await post('/simulator', { running });
    refresh();
  });

  const slider = document.getElementById('rate');
  slider.addEventListener('input', () => {
    rateSliderTouched = true;
    text(document.getElementById('rate-out'), slider.value);
  });
  slider.addEventListener('change', async () => {
    await post('/simulator', { rate: slider.value });
    rateSliderTouched = false;
    refresh();
  });

  document.querySelectorAll('[data-rec]').forEach(button => {
    button.addEventListener('click', async () => {
      await post('/receiver', { mode: button.dataset.rec, delayMillis: button.dataset.delay || 0 });
      toast(`接收方：${button.textContent.trim()}`);
      refresh();
    });
  });

  document.querySelectorAll('[data-act]').forEach(button => {
    button.addEventListener('click', async () => {
      const action = button.dataset.act;
      const slug = button.dataset.tenant;
      const tenant = lastState ? bySlug(lastState, slug) : null;
      if (!tenant) {
        toast('还没有拿到租户，稍等一下', true);
        return;
      }
      if (action === 'burst') {
        await post('/burst', { tenantId: tenant.tenantId, count: button.dataset.count, perSecond: 0 });
        toast(`已向 ${tenant.name} 灌入 ${button.dataset.count} 条`);
      } else if (action === 'one') {
        const count = Number(button.dataset.count || 1);
        for (let i = 0; i < count; i += 1) {
          await post(`/tenants/${tenant.tenantId}/event`);
        }
        toast(`${tenant.name} 提交了 ${count} 条事件`);
      } else if (action === 'budget') {
        await post(`/tenants/${tenant.tenantId}/budget`, { maxInFlight: button.dataset.value });
        toast(`${tenant.name} 的并发预算 → ${button.dataset.value === '0' ? '不限' : button.dataset.value}`);
      } else if (action === 'reset') {
        const result = await post('/reset');
        toast(`已清空：${result.deliveries} 条投递 / ${result.events} 个事件`);
      }
      refresh();
    });
  });
}

// --------------------------------------------------------------- scenarios

const scenarios = {
  normal: {
    note: '两个业务系统正常下单，接收方返回 200。看「投递流水」：每个事件一次尝试就 SUCCEEDED，'
        + '「客户接收方」里签名全部通过——接收方自己用密钥算了一遍 HMAC。',
    run: async () => {
      await post('/receiver', { mode: 'OK', delayMillis: 0 });
      await post('/simulator', { running: true, rate: 6 });
    },
  },
  broken: {
    note: '接收方现在返回 500（客户 bug 或者挂了）。看几秒内状态变成 RETRY_WAIT、尝试次数按 1s/2s/4s/8s 退避；'
        + '5 次用完变成 DEAD，点「重放」它就再试一次。速率改回正常后，同一批事件会自己投递成功。',
    run: async () => {
      await post('/receiver', { mode: 'ERROR', delayMillis: 0 });
      await post('/simulator', { running: true, rate: 3 });
    },
  },
  fair: {
    note: '接收方变慢 300ms（一台扛不住的客户服务器），acme 的预算设为「不限」，然后灌 300 条；'
        + '同时让 quick 提交 10 条。看「公平性」：quick 的 p99 会被 acme 的积压拖到秒级——'
        + '这就是 v0.2 的行为，一个租户的积压把别人的队列堵住。',
    run: async () => {
      const acme = bySlug(lastState, 'acme');
      const quick = bySlug(lastState, 'quick');
      await post('/receiver', { mode: 'OK', delayMillis: 300 });
      await post('/simulator', { running: false });
      await post(`/tenants/${acme.tenantId}/budget`, { maxInFlight: 0 });
      await post('/burst', { tenantId: acme.tenantId, count: 300, perSecond: 0 });
      await sleep(600);
      for (let i = 0; i < 10; i += 1) {
        await post(`/tenants/${quick.tenantId}/event`);
      }
    },
  },
  budget: {
    note: '同一个场景，只把 acme 的并发预算改成 4。acme 的积压还留在 outbox 里（总量没变），'
        + '但排队的永远只有 4 条，quick 的事件几乎立刻被投递。再让 quick 提交 10 条，看 p99 从秒级掉到百毫秒级。',
    run: async () => {
      const acme = bySlug(lastState, 'acme');
      const quick = bySlug(lastState, 'quick');
      await post(`/tenants/${acme.tenantId}/budget`, { maxInFlight: 4 });
      await sleep(800);
      for (let i = 0; i < 10; i += 1) {
        await post(`/tenants/${quick.tenantId}/event`);
      }
    },
  },
  overload: {
    note: '把到达率拉到 200/s（远超这台机器的容量），接收方保持 300ms。看 429/秒开始上升、outbox 积压顶到水位线、'
        + '但 p99 不会爆炸——平台在入口拒绝，而不是把所有人一起拖慢。把速率拉回 5/s，积压会在十几秒内排空。',
    run: async () => {
      await post('/receiver', { mode: 'OK', delayMillis: 300 });
      await post('/simulator', { running: true, rate: 200 });
    },
  },
};

function bindScenarios() {
  document.querySelectorAll('[data-scenario]').forEach(button => {
    button.addEventListener('click', async () => {
      const scenario = scenarios[button.dataset.scenario];
      document.querySelectorAll('[data-scenario]').forEach(other => other.classList.remove('active'));
      button.classList.add('active');
      document.getElementById('scenario-note').replaceChildren(
          document.createTextNode(scenario.note));
      try {
        await scenario.run();
        toast(`场景：${button.textContent.trim()}`);
      } catch (error) {
        toast(`场景执行失败：${error.message}`, true);
      }
      refresh();
    });
  });
}

function sleep(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

// ------------------------------------------------------------------- start

bindActions();
bindScenarios();
refresh();
setInterval(refresh, 1000);
