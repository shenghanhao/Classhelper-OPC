import test from "node:test";
import assert from "node:assert/strict";
import { createStudyServer, loadConfig } from "../server.mjs";
import { validateReviewInput, validateOutline, validateProviderOutline, validateUnit, validatePlan, sourceBatches } from "../review-v2.mjs";

const fixture = () => ({
  schemaVersion: 2, sessionId: "class-2", sourceHash: "a".repeat(64), language: "zh", title: "栈的应用", today: "2026-11-04",
  course: { id: "course-1", revision: 2, name: "数据结构", background: "计算机课程", goals: "理解栈", chapters: "线性结构", terminology: "LIFO：后进先出", prerequisites: "线性表", sources: "自编测试材料" },
  sources: [
    { id: "s1", sessionId: "class-2", segmentId: "seg-1", startMs: 0, endMs: 10000, text: "回顾栈：最后放入的数据最先取出。", isCurrent: true },
    { id: "s2", sessionId: "class-2", segmentId: "seg-2", startMs: 10000, endMs: 20000, text: "括号匹配可以用栈，遇左括号入栈。", isCurrent: true },
    { id: "s3", sessionId: "class-2", segmentId: "seg-3", startMs: 20000, endMs: 30000, text: "再回顾栈的 LIFO 顺序，递归调用也使用栈。", isCurrent: true },
    { id: "h1", sessionId: "class-1", segmentId: "seg-history", startMs: 0, endMs: 9000, text: "上一节课约定 LIFO 表示后进先出。", isCurrent: false },
  ],
  photos: [{ id: "photo-1", sessionId: "class-2", atMs: 15000 }],
  notes: [{ id: "n1", atMs: 13000, kind: "QUESTION", text: "为什么用栈？" }],
});

const outlineFixture = () => ({ overview: "整课回顾栈并应用于括号和递归。", sections: [
  { id: "section-a", title: "栈的回顾", sourceIds: ["s1"] },
  { id: "section-b", title: "栈的应用", sourceIds: ["s2", "s3"] },
], topics: [
  { id: "topic-a", title: "后进先出与应用", sectionIds: ["section-a", "section-b"], sourceIds: ["s1", "s2", "s3"] },
] });
const rangeOutlineFixture = () => ({ overview: "整课回顾栈并应用于括号和递归。", sections: [
  { id: "section-a", title: "栈的回顾", startIndex: 0, endIndex: 0 },
  { id: "section-b", title: "栈的应用", startIndex: 1, endIndex: 2 },
], topics: [{ id: "topic-a", title: "后进先出与应用", sectionIds: ["section-a", "section-b"] }] });
const unitFixture = () => ({ id: "topic-a", title: "后进先出", explanation: "最后放入的数据先取出。", conditions: "需要逆序处理时", classroomFocus: "理解栈的顺序及应用", example: "括号匹配：左括号入栈。", sourceIds: ["s1", "s2", "h1"], photoIds: ["photo-1"], sectionIds: ["section-a", "section-b"] });
const planFixture = () => ({ title: "整课复习方案", introduction: "先回忆，再追踪栈的过程。", suggestedDate: "2026-11-05", steps: [{ title: "回顾并应用", instruction: "复述 LIFO，再手动模拟括号匹配。", unitIds: ["topic-a"] }] });
const providerResponse = (result, overrides = {}) => new Response(JSON.stringify({ choices: [{ finish_reason: "stop", message: { content: JSON.stringify(result) }, ...overrides }], usage: { prompt_tokens: 100, completion_tokens: 50 } }), { status: 200 });

