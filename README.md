# FollowingCar Android App

通过 SSH 连接开发板的 Android 小车控制 App。界面包含连接、自动跟随、手动控制、电机配置和终端五页；手动控制可切换摇杆或按住操作的方向键。

## 跟随生命周期适配（1.7.2-follow-lifecycle / versionCode 10）

启动时自动按所选执行模式做无硬件预检，无需先手动刷新配置。跟随页显示启动阶段，区分正常取消、故障未退出、最终关闭状态等待和合法终态；断线保留诊断并灰显实时数据。心跳仍串行，首次故障和终态不被后续检查或预期 EOF 覆盖。实现、板端文件基线、验证范围和手机安装记录见 [适配记录](docs/follow-handoff-adaptation-2026-10-30.md)。

当前开发板的跟随生命周期实现比本仓库 `board/` 中的历史副本更新。跟随桥接须按板端交接文档成套发布（包括 options、lifecycle 和 runtime），不要用旧副本覆盖当前开发板；本次只更新 Android App。

## 服务器远程连接（1.7-remote-relay / versionCode 8）

“连接”页新增“远程”模式。首次导入为本机签发的 `.carremote` 配置，再使用原开发板账号连接 `car.nujabes.top`；不需要额外开启手机 VPN，也不覆盖已保存的局域网地址。设备私钥进入 Android Keystore，远程控制同时验证服务器 TLS 证书和原开发板 SSH 主机密钥。配置、跟随、手动、电机配置和终端沿用原有控制协议；视频经独立网关和 TURN 转发已有编码，不改原相机配置。

收到初始零速 ACK 后才解锁手动控制。控制 ACK 超时会立即使原连接失效，首错保留，重连不会自动恢复行驶；原 600ms 手动看门狗和 2s 跟随看门狗不变。海外节点的模拟器测试已观察到超过手动看门狗的网络尾延迟，不能据“连接成功”判断适合手动驾驶，也不能把模拟测试当作实车验收。部署方式见 [中转说明](relay/README.md)，实际测试、延迟和限制见 [远程版本记录](docs/remote-relay-deployment-2026-10-29.md)。

同日已在实体手机安装并验证当前 Wi-Fi 下关闭 VPN 的远程连接、模拟跟随、合成视频及断线恢复；手动控制仍会因线路抖动触发停车保护。具体结果见 [真机验证记录](docs/remote-relay-phone-validation-2026-10-29.md)。

## 完整电机接口（1.8-motor-full / versionCode 11）

“电机”页按概览、电机、保护与模式、抱闸、通信、PID、操作分为 7 类，覆盖手册全部非保留接口：69 项配置、22 项只读信息及 8 类控制命令。每项显示寄存器、单位、范围与备注，支持搜索、枚举选择、带符号角度、相序和精确 Q15 输入。

配置支持 `0x10` 保存与 50 项 `0x06` 临时设置；三项双字转速临时设置分两帧，异常按未知结果处理。控制命令单独预览确认，速度和学习限时收尾，通信改变后要求重新连接并读取。控制互锁、逐项回读、未知结果保留和禁止自动重试规则继续生效。本版使用 schema 2，仅完成模拟器与模拟电机验证，尚未更新用户手机或实车桥接，未改实车参数。完整范围见 [电机配置说明](docs/motor-configuration.md)、[99 项接口清单](docs/motor-register-interface-inventory.md) 和 [验证记录](docs/motor-full-validation-2026-10-30.md)。

## 一键蓝牙配网（1.8.1-pairing-recovery / versionCode 12）

在“连接”页点击“一键配对”，允许附近设备权限并确认首次系统蓝牙配对。App 会连接已指定开发板的加密蓝牙服务，检查控制空闲，自动创建手机专用热点，通过蓝牙发送热点名称和密码，等待开发板入网。收到真实 IP 后，还会核对本次热点接口与子网，再使用已有账号和固定 SSH 主机密钥自动连接；不会自动开始跟随或驾驶。

