import test from 'node:test';
import assert from 'node:assert/strict';
import {createStudyServer,loadConfig} from '../server.mjs';
const input={schemaVersion:3,sessionId:'c',sourceHash:'a'.repeat(64),language:'zh',title:null,today:'2026-11-05',course:null,sources:[{id:'s',sessionId:'c',segmentId:'s',startMs:0,endMs:10,text:'原文',isCurrent:true}],photos:[{id:'p',sessionId:'c',atMs:1}],notes:[{id:'n',atMs:1,kind:'QUESTION',text:'为什么'}]};
test('V3 text stages accept photo index and V2 remains available',async()=>{
 const server=createStudyServer({...loadConfig({}),providerUrl:'https://example.test',apiKey:'fake'},{log:{log(){},error(){}},fetchImpl:async(_url,opts)=>{const req=JSON.parse(opts.body);assert.equal(typeof req.messages[1].content,'string');const p=JSON.parse(req.messages[1].content);let value;
 if(p.stage==='outline')value={overview:'导览',sections:[{id:'z',title:'时间点',startIndex:0,endIndex:0}],topics:[{id:'t',title:'知识',sectionIds:['z']}]};
 else if(p.stage==='unit')value={id:p.requestedTopic.id,title:'知识',markdown:'正文 $x$',explanation:'解释',conditions:'',classroomFocus:'重点',example:null,sourceIds:['s'],photoIds:['p'],sectionIds:p.requestedTopic.sectionIds};
 else value={title:'方案',introduction:'介绍',suggestedDate:'2026-11-05',steps:[{title:'第一轮',task:'复述',completionCriteria:'说明公式',unitIds:p.units.map(u=>u.id)}]};
 return new Response(JSON.stringify({choices:[{finish_reason:'stop',message:{content:JSON.stringify(value)}}]}));}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const url=`http://127.0.0.1:${server.address().port}`;
 const post=async(path,body)=>{let r=await fetch(url+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});return [r.status,await r.json()];};
 try {let [s,outline]=await post('/v3/study/review',{stage:'outline',input});assert.equal(s,200);let [u,unit]=await post('/v3/study/review',{stage:'unit',input,outline,topicId:outline.topics[0].id});assert.equal(u,200);assert.equal(unit.markdown,'正文 $x$');let [p,plan]=await post('/v3/study/review',{stage:'plan',input,outline,units:[unit]});assert.equal(p,200);assert.equal(plan.steps[0].completionCriteria,'说明公式');assert.equal((await post('/v3/study/review',{stage:'photo',input}))[0],400);assert.equal((await post('/v2/study/review',{stage:'outline',input:{...input,schemaVersion:2}}))[0],200);}finally{server.closeAllConnections();await new Promise(r=>server.close(r));}
});
