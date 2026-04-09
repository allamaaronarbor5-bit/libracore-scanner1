/**
 * LibraCore Scanner WebSocket Server
 * Manages session codes and relays QR scan data from Android app to PC browser.
 *
 * Usage:
 *   npm install ws
 *   node server.js
 *
 * Default port: 8765
 */

const WebSocket = require('ws');
const http      = require('http');

const PORT = process.env.PORT || 8765;

// sessions: Map<code, { pc: WebSocket|null, phone: WebSocket|null }>
const sessions = new Map();

// ── helpers ──────────────────────────────────────────────────────────────────
function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function log(...args) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}

// ── HTTP server (health-check + CORS preflight) ───────────────────────────────
const httpServer = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, sessions: sessions.size }));
    return;
  }
  res.writeHead(404); res.end('LibraCore Scanner Server');
});

// ── WebSocket server ──────────────────────────────────────────────────────────
const wss = new WebSocket.Server({ server: httpServer });

wss.on('connection', (ws, req) => {
  const ip = req.socket.remoteAddress;
  log(`[CONNECT] ${ip}`);

  ws._role = null;   // 'pc' | 'phone'
  ws._code = null;

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    // ── PC registers a session code ──────────────────────────────────────────
    if (msg.type === 'register_pc') {
      const code = msg.code;
      if (!code) return;

      // Clean up any previous entry on this socket
      if (ws._code) sessions.delete(ws._code);

      if (!sessions.has(code)) {
        sessions.set(code, { pc: ws, phone: null });
      } else {
        sessions.get(code).pc = ws;
      }
      ws._role = 'pc';
      ws._code = code;

      log(`[PC] registered code=${code}`);
      send(ws, { type: 'registered', code });
      return;
    }

    // ── Phone connects with a session code ───────────────────────────────────
    if (msg.type === 'connect_phone') {
      const code = msg.code;
      if (!code) { send(ws, { type: 'error', reason: 'no_code' }); return; }

      const session = sessions.get(code);
      if (!session) { send(ws, { type: 'error', reason: 'invalid_code' }); return; }
      if (session.phone && session.phone.readyState === WebSocket.OPEN) {
        send(ws, { type: 'error', reason: 'already_connected' }); return;
      }

      session.phone = ws;
      ws._role = 'phone';
      ws._code = code;

      log(`[PHONE] connected code=${code}`);
      send(ws, { type: 'connected', code });
      send(session.pc, { type: 'phone_connected' });
      return;
    }

    // ── Phone sends scanned QR data ──────────────────────────────────────────
    if (msg.type === 'scan') {
      if (ws._role !== 'phone') return;
      const session = sessions.get(ws._code);
      if (!session) return;

      log(`[SCAN] code=${ws._code} data=${msg.data}`);
      send(session.pc, { type: 'scan', data: msg.data });
      send(ws, { type: 'scan_ack' });
      return;
    }

    // ── Ping/pong ────────────────────────────────────────────────────────────
    if (msg.type === 'ping') { send(ws, { type: 'pong' }); return; }
  });

  ws.on('close', () => {
    if (!ws._code) return;
    const session = sessions.get(ws._code);
    if (!session) return;

    if (ws._role === 'pc') {
      log(`[PC] disconnected code=${ws._code}`);
      send(session.phone, { type: 'pc_disconnected' });
      sessions.delete(ws._code);
    } else if (ws._role === 'phone') {
      log(`[PHONE] disconnected code=${ws._code}`);
      session.phone = null;
      send(session.pc, { type: 'phone_disconnected' });
    }
  });

  ws.on('error', (err) => log('[ERROR]', err.message));
});

httpServer.listen(PORT, () => {
  log(`LibraCore Scanner Server running on port ${PORT}`);
  log(`WebSocket: ws://localhost:${PORT}`);
  log(`Health:    http://localhost:${PORT}/health`);
});
