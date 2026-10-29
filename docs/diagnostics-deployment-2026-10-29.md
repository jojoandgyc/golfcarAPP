# 诊断版本部署记录

版本：`1.1-diagnostics`，Android `versionCode=2`，日志 `schemaVersion=1`。

## 部署

- 手机：Samsung SM-S9280 / Android 35，2026-10-29 15:54:15 +08 已覆盖安装；保留已有设置和凭据。
- APK SHA-256：`fd2e5d31a42a95b9c38b6c5c1b60031454d43b5bacda78f7631f2d93344d01aa`。
- 板端：2026-10-29 15:48:05 +08；部署前后 `--inspect` 均返回 `controlAvailable=true`。
- 备份：`/home/topeet/Desktop/rk_car_runtime_module/.app_bridge_backups/20261029T074805Z/`。
- 板端部署清单：`/home/topeet/Desktop/rk_car_runtime_module/.app_diagnostics_deployment.json`。
- 先原子替换 `app_diagnostic_log.py`，再替换 `app_follow_bridge.py`；worker 保持原样。

| 文件 | 部署后 SHA-256 |
| --- | --- |
| app_diagnostic_log.py | b2879da0eeb39f4876ddd02fe298b3ff4229f4502bc09e0583023e4c2a95c1b2 |
| app_follow_bridge.py | 7c6bb577505c77d5ee3d25a5155bd30febc46a4181e9c09d09f7a12942d1644d |
| app_follow_worker.py（未修改） | fb59d83be42bfd2049df42c143d3abec912e098d7e1eaf02a058569bab8898e5 |

## 验证结果

- Gradle：34 项 JVM 测试通过，主 APK 与 instrumentation APK 构建成功。
- 开发板 Linux：62 项离线测试全部通过，无跳过，包括日志轮转、队列溢出、磁盘失败、EOF、watchdog、协议异常和标准输出隔离。
- 手机：9 项传输／故障模拟、5 项页面／日志 UI、2 项真实 SSH 的模拟控制联调通过。
- ACK 超时后追加占用检查错误仍保留首错；通道创建失败分别验证仅关闭通道与整个 SSH 断开，连接状态正确。
- 模拟阻塞写取消、EOF、无效 JSON、错误序号、重复关闭均可区分；测试日志使用隔离存储。
- 日志 writer 持续阻塞、8 槽队列塞满和写入抛错时，模拟急停仍在原 2500ms ACK 期限内确认并关闭通道。
- 验证导出 ZIP 的文件白名单、版本、会话编号与密码过滤；手机上实际打开系统 ZIP 分享窗口成功，未发送给任何联系人。
- 诊断页已检查：离线可查看、日志状态可见、运行期间禁用导出；App 重启后日志仍可查看。首次故障恢复和最近 10 次保留由独立存储重启测试覆盖。
- 首轮页面测试因 Activity 尚未显示及原有未确认会话锁定失败；将 Activity 放回手机主屏，并经只读检查确认控制空闲后，5 项页面测试全部复测通过。

## 两端会话对照

以下通过 ViewModel 的正式停止／生命周期回调触发，全部 `dryRun=true`、`execute=false`。每次均验证 App 的请求停止 → 实际发送 → 终止回执 → 清理顺序，以及板端相同 `runId` 的 worker 停止、终止回执和退出码 0。重复停止保留最早来源。

| App 首次停止来源 | 板端 runId |
| --- | --- |
| USER_STOP | d479ad79c1315d877da52274f297db01 |
| PAGE_CHANGED | 53da1731886b779de6e8a4b0bb0309ca |
| APP_PAUSED | 753878ca8cca608f648bb956dcb7b577 |
| WINDOW_FOCUS_LOST | 2efdc6c60956c8e540f425e229ccc59c |

板端依照现有协议记录收到 `app_stop_requested`；具体 UI 来源由同一 `runId` 的 App 日志区分。未扩展协议或 worker 停止消息。

视频按钮、手动开启／停用、取消启动和自动跟随前释放摄像头的联调也通过。测试结束时控制资源空闲、视频已关闭。本次未运行实车电机测试；这些结果不代表机械停稳或真实跟踪已经验证。

本版本修复错误覆盖和连接状态展示，并提供下一次退出的证据链；不能凭新增日志反推此前缺失的首次触发原因。