async function withServer(fetchImpl, body, overrides = {}, headers = {}) {
  const config = { ...loadConfig({}), providerUrl: "https://provider.test/chat/completions", apiKey: "synthetic-key", ratePerHour: 100, dailyLimit: 100, ...overrides };
  const logs = [];
  const server = createStudyServer(config, { fetchImpl, log: { log(s) { logs.push(s); }, error(s) { logs.push(s); } } });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/v2/study/review`, { method: "POST", headers: { "Content-Type": "application/json", ...headers }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json(), logs };
  } finally { await new Promise((resolve) => server.close(resolve)); }
}

test("V2 outline reads every current source, retains stable source IDs and does not expose raw text in logs", async () => {
  const input = fixture();
  const response = await withServer(async (_url, init) => {
    const request = JSON.parse(init.body);
    assert.equal(request.model, "deepseek-flash");
    assert.equal(Object.hasOwn(request, "max_tokens"), false);
    const prompt = JSON.parse(request.messages[1].content);
    assert.deepEqual(prompt.currentSources, input.sources.filter((s) => s.isCurrent).map((s, index) => ({ ...s, index })));
    assert.deepEqual(prompt.sourceIndexRange, { startIndex: 0, endIndex: 2 });
    assert.equal(prompt.course.terminology, "LIFO：后进先出");
    return providerResponse(rangeOutlineFixture());
  }, { stage: "outline", input });
  assert.equal(response.status, 200);
  assert.deepEqual(response.body.sections.flatMap((s) => s.sourceIds), ["s1", "s2", "s3"]);
  assert.equal(response.body.topics.length, 1);
  assert.equal(response.logs.some((s) => s.includes(input.sources[0].text) || s.includes("synthetic-key")), false);
});

test("300 whole original paragraphs are indexed once and mapped exactly from provider ranges", async () => {
  const input = fixture();
  input.sources = Array.from({ length: 300 }, (_, index) => ({ ...input.sources[0],
    id: `s_class-2_12345678-abcd-4def-8123-${String(index).padStart(12, "0")}`, segmentId: `seg-${index}`,
    startMs: index * 1000, endMs: (index + 1) * 1000, text: `第 ${index} 段完整原文，保留课堂例子。`.repeat(4) }));
  const sourceIds = input.sources.map((source) => source.id);
  let calls = 0;
  const result = await withServer(async (_url, init) => {
    calls++;
    const request = JSON.parse(init.body), payload = JSON.parse(request.messages[1].content);
    assert.equal(Object.hasOwn(request, "max_tokens"), false);
    assert.deepEqual(payload.currentSources, input.sources.map((source, index) => ({ ...source, index })));
    assert.deepEqual(payload.sourceIndexRange, { startIndex: 0, endIndex: 299 });
    return providerResponse({ overview: "四个课堂分区中的两个贯穿主题。",
      sections: Array.from({ length: 4 }, (_, index) => ({ id: `sec-${index}`, title: `分区 ${index}`,
        startIndex: index * 75, endIndex: (index + 1) * 75 - 1 })),
      topics: [{ id: "concept", title: "贯穿概念", sectionIds: ["sec-0", "sec-2"] },
        { id: "application", title: "应用方法", sectionIds: ["sec-1", "sec-3"] }] });
  }, { stage: "outline", input }, { reviewBatchChars: 1_000_000 });
  assert.equal(result.status, 200);
  assert.equal(calls, 1);
  assert.deepEqual(result.body.sections.flatMap((section) => section.sourceIds), sourceIds);
  assert.deepEqual(result.body.topics[0].sourceIds, [...sourceIds.slice(0, 75), ...sourceIds.slice(150, 225)]);
  assert.deepEqual(result.body.topics[1].sourceIds, [...sourceIds.slice(75, 150), ...sourceIds.slice(225)]);
  assert.equal(Object.hasOwn(result.body.sections[0], "startIndex"), false, "external contract remains sourceIds only");
});

test("provider ranges reject gaps, overlap, noninteger, reversed, missing and out-of-range indexes", () => {
  const section = (startIndex, endIndex) => ({ id: `sec-${startIndex}`, title: "测试分区", startIndex, endIndex });
  const invalidRanges = [
    [section(0, 0), section(2, 2)], // gap
    [section(0, 1), section(1, 2)], // overlap
    [section(0, 0.5), section(1, 2)], // fractional end
    [section(0, 0), section(1.5, 2)], // fractional start
    [section(0, 3)], // beyond the last source
    [section(-1, 2)], // negative
    [section(1, 2)], // omitted first source
    [section(0, 1)], // omitted final source
    [section(0, 0), section(1, 0)], // reversed
    [{ id: "sec", title: "missing", startIndex: 0 }],
    [{ id: "sec", title: "string", startIndex: "0", endIndex: 2 }],
    [section(0, 0), { id: "legacy", title: "mixed", sourceIds: ["s2", "s3"] }],
  ];
  for (const sections of invalidRanges) {
    assert.throws(() => validateProviderOutline({ ...rangeOutlineFixture(), sections }, fixture()),
      (error) => error.code === "INVALID_REVIEW_RESULT", JSON.stringify(sections));
  }
  const invalidTopics = [
    [{ id: "bad", title: "unknown", sectionIds: ["missing"] }],
    [{ id: "bad", title: "missing section", sectionIds: ["section-a"] }],
    [{ id: "bad", title: "duplicate", sectionIds: ["section-a", "section-a", "section-b"] }],
    [{ id: "bad", title: "conflicting refs", sectionIds: ["section-a", "section-b"], sourceIds: ["s1"] }],
  ];
  for (const topics of invalidTopics) {
    assert.throws(() => validateProviderOutline({ ...rangeOutlineFixture(), topics }, fixture()),
      (error) => error.code === "INVALID_REVIEW_RESULT");
  }
  assert.deepEqual(validateProviderOutline(rangeOutlineFixture(), fixture()), outlineFixture());
});

test("V2 accepts an untitled Android classroom serialized with title null", async () => {
  const input = { ...fixture(), title: null };
  assert.equal(validateReviewInput(input).title, null);
  const response = await withServer(async (_url, init) => {
    const payload = JSON.parse(JSON.parse(init.body).messages[1].content);
    assert.equal(payload.title, null);
    return providerResponse(outlineFixture());
  }, { stage: "outline", input });
  assert.equal(response.status, 200);
  assert.deepEqual(response.body.sections.flatMap((s) => s.sourceIds), ["s1", "s2", "s3"]);
  assert.throws(() => validateReviewInput({ ...input, title: 123 }), /title/);
});

test("equal-time source IDs use Kotlin-compatible ordinal ordering rather than locale collation", () => {
  const input = fixture();
  input.sources = ["s_a", "s_A", "s_1"].map((id) => ({ ...input.sources[0], id }));
  const outline = {
    overview: "All equal-time sources remain covered",
    sections: [{ id: "sec", title: "Section", sourceIds: ["s_1", "s_A", "s_a"] }],
    topics: [{ id: "topic", title: "Topic", sectionIds: ["sec"], sourceIds: ["s_1", "s_A", "s_a"] }],
  };
  assert.deepEqual(validateOutline(outline, input).sections[0].sourceIds, ["s_1", "s_A", "s_a"]);
});

test("V2 unit carries historical terminology, optional example and temporal photo index with no image content", async () => {
  const result = await withServer(async (_url, init) => {
    const request = JSON.parse(init.body);
    const payload = JSON.parse(request.messages[1].content);
    assert.equal(payload.sources.find((s) => s.id === "h1").text, fixture().sources[3].text);
    assert.deepEqual(payload.photos, fixture().photos);
    assert.match(request.messages[0].content, /没有图片像素/);
    return providerResponse(unitFixture());
  }, { stage: "unit", input: fixture(), outline: outlineFixture(), topicId: "topic-a" });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body.photoIds, ["photo-1"]);
  assert.ok(result.body.sourceIds.includes("h1"));
  assert.equal(validateUnit({ ...unitFixture(), example: null, conditions: "" }, fixture(), outlineFixture(), "topic-a").example, null);
});

test("V2 plan covers the entire class and anchors a simple suggested date to input.today", async () => {
  const result = await withServer(async (_url, init) => {
    const payload = JSON.parse(JSON.parse(init.body).messages[1].content);
    assert.equal(payload.today, "2026-11-04");
    return providerResponse(planFixture());
  }, { stage: "plan", input: fixture(), outline: outlineFixture(), units: [unitFixture()] });
  assert.equal(result.status, 200);
  assert.equal(result.body.suggestedDate, "2026-11-05");
  assert.deepEqual(result.body.steps.flatMap((s) => s.unitIds), ["topic-a"]);
});

test("long-class batching passes every original unchanged, then merges repeated topics across sections", async () => {
  const input = fixture();
  input.sources = Array.from({ length: 7 }, (_, i) => ({ ...input.sources[0], id: `source-${i}`, segmentId: `seg-${i}`, startMs: i * 1000, endMs: (i + 1) * 1000, text: "同一知识点的不同课堂例子。".repeat(50) }));
  input.photos = []; input.notes = [];
  const readSources = [];
  let merges = 0;
  const result = await withServer(async (_url, init) => {
    const payload = JSON.parse(JSON.parse(init.body).messages[1].content);
    if (payload.stage === "outline") {
      assert.deepEqual(payload.currentSources.map((source) => source.index), payload.currentSources.map((_, index) => index));
      readSources.push(...payload.currentSources.map(({ index, ...source }) => source));
      return providerResponse({ overview: "本批讲解同一知识点的例子",
        sections: [{ id: "sec", title: "课堂例子", startIndex: 0, endIndex: payload.currentSources.length - 1 }],
        topics: [{ id: "topic", title: "共同概念", sectionIds: ["sec"] }] });
    }
    assert.equal(payload.stage, "merge-outline"); merges++;
    assert.equal(JSON.stringify(payload).includes('"sourceIds"'), false, "merge does not repeat hundreds of source IDs");
    return providerResponse({ overview: "所有区间围绕同一概念，保留全部课堂例子。",
      topics: [{ id: "merged-topic", title: "共同概念", sectionIds: payload.sections.map((s) => s.id) }] });
  }, { stage: "outline", input }, { reviewBatchChars: 700 });
  assert.equal(result.status, 200);
  assert.deepEqual(readSources, input.sources);
  assert.equal(merges, 1);
  assert.equal(result.body.topics.length, 1);
  assert.deepEqual(result.body.topics[0].sourceIds, input.sources.map((s) => s.id));
  assert.equal(sourceBatches(input.sources, 1).flat().length, 7, "over-target sources are never dropped");
});

test("cross-batch synthesis cannot omit sections and rely on automatic source completion", async () => {
  const result = await withServer(async (_url, init) => {
    const payload = JSON.parse(JSON.parse(init.body).messages[1].content);
    if (payload.stage === "outline") return providerResponse({ overview: "本批完整阅读",
      sections: [{ id: "sec", title: "分区", startIndex: 0, endIndex: payload.currentSources.length - 1 }],
      topics: [{ id: "topic", title: "主题", sectionIds: ["sec"] }] });
    return providerResponse({ overview: "不完整合并", topics: [{ id: "topic", title: "遗漏分区",
      sectionIds: [payload.sections[0].id] }] });
  }, { stage: "outline", input: fixture() }, { reviewBatchChars: 1 });
  assert.equal(result.status, 502);
  assert.equal(result.body.error.code, "INVALID_REVIEW_RESULT");
  assert.match(result.body.error.message, /Every section needs a topic/);
});

test("V2 rejects missing/duplicated/out-of-order coverage and foreign photo indexes", () => {
  const input = validateReviewInput(fixture());
  assert.throws(() => validateOutline({ ...outlineFixture(), sections: [{ id: "sec", title: "bad", sourceIds: ["s1", "s3"] }] }, input), /partition/);
  assert.throws(() => validateOutline({ ...outlineFixture(), sections: [{ id: "sec", title: "bad", sourceIds: ["s1", "s2", "s2"] }] }, input), /duplicate/);
  assert.throws(() => validateOutline({ ...outlineFixture(), sections: [{ id: "sec", title: "bad", sourceIds: ["s2", "s1", "s3"] }] }, input), /partition/);
  assert.throws(() => validateUnit({ ...unitFixture(), photoIds: ["invented-photo"] }, input, outlineFixture(), "topic-a"), /unknown photo/);
  assert.throws(() => validateUnit({ ...unitFixture(), sourceIds: ["h1"] }, input, outlineFixture(), "topic-a"), /current source/);
  assert.throws(() => validateUnit({ ...unitFixture(), sectionIds: ["section-a"] }, input, outlineFixture(), "topic-a"), /section references/);
});

test("V2 rejects incomplete plans, invalid dates and missing units before provider calls", async () => {
  const input = fixture(), units = [unitFixture(), { ...unitFixture(), id: "other" }];
  assert.throws(() => validatePlan(planFixture(), input, units), /omits/);
  assert.throws(() => validatePlan({ ...planFixture(), suggestedDate: "2026-11-03" }, input, [unitFixture()]), /before/);
  assert.throws(() => validatePlan({ ...planFixture(), suggestedDate: "2026-02-30" }, input, [unitFixture()]), /calendar/);
  let calls = 0;
  const response = await withServer(async () => { calls++; return providerResponse(planFixture()); }, { stage: "plan", input, outline: outlineFixture(), units: [] });
  assert.equal(response.status, 400);
  assert.equal(calls, 0);
});

test("V2 rejects provider truncation instead of accepting a valid-looking partial object", async () => {
  const result = await withServer(async () => providerResponse(outlineFixture(), { finish_reason: "length" }), { stage: "outline", input: fixture() });
  assert.equal(result.status, 502);
  assert.equal(result.body.error.code, "PROVIDER_INCOMPLETE");
});

test("V2 response-body timeout remains active after headers arrive", async () => {
  const result = await withServer(async (_url, init) => ({ ok: true, status: 200, json: () => new Promise((_resolve, reject) => {
    init.signal.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })), { once: true });
  }) }), { stage: "outline", input: fixture() }, { providerTimeoutMs: 15 });
  assert.equal(result.status, 504);
  assert.equal(result.body.error.code, "PROVIDER_TIMEOUT");
});

test("V2 retains access control and rejects inconsistent historical classroom identity", async () => {
  let calls = 0;
  const response = await withServer(async () => { calls++; return providerResponse(outlineFixture()); }, { stage: "outline", input: fixture() }, { accessToken: "required" });
  assert.equal(response.status, 401); assert.equal(calls, 0);
  const input = fixture(); input.sources[3].isCurrent = true;
  assert.throws(() => validateReviewInput(input), /identity/);
});