热点采用 Android 公开的 LocalOnlyHotspot API，用于 SSH、控制与视频，不提供手机流量共享。Android 15 使用系统生成的名称、密码和频段；Android 16 及以上请求 2.4 GHz。当前板仅支持 2.4 GHz，以实际入网结果为准。首次蓝牙配对的系统确认不可跳过，板端也必须先完成指定手机的限时登记；后续已登记手机可直接配网。

热点由前台连接服务保持，页面切换和后台不主动拆热点；控制页面原有失焦／后台停车规则保持不变。配置可能已发送但回执丢失时，只查询原请求，不重复发起切网；热点保留到结果确认。板端发现忙碌或占用不明会拒绝切网。SSH 已连接或控制尚未结束时，不能关闭热点。服务安装、设备登记、协议、凭据保护和失败恢复见 [蓝牙配网说明](docs/bluetooth-pairing.md)。

配网恢复记录在发送前原子保存，进程重启后继续查询原事务。蓝牙短暂中断会有限重连，丢失回执会自动核对结果；旧热点已被当前正常网络取代时安全解除旧事务，不再永久锁在“网络地址未确认”。损坏或丢失记录须经板端核实后修复。配对完成自动选择本地 SSH，瞬时建连失败最多尝试三次，身份认证失败不重试。原因、故障验证和部署版本见 [配网恢复记录](docs/pairing-recovery-2026-10-30.md)。

已在手机与开发板上完成两次真实配网、后台保持热点、断开后只读核对并重新登录 SSH。版本哈希、测试范围和现场修复见 [部署记录](docs/pairing-deployment-2026-10-29.md)。

手动页的“驾驶”区域同时显示实时视频与摇杆／方向键：左侧 RGB，右侧深度伪彩。手动页隐藏顶部大标题和连接卡，视频与控制按钮在一屏内显示，无需上下滚动。先让手机与开发板位于同一热点并在 App 连接开发板，即可使用视频区的“开启视频”／“关闭视频”按钮，通过 SSH 管理板端推流服务。

启用手动控制会自动开启视频；停用手动控制（包括急停并停用、离开控制页或离开 App）会关闭视频。启动自动跟随前会先停止推流，确认停止后才启动跟随，避免占用同一个摄像头。视频服务操作过程中按钮禁用，成功后更新状态与画面；失败时显示原因并允许重试。对应板端命令为：

```bash
cd /home/topeet/Desktop/video
./scripts/videoctl.sh start
# 关闭推流
./scripts/videoctl.sh stop
```

App 使用 `http://<开发板 IP>:8889/rgbd/?controls=false&muted=true&autoplay=true&playsinline=true` 纯视频页，以 8:3 比例完整展示拼接画面。服务开启后可用“重连”重新加载画面；切到驻车、离开驾驶页或把 App 切到后台时会释放播放器连接。若预览提示连接失败，可重连画面或关闭视频后重新开启，并检查板端服务、热点 IP 与 TCP 8889、UDP 8189；诊断页面仍为 `http://<开发板 IP>:8080/preview`。

## 自动跟随（1.3-follow / versionCode 4）

自动跟随页隐藏顶部大标题与连接卡，按“当前状态 → 跟随设置 → 可选车端配置”排列。启动／停止操作固定在页面底部，查看参数时也能直接操作；连接管理仍在“连接”页。

1. 连接开发板，等待控制占用检查确认空闲。上次会话是否结束尚未确认时，先完成重连和占用恢复。
2. 选择模式并设置跟随距离、手势、相机、避障和倒车。默认是“影子”模式，只计算跟随指令，不驱动电机。
3. 点击底部“启动影子测试”；需要实际驱动时，先选择实车模式，再明确点击“启动实车跟随”。启动前会自动关闭视频推流。启动动作不要求先读取或刷新车端配置，但仍检查 SSH 连接、控制占用、会话恢复状态和参数有效性。
4. 运行时从顶部查看当前状态，需要更多信息可在页内展开“运行详情”；底部停止操作持续可见。详情使用页内展开，避免弹窗失焦触发停用。

