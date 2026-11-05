# 板书辅助复盘 / Board-assisted review

This source change is not included in the currently installed or published APK. See CURRENT.md for validation and delivery status.

## 使用方式

新整理默认只发送文字。复习页的发送预览中可以勾选「结合本课板书分析」，确认要上传的图片数量，并在本地查看图片；只有点击同意后才发送。图片分析目前使用手机直连 DeepSeek，自建代理仍走文字流程。

图片先逐张交给 `deepseek-flash`，返回可辨认的观察和单独的不确定性说明。随后把这些带图片标识、课堂时间的文字与原始课堂内容一起交给复盘模型。文字阶段仍使用设置里选择的模型。追问和知识图使用已经保存的图片分析文字，不会再次上传像素。

原始照片继续保存在手机；网络请求使用内存中的图片数据，不把 Base64 写入复盘文件。图片属于哪节课、图片时间和内容摘要都参与核对。照片被修改、删除或变得不可读时，旧资料不会被当作当前结果继续使用；可以查看旧资料或重新选择纯文字整理。

每张成功的分析单独保存。失败后重试沿用已成功的图片和文字阶段。图文版使用不同的内容标识，保留此前文字版以及旧问答。图片含糊之处只能作为待确认的辅助推测，不能冒充老师确定说过的内容。

右侧「知识目录」可展开、滚动并跳到知识块；选择后收起。关联板书直接在复习页浮窗查看、缩放和切换图片，关闭后继续原位置阅读。只有「索引到出处」会进入转录详情。QA 仍放在对应知识块下方及全课问答区域。

提示词要求忽略课间休息和无关闲聊，不单列知识块或复习任务；原始转录仍完整保留，区间覆盖校验不放宽。真实模型能否始终正确区分课间，仍需真实课堂验收。

## Implementation

- `ReviewConnection` selects a direct DeepSeek connection or the existing self-hosted proxy. Vision uses the direct connection only.
- `SessionRepository.prepareReview(..., includeBoardPhotos)` selects current-class photos explicitly. `ReviewPhotoRef.contentSha256` and `ReviewInput.selectedVisionPhotoIds` distinguish the image-assisted snapshot. Absent fields preserve legacy input fingerprints.
- `ReviewCoordinator` saves each `ReviewPhotoAnalysis` before continuing to outline/unit/notes/plan. `ReviewRuntime` supplies saved observations to follow-up questions and graphs as well.
- `DirectDeepSeekGateway` sends one photo per vision request, using `image_url` with an inline JPEG data URL. Responses separate `observation` from `uncertainty`; both are treated as source material, not instructions.
- `ReviewNavigation` maps visible list keys to topic positions. `ReviewQuickDirectory` and `ReviewBoardPhotos` implement local navigation and viewing; neither initiates uploads.
- No Room migration is required. The review JSON reader accepts older snapshots without the new fields and rejects duplicate photo analysis identities.

The application does not truncate classroom text or set `max_tokens`. Provider limits still apply: inline image bytes are limited to 32 MiB per image. The existing camera path stores re-encoded JPEGs with a maximum edge of 2560 pixels. See the [official DeepSeek vision format and limits](https://api-docs.deepseek.com/guides/vision/).
