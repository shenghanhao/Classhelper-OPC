# Isolated visual acceptance fixture

This self-authored fixture is **not real class data or model quality evidence**. It is test-only source and does not enter the APK. It makes no API requests and includes no credentials.

The JVM fixture uses the production `CourseContextStore`, `ReviewInput.contentHash`, `ReviewContracts`, `ReviewJson`, and `ReviewStore`. It creates a six-minute synthetic class with twelve transcript segments, two generated PNG diagrams, two notes, four knowledge units, rich Markdown/formulas/table/code, unit and whole-class Q&A, four original exercises plus one permitted follow-up, feedback, a persisted draft, a plan and a graph. Every content set is marked `视觉验收样例`.

Generate once from `class-memory/android`, using an empty destination:

```sh
CLASSMEMORY_VISUAL_FIXTURE_DIR=/absolute/private/evidence/fixture \
./gradlew :app:testDebugUnitTest \
  --tests 'com.classmemory.app.review.VisualAcceptanceFixtureTest' \
  -PasrModel=xasr -PasrComparison=true -PasrHotwordProfile=none \
  --offline --console=plain
```

Output:

- `room-seed.json`: exact Room v6 rows, synthetic session ID and expected review source hash.
- `review-input.json`: production codec's expected input.
- `files/course-context.json`: course context and session assignment written by the real store.
- `files/class-reviews/`: real persisted review snapshot and exercise draft.
- `files/board-photos/`: self-authored PNG diagrams with `VISUAL QA SAMPLE` label.

Initialize the isolated test app once, stop it, and copy its Room v6 database to a local evidence path. Inject only into that local database copy:

```sh
python3 tools/visual-fixture/inject_visual_fixture.py \
  --fixture /absolute/private/evidence/fixture \
  --database /absolute/private/evidence/initialized-test-copy.db
```

The tool validates the schema version/columns and only writes the fixed synthetic session and sample course type. It does not operate a phone. The test owner must copy the resulting database and `files/` mapping together into an **isolated emulator/test package**, preserving database and file permissions. Do not overlay `course-context.json` on any real user app; this fixture has its own standalone course context. Do not copy WAL files from a running database. Never place fixture data, screenshots with secrets, or test APK flags into a delivery APK.

The source IDs match `SessionRepository.prepareReview` (`s_<sessionId>_<segmentId>`); sources, photos and notes use its query ordering. No history classes are added. The input is V3, pure text (`selectedVisionPhotoIds=[]`, photo hashes absent), and the course revision, title, language and content remain consistent. The generation date is excluded by the production content hash. Renaming a class or editing its context after injection will intentionally mark its review stale.
