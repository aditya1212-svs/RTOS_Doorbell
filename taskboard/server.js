/**
 * TaskBoard — zero-dependency Node HTTP server.
 * A tiny shared task board so multiple agents can create, assign and
 * sync tasks. Runs standalone on port 8081 (does not interfere with the
 * Spring Boot backend on 8080).
 *
 *   node server.js            -> http://localhost:8081
 *   PORT=8081 node server.js  -> explicit port
 *
 * REST API (all paths under /api):
 *   GET    /api/tasks               list all tasks (newest first)
 *   POST   /api/tasks               create   {title, description?, assignee?, status?}
 *   GET    /api/tasks/:id           single task
 *   PATCH  /api/tasks/:id           partial update {title?, description?, assignee?, status?}
 *   DELETE /api/tasks/:id           remove a task
 *   GET    /api/statuses            valid statuses + summary counts
 *
 * Pass an optional X-Agent header to record who created/updated a task, e.g.
 *   curl -X PATCH localhost:8081/api/tasks/TKT-003 -H 'X-Agent: agent-b' \
 *        -H 'Content-Type: application/json' -d '{"status":"in_progress"}'
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT) || 8081;
const HOST = process.env.HOST || '127.0.0.1';
const DATA_DIR = path.join(__dirname, 'data');
const DATA_FILE = path.join(DATA_DIR, 'tasks.json');
const CHAT_FILE = path.join(DATA_DIR, 'messages.json');
const PUBLIC_DIR = path.join(__dirname, 'public');

const VALID_STATUSES = ['pending', 'in_progress', 'blocked', 'completed'];

let next = 1;
let tasks = [];
let messages = [];

function load() {
  try {
    if (!fs.existsSync(DATA_FILE)) return;
    const raw = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
    tasks = Array.isArray(raw.tasks) ? raw.tasks : [];
    next = raw.next && Number.isInteger(raw.next) ? raw.next : inferNext(tasks);
  } catch (e) {
    console.error('[taskboard] failed loading data file, starting empty:', e.message);
    tasks = [];
    next = 1;
  }
}

function inferNext(list) {
  let n = 1;
  for (const t of list) {
    const m = /^TASK-(\d+)$/.exec(t.id || '');
    if (m) n = Math.max(n, Number(m[1]) + 1);
  }
  return n;
}

function save() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmp = DATA_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify({ next, tasks }, null, 2));
  fs.renameSync(tmp, DATA_FILE);
}

function loadMessages() {
  try {
    if (!fs.existsSync(CHAT_FILE)) return;
    const arr = JSON.parse(fs.readFileSync(CHAT_FILE, 'utf8'));
    messages = Array.isArray(arr) ? arr : [];
  } catch (e) {
    console.error('[taskboard] failed loading chat file, starting with no messages:', e.message);
    messages = [];
  }
}

function saveMessages() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmp = CHAT_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(messages, null, 2));
  fs.renameSync(tmp, CHAT_FILE);
}

function nowIso() {
  return new Date().toISOString();
}

function publicTask(t) {
  return { ...t };
}

function findTask(id) {
  return tasks.find((t) => t.id === id);
}

// ---- helpers -------------------------------------------------------------

function readJsonBody(req, limitBytes = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limitBytes) {
        reject(Object.assign(new Error('payload too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (chunks.length === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (e) {
        reject(Object.assign(new Error('invalid JSON body'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function sendJSON(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function agentOf(req) {
  const h = req.headers['x-agent'];
  return h && String(h).trim() ? String(h).trim() : null;
}

function payloadHasOwn(obj, key) {
  return Object.prototype.hasOwnProperty.call(obj, key);
}

function contentType(file) {
  const ext = path.extname(file).toLowerCase();
  switch (ext) {
    case '.html': return 'text/html; charset=utf-8';
    case '.js': return 'text/javascript; charset=utf-8';
    case '.css': return 'text/css; charset=utf-8';
    case '.json': return 'application/json; charset=utf-8';
    default: return 'application/octet-stream';
  }
}

// ---------- request handler ----------

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const { pathname } = url;

  // GET /api/tasks
  if (pathname === '/api/tasks' && req.method === 'GET') {
    const sorted = [...tasks].sort((a, b) => (b.createdAt < a.createdAt ? -1 : 1));
    return sendJSON(res, 200, sorted);
  }

  // POST /api/tasks
  if (pathname === '/api/tasks' && req.method === 'POST') {
    return readJsonBody(req)
      .then((body) => {
        const title = (body.title || '').toString().trim();
        if (!title) return sendJSON(res, 400, { error: 'title is required' });
        if (body.status && !VALID_STATUSES.includes(body.status)) {
          return sendJSON(res, 400, {
            error: `invalid status, must be one of: ${VALID_STATUSES.join(', ')}`,
          });
        }
        const status = VALID_STATUSES.includes(body.status) ? body.status : 'pending';
        const task = {
          id: `TASK-${String(next++).padStart(3, '0')}`,
          title,
          description: (body.description || '').toString().trim(),
          assignee: (body.assignee || '').toString().trim() || null,
          status,
          dependsOn: Array.isArray(body.dependsOn) ? body.dependsOn.map(String) : [],
          createdAt: nowIso(),
          updatedAt: nowIso(),
          createdBy: agentOf(req),
          updatedBy: agentOf(req),
        };
        tasks.push(task);
        save();
        return sendJSON(res, 201, task);
      })
      .catch((e) => sendJSON(res, e.status || 500, { error: e.message }));
  }

  // /api/tasks/:id
  const m = /^\/api\/tasks\/([^/]+)$/.exec(pathname);
  if (m) {
    const id = decodeURIComponent(m[1]);
    const task = findTask(id);

    if (req.method === 'GET') {
      if (!task) return sendJSON(res, 404, { error: 'not found' });
      return sendJSON(res, 200, publicTask(task));
    }

    if (req.method === 'PATCH') {
      if (!task) return sendJSON(res, 404, { error: 'not found' });
      return readJsonBody(req)
        .then((body) => {
          if (typeof body.title === 'string') {
            const t = body.title.trim();
            if (!t) return sendJSON(res, 400, { error: 'title cannot be empty' });
            task.title = t;
          }
          if (typeof body.description === 'string') task.description = body.description.trim();
          if (payloadHasOwn(body, 'assignee')) {
            const v = body.assignee == null ? null : String(body.assignee).trim();
            task.assignee = v || null;
          }
          if (typeof body.status === 'string') {
            if (!VALID_STATUSES.includes(body.status)) {
              return sendJSON(res, 400, {
                error: `invalid status. must be one of: ${VALID_STATUSES.join(', ')}`,
              });
            }
            task.status = body.status;
          }
          if (Array.isArray(body.dependsOn)) task.dependsOn = body.dependsOn.map(String);
          task.updatedAt = nowIso();
          task.updatedBy = agentOf(req);
          save();
          return sendJSON(res, 200, publicTask(task));
        })
        .catch((e) => sendJSON(res, e.status || 500, { error: e.message }));
    }

    if (req.method === 'DELETE') {
      if (!task) return sendJSON(res, 404, { error: 'not found' });
      tasks = tasks.filter((t) => t.id !== id);
      save();
      return sendJSON(res, 200, { ok: true, id });
    }

    return sendJSON(res, 405, { error: 'method not allowed' });
  }

  // ----- Chat / messages -----

  // GET /api/messages
  if (pathname === '/api/messages' && req.method === 'GET') {
    return sendJSON(res, 200, messages);
  }

  // POST /api/messages  {text?, to?}
  if (pathname === '/api/messages' && req.method === 'POST') {
    return readJsonBody(req)
      .then((body) => {
        const text = (body.text || '').toString().trim();
        if (!text) return sendJSON(res, 400, { error: 'text is required' });
        const agent = agentOf(req) || 'unknown';
        const msg = {
          id: `MSG-${messages.length + 1}`,
          text,
          author: agent,
          to: body.to ? String(body.to).trim() : null,
          at: nowIso(),
        };
        messages.push(msg);
        saveMessages();
        return sendJSON(res, 201, msg);
      })
      .catch((e) => sendJSON(res, e.status || 500, { error: e.message }));
  }

  // DELETE /api/messages  (clear all)
  if (pathname === '/api/messages' && req.method === 'DELETE') {
    messages = [];
    saveMessages();
    return sendJSON(res, 200, { ok: true, cleared: true });
  }

  // GET /api/agents  (every known participant)
  if (pathname === '/api/agents' && req.method === 'GET') {
    const names = new Set();
    for (const t of tasks) {
      if (t.assignee) names.add(t.assignee);
      if (t.createdBy) names.add(t.createdBy);
      if (t.updatedBy) names.add(t.updatedBy);
    }
    for (const m of messages) {
      if (m.author) names.add(m.author);
      if (m.to) names.add(m.to);
    }
    names.delete('unknown');
    return sendJSON(res, 200, [...names].sort());
  }

  // GET /api/statuses
  if (pathname === '/api/statuses' && req.method === 'GET') {
    const counts = {};
    for (const s of VALID_STATUSES) counts[s] = tasks.filter((t) => t.status === s).length;
    return sendJSON(res, 200, { statuses: VALID_STATUSES, counts });
  }

  // GET /api/health
  if (pathname === '/api/health' && req.method === 'GET') {
    return sendJSON(res, 200, { ok: true, service: 'taskboard', tasks: tasks.length });
  }

  // Static frontend
  if (req.method === 'GET' || req.method === 'HEAD') {
    return serveStatic(req, res, pathname);
  }

  return sendJSON(res, 404, { error: 'not found' });
});

function serveStatic(req, res, pathname) {
  const rel = pathname === '/' ? '/index.html' : pathname;
  const file = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!file.startsWith(PUBLIC_DIR)) return sendJSON(res, 403, { error: 'forbidden' });
  fs.readFile(file, (err, data) => {
    if (err) return sendJSON(res, 404, { error: 'not found' });
    res.writeHead(200, {
      'Content-Type': contentType(file),
      'Content-Length': data.length,
    });
    if (req.method === 'HEAD') return res.end();
    res.end(data);
  });
}

// ---------- boot ----------

function boot() {
  load();
  loadMessages();
  server.listen(PORT, HOST, () => {
    console.log(`\n  TaskBoard running  ->  http://${HOST}:${PORT}`);
    console.log(`  Task data file     ->  ${DATA_FILE}`);
    console.log(`  Chat data file    ->  ${CHAT_FILE}`);
    console.log(`  API base          ->  http://${HOST}:${PORT}/api`);
    console.log(`  valid statuses    ->  ${VALID_STATUSES.join(', ')}\n`);
    console.log('  Quick task create:');
    console.log(`    curl -X POST http://${HOST}:${PORT}/api/tasks -H 'Content-Type: application/json' \\`);
    console.log(`         -H 'X-Agent: backend-agent' -d '{"title":"Add auth","assignee":"backend-agent"}'`);
    console.log('\n  Ctrl+C to stop.\n');
  });
}

process.on('SIGINT', () => {
  save();
  console.log('\n[taskboard] saved and shutting down.');
  process.exit(0);
});

boot();