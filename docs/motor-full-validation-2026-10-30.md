# 完整电机接口验证记录

版本：`1.8-motor-full`，versionCode `11`，电机桥接 JSON `schemaVersion=2`。

本轮按用户要求仅在电脑和 Android 模拟器验证。没有连接实车、打开实车串口、写入已调电机参数、运行电机或相序学习，也没有更新开发板程序或用户手机。所有控制命令和恢复出厂测试都只作用于内存寄存器。

## 覆盖范围

按用户手册逐项抄录的独立地址集合核对 [接口清单](motor-register-interface-inventory.md)：69 个配置字段、22 个只读字段、8 个控制接口，共 99 个逻辑接口。双字字段算一个逻辑接口，保留地址不对外开放。

- 69 项配置均验证 FC10 保存；文档支持的 50 项配置验证 FC06 临时写入，19 项 PID 仅允许 FC10。
- 三项双字转速临时写入使用两帧 FC06；跨 65535 边界时选择保持中间值合法的顺序。前帧回读和状态检查失败时不发下一帧，部分写入标为未知，不重试或回滚。
- 8 类控制执行 18 种有效值组合，覆盖停止类型、正负目标速度、两侧抱闸、恢复出厂以及学习／取消。额外验证超出当前配置速度上限时拒绝发送。
- 检查有符号角度、精确 Q15、相序字节顺序、模式保留值、单字范围冲突，以及文档 CRC 示例勘误。
- 异常覆盖 CRC／地址／功能／长度／回显错误、全部文档异常码、回执丢失、部分写入、占用／旧快照、终止信号、父进程丢失、收尾失败和差速模式有效停止地址。

## 模拟器链路

设备：Google `sdk_gphone64_x86_64`，Android 15 / API 35，ADB `emulator-5554`。编译 SDK / target SDK 为 37。

`MotorFullSimulationTest` 使用生产 App 编码和解析函数，通过仅监听电脑回环地址的测试网关调用真实 Python 桥接；网关硬编码 `--simulate`，桥接串口由内存 Modbus 控制器替代。此测试不使用真实 SSH，不访问开发板，也不是实车断电保存验证。

模拟器执行的每条配置和动作会核对功能码、地址、寄存器值、回执、回读、收尾及必须重连标记。RS485 链路参数改变只认回执并提示重连，不伪装成已回读；每次模拟进程独立初始化，不把测试参数保存到硬件。

页面测试验证 7 分类、搜索、编辑草稿、预览确认、模式切换、断连／互锁导致旧确认失效、停止与学习取消入口、串口参数仅保存在 App。完整模拟快照截图覆盖全部分类及负角度、相序、Q15、串口设置。

动作恢复策略另有单元测试：ACK 与收尾均确认才能清锁，未知／矛盾回执／通信变化不能清锁，重连保持锁而允许恢复网络和主动读取。模拟器持久化测试使用真实 `motor_recovery` 键重建两次 ViewModel，并隔离其他偏好、远程凭据目录和无线服务；只修改内存中的连接状态，不实际连接网络。窗口失焦、后台及切页取消只适用于限时速度／学习，不用于普通配置写入。

## 复现方式

最终验证结果：

| 检查 | 结果 |
| --- | --- |
| Gradle 编译、App APK、测试 APK | 通过 |
| App JVM 单元测试 | 133 项通过，0 失败／跳过 |
| Linux Python 桥接模拟测试 | 44 项通过，0 失败／跳过 |
| Windows Python 桥接模拟测试 | 43 项通过；1 项 POSIX ioctl mock 测试仅在 Linux 执行 |
| Android 模拟器仪器测试 | 30 项通过，122.746 秒 |
| 独立协议地址审计 | 69 配置 + 22 只读 + 8 控制；无保留地址暴露 |
| 完整接口端到端统计 | 69 项保存、50 项临时写入、18 种动作值组合；`hardwareTouched=false` |
| 页面视觉检查 | 11 张完整模拟快照截图；输入、备注和底部按钮可达 |

30 项模拟器测试包括 12 项协议、15 项页面、1 项完整接口联调、1 项分类截图与编辑器检查、1 项真实偏好恢复锁重建测试。恢复锁重建测试没有真实 SSH／无线连接，生命周期取消策略结合纯策略测试及接线审查验证，没有以实车运动验证取消效果。

本机生成并安装到模拟器的 APK SHA-256：`2d273629cb3ecf70d31f896131b2051ad7144ada5b564c20fa3d732409a40437`。

配套板端源码 SHA-256（未部署）：

- `app_motor_config_bridge.py`：`1a32fb8c71a78128c3e78ee093ca12f2c61032e6f728f4d9ae45e6880a0717bf`
- `motor_register_catalog.py`：`94366d11759ec1a5ac4e350a90bbeb34c061841297dbd52c0322a70a8e49070b`

```powershell
$env:JAVA_HOME='F:\AndroidStudio2026\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python tools/check_motor_protocol_coverage.py
python -m unittest discover -s board -p test_app_motor_config_bridge.py -v
wsl -d Ubuntu-24.04 --cd /mnt/e/FollowingCarAPP -- python3 -m unittest discover -s board -p test_app_motor_config_bridge.py -v
```

启动模拟网关后再单独执行模拟器测试：

```powershell
python tools/motor_simulation_server.py
# 以下在另一个终端执行；只针对 emulator-5554。
$adb='G:\AndroidSDK\platform-tools\adb.exe'
& $adb -s emulator-5554 reverse tcp:28765 tcp:28765
& $adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s emulator-5554 shell am instrument -w -r -e class com.example.followingcar.MotorConfigurationProtocolTest,com.example.followingcar.MotorConfigurationPageTest,com.example.followingcar.MotorFullSimulationTest,com.example.followingcar.MotorConfigurationFullPreviewTest,com.example.followingcar.MotorRecoveryPersistenceTest -e motorSimulator true -e captureMotorFull true com.example.followingcar.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 reverse --remove tcp:28765
```

完整截图位于模拟器 `/sdcard/Android/data/com.example.followingcar/files/motor-full-preview/`。本机报告、截图和测试日志位于 `app/build/reports/motor-protocol-full/`，均不纳入 Git。

## 交付边界

App 新版需要配套 v2 电机桥接和 `motor_register_catalog.py`；旧 v1 回执不会被当作有效全量配置。将来部署应只更新电机接口相关文件，保留当前跟随程序和控制互锁；本次没有远程部署，也未修改实车参数。模拟通过不能替代真实控制器对保存介质、通信切换、动作执行及停车的验收。
