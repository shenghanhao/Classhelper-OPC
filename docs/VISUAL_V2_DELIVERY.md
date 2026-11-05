# Visual V2 implementation and acceptance

Status: implementation and local acceptance complete on 2026-11-06. Final APK: `0.5.0-opc.8` / `10008`. Physical-phone acceptance and live-provider quality were not part of this local verification.

## Baseline and scope

- Public source baseline: `737ce91f8c8caa8f3291352d1648ecd7982b8c7b`.
- Rollback ref: `codex/checkpoint-before-visual-v2-20261106`.
- Implementation branch: `codex/opc-visual-v2`.
- Design authority: approved V2 visual boards A–I, plus the right-hand classroom detail in `00-approved-detail-reference.png`. The source design is an illustration, not evidence of runtime behavior.
- Intended delivery: locally signed OPC APK and source archive, same application ID/signature as the existing OPC channel. No upload of APK or classroom data is authorized by this task.
- Retain ASR, microphone, data model, exports, consent, credentials, retry, drafts, and review semantics. No Room migration or new model call is required for this visual change.

## Design and implementation map

| Area | Design | Implementation | Required evidence |
| --- | --- | --- | --- |
| Shared palette, type, drawings, system bars | all | ClassMemoryTheme, VisualComponents, XML colors | light/dark screenshots; no clipped English actions |
| Home, history, classroom metadata | A, I | HomeScreen, PendingReviews, class information editor | real counts/progress; import/language/filter/rename/delete/append retained |
| Recording and photos | B, I | RecordingScreen, RecordingBoardPreview | partial/final distinction; end confirmation; photo drawer, zoom and delete confirmation |
| Classroom detail and source | approved detail, E, I | SessionDetailScreen | real timeline filters; exports/organized state; source return |
| Course context and settings | A, I | CourseContextsScreen, SettingsScreen, ReviewConnectionSettings | separate context/category persistence; credential protection |
| Review guide, generation, plan | B, C, E | ReviewScreen | actual partial progress/errors; two-line checkable tasks; no invented results |
| Knowledge, QA, practice | D, F, H | ReviewContent, ReviewQuestions, ReviewExercises | full Markdown/formula/table rendering; independent folding; drafts and retry preserved |
| Directory, graph, floating controls | C, G | ReviewQuickDirectory, ReviewNavigation, graph UI | stable-ID navigation; overlay/keyboard avoidance; retained reading position |
| Recording import | I | DebugBenchmarkActivity | shared style; real decoder/service status; no debug controls in OPC |

## Data and rendering invariants

- All displayed classes, totals, knowledge titles, statuses, QA and practice results come from existing repository/runtime state. No sample class is embedded in the delivery APK.
- Display labels such as K01 never replace stable knowledge IDs.
- Review remains a lazy list. Untrusted Markdown still uses bundled DOMPurify/KaTeX, network blocking, no JavaScript bridge and bounded memory caching. Long text is not truncated.
- Original classroom organization, review checklist completion and exercise feedback remain separate concepts.
- Only explicit consent can send classroom text or optional photos. This task's automated verification must not spend provider credit or send real classroom content.

## Acceptance scope

1. Compile and run targeted navigation/rendering/QA/exercise/language/filter checks once after integration; one targeted retry if needed.
2. Build the final OPC artifact and verify package, version, signature, ABI, model assets and absence of testing CA/debug-only entry points.
3. Run a local Android visual acceptance using clearly synthetic data. Cover empty/populated home, classroom detail, recording controls, course context, review guide, long Markdown/TeX/table, QA, exercise draft/fold, directory/source return, image popup, English and dark theme.
4. Capture real rendered screenshots and record limitations. If no physical phone is connected, do not call emulator evidence physical-device acceptance.
5. Inspect final diff for feature loss, placeholder data, secrets and unrelated changes; commit source; make source archive and SHA-256 checksums.
6. Deliver only when evidence supports the scope. Send one Feishu completion notification after final audit.

## Environment and evidence

- At kickoff there was no connected physical phone. The available local API 36 emulator is used for UI verification; physical microphone/thermal/ASR performance is not inferred from it.
- No real DeepSeek requests are part of this visual acceptance.
- Build and screenshot evidence are stored alongside the local delivery in `evidence/`; this source archive contains only self-authored test source, not generated classroom data or screenshots.
- Final APK SHA-256: `efd19f6c9eb199ba20e86eb7a92e937108a25b4a2946d42c39c1b36accd34d4d`.
- Package: `com.classmemory.app.publish.opc2026`. Version: `0.5.0-opc.8` / `10008`. Size: 180,931,005 bytes.
- Certificate SHA-256: `83759a18936f8b5ed83b3170bea11ea2b1a4ab7864e9df9c5c9f2f61cb172248`. It matches the prior OPC APK, retaining its development signing identity for update compatibility. This is not a new production signing setup.
- Final manifest: `debuggable=false`, `allowBackup=false`; arm64 JNI and the four required X-ASR model files are present. No test CA, fixture database or generated sample data is bundled.

## Completed checks

