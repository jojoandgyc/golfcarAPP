# FollowingCar Android App

通过 SSH 连接开发板的 Android 小车控制 App。界面包含连接、自动跟随、手动控制和终端四页；手动控制可切换摇杆或按住操作的方向键。

手动页的“画面”子页显示开发板视频服务提供的拼接画面：左侧 RGB，右侧深度伪彩。先让手机与开发板位于同一热点并在 App 连接开发板，再由用户在开发板上手动启动独立视频服务：

```bash
cd /home/topeet/Desktop/video
./scripts/videoctl.sh start
```

App 读取 `http://<开发板 IP>:8080/preview`，不会启动或停止板端视频服务。画面页提供重连按钮；离开画面页或把 App 切到后台时会释放预览连接。若画面页提示连接失败，先检查板端服务、热点 IP 与 TCP 8080、8889 和 UDP 8189。

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
