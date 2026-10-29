# 远程中转部署与验证记录（2026-10-29）

本次在用户指定的海外服务器 `43.162.101.137` 上部署，入口为 `car.nujabes.top`。App 版本为 **1.7-remote-relay，versionCode 8**。此前提到的 `10.4.4.13` 没有被当作已验证的公网入口，也没有在其上另行部署。

本记录保留最初模拟器部署的结果；同日后续已完成 [实体手机安装与无 VPN 验证](remote-relay-phone-validation-2026-10-29.md)，两轮测试分别记录。

本次远程 SSH、独立模拟跟随、合成视频及专用隧道中断测试已完成；当前模拟器网络的手动控制未通过 600ms 看门狗验收，不能将“能够连接”解释为已满足低延迟驾驶要求。

所有控制联调均为 `dryRun=true`、`execute=false`。没有运行实车电机测试，没有改动调好的电机参数，没有启动摄像头采集或原视频服务。

## 部署版本与位置

| 项目 | 版本或位置 |
|---|---|
| Android | `1.7-remote-relay` / `8`，包名 `com.example.followingcar` |
| frp | 固定 `0.71.1`，下载包校验 SHA-256，见 `relay/configuration.py` |
| MediaMTX | 固定 `v1.21.3`，板端 ARM64 |
| 服务器 | `43.162.101.137`，域名 `car.nujabes.top` |
| 板端联调 SSH | `172.16.16.82:22`，仅作为本次局域网维护入口 |
| 自有程序目录 | `/opt/followingcar-relay/` |
| 自有配置和私有凭据目录 | `/etc/followingcar-relay/` |
| 既有配置备份目录 | `/etc/followingcar-relay/backups/` |
| 板端视频网关部署回执 | `/etc/followingcar-relay/video-gateway-deployment.json` |
| Android APK 构建时间 | `2026-10-29 22:24:17`（UTC+8），33,613,613 bytes，已安装到模拟器 |
| Android 最终 APK SHA-256 | `5b46c14bad1ce8cb826e975cd7f314ff47094595a08d99de49e9b9f149b014d5` |
| Git 实现版本 | `e0553e1e071178a12cab543e1910c694f7f68337`；后续真机记录为文档更新，APK 未重编译 |

板端独立视频网关使用的 MediaMTX 二进制 SHA-256：

```text
a1621ba39c0aa58430809df28263865e37a75bb4d2f1c87e642ddf5f3f67b19d
```

## 实际链路

```text
手机 App
  ├─ JSch SSH → mTLS 2443 → 服务器 nginx → 回环 22022
  │                                      → frps/frpc QUIC（必要时 TLS/TCP）→ 板端 SSH 22
  ├─ WebView HTTPS mTLS 8443 → /player/ 静态播放器
  │                         → /rgbd/whep 信令 → 回环 18889 → frpc → 板端 19889
  └─ WebRTC 媒体 → 服务器 Coturn → 板端独立 MediaMTX 视频网关
                                      └─ 按需拉取 RTSP/TCP 127.0.0.1:8554/rgbd
```

SSH 路由承载现有配置查看、跟随、手动控制、视频服务控制、诊断日志和终端能力。视频媒体走 TURN，不经过 SSH 或 frp 的可靠字节流。网关复用已经编码的板内 RTSP，不转码；额外增加一次板内 RTSP 转发，尚未单独量化这一跳的延迟。

原视频生产服务仍由 App 的既有 `videoctl.sh start/stop` 流程控制。独立网关只在有观看请求时拉取已有视频源，不会替 App 启动摄像头；原生产服务停止时，网关没有可播放的视频。

### 端口与服务

