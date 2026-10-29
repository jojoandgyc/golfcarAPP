# App 跟随生命周期适配记录

依据开发板 `/home/topeet/Desktop/rk_car_runtime_module/docs/app_follow_developer_handoff.md`（更新日期 2026-10-29），App 适配日期 2026-10-30，时区 Asia/Taipei。

## 用户可见变化

- 点击启动即自动使用本次相机、参数和实车/影子模式执行静态预检；必须返回 `hardware_opened=false`。不需要先点“刷新配置”。预检失败不关闭视频、不建立控制会话；纯协议 `dry-run` 跳过硬件配置预检。
- 启动时显示环境、模型、相机等阶段及板端报告的已用时间。未知阶段和老板端缺少 `startup` 时显示通用启动状态，不推算百分比或保证剩余时间。
- 区分“启动已取消”“正在停止”“故障且进程尚未退出”“正在确认退出结果”“程序已退出”。`processExited=true`、STOP 通信回执和退出码 0 都不作为机械停稳证据。
- `FAULT + terminal=false` 和 `finalStatusPending=true` 期间继续续租与查询，控制保持锁定。只有合法终态结束协议；首次故障不会被之后的空 fault、正常退出或占用检查覆盖。
- 断线/协议失败后保留最后快照供诊断，实时距离、轮速和目标状态灰显。占用检查只解除资源占用锁，不补造历史终态，也不自动开始新会话。

## 协议与调度

请求仍是原有 `seq/token/cmd/runId` 四字段，单个串行协程发送。停止意图优先，但等待在途请求的合法回执及新 token 后才发下一条。未启动的 ready 会话取消时发送 `close` 并等待终态；不能用本地关通道冒充关闭成功。

500 ms 心跳目标按上一次发送时间计算，减少“网络 RTT 后再额外等 500 ms”的延迟；不并发补发、不重发超时请求、不放宽 1200 ms ACK 超时或板端 2 秒租约。终态已验证但调用协程在返回界面前被取消时，仍保留通道缓存的合法终态。

响应忽略未知字段；按 UTF-8 换行组帧，限制 16 KiB，拒绝非法 JSON、重复键、额外尾部文本和错配 seq/runId/token。`type=error` 可携带诊断状态，但不是终态 ACK；停止发送请求。正常终态后的预期 EOF 不新增断连故障。

合法回执记录手机单调时钟，展示时把手机接收后经过的时间加入板端数据年龄。诊断记录增加启动阶段、阶段/总预算、最终状态等待、退出码和日志目录；不序列化完整状态，不记录 token。异常文本在写日志和进入 UI 前统一脱敏。

## 验证范围

- JVM：117 项通过，21 个测试套件，0 失败/错误。
- Android 35 模拟器：71 项离线回归通过（76.878 秒），覆盖模型/界面、真实 ViewModel + 内存 SSH 通道、协议错误、阻塞 I/O、回执超时和终态取消竞态。
- 模拟器通过 SSH 连接当前开发板：4 项通过（8.576 秒），包括 ready 后仅心跳保持超过 2 秒且不启动 worker、正常 close、带选项的 dry-run 启停、默认配置 dry-run 启停、实车模式的静态预检。静态预检报告 `execute=true / hardware_opened=false`，不是实车执行。
- 截图检查只使用模拟状态。最后的 UI 调整移除了与已收到状态重复的准备文案；测试容器补上系统栏留白（正式 App 已有 Scaffold 留白）。
- 最后 UI 调整后，14 项 `FollowControlsTest` 再次全部通过（55.341 秒）；两张阶段截图已检查。

测试不执行实车电机，不打开相机，不调用视频启停服务；不替换板端脚本。运行前确认 `controlAvailable=true`、两个原视频服务均 inactive；每项 SSH 集成测试也先检查占用。4 项完成后收尾复查时，局域网 SSH 出现超时/连接关闭，远程中转的板端转发端口也拒绝连接，因此未取得最后一次板端状态，未重启服务或调整网络。当前实车冷启动、机械停稳时间、真实初始化阻塞和本次真机断网场景未测试；板端自身的 fake-device 测试结果见其交接文档，不能计作本次 App 验证。

原始测试输出保存在被 Git 忽略的 `app/build/follow-handoff-*.txt`，截图位于 `app/build/reports/follow-handoff/`。

## 板端基线

板端 Git HEAD：`4d57b2863425edc96e2ce59305d5793e69352789`，另有本轮未提交的生命周期更新。因此同时记录实际文件 SHA-256；没有用 App 仓库中较旧的 `board/` 副本覆盖开发板。

| 文件 | SHA-256 |
| --- | --- |
| `docs/app_follow_developer_handoff.md` | `9a4e47204d17643b67765f48066eb20690ed9414faf75d0f117dc3ae300b2514` |
| `app_follow_bridge.py` | `cfad3a077fa80c453577886226e8e8a84a5beaec29b34aed2d6fc7492a630139` |
| `app_follow_worker.py` | `67b1599577c14dbd75fc893f78ff69603d25a8ea7ef19a7d2df1ddbb0e0c93c5` |
| `app_follow_options.py` | `9faa27522542adae834a6757fd7aa10f32e23a0efdd191bbc4c7e9ac4b3a1516` |
| `app_follow_lifecycle.py` | `b039d23ed9ef8dd97f4e018d308d9891f2638cb7a5e1e599a4bb946da7ca2f0c` |
| `continuous_follow/runtime.py` | `0b13bc02fcddfc8c91922d8d809087d4eba28cfcfaa30d47ce875f9bea19a7ef` |

## 构建

App `versionCode=10`，`versionName=1.7.2-follow-lifecycle`。构建基于已提交的蓝牙诊断版本 `e8588af`，保留原有连接、视频和电机配置功能。产物为 `app/build/outputs/apk/debug/app-debug.apk`。

APK SHA-256：`14add7ce23b0c0b4cf73e2fc3a7243d6084b7bd6e0740ee0c662da71118a60fc`。

用户确认手机空闲后，已于 2026-10-30 00:15:18（Asia/Taipei，手机 PackageManager 时间）使用 `adb install -r` 安装到 Samsung SM-S9280；读取安装信息确认 versionCode 10 / versionName 1.7.2-follow-lifecycle，并打开跟随页面检查实际布局，显示未连接且没有自动恢复控制。没有清除应用数据，也没有启动控制或修改手机网络设置。真机截图为 `app/build/reports/follow-handoff/phone-installed.png`。