| Check | Actual result and evidence |
| --- | --- |
| Compilation and build | `compileOpcKotlin` passed. Final `assembleOpc` passed in 18 seconds after the last graph height adjustment; `delivery-final-checked-build.log`. No clean or dependency reinstall. |
| Targeted JVM checks | 65 tests in 18 classes passed once. Coverage includes navigation rows, HTML render cache, QA/graph contracts, exercise drafts/folding, language, course context, recording presentation, timeline/home filters and photo storage/consent. `targeted-tests.log`, `test-results/`. |
| Installation and state retention | Final non-debuggable APK installed over the isolated test APK successfully; saved review progress remained 2/4. No real phone connected or altered. |
| Home and metadata | Empty and populated states, real counts and progress, three main destinations, metadata editor, course context and settings opened correctly. Editing was canceled rather than replacing stored real information. |
| Recording controls | Started an actual local recording session on the emulator; ASR reached ready state. Added a typed question, captured an emulated camera image, expanded the drawer, zoomed the photo and canceled photo deletion. Canceled the first end confirmation, then ended normally. History saved; recording service was absent afterward. No speech input was supplied, so this is lifecycle evidence, not recognition accuracy or real-time performance. |
| Review navigation | Checklist step saved; guide, directory and graph use stable knowledge IDs. K03 source opened its transcript location; returning preserved the reading position. Top control returned to the beginning. |
| Reading and overlays | Full Markdown, KaTeX formula, table and code were visible; a reserved bottom viewport keeps the floating dock off the content. Board image opens inside the review; it does not navigate to the transcript. |
| QA and practice | Existing QA and each practice item start expanded and fold independently. Stored exercise draft survived collapse/reopen. Question sheet selected the correct unit; keyboard did not hide its input; closing an unsent draft asked for confirmation. No answer was sent to a provider. |
| Graph | Readable 100% initial view, Fit all/Reset and node navigation verified. Final 96dp node height displays two-line titles at 130% system font; `83-final-graph-130.png`. Large graphs can be panned or fitted to the viewport. |
| Language and theme | Chinese → English → Chinese worked. Dark mode and 130% system font checked on home, import, settings, review, directory, graph and rich text. Saved Chinese review is intentionally marked outdated in English because language participates in its input hash; it remains readable. |
| Import and export entrances | Import page and system file picker opened in dark mode. Markdown/Notebook export choices remain available. This round did not rerun complete audio-file decoding or share an export to another app; those pipelines were not modified. |

UI evidence is intentionally synthetic. The fixture is created by production codecs/stores and marked “视觉验收样例”; it is not a substitute for actual DeepSeek response quality. Raw evidence includes intermediate screenshots used to diagnose and fix layout issues; use the final numbered evidence referenced above rather than treating every intermediate screenshot as a passing result.

## Deliberate mobile adaptations

- Match the approved paper/purple/teal/amber visual language using native Compose shapes and original notebook drawings; no extracted third-party raster asset is required.
- Keep live user data and long returned content instead of fixed design-board text. Display K01-style labels while retaining stable domain IDs.
- Keep one lazy scrolling review surface. Formula and table bodies use the existing secure, cached WebView renderer; no second rendering engine, dependency or truncation was introduced.
- On small screens, reserve space below the review list for the bottom-right controls. Keep buttons reachable with system insets and the software keyboard.
- Use a readable initial graph scale rather than shrinking a whole large graph to unreadable text. Fit-all remains an explicit action.
- Recording still puts End class at the top, keeps a second confirmation, joins question/note actions, and puts the camera action at the bottom.

## Architecture / next handoff

The source of truth remains the existing domain/repositories. This is a presentation update; it adds no database version and changes no ASR, audio, review gateway or prompt contract.

1. `MainActivity` owns page navigation and the return link from original transcript to review.
2. `ui/theme/ClassMemoryTheme.kt`, `ui/VisualComponents.kt`, `ui/ReviewVisuals.kt` provide shared tokens and native decorative components.
3. `HomeScreen`, `PendingReviews`, `SessionDetailScreen`, `ClassInformationEditor`, `CourseContextsScreen` consume existing session/context repositories; titles, categories and course context keep their distinct meanings.
4. `RecordingScreen` and `RecordingBoardPreview` consume recording runtime and photo repository state. They do not own ASR scheduling.
5. `ReviewScreen`, `ReviewQuickDirectory`, `ReviewQuestions`, `ReviewExercises` organize saved/active review state. `ReviewNavigation` remains the authoritative row map. `ReviewContent` changes only presentation CSS; sanitization, CSP, blocked networking and bounded cache remain.
6. `ReviewConnectionSettings` retains encrypted credentials and screenshot protection. The UI accurately distinguishes local speech recognition from an explicitly authorized online review request.
7. `benchmark/DebugBenchmarkActivity` is also the public “Import class recording” entry. OPC uses normal import controls; debug-only automation remains excluded.

The next change should begin with `CURRENT.md`, this record, and the relevant functional document under `docs/`. Keep stable IDs, source navigation, draft/retry semantics and consent when changing visuals.

## Rebuild and delivery boundaries

Prepare the exact X-ASR weights using [MODELS.md](MODELS.md), then from `class-memory/android`:

```sh
./gradlew :app:assembleOpc \
  -PasrModel=xasr -PasrComparison=true -PasrHotwordProfile=none
```

On the acceptance machine the same command used `--offline --console=plain` because dependencies were already cached. A fresh machine needs normal dependency resolution. Do not set `opcVisualCheck` or `opcDeviceTest` for delivery. `opcVisualCheck` defaults to false and exists solely for injecting synthetic data into an isolated emulator; it adds neither fixtures nor certificates to the APK.

The source ZIP is made from the final Git commit, without `.git`, AGENTS.md, local properties, model weights, credentials, classroom recordings or database files. It includes existing dependency/model license notices and model setup instructions. A source rebuild on another machine uses its own signing setup; it cannot overwrite the delivered signed APK unless the original signing identity is supplied separately. No signing key is included.

This task does not upload the APK, source, or classroom data to GitHub; it does not install on the user's physical phone. It does not establish a new live-provider quality/performance benchmark. For competition filming, use this exact APK and an authorized recording, do not present the synthetic review fixture as an actual model result.

