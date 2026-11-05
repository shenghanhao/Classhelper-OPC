// Explicit, synthetic-only integration run. Load provider.env with node --env-file.
import { createStudyServer, loadConfig } from '../class-memory/study-agent-server/server.mjs';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, open, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { homedir } from 'node:os';
import assert from 'node:assert/strict';

const directory = join(homedir(), '.config', 'classmemory-opc');
await mkdir(directory, {recursive: true, mode: 0o700});
const config = loadConfig();
assert.equal(config.providerUrl, 'https://api.deepseek.com/chat/completions', 'Only the official provider endpoint is allowed');
assert.equal(config.model, 'deepseek-flash', 'Budget reservation is priced for deepseek-flash');
assert.ok(config.apiKey && config.apiKey.length > 10, 'Private API key is missing');
const budget = 10;
const ledgerFile = join(directory, 'synthetic-test-budget.json');
const lockPath = join(directory, 'synthetic-test.lock');
const lock = await open(lockPath, 'wx', 0o600);
let ledger;
try { ledger = JSON.parse(await readFile(ledgerFile, 'utf8')); }
catch (e) { if (e.code !== 'ENOENT') throw e; ledger = {reservedOrEstimatedCny: 0, calls: []}; }
const nativeFetch = globalThis.fetch;
const budgetedFetch = async (url, init) => {
  assert.equal(String(url), config.providerUrl);
  // Peak CNY prices verified 2026-11-04: input2/M, output8/M, max output384K.
  // Reserve the full provider output capacity before every call, including uncertain failures.
  const inputUpper = Buffer.byteLength(init.body || '') + 4096;
  const reservation = inputUpper * 2 / 1e6 + 384 * 1024 * 8 / 1e6;
  if (ledger.reservedOrEstimatedCny + reservation > budget) throw new Error('Synthetic test budget exhausted; no request sent');
  const entry = {at: new Date().toISOString(), reservedCny: reservation, costUpperCny: reservation, state: 'reserved'};
  ledger.reservedOrEstimatedCny += reservation; ledger.calls.push(entry);
  await writeFile(ledgerFile, JSON.stringify(ledger, null, 2), {mode: 0o600});
  try {
    const response = await nativeFetch(url, init);
    const body = await response.clone().json();
    if (response.ok && Number.isFinite(body.usage?.prompt_tokens) && Number.isFinite(body.usage?.completion_tokens)) {
      const cost = (body.usage.prompt_tokens * 2 + body.usage.completion_tokens * 8) / 1e6;
      entry.costUpperCny = cost; entry.state = 'metered';
      entry.promptTokens = body.usage.prompt_tokens; entry.completionTokens = body.usage.completion_tokens;
      ledger.reservedOrEstimatedCny += cost - reservation;
    } else { entry.state = 'uncertain-cost-reservation-retained'; }
    await writeFile(ledgerFile, JSON.stringify(ledger, null, 2), {mode: 0o600});
    return response;
  } catch (error) {
    entry.state = 'failed-cost-reservation-retained';
    await writeFile(ledgerFile, JSON.stringify(ledger, null, 2), {mode: 0o600});
    throw error;
  }
};

