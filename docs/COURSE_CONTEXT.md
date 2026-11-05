# Course context foundation / 课程上下文底座

Entry: Settings → Course context · View and edit. Create a course, edit its facts and sources, save, then explicitly assign existing classes to it. Each linked class opens the original classroom detail. Existing labels and hotword libraries do not assign courses automatically.

入口：设置 → 课程上下文 · 查看与编辑。可以新建课程，保存背景、目标、章节、术语/符号、先修关系与来源说明，再选择每节课堂的课程归属。课程改名保留关联；同名课程仍是独立对象。查看关联课堂可返回原始课堂记录。保存代表用户确认，不代表模型已经核实事实或学生已经掌握知识。

## Persistence and identity

- `CourseContextStore` saves a schema-versioned atomic JSON document in app-private `filesDir/course-context.json`. Existing Room v6 classroom data is unchanged.
- Each course has a UUID independent of its name, an increasing revision, update time and `user_confirmed` origin. Editing checks the expected revision to reject stale overwrites.
- Facts: name, background, goals, chapters, terminology, prerequisites, free-text source references. Missing sources are explicitly shown as manual input without supporting references.
- Each session ID maps to at most one course UUID. Reassignment is explicit; removing the association does not delete the class. Deleted classes are excluded from the UI by the existing session list; old association IDs may remain inert in the context document.
- Classroom snapshots include `course UUID:revision` in their content hash when assigned. Context edits or reassignment invalidate cached analysis; classes without context retain their existing hash format. Cached returns also recheck current content/deletion.
- Corrupt or unsupported documents raise an error and are not silently replaced. Writes use a synchronized temporary file and same-directory rename; UI state updates after a successful save.
- Saving the course card itself never uploads it. The V2 review pipeline selects this card and originals from earlier finished classes assigned to the same course, shows the complete selection, then requires explicit consent before sending it to the configured proxy/model provider.

## Content size policy

No application-level content character/count/byte cap is imposed on study input or returned summaries, knowledge points and quotations. The proxy no longer sets `max_tokens: 2500`. It preserves schema/ID/evidence checks and rejects an explicitly truncated provider response (`finish_reason: length`). Provider context/output capacity, device memory, request timeouts and service rate/concurrency controls still apply. No automatic chunking, continuation or real provider integration is claimed by M0.

## Integrated review pipeline

M1 whole-class overview and semantic sections; M2 concise knowledge explanations, conditions, classroom focus and optional grounded examples; M3 ordered review steps covering every unit; M4 suggested date and a local reviewed flag. The latest approved MVP does not require quizzes, answer attempts or time-budget scheduling. Source/photo identities link to original local material. Course facts remain separate from review completion. QA scope identities are reserved; live QA and image understanding remain later work.

The implementation contract is `docs/REVIEW_V2_CONTRACT.md`; concrete checks and environment boundaries are in `docs/REVIEW_V2_ACCEPTANCE.md`.
