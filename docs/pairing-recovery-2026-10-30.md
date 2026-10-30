# 一键配对恢复与部署记录

日期：2026-10-30（Asia/Taipei）。App `1.8.1-pairing-recovery` / versionCode `12`。

## 现场原因

手机在 13:01:12、13:01:33、13:02:02 发起的三次操作，都成功完成蓝牙连接和状态读取，随后在 `CHECKING_BOARD` 报 `network_address_unconfirmed`。历史日志显示上一次实际配网于 00:21:23 成功，00:26:03 再次核对成功；本次失败不是首次蓝牙绑定失败，也不是尚未连上就发送配置。

板端最后一笔实际事务为 `3107a988-3da4-49a0-9052-40eacffe84ce`，热点 profile 为 `a5cc1935-1334-4ecd-b72a-6c46c6311ed3`，原 profile 为 `8c44fc97-5cad-4f7f-b64a-617c32597314`。现场已经使用另一个 profile `2f73c1af-5e3c-42f5-8093-e0a7f656e37f`，地址 `172.16.16.82/22`，控制空闲。

旧实现只在当前 profile 恰好等于原 profile 时报告 `network_restored`，否则一直把历史成功改成“不确定”。因此正常切回其他局域网后，重复查询也无法解除 App 的未知状态。

## 实现

| 故障/场景 | 处理 |
| --- | --- |
| 旧热点已被另一个稳定网络替代 | 双次核对网络及活动 UUID，确认旧 profile 不活动且不会自动回切，返回 `network_superseded`；仅结束旧事务，不把外部地址当成手机热点 |
| 蓝牙短暂连接/读取失败 | 最多三次新建认证 socket，750/1500 ms 退避；权限、协议、绑定失败不盲目重试 |
| 凭据写出后丢 ACK、EOF 或回复损坏 | 凭据只发送一次，自动重新连接查询同一 UUID，恢复轮询最多 90 秒 |
| App 重启、系统关闭热点 | 发送前原子保存的最小事务记录恢复为待确认，不重复发送配置 |
| 热点消失，附近没有原 Wi-Fi | 仅在受管 wlan0 稳定 state30、无 profile/IP、旧自有热点不活动且不会自动回切、双读一致时返回 `hotspot_disconnected`，恢复新配网入口；过渡状态不算已断开 |
| 手机日志记录损坏或 UUID 不在板端历史 | 通过板端严格的 `clear/pending/unavailable` 证明恢复；未知 UUID 仅在证明成立后保存 `request_expired` 墓碑，禁止迟来的同 UUID 再执行 |
| 磁盘慢/失败 | 后台 IO 写入、文件与目录同步、原子替换；提交失败不发配置，按请求 UUID 清理，不能误删其他事务 |
| 旧蓝牙 socket 残留/迟来帧 | 同一已认证手机的新连接可替换旧 socket；旧帧代际失效，已接受 worker 独立完成 |
| 板端 worker 启动与终态写盘同时失败 | 内存保留终态，重启后保留未知；不永远停在 accepted，不用新事务覆盖恢复证据 |
| BlueZ 正常释放 Profile | 独立配网服务 `Restart=always`、`PartOf=bluetooth.service`，不绑定 NetworkManager 重启 |
| 配对前 App 处于远程模式 | 已核实热点成功后明确选择本地 SSH，保留远程配置和 LAN 默认地址 |
| DHCP 已完成但 SSH 暂未就绪 | 已核实热点的初次 SSH 建连最多三次；认证/主机密钥错误不重试，远程命令不重放 |

首次异常与最近错误分开显示，所有配网日志带原请求编号；日志和恢复文件不包含热点 SSID/PSK、任意命令正文或帧内容。恢复文件放在 `noBackupFilesDir`。一旦进入未知状态，页面提供“恢复并核对连接”，加载和恢复期间不能新建事务或修改目标。

外部网络断开、设备关机、权限撤销仍可能使连接失败；处理有明确期限，耗尽后保留恢复入口及首错，不会无限重试、假报成功或自动重新行车。旧版板端缺少恢复证明字段时不猜测解锁。

## 验证

- App：`testDebugUnitTest` **167 项通过**；Debug App 与 AndroidTest APK 编译成功。
- Linux 离线板端：配网 **87 项**、既有 NetworkManager 事务 **26 项**，共 **113 项通过**，无跳过。包含 socketpair 真实字节流断连恢复、迟来帧、墓碑重启、写盘失败、外部网络抖动、控制忙、自有热点、稳定断网及损坏历史。
- Android 15 模拟器：配对 UI **14 项**、真实 Android 原子存储/慢盘 **2 项**，共 **16 项通过**。
- Samsung 真机历史事务查询：生产蓝牙协议返回 `network_superseded / definitive=true / networkChanged=false`，**2480 ms**。
- Samsung 正式页面：连续三次“检查蓝牙连接”通过，没有创建热点、发送配网凭据或打开 SSH。
- Samsung 正式恢复页面：准备真实历史 UUID 的恢复记录，重新创建 App 读取该记录，通过正式按钮完成核实、清理已确认记录并恢复“一键配对”入口，**4220 ms**。没有热点、SSH、跟随、手动或电机动作。
- 最终稳定断网补充部署后，真机历史协议查询及正式恢复 UI 两项再次通过，正式 UI 耗时 **2047 ms**。
- 最终手机版本核对为 `1.8.1-pairing-recovery` / `12`，当前默认 LAN 地址保持 `172.16.16.82`。

本轮实机验证恢复链路，没有重新切换开发板 Wi-Fi 做一次新的热点入网；原有完整热点入网验证见 [首次部署记录](pairing-deployment-2026-10-29.md)。没有运行实车电机测试或改写任何电机配置。没有改动跟随 worker、心跳与失焦停车规则。

## 部署与回退

App 已覆盖安装到手机与模拟器并保留数据。APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256：

`ee7ef59eed2b193d04cde25da4ecf7ffbdaabed15cb8e343ef5620c48f10b198`

板端只更新两个文件，并只重启 `followingcar-pairing.service`：

- `/opt/followingcar-pairing/app_bluetooth_provision.py`：`f51f1124190dd3432310470c4d154d39ac14ecc4da88a7d0a758ce7c1abee9b7`。
- `/etc/systemd/system/followingcar-pairing.service`：`193322345abb7aaa154699a342e00546d4678379c91ca750bfdc6bd2d58e717a`。

原版本备份：`/opt/followingcar-pairing/backup-recovery-20261030T052318Z/`，包含原文件和哈希清单。最终稳定断网补充前的中间版本备份：`/opt/followingcar-pairing/backup-recovery-20261030T053706Z/`。部署持有既有两把控制锁，确认没有未完成网络事务后替换；服务 PID `1358 → 5440 → 6489`，随后 `active/running`，快速蓝牙接听仍开启。两次部署前后当前 profile 和 `172.16.16.82/22` 地址完全一致。

回退须在控制及配网空闲时停止该独立服务，恢复备份的两文件，`systemctl daemon-reload` 后启动；不重启整机、蓝牙或 NetworkManager。旧版不具备新恢复字段，回退会失去自动解除过期事务的能力。不要通过删除未知事务文件规避核实。

本地验证证据保存在忽略目录 `app/build/reports/pairing-recovery/`：部署清单、手机脱敏事件、模拟器与真机测试输出、最终截图。相关测试显式 opt-in，正常测试运行不会连接真实小车或启动热点。

API 依据：[Android BluetoothSocket](https://developer.android.com/reference/android/bluetooth/BluetoothSocket)、[Android WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager)。
