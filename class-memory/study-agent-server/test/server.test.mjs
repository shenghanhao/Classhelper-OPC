import test from "node:test";
import assert from "node:assert/strict";
import { createStudyServer, loadConfig } from "../server.mjs";

const HASH = "a".repeat(64);

function requestBody(overrides = {}) {
  return {
    schemaVersion: 1,
    requestId: "req-1",
    sourceHash: HASH,
    skillVersion: "opc-summary-v1",
    language: "zh",
    title: "数据结构",
    courseType: "计算机",
    segments: [
      { id: "seg-1", startMs: 1000, endMs: 4000, text: "栈是后进先出的线性表" },
      { id: "seg-2", startMs: 5000, endMs: 9000, text: "队列是先进先出的线性表" },
    ],
    notes: [{ id: "note-1", atMs: 6000, kind: "QUESTION", text: "栈和队列的区别是什么？" }],
    ...overrides,
  };
}

function analysis(overrides = {}) {
  return {
    schemaVersion: 1,
    sourceHash: HASH,
    skillVersion: "opc-summary-v1",
    summary: "本节课比较了栈与队列。",
    knowledgePoints: [
      {
        id: "kp-1",
        title: "栈与队列的顺序",
        explanation: "栈后进先出，队列先进先出。",
        evidence: [{ segmentId: "seg-1", quote: "后进先出" }],
      },
    ],
    ...overrides,
  };
}

function baseConfig(overrides = {}) {
  return {
    ...loadConfig({}),
    providerUrl: "https://provider.test/chat/completions",
    apiKey: "test-key",
    model: "test-model",
    ratePerHour: 100,
    dailyLimit: 100,
    ...overrides,
  };
}

async function withServer(config, fetchImpl, run) {
  const server = createStudyServer(config, {
    fetchImpl,
    log: { log() {}, error() {} },
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    await run(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

function providerResponse(content, status = 200) {
  return new Response(
    JSON.stringify({
      choices: [{ message: { content } }],
      usage: { prompt_tokens: 100, completion_tokens: 50 },
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
}

async function post(base, body, headers = {}) {
  return fetch(`${base}/v1/study/analyze`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

test("returns a source-bound, evidence-checked analysis", async () => {
  let providerPayload;
  await withServer(baseConfig(), async (_url, init) => {
    providerPayload = JSON.parse(init.body);
    return providerResponse(JSON.stringify(analysis()));
  }, async (base) => {
    const response = await post(base, requestBody());
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.sourceHash, HASH);
    assert.equal(body.summary, "本节课比较了栈与队列。");
    assert.equal(body.knowledgePoints[0].evidence[0].segmentId, "seg-1");
    assert.equal(providerPayload.model, "test-model");
    assert.equal(Object.hasOwn(providerPayload, "max_tokens"), false);
    assert.match(providerPayload.messages[0].content, /逐字/);
    assert.match(providerPayload.messages[1].content, /栈是后进先出/);
  });
});

test("preserves large input and output without character, byte or item caps", async () => {
  const quote = "完整课堂原文".repeat(1000);
  const request = requestBody({
    title: "课程".repeat(150),
    courseType: "名称".repeat(100),
    segments: Array.from({ length: 401 }, (_, i) => ({ id: `seg-${i}`, startMs: i, endMs: i + 1, text: quote })),
    notes: Array.from({ length: 101 }, (_, i) => ({ id: `note-${i}`, atMs: i, kind: "COMMENT", text: "注释".repeat(1100) })),
  });
  const result = analysis({
    summary: "总结".repeat(150_000),
    knowledgePoints: Array.from({ length: 13 }, (_, i) => ({
      id: `kp-${i}`, title: "标题".repeat(100), explanation: "解释".repeat(1000),
      evidence: Array.from({ length: 6 }, (_, j) => ({ segmentId: `seg-${j}`, quote })),
    })),
  });
  await withServer(baseConfig(), async (_url, init) => {
    const payload = JSON.parse(init.body);
    const passed = JSON.parse(payload.messages[1].content);
    assert.deepEqual(passed.segments, request.segments);
    assert.deepEqual(passed.notes, request.notes);
    assert.equal(Object.hasOwn(payload, "max_tokens"), false);
    return providerResponse(JSON.stringify(result));
  }, async (base) => {
    const response = await post(base, request);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.summary, result.summary);
    assert.deepEqual(body.knowledgePoints, result.knowledgePoints);
  });
});

test("rejects provider-truncated output even if the partial content is valid JSON", async () => {
  await withServer(baseConfig(), async () => new Response(JSON.stringify({
    choices: [{ finish_reason: "length", message: { content: JSON.stringify(analysis()) } }],
  }), { status: 200 }), async (base) => {
    const response = await post(base, requestBody());
    assert.equal(response.status, 502);
    assert.equal((await response.json()).error.code, "PROVIDER_INCOMPLETE");
  });
});

test("rejects evidence that is not in the selected transcript", async () => {
  await withServer(baseConfig(), async () =>
    providerResponse(JSON.stringify(analysis({
      knowledgePoints: [{
        id: "kp-1",
        title: "伪造",
        explanation: "来源不存在。",
        evidence: [{ segmentId: "seg-1", quote: "这是编造的证据" }],
      }],
    }))), async (base) => {
    const response = await post(base, requestBody());
    assert.equal(response.status, 502);
    const body = await response.json();
    assert.equal(body.error.code, "INVALID_ANALYSIS");
  });
});

test("rejects malformed requests before calling the provider", async () => {
  let called = 0;
  await withServer(baseConfig(), async () => {
    called += 1;
    return providerResponse(JSON.stringify(analysis()));
  }, async (base) => {
    const badHash = await post(base, requestBody({ sourceHash: "not-a-hash" }));
    assert.equal(badHash.status, 400);
    assert.equal((await badHash.json()).error.code, "INVALID_REQUEST");
    assert.equal(called, 0);
  });
});

test("maps provider rate limits and enforces the shared access token", async () => {
  await withServer(baseConfig({ accessToken: "secret" }), async () =>
    new Response(JSON.stringify({ error: { message: "quota" } }), { status: 429 }), async (base) => {
    const unauthorized = await post(base, requestBody());
    assert.equal(unauthorized.status, 401);

    const limited = await post(base, requestBody(), { Authorization: "Bearer secret" });
    assert.equal(limited.status, 429);
    assert.equal((await limited.json()).error.code, "RATE_LIMITED");
  });
});

test("enforces the per-hour quota", async () => {
  await withServer(baseConfig({ ratePerHour: 1 }), async () =>
    providerResponse(JSON.stringify(analysis())), async (base) => {
    assert.equal((await post(base, requestBody())).status, 200);
    const blocked = await post(base, requestBody({ requestId: "req-2" }));
    assert.equal(blocked.status, 429);
    assert.equal((await blocked.json()).error.code, "RATE_LIMITED");
  });
});
