# 真机远程中转部署与验证记录（2026-10-29）

手机已安装新版，手机专用客户端身份已导入，远程连接 UI 测试及合成视频验证通过。VPN 开启及关闭后的手动模拟均触发板端看门狗，未通过持续手动控制验收；关闭 VPN 后的只读测量也未显示延迟稳定改善。独立无 VPN 自动跟随模拟、真实隧道断开及数据黑洞后的退出处理均已验证，故障后连接 UI 恢复通过。联调临时环境已恢复，App 已重新打开到正常页面。

本记录补充 [远程中转部署与验证记录](remote-relay-deployment-2026-10-29.md)。其中模拟器的数据和本次手机数据分别保留；本次控制测试均为 `dryRun=true`、`execute=false`，不驱动实车电机，不修改电机参数。

## 部署身份

| 项目 | 记录 |
|---|---|
| 手机 | Samsung SM-S9280 |
| Android | Android 15 / API 35 |
| App | `com.example.followingcar`，`1.7-remote-relay`，versionCode `8` |
| 源版本 | `e0553e1e071178a12cab543e1910c694f7f68337` |
| 安装 APK SHA-256 | `5b46c14bad1ce8cb826e975cd7f314ff47094595a08d99de49e9b9f149b014d5` |
| 远程入口 | `car.nujabes.top`，控制 mTLS `2443`、视频 HTTPS mTLS `8443` |
| 本轮板端隧道 | QUIC |

手机使用独立的 `phone-001` 客户端身份，与模拟器身份分开。专用配置成功导入后，App 私有目录中的原始暂存导入文件已删除；私钥存放于 Android Keystore，验证为不可导出。本报告不包含导入包、密码、私钥或服务令牌。

`app/build/reports/remote-relay/deployment-version.json` 是手机安装前保存的部署快照，其 `physicalPhoneInstalled=false` 对应当时状态，不能覆盖本记录中的后续真机安装结果。

## 连接 UI 验证

首次 `RemoteConnectionUiTest` 通过，用时 **10.003 秒**。已验证远程模式及 mTLS SSH 连接流程。

首次截图被用户通知栏遮挡，未用于页面布局验收。随后在无 VPN 条件下重新执行连接 UI 测试，**7.954 秒通过**；新的 `app/build/reports/remote-relay/phone-remote-connection-ui.png` 已覆盖旧截图，并完成目视检查，确认远程连接页实际显示正常。后续截图的结论不追溯覆盖首次截图的限制，也不代表驾驶页视频布局已在真机验收。

## VPN 开启时的网络基线

测量为手机经服务器到开发板的持续 SSH 只读 echo，不访问电机。样本是往返时间，分位数采用 nearest-rank；12 个样本不足以代表长期网络质量。

| 项目 | 结果 |
|---|---:|
| DNS | `car.nujabes.top` → `43.162.101.137` |
| SSH 连接耗时 | 4807ms |
| echo 样本数 | 12 |
| p50 | 384ms |
| p95 / p99 | 692ms / 692ms |

原始样本（ms，采集顺序）：

```text
402,370,401,371,599,373,393,372,692,367,388,384
```

原始报告：`app/build/reports/remote-relay/phone-vpn-echo-validation.json`。

## VPN 开启时的手动模拟：未通过

| 项目 | 结果 |
|---|---|
| 模式 | `dryRun=true`，`execute=false` |
| SSH 连接耗时 | 3205ms |
| READY 阶段耗时 | 764ms |
| 已成功 ACK | 2 次：`349,369` ms |
| 后续结果 | 板端返回 `SessionError: Control watchdog expired`，App 以 `IOException` 退出该测试 |
| 失败阶段 | `manual_ack` |
| 自动跟随阶段 | 本次完整测试未执行到该阶段，`followAck=null` |

这是**取得两次 ACK 后的失败**，与早先模拟器在首 ACK 前退出的现象不同。两次成功回执不能证明这条线路可持续满足手动 600ms 租约。原始报告保留 `passed=false`，没有将本轮记作成功完成。

原始报告：`app/build/reports/remote-relay/phone-vpn-manual-validation.json`。手动看门狗 600ms、App ACK 截止 900ms 及既有心跳规则均未修改。

## 真机离线测试与息屏干扰

首次运行共 15 项：`RemoteTlsTransportTest` 6 项、`DriveStartupStateTest` 4 项、`RemoteCredentialsTest` 5 项。原始结果为 **14 通过、1 失败**，该失败记录保留。

