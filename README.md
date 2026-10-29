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

`board/app_joystick_bridge.py` 部署到 `/home/topeet/lianzhan/scripts/`；`board/app_follow_bridge.py` 与 `board/app_follow_worker.py` 一起部署到开发板的 `/home/topeet/Desktop/rk_car_runtime_module/`。部署方式和协议说明见 [board/README.md](board/README.md)。自动跟随还依赖开发板上的 `rk_car_runtime_module` 运行环境，本仓库未包含该完整运行模块。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Android 设备上的 UI 测试使用 `app/src/androidTest/`。连接真实开发板的测试需显式传入 `boardPassword`；方向键与页面测试不启动电机。
