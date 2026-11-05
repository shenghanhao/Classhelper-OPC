import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

// Execute the workflow's exact inline program with a fake event and fake REST API.
// This file never calls the network or deletes a real release attachment.
const workflow = readFileSync(new URL('../.github/workflows/cleanup-temporary-opc-data.yml', import.meta.url), 'utf8');
const program = workflow.split('// BEGIN_TEMPORARY_OPC_CLEANUP\n')[1]
  .split('// END_TEMPORARY_OPC_CLEANUP')[0]
  .split('\n').map(line => line.replace(/^ {10}/, '')).join('\n');
const repository = 'shenghanhao/Classhelper-OPC';
const assetName = 'TEMPORARY-OPC-TEST-DATA-v0.4.0.zip';
const target = { id: 40, tag_name: 'v0.4.0', draft: false, prerelease: false, published_at: '2026-11-05T10:00:00Z' };
const later = { id: 41, tag_name: 'v0.4.1', draft: false, prerelease: false, published_at: '2026-11-06T10:00:00Z' };
const asset = { id: 99, name: assetName };
const otherAssets = [
  { id: 100, name: 'ClassMemory.apk' },
  { id: 101, name: 'SHA256SUMS' },
  { id: 102, name: 'source.zip' },
  { id: 103, name: assetName + '.sha256' },
  { id: 104, name: 'TEMPORARY-OPC-TEST-DATA-v0.4.1.zip' },
];

async function simulate({ eventName = 'release', event = {}, environment = {}, responses = {} } = {}) {
  const calls = [];
  const logs = [];
  const payload = {
    action: 'published', repository: { full_name: repository }, release: later, ...event,
  };
  const routes = {
    'GET /releases/tags/v0.4.0': target,
    'GET /releases/40': target,
    'GET /releases/41': later,
    'GET /releases?per_page=100&page=1': [later, target],
    'GET /releases/40/assets?per_page=100&page=1': [...otherAssets, asset],
    'GET /releases/assets/99': asset,
    'DELETE /releases/assets/99': { status: 204 },
    ...responses,
  };
  const process = {
    exitCode: 0,
    env: {
      GITHUB_TOKEN: 'fixture-token-only', GITHUB_EVENT_PATH: '/fixture/event.json',
      GITHUB_REPOSITORY: repository, GITHUB_EVENT_NAME: eventName, ...environment,
    },
  };
  await vm.runInNewContext(program, {
    process,
    require(name) {
      assert.equal(name, 'node:fs');
      return { readFileSync(path) {
        assert.equal(path, '/fixture/event.json');
        return JSON.stringify(payload);
      } };
    },
    AbortSignal: { timeout: milliseconds => ({ milliseconds }) },
    console: { log: value => logs.push(value), error: value => logs.push(value) },
    async fetch(url, options) {
      assert.equal(options.headers.Authorization, 'Bearer fixture-token-only');
      assert.equal(options.redirect, 'error');
      const prefix = 'https://api.github.com/repos/' + repository;
      assert.ok(url.startsWith(prefix + '/'));
      const route = options.method + ' ' + url.slice(prefix.length);
      calls.push(route);
      assert.ok(Object.hasOwn(routes, route), 'Unexpected API call: ' + route);
      const body = routes[route];
      const status = body?.status ?? 200;
      return { status, ok: status >= 200 && status < 300, json: async () => structuredClone(body) };
    },
  });
  return { calls, logs, exitCode: process.exitCode, deletions: calls.filter(call => call.startsWith('DELETE ')) };
}

test('later stable release deletes exactly the allowlisted ZIP by server asset ID', async () => {
  const result = await simulate();
  assert.deepEqual(result.deletions, ['DELETE /releases/assets/99']);
  assert.equal(result.exitCode, 0);
});

for (const [name, replacement] of [
  ['same release', target],
  ['same tag on another ID', { ...later, tag_name: 'v0.4.0' }],
  ['draft', { ...later, draft: true }],
  ['prerelease', { ...later, prerelease: true }],
  ['older publication', { ...later, published_at: '2026-11-04T10:00:00Z' }],
  ['equal publication', { ...later, published_at: target.published_at }],
  ['invalid publication', { ...later, published_at: null }],
  ['missing tag', { ...later, tag_name: undefined }],
]) {
  test(name + ' cannot authorize cleanup', async () => {
    const result = await simulate({ responses: { 'GET /releases/41': replacement } });
    assert.deepEqual(result.deletions, []);
    assert.equal(result.exitCode, 0);
  });
}

test('fork repository and payload mismatch stop before any API call', async () => {
  for (const override of [
    { environment: { GITHUB_REPOSITORY: 'example/fork' } },
    { event: { repository: { full_name: 'example/fork' } } },
  ]) {
    const result = await simulate(override);
    assert.deepEqual(result.calls, []);
  }
});

test('unsupported event or non-published action stops before API', async () => {
  for (const override of [{ eventName: 'push' }, { event: { action: 'edited' } }]) {
    assert.deepEqual((await simulate(override)).calls, []);
  }
});

test('manual dispatch re-reads a later stable release before cleanup', async () => {
  const result = await simulate({ eventName: 'workflow_dispatch' });
  assert.ok(result.calls.includes('GET /releases/41'));
  assert.deepEqual(result.deletions, ['DELETE /releases/assets/99']);
});

test('manual dispatch without a later stable release keeps the ZIP', async () => {
  const result = await simulate({ eventName: 'workflow_dispatch', responses: {
    'GET /releases?per_page=100&page=1': [target, { ...later, prerelease: true }],
  } });
  assert.deepEqual(result.deletions, []);
});

test('absence of exact filename does not delete lookalikes, APK, or checksums', async () => {
  const result = await simulate({ responses: { 'GET /releases/40/assets?per_page=100&page=1': otherAssets } });
  assert.deepEqual(result.deletions, []);
});

test('renamed, mismatched, or removed asset is not deleted', async () => {
  for (const replacement of [{ ...asset, name: 'ClassMemory.apk' }, { ...asset, id: 100 }, { status: 404 }]) {
    const result = await simulate({ responses: { 'GET /releases/assets/99': replacement } });
    assert.deepEqual(result.deletions, []);
  }
});

test('missing target release and idempotent DELETE 404 are safe', async () => {
  const missing = await simulate({ responses: { 'GET /releases/tags/v0.4.0': { status: 404 } } });
  assert.deepEqual(missing.deletions, []);
  const alreadyRemoved = await simulate({ responses: { 'DELETE /releases/assets/99': { status: 404 } } });
  assert.equal(alreadyRemoved.exitCode, 0);
});

test('API failure aborts before deleting anything', async () => {
  const result = await simulate({ responses: { 'GET /releases/41': { status: 403 } } });
  assert.deepEqual(result.deletions, []);
  assert.equal(result.exitCode, 1);
});

test('asset pagination never expands the filename allowlist', async () => {
  const result = await simulate({ responses: {
    'GET /releases/40/assets?per_page=100&page=1': Array.from({ length: 100 }, (_, index) => ({ id: index + 200, name: `keep-${index}.zip` })),
    'GET /releases/40/assets?per_page=100&page=2': [asset],
  } });
  assert.deepEqual(result.deletions, ['DELETE /releases/assets/99']);
});