“跟随距离”统一设置前进目标距离；启用倒车跟随后，同一数值同时用于前进和倒车距离参数。启用手势后可选择握拳（`Closed_Fist`）或点赞（`Thumb_Up`）作为启动／恢复手势。开启避障会自动选择 Astra Pro；倒车仍没有后方障碍物保护。

状态展示区分软件会话状态与实际控制行为：“运行中”不等于正在跟随。目标、距离和行为仅在对应状态新鲜时显示；回执过期或连接中断时显示未知／已过期，不把历史数据继续当作实时状态。软件结束、进程退出也不等于已确认车轮停稳。

“车端配置”按需展开并读取，供核对板端参数；读取失败会单独显示错误，不作为启动门禁。实际启动使用页面当前选择的设置。断线重连不会自动恢复运行；确认旧会话结束并解锁后，可以直接选择模式、手动点击启动，无需先读取配置。

## Wi-Fi Direct（1.4-direct / versionCode 5）

在“连接”页选择“Wi-Fi Direct”，授权附近设备后，由手机创建 2.4 GHz 无线组并担任 GO。页面显示组名称、默认隐藏的入组密码、已加入设备及其实际 IP，可搜索并邀请 P2P 设备。选择成员的“使用 IP”只填写 SSH 地址，不自动连接或启动控制。Direct 模式只接受系统报告的当前成员 IP，或手机 GO 接口真实 IPv4 前缀内的有效主机地址；手填旧局域网 IP、手机 GO 自身、网络地址和广播地址会被拒绝。前缀读不到时不猜 `/24`，仍可使用系统确认的成员 IP。

无线组只能在 SSH 断开且控制空闲时创建、邀请或解散；页面切换、折叠和后台不主动拆组。系统异步操作超时或结果未知时，App 保留“未确认”标记，阻止后续无线变更和 SSH 连接；必须点击“刷新状态”，取得新的组状态和发现状态后才能解除，广播或回到前台不会自动解除。旧控制会话待确认时允许恢复无线网络，控制锁仍保留，重连 SSH 后再检查占用。Direct 连接所属无线组消失、被替换或权限丢失，会请求停用控制并断开 SSH，同时取消尚在进行的连接及重试；不会自动重建组或恢复行驶。组密码仅保留内存，不写日志和备份。