| 位置 | 端口或服务 | 用途 |
|---|---|---|
| 服务器公网 | `2443/TCP` | mTLS 内封装 SSH |
| 服务器公网 | `8443/TCP` | mTLS HTTPS 播放器与 WHEP |
| 服务器公网 | `7443/UDP`、`7443/TCP` | frp QUIC 首选及 TLS/TCP 回退 |
| 服务器公网 | `3478/UDP+TCP`、`5349/TCP` | TURN / TURN TLS |
| 服务器公网 | `62000–62099/UDP` | TURN 媒体转发端口范围 |
| 服务器回环 | `127.0.0.1:22022`、`127.0.0.1:18889` | SSH / 视频信令代理后端，不直接暴露公网 |
| 板端回环 | `127.0.0.1:19889` | 独立视频网关 WHEP |
| 板端 | `18189/UDP` | 独立视频网关 ICE |
| 板端回环 | `127.0.0.1:17400` | 带认证的 frpc 状态接口 |
| 服务器 | `followingcar-frps.service` | 中转隧道服务 |
| 服务器 | `followingcar-nginx.service` | mTLS 入口及静态播放器 |
| 服务器 | `followingcar-turn.service` | 视频媒体中转 |
| 服务器 | `followingcar-certificate-maintenance.timer` | 只检查并报告待维护证书状态，不自动重启服务 |
| 板端 | `followingcar-frpc.service` | 独立隧道 supervisor 与其 frpc 子进程 |
| 板端 | `followingcar-video-gateway.service` | 独立视频网关，不访问电机或摄像头设备 |

自有服务使用无登录权限的 `followingcar-relay` 账户。没有为了本功能修改板端 Wi-Fi、默认路由或服务器原 VPN 业务。没有创建 TUN/VPN 网卡。

## 身份认证与恢复规则

- App 在内部选择远程模式，通过系统 CA 校验服务器，再提供该设备自己的客户端证书；私钥导入 Android Keystore。模拟器与手机使用独立身份。
- WebView 只向配置中的远程主机和 `8443` 端口提供客户端证书；页面、资源和 WHEP 会话限制同源及固定路径。TLS 校验失败时拒绝连接，不降级到明文。
- SSH 仍验证板端公钥，不因使用中转放弃原主机身份校验。公网不能直接访问 `22022` 或 `18889` 后端。
- frpc 默认 QUIC。只有启动 10 秒仍未注册任何本车代理，且认证状态接口明确返回空注册状态时，才选择 TLS/TCP。API 读取失败、部分代理已注册等情况不会触发这一回退。
- 任一代理已经注册成功后，本次服务生命周期不再主动切换传输。断线后的隧道恢复交给 frpc，车辆控制不会被 supervisor 重放或恢复。
- 手动 600ms、跟随 2s 看门狗和既有停止/锁定规则保持原值。App 收到首条有效零速 ACK 后才解锁手动输入，避免仅收到 READY 就把控制显示为可用。

