# ClassMemory AI · OPC — development preview

This public snapshot contains the local classroom recorder and the first study-analysis infrastructure. The AI consent/results UI, course context, semantic sections, exercises and review scheduling are not implemented yet. No real model-provider integration or device acceptance is claimed.

The existing local transcription, classroom notes, board photographs and Markdown/Notebook exports remain the usable application workflow. See the root README for usage and model setup.

## Contribute

Everyone may fork this public repository, edit their fork and open a pull request. The repository owner @shenghanhao reviews and merges contributions. CODEOWNERS covers all files; branch protection requires one code-owner approval, dismisses stale approvals, and blocks force pushes and branch deletion. Administrators retain emergency bypass for owner maintenance. No automatic merge is enabled.

Do not include recordings, identifiable classroom material, credentials or local machine paths in contributions. Model-provider credentials belong only in the server environment.

## Current implementation

- Independent OPC build channel and app identity.
- Consistent classroom snapshots, content hashes and local analysis storage.
- HTTPS client, cached analysis orchestration and result validation.
- Node proxy with input/output validation and basic in-memory request limits.

The previous implementer reported 37 targeted Android tests and 5 proxy tests passing. These were not rerun for publication. Full classroom analysis and production service readiness remain unverified. Input is currently limited to 20,000 characters and 400 transcript segments; longer classes need further implementation.

## 中文

这是开发预览版：课堂记录、本地转写、板书和笔记导出是现有功能；AI 目前只有底层模块，尚未接入界面。课程上下文、整课分区、复习题案和每日计划仍待开发。任何人可 Fork 后提交 PR，由仓库所有者审核合并。

测试安装包与正式签名发行版不同。具体安装包来源、构建方式与验收限制见对应 Release。