当前开发板使用单接口 rtl8723du，加入手机组会切断原有无线连接。独立入组 helper 已部署并通过只读空闲检查，未修改正在使用的控制桥接脚本；实际入组尚未验证。实机手机已成功创建 GO（0 个客户端）；模拟器已验证创建 GO、后台保持组和显式解散。具体部署记录、步骤和限制见 [Wi-Fi Direct 接入](docs/wifi-direct.md)。Android 官方说明了 [`createGroup` 的手机 GO 行为](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager#createGroup(android.net.wifi.p2p.WifiP2pManager.Channel,%20android.net.wifi.p2p.WifiP2pConfig,%20android.net.wifi.p2p.WifiP2pManager.ActionListener)) 和 [`WifiP2pDevice.getIpAddress()` 的客户端 IP 来源](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pDevice#getIpAddress())。

## 构建与安装

使用 Android Studio 打开项目并安装 Android SDK 37，或在配置好 Android SDK 与 JDK 后运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/`。`local.properties`、构建产物和设备凭据不纳入仓库。SSH 密码仅在手机上连接成功后保存在 App 私有存储中，并排除系统备份。

## 开发板脚本

`board/app_joystick_bridge.py` 部署到 `/home/topeet/lianzhan/scripts/`；`board/app_follow_bridge.py`、`board/app_diagnostic_log.py` 与 `board/app_follow_worker.py` 一起部署到开发板的 `/home/topeet/Desktop/rk_car_runtime_module/`。部署方式和协议说明见 [board/README.md](board/README.md)。自动跟随还依赖开发板上的 `rk_car_runtime_module` 运行环境，本仓库未包含该完整运行模块。

## 诊断日志与断线恢复（1.2-recovery / versionCode 3）

“终端 → 诊断日志”可查看最近异常和事件时间线，断开 SSH 或控制锁定时也能查看。控制空闲后可导出 ZIP；手动、跟随运行或停止处理中禁用导出，防止分享窗口触发失焦停车。日志默认开启，状态栏会提示写入失败或事件丢弃。

App 日志包含 UTC 时间、单调时钟、App 实例、连接编号、操作编号及板端 `runId`。排查时按同一操作／会话查找 `stop_requested`（触发来源）、`stop_sending`、`follow.request_send`、`follow.ack`、`follow.terminal_received` 和 `follow.finished`。每次请求包含序号和发送／等待／校验阶段；超时、EOF、协议错误和关闭范围单独记录。`USER_STOP`、`PAGE_CHANGED`、`APP_PAUSED`、`WINDOW_FOCUS_LOST`、`DISCONNECT`、`TRANSPORT_LOST` 等来源区分用户和生命周期动作，重复停止不覆盖首次来源。

会话首次故障与最近操作错误分别展示；重连和重启后仍可在诊断页查阅。占用检查失败不会覆盖首次跟随故障，SSH 已断开时连接栏显示“连接已断开”。本版本不调整心跳、超时、失焦停用和控制锁定规则。

跟随 ACK 等待超时或 ready 超时会立即使原 SSH 连接失效，不再等待 JSch 的较慢存活检测。网络／SSH 中断后，配置、视频状态和实时观测失效；未收到终止回执时持续锁定控制，不能把断线当作停车成功。到连接页重新连接后，App 自动只读检查一次旧会话是否结束；明确空闲才解锁，检查失败或仍被占用则保持锁定。不会自动重新启动跟随或驱动电机；在 1.3-follow 中，解锁后可直接手动启动，无需先读取配置。

未确认会话额外保留一份最小恢复记录，直到终止回执或占用检查确认结束；即使最近 10 条故障历史轮转后重启，原始 `runId` 和首错仍可恢复。该记录排除系统备份，并在未确认期间加入导出包的版本元数据。SSH 清理在后台执行，旧会话迟到的超时／清理不会关闭新连接。断开流程保留原有分阶段等待，额外设置 130 秒总清理期限，超时或取消后仍归位界面并保留未确认锁。

两端各保留最多 7 天、20 MB 日志，App 另保留最近 10 次首次故障摘要；手动高频控制每秒汇总，日志使用独立后台任务和有界队列。磁盘失败不阻塞控制或急停。App 日志位于私有 `files/diagnostics/`，排除系统备份；ZIP 仅含日志、版本及会话信息，不包含密码、控制令牌、任意终端命令正文／输出、完整状态或画面。导出缓存最多保留 2 包。

板端独立日志位于 `rk_car_runtime_module/app_diagnostic_logs/`，按 `runId` 匹配对应文件，再关联记录中的 worker PID 与运行日志目录。板端区分收到 stop、SSH 输入 EOF、心跳超时、协议错误、信号和清理，保留最早原因及最终退出码；不改变 SSH JSON 协议及 worker 停止指令。App 导出包不自动下载板端文件。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Android 设备上的 UI 测试使用 `app/src/androidTest/`。连接真实开发板的测试需显式传入 `boardPassword`；支持已保存凭据的诊断及视频联动测试使用 `useSavedBoardCredentials=true`。方向键、页面和传输故障测试不连接电机；诊断联调仅启用 `dryRun=true`。故障测试使用独立日志目录，避免覆盖用户的首次故障历史。

网络故障联调还需显式传入 `injectFollowNetworkFaults=true`。测试只对专用 TCP 转发连接注入丢包或 EOF，不修改手机 Wi-Fi、板端网络或 SSH 服务；板端均使用 `--dry-run`。日志中的 `test.network_fault_injected` 标记本次模拟异常。

验证与部署记录见 [诊断版本](docs/diagnostics-deployment-2026-10-29.md) 和 [断线恢复版本](docs/network-recovery-deployment-2026-10-29.md)。