const input = {
  schemaVersion: 2, sessionId: 'synthetic-class-2', sourceHash: '', language: 'zh', title: '自编力学课堂：合力与动量',
  today: new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai'}).format(new Date()),
  course: {id:'synthetic-mechanics', revision:1, name:'自编力学演示课',background:'两节完全自编、非真实课堂的数据',goals:'区分合力与动量守恒条件',chapters:'合力；动量',terminology:'F合表示合外力；正方向向右；p表示动量',prerequisites:'向量与质量',sources:'本工具内自编材料'},
  sources: [
    {id:'current-1',sessionId:'synthetic-class-2',segmentId:'seg-1',startMs:0,endMs:30000,text:'沿用上一课的约定，F合表示合外力，向右为正。质量恒定时，合外力等于质量乘加速度。今天的重点是先选择研究对象，再画受力图。',isCurrent:true},
    {id:'current-2',sessionId:'synthetic-class-2',segmentId:'seg-2',startMs:30000,endMs:60000,text:'看本课35秒拍摄的板书索引。一个质量2千克的物块受到向右6牛顿、向左2牛顿的力，合外力向右4牛顿，加速度向右2米每二次方秒。这个例子说明方向不能遗漏。',isCurrent:true},
    {id:'current-3',sessionId:'synthetic-class-2',segmentId:'seg-3',startMs:60000,endMs:90000,text:'接着讲动量。动量p等于质量乘速度，是矢量。系统合外力为零时，总动量守恒。本节只讨论这个条件，不扩展到能量守恒。',isCurrent:true},
    {id:'current-4',sessionId:'synthetic-class-2',segmentId:'seg-4',startMs:90000,endMs:120000,text:'最后回到前面的合力。计算单个物块加速度，和判断系统动量是否守恒，都需要明确研究对象及外力。两处的研究对象可能不同，不能直接套同一个结论。这就是本节回顾重点。',isCurrent:true},
    {id:'history-1',sessionId:'synthetic-class-1',segmentId:'old-1',startMs:0,endMs:30000,text:'第一节约定F合表示合外力，向右为正方向。力与动量都是矢量。分析一个系统前，要先确定系统包括哪些物体。',isCurrent:false},
  ],
  photos:[{id:'photo-35',sessionId:'synthetic-class-2',atMs:35000}],
  notes:[{id:'note-1',atMs:100000,kind:'QUESTION',text:'为什么物块和整个系统的外力条件不能混用？'}],
};
input.sourceHash = createHash('sha256').update(JSON.stringify(input)).digest('hex');
const report = {syntheticOnly:true, model:config.model, startedAt:new Date().toISOString(), stages:[], input};
const server = createStudyServer(config, {fetchImpl:budgetedFetch, log:{log(){},error(){}}});
let outputPath = join(directory, 'synthetic-review-result.json');
try {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = `http://127.0.0.1:${server.address().port}`;
  async function stage(name, extra={}) {
    const response = await nativeFetch(address + '/v2/study/review', {
      method:'POST', headers:{'Content-Type':'application/json', ...(config.accessToken ? {Authorization:`Bearer ${config.accessToken}`} : {})},
      body:JSON.stringify({stage:name,input,...extra}), signal:AbortSignal.timeout(30*60*1000),
    });
    const body = await response.json();
    if (!response.ok) throw new Error(`${name}: ${body.error?.code || response.status}: ${body.error?.message || 'stage failed'}`);
    report.stages.push({stage:name,topicId:extra.topicId || null,output:body});
    await writeFile(outputPath, JSON.stringify(report,null,2), {mode:0o600});
    console.log(JSON.stringify({stage:name,topicId:extra.topicId || null,status:'passed',estimatedPeakCostCny:ledger.reservedOrEstimatedCny}));
    return body;
  }
  const outline = await stage('outline');
  const units=[];
  for (const topic of outline.topics) units.push(await stage('unit',{outline,topicId:topic.id}));
  const plan = await stage('plan',{outline,units});
  assert.deepEqual(new Set(plan.steps.flatMap(s=>s.unitIds)),new Set(units.map(u=>u.id)));
  assert.equal(outline.sections.flatMap(s=>s.sourceIds).length,input.sources.filter(s=>s.isCurrent).length);
  report.complete=true;report.estimatedPeakCostCny=ledger.reservedOrEstimatedCny;
  report.summary={sections:outline.sections.length,units:units.length,photoReferences:units.flatMap(u=>u.photoIds).length,historyReferences:units.flatMap(u=>u.sourceIds).filter(id=>id.startsWith('history-')).length};
  await writeFile(outputPath,JSON.stringify(report,null,2),{mode:0o600});
  console.log(JSON.stringify({result:'PASS',...report.summary,estimatedPeakCostCny:ledger.reservedOrEstimatedCny}));
} catch(error) {
  report.complete=false;report.error=error.message;await writeFile(outputPath,JSON.stringify(report,null,2),{mode:0o600});
  console.error(JSON.stringify({result:'FAIL',error:error.message}));process.exitCode=1;
} finally {
  server.closeAllConnections(); await new Promise(resolve=>server.close(resolve));
  await lock.close();await unlink(lockPath);
}
