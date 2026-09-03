'use strict';

const STATUSES = ['pending', 'in_progress', 'blocked', 'completed'];
const STATUS_LABELS = {
  pending: 'pending',
  in_progress: 'in_progress',
  blocked: 'blocked',
  completed: 'completed',
};

const els = {
  form: document.getElementById('createForm'),
  fTitle: document.getElementById('fTitle'),
  fDesc: document.getElementById('fDesc'),
  fAssignee: document.getElementById('fAssignee'),
  fStatus: document.getElementById('fStatus'),
  fDepends: document.getElementById('fDepends'),
  fAgent: document.getElementById('fAgent'),
  taskList: document.getElementById('taskList'),
  summary: document.getElementById('summary'),
  refreshBtn: document.getElementById('refreshBtn'),
  tabTasksBtn: document.getElementById('tabTasksBtn'),
  tabChatBtn: document.getElementById('tabChatBtn'),
  panelTasks: document.getElementById('panel-tasks'),
  panelChat: document.getElementById('panel-chat'),
  chatMessages: document.getElementById('chatMessages'),
  chatForm: document.getElementById('chatForm'),
  chatTo: document.getElementById('chatTo'),
  chatText: document.getElementById('chatText'),
  clearChatBtn: document.getElementById('clearChatBtn'),
};

function agent() {
  return (els.fAgent.value || 'anonymous').trim() || 'anonymous';
}

async function api(method, path, body) {
  const opts = { method, headers: { 'X-Agent': agent() } };
  if (body) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error || `HTTP ${res.status}`);
  }
  return res.json();
}

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function timeAgo(iso) {
  if (!iso) return '';
  const diff = Date.now() - new Date(iso).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 5) return 'just now';
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  return `${h}h ago`;
}

function renderSummary(counts) {
  const parts = STATUSES.map(
    (s) =>
      `<span class="chip chip-${s}">${STATUS_LABELS[s]}: <b>${counts[s] || 0}</b></span>`
  ).join('');
  els.summary.innerHTML = `<h2>Summary</h2><div class="chips">${parts}</div>`;
}

function renderTasks(list) {
  if (!list.length) {
    els.taskList.innerHTML = `<p class="empty">No tasks yet — create one above, or an agent can POST to /api/tasks.</p>`;
    return;
  }
  els.taskList.innerHTML = list
    .map((t) => {
      const depBadges = (t.dependsOn || [])
        .map((d) => `<span class="dep">⬅ ${esc(d)}</span>`)
        .join('');
      return `
      <div class="task card card-${t.status}" data-id="${esc(t.id)}">
        <div class="task-head">
          <span class="task-id">${esc(t.id)}</span>
          <span class="status-badge ${esc(t.status)}">${esc(t.status)}</span>
        </div>
        <div class="task-title">${esc(t.title)}</div>
        ${t.description ? `<div class="task-desc">${esc(t.description)}</div>` : ''}
        <div class="meta">
          <span>👤 ${esc(t.assignee || '—unassigned')}</span>
          ${depBadges}
          <span class="muted">by ${esc(t.createdBy || '?')} · updated ${esc(t.updatedBy || '—')} ${timeAgo(t.updatedAt)}</span>
        </div>
        <div class="task-actions">
          <select class="status-select">
            ${STATUSES.map((s) => `<option value="${s}" ${s === t.status ? 'selected' : ''}>${s}</option>`).join('')}
          </select>
          <input class="assign-input" placeholder="assignee" value="${esc(t.assignee || '')}" />
          <button class="btn-save">Save</button>
          <button class="btn-del">🗑 Delete</button>
        </div>
      </div>`;
    })
    .join('');

  els.taskList.querySelectorAll('.task').forEach((card) => {
    const id = card.dataset.id;
    const saveBtn = card.querySelector('.btn-save');
    const delBtn = card.querySelector('.btn-del');

    saveBtn.addEventListener('click', async () => {
      const status = card.querySelector('.status-select').value;
      const assignee = card.querySelector('.assign-input').value.trim() || null;
      const body = { status };
      if (typeof assignee === 'string') body.assignee = assignee;
      try {
        await api('PATCH', `/api/tasks/${id}`, body);
        await load();
      } catch (e) {
        alert(String(e.message));
      }
    });

    delBtn.addEventListener('click', async () => {
      if (!confirm(`Delete ${id}?`)) return;
      try {
        await api('DELETE', `/api/tasks/${id}`);
        await load();
      } catch (e) {
        alert(e.message);
      }
    });
  });
}

