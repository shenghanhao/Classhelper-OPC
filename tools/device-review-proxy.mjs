/**
 * Owner-authorized, USB-only real-classroom review test harness.
 * Run: node tools/device-review-proxy.mjs
 * Prerequisites under ~/.config/classmemory-opc/device-review-20261105:
 *   server-key.pem, server-cert.pem, selected-sessions.json
 * selected-sessions.json: exactly three objects {sessionId, courseName}.
 * Reads ../provider.env privately; never put provider credentials in an APK.
 * Uses the existing ../synthetic-test-budget.json and ../synthetic-test.lock.
 * Binds TLS to 127.0.0.1:8788 only; owner separately sets up adb reverse.
 * No microphone access, APK installation, database writes, or public deployment.
 */
import { createStudyServer, loadConfig } from '../class-memory/study-agent-server/server.mjs';
import { ReviewError } from '../class-memory/study-agent-server/review-v2.mjs';
import { createServer as createHttpsServer } from 'node:https';
import { AsyncLocalStorage } from 'node:async_hooks';
import { X509Certificate, randomUUID, randomBytes } from 'node:crypto';
import { readFile, open, lstat, rename, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { loadEnvFile } from 'node:process';
import { Readable } from 'node:stream';

const privateRoot = join(homedir(), '.config', 'classmemory-opc');
const directory = join(privateRoot, 'device-review-20261105');
const ledgerFile = join(privateRoot, 'synthetic-test-budget.json');
const lockPath = join(privateRoot, 'synthetic-test.lock');
const eventsFile = join(directory, 'stage-events.jsonl');
const accessTokenFile = join(directory, 'proxy-access-token.txt');
const maxBudgetCny = 10;
// Conservative peak prices verified by owner workflow on 2026-11-04.
const inputCnyPerMillion = 2;
const outputCnyPerMillion = 8;
const providerMaxOutputTokens = 384 * 1024;
const requestContext = new AsyncLocalStorage();
let exclusiveLock;
let ledger;
let server;
let fatal = false;
let stopping = false;
let fetchQueue = Promise.resolve();
let eventQueue = Promise.resolve();
let config;

function safeError(code, status = 500) {
  const error = new Error(code);
  error.safeCode = code;
  error.status = status;
  return error;
}

async function requirePrivate(path, kind = 'file') {
  const stat = await lstat(path);
  if (stat.isSymbolicLink() || (kind === 'file' ? !stat.isFile() : !stat.isDirectory())) throw safeError('PRIVATE_FILE_TYPE_INVALID');
  if ((stat.mode & 0o077) !== 0 || (process.getuid && stat.uid !== process.getuid())) throw safeError('PRIVATE_FILE_PERMISSIONS_INVALID');
  return stat;
}

async function writeLedger() {
  const temporary = join(privateRoot, `device-budget-${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(JSON.stringify(ledger, null, 2));
    await handle.sync();
    await handle.close(); handle = null;
    await rename(temporary, ledgerFile);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await unlink(temporary).catch((e) => { if (e.code !== 'ENOENT') throw safeError('LEDGER_TEMP_CLEANUP_FAILED'); });
  }
}

/** Only stage, status, counts and aggregate cost are logged, even for failures. */
function record(stage, status, count = {}) {
  const operation = eventQueue.then(async () => {
    const event = { stage, status, count, costCny: ledger?.reservedOrEstimatedCny ?? null };
    const handle = await open(eventsFile, 'a', 0o600);
    try { await handle.writeFile(JSON.stringify(event) + '\n'); await handle.sync(); }
    finally { await handle.close(); }
    process.stdout.write(JSON.stringify(event) + '\n');
  });
  eventQueue = operation.catch(() => {});
  return operation;
}

function validUsage(usage) {
  return Number.isSafeInteger(usage?.prompt_tokens) && usage.prompt_tokens >= 0 &&
    Number.isSafeInteger(usage?.completion_tokens) && usage.completion_tokens >= 0;
}

/** Process-wide queue plus the old smoke test's lifetime lock prevent ledger races. */
function budgetedFetch(url, init) {
  const context = requestContext.getStore() || { stage: 'unknown', counts: {} };
  const operation = fetchQueue.then(() => performBudgetedFetch(url, init, context));
  fetchQueue = operation.catch(() => {});
  return operation;
}

async function performBudgetedFetch(url, init, context) {
  if (fatal || stopping) throw safeError('DEVICE_REVIEW_STOPPED');
  if (String(url) !== 'https://api.deepseek.com/chat/completions' || config.model !== 'deepseek-flash') throw safeError('UNPRICED_PROVIDER_REJECTED');
  if (init.signal?.aborted) throw safeError('REQUEST_CANCELLED');
  const request = JSON.parse(init.body);
  if (request.model !== 'deepseek-flash' || Object.hasOwn(request, 'max_tokens') || Object.hasOwn(request, 'max_completion_tokens')) throw safeError('UNEXPECTED_PROVIDER_REQUEST');
  // UTF-8 bytes upper-bound ordinary text tokens; reserve framing overhead too.
  const inputUpper = Buffer.byteLength(init.body || '', 'utf8') + 4096;
  const reservation = (inputUpper * inputCnyPerMillion + providerMaxOutputTokens * outputCnyPerMillion) / 1e6;
  if (ledger.reservedOrEstimatedCny + reservation > maxBudgetCny) {
    await record(context.stage, 'budget-refused-no-provider-call', { ...context.counts, providerCalls: 0 });
    throw new ReviewError(429, 'TEN_YUAN_BUDGET_EXHAUSTED', 'The shared 10 CNY test budget cannot reserve another full provider response; no request was sent');
  }
  const entry = { at: new Date().toISOString(), stage: context.stage, test: 'device-review-20261105',
    reservedCny: reservation, costUpperCny: reservation, state: 'reserved' };
  ledger.reservedOrEstimatedCny += reservation;
  ledger.calls.push(entry);
  try {
    // Reservation must reach disk before the chargeable request can leave this process.
    await writeLedger();
    await record(context.stage, 'provider-call-reserved', { ...context.counts, providerCalls: 1 });
  } catch {
    fatal = true;
    throw safeError('BUDGET_PERSISTENCE_FAILED_NO_PROVIDER_CALL');
  }
  let response;
  try {
    response = await globalThis.fetch(url, { ...init, redirect: 'error' });
    // Read a clone to meter all billed responses, including truncated/invalid outputs.
    const body = await response.clone().json();
    if (validUsage(body?.usage)) {
      const cost = (body.usage.prompt_tokens * inputCnyPerMillion + body.usage.completion_tokens * outputCnyPerMillion) / 1e6;
      entry.costUpperCny = cost;
      entry.state = 'metered';
      entry.promptTokens = body.usage.prompt_tokens;
      entry.completionTokens = body.usage.completion_tokens;
      ledger.reservedOrEstimatedCny += cost - reservation;
      if (cost > reservation || ledger.reservedOrEstimatedCny > maxBudgetCny) fatal = true;
    } else entry.state = 'uncertain-cost-reservation-retained';
    await writeLedger();
    await record(context.stage, response.ok ? entry.state : 'provider-error-cost-recorded', {
      ...context.counts, providerCalls: 1, promptTokens: entry.promptTokens ?? 0, completionTokens: entry.completionTokens ?? 0,
    });
    return response;
  } catch {
    // No refunds are assumed on network, body parsing, usage or disk uncertainty.
    if (entry.state === 'reserved') entry.state = 'failed-cost-reservation-retained';
    try { await writeLedger(); await record(context.stage, entry.state, { ...context.counts, providerCalls: 1 }); }
    catch { fatal = true; }
    throw safeError('PROVIDER_CALL_FAILED_COST_RETAINED', 502);
  }
}

function reply(res, status, code) {
  if (res.headersSent || res.destroyed) return;
  const text = JSON.stringify({ error: { code, message: code } });
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'Content-Length': Buffer.byteLength(text) });
  res.end(text);
}

async function start() {
  await requirePrivate(privateRoot, 'directory');
  await requirePrivate(directory, 'directory');
  for (const name of ['server-key.pem', 'server-cert.pem', 'selected-sessions.json']) await requirePrivate(join(directory, name));
  await requirePrivate(join(privateRoot, 'provider.env'));
  await requirePrivate(ledgerFile);
  try { await requirePrivate(eventsFile); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  loadEnvFile(join(privateRoot, 'provider.env'));
  config = { ...loadConfig(), host: '127.0.0.1', port: 8788 };
  try { await requirePrivate(accessTokenFile); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const tokenHandle = await open(accessTokenFile, 'wx', 0o600);
    try { await tokenHandle.writeFile(randomBytes(32).toString('hex') + '\n'); await tokenHandle.sync(); }
    finally { await tokenHandle.close(); }
  }
  config.accessToken = (await readFile(accessTokenFile, 'utf8')).trim();
  if (!/^[0-9a-f]{64}$/.test(config.accessToken)) throw safeError('PRIVATE_PROXY_TOKEN_INVALID');
  if (config.providerUrl !== 'https://api.deepseek.com/chat/completions' || config.model !== 'deepseek-flash') throw safeError('UNPRICED_PROVIDER_REJECTED');
  if (!config.apiKey || config.apiKey.length <= 10) throw safeError('PROVIDER_KEY_MISSING');
  if (!Number.isSafeInteger(config.providerTimeoutMs) || config.providerTimeoutMs <= 0) throw safeError('INVALID_PROVIDER_TIMEOUT');
  const selected = JSON.parse(await readFile(join(directory, 'selected-sessions.json'), 'utf8'));
  if (!Array.isArray(selected) || selected.length !== 3 || selected.some((s) => !s || typeof s.sessionId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(s.sessionId))) throw safeError('THREE_SESSION_ALLOWLIST_REQUIRED');
  const allowed = new Set(selected.map((s) => s.sessionId));
  if (allowed.size !== 3) throw safeError('THREE_UNIQUE_SESSIONS_REQUIRED');
  const certificate = await readFile(join(directory, 'server-cert.pem'));
  const x509 = new X509Certificate(certificate);
  if (!x509.checkIP('127.0.0.1') || Date.parse(x509.validTo) <= Date.now()) throw safeError('LOCAL_TLS_CERTIFICATE_INVALID');
  const key = await readFile(join(directory, 'server-key.pem'));
  // Same lock as review-real-smoke.mjs: no competing process may reset or overwrite spending.
  exclusiveLock = await open(lockPath, 'wx', 0o600);
  await exclusiveLock.writeFile(JSON.stringify({ pid: process.pid, purpose: 'device-review' }));
  await exclusiveLock.sync();
  ledger = JSON.parse(await readFile(ledgerFile, 'utf8'));
  if (!Number.isFinite(ledger.reservedOrEstimatedCny) || ledger.reservedOrEstimatedCny < 0 || !Array.isArray(ledger.calls) ||
      ledger.calls.some((c) => !Number.isFinite(c.costUpperCny) || c.costUpperCny < 0)) throw safeError('EXISTING_BUDGET_LEDGER_INVALID');
  const recordedTotal = ledger.calls.reduce((sum, c) => sum + c.costUpperCny, 0);
  if (Math.abs(recordedTotal - ledger.reservedOrEstimatedCny) > 0.000001) throw safeError('EXISTING_BUDGET_TOTAL_MISMATCH');
  if (ledger.reservedOrEstimatedCny >= maxBudgetCny) throw safeError('TEN_YUAN_BUDGET_EXHAUSTED', 429);

  const core = createStudyServer(config, { fetchImpl: budgetedFetch, log: { log() {}, error() {} } });
  const listener = core.listeners('request')[0];
  server = createHttpsServer({ key, cert: certificate, minVersion: 'TLSv1.2' }, async (req, res) => {
    let stage = 'unknown';
    let counts = {};
    try {
      if (req.method === 'GET' && req.url === '/healthz') {
        const health = JSON.stringify({ ok: !fatal && !stopping, providerConfigured: true, allowedSessions: allowed.size, costUpperCny: ledger.reservedOrEstimatedCny, budgetCny: maxBudgetCny });
        res.writeHead(fatal ? 503 : 200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(health); return;
      }
      if (fatal || stopping) throw safeError('DEVICE_REVIEW_STOPPED', 503);
      if (req.method !== 'POST' || req.url !== '/v2/study/review') throw safeError('DEVICE_REVIEW_ROUTE_NOT_ALLOWED', 404);
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = Buffer.concat(chunks);
      let payload;
      try { payload = JSON.parse(body.toString('utf8')); } catch { throw safeError('INVALID_JSON', 400); }
      if (!['outline', 'unit', 'plan'].includes(payload?.stage)) throw safeError('INVALID_STAGE', 400);
      stage = payload.stage;
      const input = payload?.input;
      if (!input || !allowed.has(input.sessionId)) throw safeError('SESSION_NOT_AUTHORIZED', 403);
      if (!Array.isArray(input.sources) || !Array.isArray(input.photos) || !Array.isArray(input.notes)) throw safeError('INVALID_INPUT_ARRAYS', 400);
      if (input.sources.some((s) => !s || !allowed.has(s.sessionId)) || input.photos.some((p) => !p || !allowed.has(p.sessionId))) throw safeError('HISTORY_SESSION_NOT_AUTHORIZED', 403);
      counts = { sources: input.sources.length, photos: input.photos.length, notes: input.notes.length };
      await record(stage, 'accepted', counts);
      // Replay the already allowlisted body through the existing production request handler.
      const replay = Readable.from([body]);
      Object.assign(replay, { method: req.method, url: req.url, headers: req.headers, socket: req.socket });
      await requestContext.run({ stage, counts }, () => listener(replay, res));
      await record(stage, `http-${res.statusCode}`, counts);
    } catch (error) {
      const code = error.safeCode || 'DEVICE_REVIEW_REQUEST_FAILED';
      try { await record(stage, code, counts); } catch { fatal = true; }
      reply(res, error.status || 500, code);
    }
  });
  server.requestTimeout = 0; // Whole-class stages may contain several provider calls.
  server.headersTimeout = 30_000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(8788, '127.0.0.1', resolve); });
  await record('proxy', 'ready-local-tls', { allowedSessions: allowed.size, historicalProviderCalls: ledger.calls.length });
}

async function stop() {
  if (stopping) return;
  stopping = true;
  if (server) {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
  await fetchQueue;
  await eventQueue;
  if (exclusiveLock) { await exclusiveLock.close(); exclusiveLock = null; await unlink(lockPath); }
}

process.once('SIGINT', () => stop().then(() => process.exit(0)).catch(() => process.exit(1)));
process.once('SIGTERM', () => stop().then(() => process.exit(0)).catch(() => process.exit(1)));
try { await start(); }
catch (error) {
  // A TLS/configuration error may carry key or path details; output only a controlled code.
  process.stderr.write(JSON.stringify({ stage: 'proxy', status: error.safeCode || (error.code === 'EEXIST' ? 'BUDGET_LOCK_ALREADY_HELD' : 'STARTUP_FAILED'), count: {}, costCny: ledger?.reservedOrEstimatedCny ?? null }) + '\n');
  await stop().catch(() => {});
  process.exitCode = 1;
}
