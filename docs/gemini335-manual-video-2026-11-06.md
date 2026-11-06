# 手动页 Gemini 335 视频适配（2026-11-06）

## 故障原因

原独立视频服务的 `config/video.json` 固定为 Astra Pro，采集程序与 `videoctl.sh` 只识别 `astra` 和 `synthetic`。车上当前接入 Gemini 335 时，手动页的“开启视频”仍查找 Astra 的 V4L 设备路径，`rgbd-video.service` 启动失败；MediaMTX 存活不代表 RGB／深度源已就绪。手机日志显示 SSH 命令到达板端，但就绪检查超时。

## 改动

- 板端视频服务新增 `gemini335` 采集源，复用跟随程序现有的 Gemini SDK 适配器，只在手动视频服务运行时拥有摄像头。RGB 和已配准深度来自同一次采集，按时间戳配对。
- `videoctl.sh start --camera auto|astra|gemini335` 可选择相机。`auto` 按当前 USB 设备识别；不带参数的旧启动命令仍可使用。视频仍发布到原 `/rgbd` 路径，关闭后释放相机。
- Gemini 原始两路 1280×720 画面等比例缩为两幅 960×540，合成 H.264 1920×540；Astra 的 1280×480 输出保持原样。健康接口回报实际 `source_mode`，App 核对显式选择的相机。
- App 手动页新增“自动识别 / Astra Pro / Gemini 335”选择，按相机比例显示 RGB／深度。视频运行或状态未确认时提示先关闭视频再切换。自动跟随页允许板端已支持的 Gemini 335 与避障组合。

## 验证和限制

板端 35 项无硬件测试通过；App 378 项 JVM 测试、3 项模拟器聚焦界面测试通过。第一次真实相机测试遇到另一个进程短暂占用 USB，服务正确拒绝抢占；该进程自行退出后，第二次测试获得持续递增的 RGB／深度帧：`/api/health` 为 `ok=true`、`source_mode=gemini335`、`depth_status=ok`，RTSP 探测为 H.264 1920×540。结束后服务停止、相机设备无持有进程，控制锁和视频锁释放。全程未运行跟随或电机。

模拟器运行 `GeminiManualVideoIntegrationTest` 通过（1/1，17.6 秒）：连接板端、选择 Gemini 335、启动视频、核对服务为 `RUNNING` 且相机来源为 Gemini、显示预览容器，然后关闭视频并断开。事后板端 `rgbd-video` 为 `inactive`、`MainPID=0`、`ExecMainStatus=0`。该测试验证控制流程，不验证真实手机的视频首帧解码。

手机已在控制空闲锁保护下从 v39 更新到 v40（`1.8.29-gemini-manual-video`），旧 APK 已备份，连接配置哈希未变，新 APK 安装哈希与构建哈希一致；见[手机安装记录](gemini335-manual-video-phone-install-2026-11-06.json)。真机基础启停测试通过（1/1，8.242 秒）。增强的 `GeminiManualVideoIntegrationTest` 再次通过（1/1，10.36 秒）：手机 WebView 连续收到至少两帧已解码的 1920×540 画面，截图目视左侧 RGB、右侧深度伪彩均正常。该次手机使用公网连接，截图保留在本机 `E:/Temp/gemini335-video-2026-11-06/phone-gemini335-first-frame.png`，未上传仓库。测试后板端 `rgbd-video.service` 和 `rgbd-mediamtx.service` 均为 `inactive`、`MainPID=0`、`ExecMainStatus=0`，无跟随或摇杆进程。

手机和板端同在 `172.16.16.0/22`，手机能 ping 通板端。首次局域网自动测试切到本地连接后，被 App 的“上次配网结果尚未确认”恢复锁挡在 SSH 连接前，未启动视频；测试已恢复原连接设置，没有清除配网记录。随后手机日志于 2026-11-06 16:12:39 UTC 记录板端返回 `network_restored` 的确定结果、配网恢复完成，手机配网 journal 已清空。再次只读查看手机当前页面时，App 显示“局域网 SSH 已连接”，Gemini 335 的 RGB 与深度画面均在手动页显示；这是用户正在使用的画面，未重启或关闭服务。只读截图保留在本机 `E:/Temp/gemini335-video-2026-11-06/current-phone-pairing-audit.png`，未上传仓库。局域网自动双帧测试未在用户使用期间重跑。

期间发现 Gemini USB 曾被另一项独立长跑实验占用，不属于视频服务残留；该实验自行退出后设备无持有进程。

板端备份与哈希见[部署记录](gemini335-manual-video-board-deployment-2026-11-06.json)，真实采集指标见[板端测试记录](gemini335-manual-video-live-test-2026-11-06.json)，可复现补丁见[视频服务补丁](../board/patches/gemini335-manual-video-2026-11-06.patch)。本机保留带空闲锁及哈希前置检查的回滚脚本 `E:/Temp/gemini335_video_rollback.py`，尚未执行。
