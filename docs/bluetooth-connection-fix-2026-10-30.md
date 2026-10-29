# 蓝牙连接修复与真机验证

设备：Samsung SM-S9280（Android 15），小车蓝牙控制器 RTL8723DU。测试跨越北京时间 2026-10-29/30 午夜；下列时刻使用 UTC。

## 现场原因与证据

手机系统在 15:35:35、15:36:49、15:45:06 等时刻记录小车经典蓝牙 ACL `PAGE_TIMEOUT(0x04)`。连接尚未建立，App 未发送热点配置。系统码表示寻呼超时，见 [Bluetooth SIG 错误码定义](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/architecture%2C-mixing%2C-and-conventions/controller-error-codes.html)。

两端绑定/信任、手机权限、服务 UUID、RFCOMM 22 监听和硬件 Page Scan 均正常。用户确认手机一直在小车旁；曾有一次成功，随后仍连续失败，不能单纯归因距离。板端蓝牙与 Wi-Fi 共用一根天线；没有发现 USB 复位、挂起或明确驱动错误，亦未证明特定共存算法故障。

对照只改变蓝牙 fast-connectable，未重启蓝牙守护进程、删除绑定或改动 Wi-Fi：

| 配置 | 硬件接听参数 | 连接结果 |
| --- | --- | --- |
| 原设置 A | 间隔 1280 ms，窗口 11.25 ms，standard | 多次约 6.4–6.7 秒超时 |
| 临时快速接听 B | 间隔 160 ms，窗口 11.25 ms，interlaced | 原生探针 1017 ms；App 按钮 597 / 781 / 1352 ms，全部成功 |
| 恢复原设置 A | 硬件读回与原快照相同 | 一次 6353 ms 成功，随后 App 再次超时 |
| 正式部署 B | 服务启动自动设置，硬件读回确认 | App 按钮 1726 / 718 / 719 ms，全部成功；状态读取 266 / 261 / 256 ms |

App 每轮间隔 6.5 秒，让上次空闲 ACL 释放后重新建立连接。证据支持原接听方式在本机现场造成间歇性建链超时，快速接听明显改善；不把短期样本称为长期或任意距离稳定性保证。

## 实现与验证

- App 新增“检查蓝牙连接”，复用正式连接/状态协议，只发 `status`；不创建热点、不发 `provision`、不连接 SSH，不更改保存的小车 IP。
- 失败日志保留原始阶段、connect/read/write、耗时、绑定状态、异常类名链；避免错误被 UI 的 ERROR 状态覆盖，不记录异常消息、堆栈或凭据帧。
- 连接失败提示靠近小车并检查开机/蓝牙服务；用户取消、权限限制、控制锁和原有超时不变。
- 板端 PageScanLease 仅在支持时开启快速接听，记录自己修改的状态，退出恢复；失败回滚、不支持能力、既有状态与清理失败均有测试。
- Android JVM 111 项通过；配对界面 7 项通过；正式安装后真实 App 连续检查 3 次通过。新增 UI 测试首次因错误的测试标签修正后重跑；原设置 A 的链路失败作为对照保留。
- Linux 开发板离线 Python 测试 47 项全部通过（Windows 的 POSIX 权限测试在 Linux 补验）。

所有板端请求均为只读状态检查。本轮未运行电机，未改电机参数、原 Wi-Fi 或跟随 worker；测试期间观察到短暂外部跟随进程，未干预，部署前重新确认控制空闲。配网状态文件仍为 UTC 11:35:34 的原记录，未发起新配网事务。

## 部署记录

- 手机版本：`1.7.1-bluetooth-check`，versionCode `9`，覆盖安装并保留应用数据。
- APK SHA-256：`672800d51265f7ed5a56bff5339b4c8e92f795a79f1e00b43a09984a06bc35f7`。
- 板端只更新 `/opt/followingcar-pairing/app_bluetooth_provision.py`，只重启 `followingcar-pairing.service`。
- 板端脚本 SHA-256：`71ff113a80a49b113fba7024875edbdcb46c89623162c3cabe998a9c116d2989`。
- 旧脚本备份：`/opt/followingcar-pairing/backup-bt-fast-20261029T160530Z/`。
- 临时 A/B 自动恢复 timer 已停止；正式配置由配网服务管理。
- 本地证据：`app/build/reports/bluetooth-repair/app-pairing-events.jsonl`、`phone-link-events.txt`、`after.png`（正式 App 页面）。文件不提交 Git。

回退应在控制及配网空闲时停止配网服务、恢复上述备份脚本，再启动配网服务；不需要重启整机、蓝牙或 NetworkManager。