失败项为 `initialAckTimeoutKeepsInputLockedAndPublishesTransportLoss`。它在本地内存模拟视频和控制响应，不访问 SSH、摄像头或电机。2026-10-29 23:04:48（UTC+8）开始后未按预期完成，设备同时处于锁屏、`mWakefulness=Dozing` 状态；USB 已供电，但 `mStayOn=false`。23:08:08 发送 SIGQUIT 取栈后，积压任务恢复执行，该项上报等待 5000ms 的 `TimeoutCancellationException`，其余测试随后完成。

`/data/anr/trace_01` 通过普通 ADB 读取被拒绝，未绕过权限，也没有据此声称已从线程栈证明某种死锁。

恢复解锁亮屏后，**仅复测该失败项，1.215 秒通过**；模拟器独立执行同一项为 **1.301 秒通过**。复测没有修改代码、测试截止或任何控制 watchdog。结合设备休眠状态及恢复后的结果，本次异常记录为息屏条件对离线测试的干扰，未发现该超时路径或 Fake Session 的死锁证据。首次失败与复测通过分开记录，不用复测结果抹去前者。

## 用户关闭 VPN 后的只读测量

用户已关闭手机 VPN；随后现场网络检查仅看到 `WIFI` / `CELLULAR`，没有 VPN 网络，本轮当前网络为 Wi-Fi。只读 echo 测试通过，用时 **12.266 秒**。

| 项目 | 结果 |
|---|---:|
| DNS | `car.nujabes.top` → `43.162.101.137` |
| SSH 连接耗时 | 4130ms |
| echo 样本数 | 12 |
| p50 | 377ms |
| p95 / p99 | 799ms / 799ms |

原始样本（ms，采集顺序）：

```text
377,372,378,571,794,374,374,369,578,375,799,572
```

原始报告：`app/build/reports/remote-relay/phone-direct-echo-validation.json`。

本轮中位数与 VPN 开启时接近，尾部仍超过手动 600ms 租约，关闭 VPN 没有带来稳定改善。两个短测量窗口不能证明高延迟由 VPN 引起，也不能单凭 echo 通过认定低延迟手动驾驶合格。

## 无 VPN 手动模拟：未通过

`RemoteRelayIntegrationTest` 本轮运行 **11.538 秒**，失败阶段仍为 `manual_ack`。

| 项目 | 结果 |
|---|---|
| 模式 | `dryRun=true`，`execute=false` |
| SSH 连接耗时 | 4480ms |
| READY 阶段耗时 | 1012ms |
| 已成功 ACK | 4 次：`383,365,357,775` ms |
| 成功样本 p50 / p95 | `365 / 775` ms |
| 第 5 次请求 | 未获得有效 ACK，收到板端 watchdog 退出错误 |
| 结束后占用检查 | `controlAvailable=true` |

原始报告：`app/build/reports/remote-relay/phone-direct-manual-validation.json`。仅统计到成功返回的四次 ACK，不把缺失的第五次算作零延迟，也不把短暂通信成功记作手动测试通过。600ms 看门狗保护保持不变，测试结束后没有自动重新启动控制。

## 无 VPN 独立自动跟随模拟：通过

跟随测试单独运行，避免被上一项手动失败提前终止。测试用时 **15.934 秒**，正常停止后取得板端退出回执和同一 `runId` 的日志。

| 项目 | 结果 |
|---|---|
| runId | `94b045fe96b3a56ba77893174a3de5a8` |
| 模式 | `dryRun=true`，`execute=false` |
| SSH 连接耗时 | 3029ms |
| 跟随 READY 阶段耗时 | 1048ms |
| 5 次心跳 ACK | `412,374,379,811,587` ms |
| ACK p50 / p95 | `412 / 811` ms |
| 板端最先停止来源 | `app_stop_requested` |
| 板端退出码 | `0` |

原始报告：`app/build/reports/remote-relay/phone-direct-follow-validation.json`。此结果验证远程跟随协议、正常停止及会话日志关联；没有驱动电机，不能替代实车跟随验收。原有跟随心跳和超时规则保持不变。

## 无 VPN 合成视频：通过

`RemoteVideoIntegrationTest` 通过，用时 **42.665 秒**。视频源为受控合成画面，没有启动实际摄像头采集。播放器在独立测试容器内使用实际远程 HTTPS mTLS 与 WebRTC 链路。

| 项目 | 结果 |
|---|---|
| 稳态采样时间 | 30.234435197 秒 |
| 新解码帧数 | 576 |
| 平均解码帧率 | 19.051 fps |
| 分辨率 | 1280 × 480 |
| ICE / 传输 | relay，`udp / udp / udp` |
| WebRTC RTT 样本数 | 11 |
| RTT p50 / p95 | `341 / 361` ms |