凭据只记录位置，不记录内容：服务器 `/etc/followingcar-relay/clients/<身份>/<身份>.carremote`、板端私有 `board-001.boardremote`、电脑私有中转目录 `E:\Temp\followingcar-remote-provisioning\`。导入包不进入 Git；密码、P12 内容、frp token、TURN secret 不写入本报告。板端暂存目录权限为 `0700`，bundle 为 root 所有且 `0600`。

证书续期自动复制证书并 HUP 自有 nginx。frps/Coturn 的重启必须留在显式维护窗口；自动任务只报告 pending，绝不因为观察到空闲就重启。当前服务器证书到期日 2027-01-27，须在到期前安排后端维护。维护与回滚步骤见 [relay/README.md](../relay/README.md)。

## 控制链路验证

### 环境与测量口径

设备为 Android 模拟器 `emulator-5554`，经 Windows 当前 Mihomo/Fake-IP 网络路径访问公网；模拟器将域名解析为 `198.18.0.77`。这不是实际手机移动网络的表现。下列数值是短窗口往返测量，不是单向延迟，也没有用两台设备各自的单调时钟直接相减。

### QUIC 与 TLS/TCP 只读 echo

每组 12 次 SSH 只读 echo，不涉及控制电机。分位数使用 nearest-rank。

| 隧道传输 | 连接耗时 | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| QUIC | 4336ms | 361ms | 774ms | 774ms |
| TLS/TCP | 4443ms | 354ms | 1329ms | 1329ms |

原始样本（ms，按采集顺序）：

```text
QUIC: 774,361,358,565,355,770,770,359,359,563,357,773
TCP:  352,351,569,354,348,764,878,1329,353,563,936,353
```

QUIC 保持首选。TCP 的中位数接近，但本轮尾部更长，不能以中位数掩盖看门狗风险。

### 手动模拟：两次均未通过

`RemoteRelayIntegrationTest` 分别经 QUIC、TLS/TCP 执行手动模拟。两次均在首个有效 ACK 前收到 `SessionError: Control watchdog expired`；没有获得可用的 `manualAck` 样本，不能把它记作正常完成的手动测试。

TCP 记录中 SSH 连接耗时为 6551ms，READY 阶段为 2191ms，失败阶段为 `manual_ack`。App 已增加首条零速 ACK 确认后才开放输入的逻辑，尚不能据此认定该海外测试线路具备合格的手动驾驶延迟。没有放宽 600ms 保护时限。

### 独立自动跟随模拟：通过

| 项目 | 结果 |
|---|---|
| 传输 | `mtls_frp_quic_ssh` |
| runId | `724269509eae3ee0ed10a28c97228b6d` |
| 模式 | `dryRun=true`，`execute=false` |
| 连接耗时 | 3070ms |
| 5 次心跳 ACK | `362,365,364,361,368` ms |
| ACK p50 / p95 / 最大值 | `364 / 368 / 368` ms |
| 停止来源 | `app_stop_requested` |
| 板端最终退出码 | `0` |

这是单独的模拟跟随成功记录，与前述手动阶段失败分开统计。它没有打开电机，不能替代实车跟随验收。

### 专用 frpc 中断测试

`RemoteRelayFaultIntegrationTest` 的两种实际故障注入分别 **1/1 通过**。测试通过私有 ready barrier 与宿主机协调，只操作本功能的 `followingcar-frpc`；没有停止原 SSH、Wi-Fi、自动跟随服务，没有改变网络配置或任何 watchdog 时限。

| 项目 | 连接关闭 / EOF | 数据黑洞，连接仍保留 |
|---|---|---|
| 注入方式 | 停止独立 `followingcar-frpc` | 向该服务 cgroup 发送 `SIGSTOP` 暂停转发，不关闭连接 |
| runId | `9c76e0cd749bc1d94068873bfc9d4f37` | `89c31ffcf61234de029842dad0d357d8` |
| 模式 | `dryRun=true`，`execute=false` | `dryRun=true`，`execute=false` |
| App 首次故障 | `follow.transaction_failed` | `follow.ack_timeout` |
| App 异常类型 | `IllegalStateException` | `SocketTimeoutException` |
| App 故障后连接 / 通道 | `false / false` | `false / false` |
| App 启动请求数量 | `1`，没有重发启动 | `1`，没有重发启动 |
| barrier 至 App 上报异常 | `1932ms` | `3337ms` |
| 板端最先停止来源 | `input_eof` | `heartbeat_timeout` |
| 最后有效板端请求 | `heartbeat`，seq `3` | `heartbeat`，seq `3` |
| 最后有效请求至板端超时 | 不适用，先收到 EOF | `2013ms`，板端同一单调时钟相减 |
| 板端 bridge / worker PID | `68558 / 68561` | `69597 / 69600` |
| session / worker 退出码 | `1 / -15` | `1 / -15` |

两组板端时间线均记录 `stop_requested`、`worker_stop_sent`、`SIGTERM`、worker `STOPPING`、worker 退出和 `cleanup_completed`。后续连接清理和 `worker_fault` 没有覆盖最早原因。EOF 变体保留 `input_eof` / `Follow input closed`；黑洞变体保留 `heartbeat_timeout`，表明未收到 EOF 时仍由板端 2 秒心跳保护结束模拟会话。

黑洞测试事先设置 30 秒应急 `SIGCONT` timer，测试结束立即恢复 cgroup 并取消 timer；没有留下暂停的转发进程。两组 bridge/worker PID 均已消失，`controlAvailable=true`。EOF 与黑洞注入恢复后，真实远程 UI 重连均通过；黑洞后的 `RemoteConnectionUiTest` 耗时 17.552 秒，原局域网地址保留，没有自动启动控制。

表中 barrier 时间包含宿主协调与网络延迟，不是精确的断网至停车时长。这些证据说明模拟会话退出、进程清理和控制占用释放；worker `-15` 表示收到 SIGTERM，`physicalStopped` 和停止回执均未确认，**不能将进程退出或 controlAvailable 解释为实车已经机械停稳**。本次没有实车制动测量。

## 视频验证与实测限制

使用 `synthetic_video_board.py --upstream-only --duration 600` 创建合成 RTSP 源，测试原生产路径 `127.0.0.1:8554/rgbd` → 正式独立网关 `19889` → 真实 WHEP/TURN → App WebView。源为 lavfi 1280×480、20fps、约 3Mbps H264，低调度优先级；未访问摄像头、串口或电机。

`RemoteVideoIntegrationTest` 最终 **OK（1 test），55.117 秒**。除确认连接外，持续观察了 30 秒的实际解码增长，并断言页面没有“暂无画面”提示，顶部显示“远程画面已连接”。

| 测量 | 结果 |
|---|---|
| 稳态观察窗口 | 30.329681928 秒 |
| 解码帧增量 | 612 帧 |
| 平均解码帧率 | 20.18fps |
| 视频尺寸 | 1280×480 |
| WebView readyState | 4 |
| ICE 候选类型 | relay；板端日志也确认双方使用 relay |
| 本地 / TURN 连接 / 远端协议枚举 | `udp / tcp / udp` |
| RTT 样本数 | 11 |
| RTT p50 | 716ms |
| 最后一次 RTT | 1270ms |
| 有效 RTT p95 | 本轮没有可报告数值 |

本轮手机模拟器到 TURN 实际使用 TCP 回退，视频仍能保持 20fps，但往返时延较高。初次连通截图还出现过 2703ms 的瞬时 RTT。视频流畅不等于远程驾驶已满足低延迟要求。

旧测试的 p95 下标向下取整，11 个样本少算了尾部，打印过的 `1117ms` **不是有效 p95**；该轮没有保留全部 RTT 原始值，无法离线纠正。测试现已改为 `ceil(N×0.95)-1`，并保留 `rttValuesMs`，没有为生成更漂亮的数字反复采样。

播放器新增已有解码帧后至少 6 秒没有增长的检测，使用 `performance.now()`，输出固定 `media_stalled`，关闭旧连接后重连；浏览器没有帧计数器时不误判。该逻辑有 Node 测试，本轮尚未完成真实断源负测试。视频重连不改变车辆控制心跳或停止规则。

## 本轮定位并修复的问题

| 问题 | 证据与修复 |
|---|---|
| WebRTC POST HTTP 400 | 网关日志为 `error getting local interfaces: route ip+net: netlinkrib: address family not supported by protocol`。systemd 地址族限制漏了 `AF_NETLINK`，导致 Go/Pion 无法读取网卡。仅在独立网关 unit 增加该地址族，仍不授予 capabilities；原服务不修改。 |
| 播放正常却覆盖“暂无画面” | `playing` 的空提示字符串被 `||` 当作缺省值；改用 `??`，实测页面断言通过。 |
| 视频失败只能看到反复重连 | 增加固定安全失败码；不输出任意异常正文、SDP、TURN 凭据或完整状态对象。 |
| 视频状态枚举被日志脱敏 | 日志字段 `state` 属于完整状态禁用字段，改为仅承载枚举的 `playerState`，不放宽全局脱敏规则。 |
| Android Keystore 客户端签名失败 | 为 Conscrypt 的原始 TLS 签名路径补齐 `DIGEST_NONE`，RSA 补齐原始签名所需 padding 权限；用途仍仅为 SIGN，不授予私钥解密用途。RSA 和 EC P-256 的实际 Keystore mTLS 握手均通过，服务器 CA 与域名校验保持开启。 |
| 证书自动维护可能中断新连接 | 检查空闲与重启之间存在新客户端接入窗口。最终移除自动重启执行路径；任务只报告待维护并保留 pending。新连接恰在检查期间进入的回归测试确认不会调用 restart。 |
| 手动 READY 可能早于有效控制回执 | App 在首条有效零速 ACK 后才开放输入。此修复没有改变网络等待时限和板端看门狗。 |
| 已连接但画面冻结缺少处理 | 新增 6 秒无解码增长检测、固定故障码及重连；Node 验证通过，真实断源尚待覆盖。 |

最终验证汇总：

| 范围 | 结果与边界 |
|---|---|
| JVM 单测 | 106 项、20 个 suite，0 失败 |
| Android 传输与凭据 | 17 项传输故障测试通过；凭据及 TLS 10 项通过，随后增加的 EC Keystore 握手 1 项通过。最初失败记录保留，以上计数使用修复后的结果。 |
| Python | 两组分别 22 项、11 项通过；独立网关 3 项包含在相应集合中，不重复计数。22 项包含维护竞态回归。 |
| 视频 Node | 11/11 通过，包含同源检查、关闭清理、候选队列界限和停帧检测 |
| `DriveStartupStateTest` | 4/4，真实 VM/client/channel 状态机，SSH/视频/电机响应均在内存中模拟 |
| `RemoteConnectionUiTest` | 1/1，真实远程重连、局域网 IP 保留，没有启动控制 |
| `RemoteVideoIntegrationTest` | 1/1，真实 WHEP/TURN、30 秒合成视频稳态观察 |
| `RemoteRelayFaultIntegrationTest` | EOF 与数据黑洞两种实际注入分别 1/1，见上文 |
| 手动实网模拟 | QUIC 与 TCP 两次均失败，保留失败结论 |

APK 已安装到模拟器；没有把模拟器安装描述为已更新用户的实体手机。

## 原程序保护与测试清理

部署前后校验结果完全一致：

| 原有文件 | SHA-256 |
|---|---|
| `/home/topeet/Desktop/video/config/mediamtx.yml` | `ff76fa68e2274e3989b26f508690d47df3d503133581ea40bec7dc2abe74a9c2` |
| `/etc/systemd/system/rgbd-mediamtx.service` | `cf83ba9f6e2993fb9a23b8c9a0867dd71726f7634d5a84f38a8b0cf16b54031e` |

最终收尾时，板端 `followingcar-frpc`、`followingcar-video-gateway` 均为 active，控制空闲；原 `rgbd-mediamtx.service`、`rgbd-video.service` 均为 inactive。独立网关仅按需读取已有源。

两次有时限的合成源只通过记录的 runner PID 请求退出，分别回收自己的 MediaMTX 与 ffmpeg。已检查 PID `65987/65999/66009/67430/67442/67453` 全部不存在。没有使用全局 `pkill`，没有停止原视频或跟随程序。临时 gateway `info` 日志恢复为 `warn`；临时 nginx 安全诊断日志也已恢复原配置。没有保留后台测试发布源。

服务器已部署移除自动重启路径的最终维护脚本。修复期间只暂时停止维护 timer，未重启 frps/Coturn/nginx；随后删除 `/etc/followingcar-relay/maintenance.hold`，执行状态检查并恢复 timer。`followingcar-frps`、`followingcar-nginx`、`followingcar-turn` 与仅报告状态的维护 timer 最终均为 active。

本轮板端临时私密文件 `/tmp/followingcar-video-deploy-TtBMtKzT/board-001.boardremote`、`/tmp/followingcar-relay-validation-zzmre7b9/turn-test.json` 已删除。正式 `/etc/followingcar-relay/` 配置和电脑受保护的手机导入包仍保留，便于后续实体手机接入；没有将任何秘密写入版本库。

## 本地证据索引

以下是本次维护电脑的本地文件，未将含身份材料的目录上传 Git。视频截图仅包含合成测试图案。

| 证据 | 位置 |
|---|---|
| QUIC echo 原始数值 | `E:\Temp\remote-relay-quic-echo-validation.json` |
| TCP echo 原始数值 | `E:\Temp\remote-relay-tcp-echo-validation.json` |
| 独立跟随 runId/ACK/退出码 | `E:\Temp\remote-relay-quic-follow-validation.json` |
| QUIC 手动失败报告 | `E:\Temp\remote-relay-quic-healthy-test.txt` |
| TCP 手动失败报告 | `E:\Temp\remote-relay-tcp-healthy-test.txt` |
| 视频稳态测试报告 | `E:\Temp\followingcar-remote-video-steady-20261029.log` |
| 视频数值记录（p95 限制见上文） | `E:\Temp\followingcar-remote-video-steady-stats-20261029.log` |
| 最终视频截图 | `E:\Temp\followingcar-remote-video-steady-success-20261029.png` |
| 合成源退出记录 | `E:\Temp\followingcar-remote-video-synthetic-20261029.log`、`E:\Temp\followingcar-remote-video-synthetic-fixed-20261029.log` |
| frpc 中断 App 报告 | `E:\Temp\remote-relay-fault-validation.json` |
| frpc 中断板端时间线 | `E:\Temp\remote-relay-fault-board.json` |
| frpc 数据黑洞 App 报告 | `E:\Temp\remote-relay-blackhole-validation.json` |
| frpc 数据黑洞板端时间线 | `E:\Temp\remote-relay-blackhole-board.json` |
| 真实远程连接 UI 截图 | `app/build/reports/remote-relay/remote-connection-ui.png` |

复现入口及安装/回滚命令见 [relay/README.md](../relay/README.md)。下一阶段需要在实际手机网络与更近的中转节点复测，继续保留现有停车保护；本记录没有给出实车远程驾驶验收结论。
