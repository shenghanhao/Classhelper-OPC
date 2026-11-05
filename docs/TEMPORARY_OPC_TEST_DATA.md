# Temporary OPC test-data attachment / 临时 OPC 测试数据附件

The only temporary attachment is `TEMPORARY-OPC-TEST-DATA-v0.4.0.zip` on this repository's `v0.4.0` release. Its manifest and checksums are inside the ZIP. Do not upload separate manifest/checksum attachments for this bundle.

唯一临时附件为本仓库 `v0.4.0` Release 中的 `TEMPORARY-OPC-TEST-DATA-v0.4.0.zip`。清单和校验文件放在 ZIP 内部，不单独上传。

## Cleanup rule / 清理规则

`.github/workflows/cleanup-temporary-opc-data.yml` runs when a release is published. It deletes that exact ZIP only after GitHub reports another **published, non-draft, non-prerelease** release with a later `published_at`. Publishing `v0.4.0` itself does nothing. Prereleases do not trigger deletion. Repeated runs safely skip an already absent ZIP.

工作流在下一次正式版本发布后，只删除上述 ZIP。新版本必须已发布、不是草稿或预发布版，而且 GitHub 返回的发布时间晚于 `v0.4.0`。发布 `v0.4.0` 本身不会删除附件。文件已不存在时安全跳过。

The repository, source release tag, and filename are hardcoded. The script lists assets of `v0.4.0`, rechecks the exact asset, then deletes its GitHub-returned ID. It cannot select APKs, `SHA256SUMS`, source bundles, similarly named files, or attachments on other releases. It uses the repository's `GITHUB_TOKEN` with `contents: write`; no external Actions or dependencies are used.

仓库名、来源版本和文件名均为固定值；脚本复核文件后，使用 GitHub 返回的附件 ID 删除。APK、`SHA256SUMS`、源码包、相似名称文件及其他版本附件均不在删除名单内。工作流只使用本仓库的 `GITHUB_TOKEN`，不依赖第三方 Action。

## Activation requirements / 生效条件

- **Automatic release event:** the newly published release's **tagged commit must contain this workflow**. Preserve the file in later release tags. The release event does not require this file to be on the default branch. [GitHub release events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release)
- **Manual fallback:** `workflow_dispatch` requires the workflow file on the **default branch**. Once reviewed and merged there, open **Actions → Remove temporary OPC test data after the next release → Run workflow**. It still requires an already published later stable release; the button cannot bypass that condition. [GitHub manual events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch)
- **Publishing from another workflow:** events created using `GITHUB_TOKEN` generally do not start another workflow. Such publication may therefore need the manual fallback above, or an explicitly authorized dispatch. [GitHub token event restrictions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow#triggering-a-workflow-from-a-workflow)

- **自动执行：** 新 Release 的标签所指提交必须包含这个工作流；后续发布时应保留文件。`release` 事件不要求文件必须在默认分支。
- **手动补跑：** `workflow_dispatch` 需要文件已经进入默认分支。审查合并后可在 Actions 页面点 Run workflow；仍需存在较晚发布的正式版本，不能绕过清理条件。
- **使用其他工作流发布：** 使用 `GITHUB_TOKEN` 产生的事件通常不会触发下一个工作流，此时可能需要上述手动补跑或另行授权的 dispatch。

## Local verification / 本地验证

Run `node --test tools/test_temporary_opc_cleanup.mjs` from the repository root. The fixtures execute the actual inline workflow program with synthetic events and a mocked REST API. They have no network access and cannot delete real attachments. They cover the precise allowlist, release eligibility, repository checks, manual fallback, missing/renamed assets, API failures, and pagination.

在仓库根目录运行上述命令。测试直接执行工作流里的脚本，但事件和接口完全为本地模拟数据，没有网络请求，也不会删除真实附件。本地测试通过不代表已在 GitHub 执行过实际清理。
