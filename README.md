# FollowingCar Android App

通过 SSH 连接开发板的 Android 小车控制 App。界面包含连接、自动跟随、手动控制和终端四页；手动控制可切换摇杆或按住操作的方向键。

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