function timeStr(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

// ---------- Tabs ----------

function switchTab(tab) {
  const showTasks = tab === 'tasks';
  els.panelTasks.classList.toggle('hidden', !showTasks);
  els.panelChat.classList.toggle('hidden', showTasks);
  els.tabTasksBtn.classList.toggle('active', showTasks);
  els.tabChatBtn.classList.toggle('active', !showTasks);
  if (!showTasks) loadChat();
}

els.tabTasksBtn.addEventListener('click', () => switchTab('tasks'));
els.tabChatBtn.addEventListener('click', () => switchTab('chat'));

// ---------- Chat ----------

function fillAgentOptions() {
  els.chatTo.innerHTML = '<option value="">everyone</option>';
  els.chatTo.options[0].selected = true;
  api('GET', '/api/agents')
    .then((agents) => {
      agents.forEach((a) => {
        const opt = document.createElement('option');
        opt.value = a;
        opt.textContent = a;
        els.chatTo.appendChild(opt);
      });
    })
    .catch(() => {});
}

function renderChat(list) {
  if (!list.length) {
    els.chatMessages.innerHTML =
      '<p class="empty">No messages yet — say hi to the team, or an agent can POST to /api/messages.</p>';
    return;
  }
  const you = agent();
  els.chatMessages.innerHTML = list
    .map((m) => {
      const mine = m.author === you;
      const target = m.to && m.to !== '' ? `<span class="chat-to">→ ${esc(m.to)}</span>` : '';
      return `
      <div class="msg ${mine ? 'mine' : 'theirs'}">
        <div class="bubble">
          <div class="msg-head">
            <b class="author">${esc(m.author)}</b>
            ${target}
            <span class="time">${timeStr(m.at)}</span>
          </div>
          <div class="msg-text">${esc(m.text)}</div>
        </div>
      </div>`;
    })
    .join('');
  els.chatMessages.scrollTop = els.chatMessages.scrollHeight;
}

async function loadChat() {
  try {
    const msgs = await api('GET', '/api/messages');
    renderChat(msgs);
  } catch (e) {
    els.chatMessages.innerHTML = `<p class="empty error">Chat unavailable: ${esc(e.message)}</p>`;
  }
}

els.chatForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const text = els.chatText.value.trim();
  if (!text) return;
  const body = { text };
  const to = els.chatTo.value;
  if (to) body.to = to;
  try {
    await api('POST', '/api/messages', body);
    els.chatText.value = '';
    await Promise.all([loadChat(), fillAgentOptions()]);
  } catch (err) {
    alert(err.message);
  }
});

els.clearChatBtn.addEventListener('click', async () => {
  if (!confirm('Clear all chat messages?')) return;
  try {
    await api('DELETE', '/api/messages');
    await loadChat();
  } catch (e) {
    alert(e.message);
  }
});

async function load() {
  try {
    const [tasks, statusInfo] = await Promise.all([
      api('GET', '/api/tasks'),
      api('GET', '/api/statuses'),
    ]);
    renderSummary(statusInfo.counts);
    renderTasks(tasks);
  } catch (e) {
    els.taskList.innerHTML = `<p class="empty error">Could not reach the TaskBoard API: ${esc(e.message)}.<br>Is the server running? (<code>node server.js</code> on port 8081)</p>`;
  }
}

els.form.addEventListener('submit', async (e) => {
  e.preventDefault();
  const title = els.fTitle.value.trim();
  if (!title) return;
  const dependsOn = els.fDepends.value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  const body = {
    title,
    description: els.fDesc.value.trim(),
    assignee: els.fAssignee.value.trim() || null,
    status: els.fStatus.value,
  };
  if (dependsOn.length) body.dependsOn = dependsOn;
  try {
    await api('POST', '/api/tasks', body);
    els.form.reset();
    await load();
  } catch (err) {
    alert(err.message);
  }
});

els.refreshBtn.addEventListener('click', () => {
  load();
  loadChat();
});

fillAgentOptions();
load();
loadChat();
setInterval(() => {
  load();
  loadChat();
}, 3000);