RTT 样本（ms）：

```text
335,335,338,339,339,341,344,345,353,358,361
```

RTT 是 WebRTC 网络统计，不能解释为摄像头到屏幕的完整视觉延迟。原始报告为 `app/build/reports/remote-relay/phone-direct-video-validation.json`，截图为 `app/build/reports/remote-relay/phone-direct-video.png`。该截图展示测试容器，**不能用来证明正式驾驶页的视频布局**。

测试后按本轮记录的 PID `74200 / 74212 / 74223` 清理合成源，确认 `8554` 已释放；原视频服务保持 inactive，独立视频网关及 frpc 保持 active。

## 手机真实隧道中断：EOF 通过

仅对本功能自有 frpc 隧道实施中断，跟随会话为模拟模式，不驱动电机。手机测试 **1/1 通过**，板端日志按 `runId` 对照。

| 项目 | 结果 |
|---|---|
| runId | `29e99fc59186a83035a3380245f35a73` |
| App 异常 / 首次故障 | `IllegalStateException` / `follow.transaction_failed` |
| 故障后 SSH / 控制通道 | `connected=false` / `channel=false` |
| 启动请求次数 | `1`，没有自动重启控制 |
| ready barrier 至 App 报告异常 | 2067ms |
| 板端最先停止来源 | `input_eof` |
| bridge / worker PID | `75432 / 75435` |
| bridge session / worker 退出码 | `1 / 0` |
| 清理后状态 | `controlAvailable=true`，frpc 已恢复 |

App 原始报告：`E:/Temp/phone-remote-relay-fault-validation.json`；板端关联报告：`E:/Temp/phone-remote-relay-fault-board.json`。桥接会话将输入断开记录为异常退出；worker 已正常清理，不能把两个退出码合并为一个结果。2067ms 是故障协调 barrier 到 App 报告的测量值，不是直接测得的停车延迟。

## 手机真实隧道数据黑洞：通过

本轮暂停专用隧道转发，保持连接对象存在，用于验证无 ACK 时的处理；没有放宽 App 或板端超时。手机测试 **1/1 通过**。

| 项目 | 结果 |
|---|---|
| runId | `7aa886a7d5ce4e179b586c8c4e8dcbec` |
| App 异常 / 首次故障 | `SocketTimeoutException` / `follow.ack_timeout` |
| 故障后 SSH / 控制通道 | `connected=false` / `channelOpen=false` |
| 启动请求次数 | `1`，没有自动重启控制 |
| ready barrier 至 App 报告异常 | 3082ms |
| 板端最先停止来源 | `heartbeat_timeout` |
| 板端最后有效请求 → 停止请求 | 同一单调时钟 `39215904 → 39217918` ms，间隔 **2014ms** |
| bridge / worker PID | `76277 / 76280` |
| bridge session / worker 退出码 | `1 / -15` |

板端日志保留 STOP 请求、SIGTERM、`STOPPING → FAULT` 及 cleanup 的完整退出过程。worker 的 `-15` 记录对应 SIGTERM 终止，本次不将其写成正常退出码 `0`。3082ms 是 barrier 至 App 报告异常的时间；2014ms 是板端同一单调时钟下最后有效请求至停止请求的间隔，两者不混作实车停车耗时。

App 原始报告：`E:/Temp/phone-remote-relay-blackhole-validation.json`；板端关联报告：`E:/Temp/phone-remote-relay-blackhole-board.json`。

## 故障恢复与最终现场状态

EOF 与黑洞测试完成后，四个会话进程 PID `75432 / 75435 / 76277 / 76280` 均已不存在，`controlAvailable=true`。独立 `frpc` 和视频网关保持 active，原视频服务保持 inactive；测试保险恢复定时器 `followingcar-phone-test-resume-20261029.timer` 已 inactive。

故障后的 `RemoteConnectionUiTest` 再次通过，用时 **9.03 秒**，验证原局域网地址保留、连接 UI 可恢复，控制没有自动启动。App 已重新打开到正常页面。正式驾驶页的实摄像头画面和布局不在本轮合成视频验证范围内。

联调期间临时启用的 USB 供电保持亮屏已经恢复：`stay_on_while_plugged_in` 原值 `0`，恢复后回读也是 `0`。原 `screen_off_timeout=300000` 未修改。App 私有目录中的原始导入暂存 JSON 已再次确认删除。
