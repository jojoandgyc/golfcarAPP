# FollowingCar Android App

通过 SSH 连接开发板的 Android 小车控制 App。界面包含连接、自动跟随、手动控制和终端四页；手动控制可切换摇杆或按住操作的方向键。

手动页的“驾驶”区域同时显示实时视频与摇杆／方向键：左侧 RGB，右侧深度伪彩。手动页隐藏顶部大标题和连接卡，视频与控制按钮在一屏内显示，无需上下滚动。先让手机与开发板位于同一热点并在 App 连接开发板，即可使用视频区的“开启视频”／“关闭视频”按钮，通过 SSH 管理板端推流服务。

启用手动控制会自动开启视频；停用手动控制（包括急停并停用、离开控制页或离开 App）会关闭视频。启动自动跟随前会先停止推流，确认停止后才启动跟随，避免占用同一个摄像头。视频服务操作过程中按钮禁用，成功后更新状态与画面；失败时显示原因并允许重试。对应板端命令为：

```bash
cd /home/topeet/Desktop/video
./scripts/videoctl.sh start
# 关闭推流
./scripts/videoctl.sh stop
```

App 使用 `http://<开发板 IP>:8889/rgbd/?controls=false&muted=true&autoplay=true&playsinline=true` 纯视频页，以 8:3 比例完整展示拼接画面。服务开启后可用“重连”重新加载画面；切到驻车、离开驾驶页或把 App 切到后台时会释放播放器连接。若预览提示连接失败，可重连画面或关闭视频后重新开启，并检查板端服务、热点 IP 与 TCP 8889、UDP 8189；诊断页面仍为 `http://<开发板 IP>:8080/preview`。

## 构建

使用 Android Studio 打开项目并安装 Android SDK 37，或在配置好 Android SDK 与 JDK 后运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/`。`local.properties`、构建产物和设备凭据不纳入仓库。SSH 密码仅在手机上连接成功后保存在 App 私有存储中，并排除系统备份。

## 开发板脚本

`board/app_joystick_bridge.py` 部署到 `/home/topeet/lianzhan/scripts/`；`board/app_follow_bridge.py`、`board/app_diagnostic_log.py` 与 `board/app_follow_worker.py` 一起部署到开发板的 `/home/topeet/Desktop/rk_car_runtime_module/`。部署方式和协议说明见 [board/README.md](board/README.md)。自动跟随还依赖开发板上的 `rk_car_runtime_module` 运行环境，本仓库未包含该完整运行模块。

## 诊断日志（1.1-diagnostics / versionCode 2）

“终端 → 诊断日志”可查看最近异常和事件时间线，断开 SSH 或控制锁定时也能查看。控制空闲后可导出 ZIP；手动、跟随运行或停止处理中禁用导出，防止分享窗口触发失焦停车。日志默认开启，状态栏会提示写入失败或事件丢弃。

App 日志包含 UTC 时间、单调时钟、App 实例、连接编号、操作编号及板端 `runId`。排查时按同一操作／会话查找 `stop_requested`（触发来源）、`stop_sending`、`follow.request_send`、`follow.ack`、`follow.terminal_received` 和 `follow.finished`。每次请求包含序号和发送／等待／校验阶段；超时、EOF、协议错误和关闭范围单独记录。`USER_STOP`、`PAGE_CHANGED`、`APP_PAUSED`、`WINDOW_FOCUS_LOST`、`DISCONNECT`、`TRANSPORT_LOST` 等来源区分用户和生命周期动作，重复停止不覆盖首次来源。

会话首次故障与最近操作错误分别展示；重连和重启后仍可在诊断页查阅。占用检查失败不会覆盖首次跟随故障，SSH 已断开时连接栏显示“连接已断开”。本版本不调整心跳、超时、失焦停用和控制锁定规则。

两端各保留最多 7 天、20 MB 日志，App 另保留最近 10 次首次故障摘要；手动高频控制每秒汇总，日志使用独立后台任务和有界队列。磁盘失败不阻塞控制或急停。App 日志位于私有 `files/diagnostics/`，排除系统备份；ZIP 仅含日志、版本及会话信息，不包含密码、控制令牌、任意终端命令正文／输出、完整状态或画面。导出缓存最多保留 2 包。

板端独立日志位于 `rk_car_runtime_module/app_diagnostic_logs/`，按 `runId` 匹配对应文件，再关联记录中的 worker PID 与运行日志目录。板端区分收到 stop、SSH 输入 EOF、心跳超时、协议错误、信号和清理，保留最早原因及最终退出码；不改变 SSH JSON 协议及 worker 停止指令。App 导出包不自动下载板端文件。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Android 设备上的 UI 测试使用 `app/src/androidTest/`。连接真实开发板的测试需显式传入 `boardPassword`；支持已保存凭据的诊断及视频联动测试使用 `useSavedBoardCredentials=true`。方向键、页面和传输故障测试不连接电机；诊断联调仅启用 `dryRun=true`。故障测试使用独立日志目录，避免覆盖用户的首次故障历史。

此次验证与部署记录见 [诊断版本部署记录](docs/diagnostics-deployment-2026-10-29.md)。
