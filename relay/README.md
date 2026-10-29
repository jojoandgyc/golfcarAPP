# FollowingCar 远程中转

此目录提供独立于电机、自动跟随和视频采集程序的网络中转。当前开发节点为用户选定的海外服务器 `43.162.101.137`，域名 `car.nujabes.top`；不把它标为此前尚未确认公网入口的内地 `10.4.4.13`。

2026-10-30 已完成 [完整电机接口的中转兼容性检查](../docs/relay-motor-compatibility-2026-10-30.md)：现有运行协议无需升级，服务器旧安装源／测试副本已备份同步，未重启服务。检查时小车隧道未注册，端到端验证仍待恢复联网。

## 链路与入口

| 流量 | 路径 |
|---|---|
| 控制、配置、日志、终端 | App 内 JSch → TLS 客户端证书认证 `2443/TCP` → nginx → frps 回环 `22022` → QUIC → frpc → 板端 SSH `22` |
| 播放器和 WHEP 信令 | App WebView → HTTPS 双向认证 `8443/TCP` → `/player/` 或 `/rgbd/whep` → frps 回环 `18889` → frpc → 独立视频网关 `19889` |
| RGB/深度合成视频 | WebRTC → 同一服务器 Coturn → 板端 MediaMTX；优先 UDP，不转码，不经过 SSH/frp 字节流 |

服务器新增 `7443/TCP+UDP` 供板端 frpc，Coturn 使用 `3478/TCP+UDP`、`5349/TCP` 和 `62000–62099/UDP`。TURN 端口段位于服务器当前临时端口范围之外。现有 VPN 的 `443/UDP`、SSH、防火墙、默认路由保持原样。`22022`、`18889` 只绑定 `127.0.0.1`，不能直接从公网登录板端默认密码。

frp 固定 **0.71.1**，两个架构的官方发布文件 SHA-256 写在 `configuration.py`。下载后先校验摘要，再提取唯一目标可执行文件。板端内核没有 TUN/WireGuard，本方案不创建 VPN 网卡，也不修改 Wi-Fi。

## 安装与凭据

只部署自己命名的 `followingcar-frps`、`followingcar-nginx`、`followingcar-turn` 和板端 `followingcar-frpc` systemd 服务，统一使用无登录权限的专用非 root 用户。所有秘密位于 `/etc/followingcar-relay/`，Git 忽略私钥、P12 和导入包。

服务器依赖 Ubuntu 的 `nginx`、`libnginx-mod-stream`、`coturn`、`openssl`、Python 3。首次安装前检查原厂 nginx/coturn 服务确实不存在，才 mask 这两个原厂服务以阻止软件包自动开放默认端口；不能停掉已经在用的服务。安装时 `NEEDRESTART_MODE=l`，避免包管理器重启其他业务。

服务器先取得 `car.nujabes.top` 的合法公开 TLS 证书，保存在 `/etc/letsencrypt/live/car.nujabes.top/`，再执行：

```sh
python3 relay/provision.py prepare-server
python3 relay/provision.py issue-client --name phone-001
python3 relay/provision.py issue-client --name emu-001
python3 relay/provision.py export-board
python3 relay/provision.py activate-server
```

`prepare-server` 不启动服务；`activate-server` 先验证真实 frps/nginx 配置，再启用并启动独立服务。启动使用 `start` 而非 `restart`，不会为重复执行安装主动关闭已存在的连接。

每台手机单独颁发 clientAuth 证书，不共享模拟器身份。导入包路径为 `/etc/followingcar-relay/clients/<身份>/<身份>.carremote`，权限 `0600`。内容合同如下，秘密字段不能写入日志、版本库或聊天：

```json
{
  "schemaVersion": 1,
  "host": "car.nujabes.top",
  "controlPort": 2443,
  "videoPort": 8443,
  "clientPkcs12Base64": "<私有 P12 的 Base64>",
  "clientPkcs12Password": "<独立随机口令>"
}
```

导入包小于 64 KiB，App 导入 Android Keystore 后应删除手机共享目录里的导入文件。电脑中转目录也必须只允许当前用户访问。服务器 root 密码和 frp/TURN 密钥不进入 App。

将生成的 `board-001.boardremote` 通过现有受信任 SSH 放到板端权限 `0700` 的临时目录，与 `configuration.py`、`provision.py`、`install_board.py`、`transport_supervisor.py` 一起运行：

```sh
sudo -n python3 install_board.py --bundle board-001.boardremote --activate
```

安装器只部署隧道，输出 `motorAccess=false`、`videoConfigChanged=false`、`networkRoutesChanged=false`。TURN 配置仅供独立 `followingcar-video-gateway` 使用，该网关在有人观看时拉取原来的板内 RTSP `127.0.0.1:8554/rgbd`，不转码、不直接打开相机。原 MediaMTX/采集程序与 YAML 不改变；原视频停用后网关没有可播放的源，也不会替 App 擅自启动采集。

## 重连、回退和维护

板端默认 QUIC。supervisor 只读取 `127.0.0.1:17400` 的带认证 frpc 状态，启动 10 秒仍未注册任何本车代理、且 API 返回有效空注册状态时，终止自己的 frpc 子进程并改用 TLS/TCP `7443`。TCP 选择在本次开机期间保持；不会自动抢回 QUIC。

任何本车代理一旦注册成功，本服务生命周期不再切换传输；API 不可读也不会被误判为 UDP 不通。此后断线由 frpc 自身重连，App 与板端既有 ACK/watchdog 负责会话终止，supervisor 不接触或重放控制命令。测试需要恢复 QUIC时，必须先确认控制空闲，再清理 `/var/lib/followingcar-relay/transport-selection.json` 并重启独立隧道。不要把 TLS/TCP 回退描述为能穿过所有网络策略，`2443/7443/8443` 仍可能被某些网络封锁。

本目录提供服务器证书续期钩子，只对 `car.nujabes.top` 生效。证书更新后 nginx 通过 HUP 无损更新新连接证书，并写入 `certificate-maintenance-pending.json`。独立维护 timer 每 5 分钟只检查并报告状态：忙时 `busy_deferred`，观察到空闲时 `manual_maintenance_required`，均保留 pending。**自动任务绝不重启 frps/Coturn**：即使刚检查为空闲，也可能马上有新客户端接入，不能据此安全地自动重启。

frps/Coturn 使用新证书需要单独安排明确的维护窗口：先结束全部控制和视频、断开所有 App，确保维护期间不会有人重新接入，再由管理员显式执行 `systemctl try-restart followingcar-frps.service followingcar-turn.service`。核对新证书、代理恢复和无控制自动启动后，再清除对应的 pending 标记。当前证书到期日为 2027-01-27；应在到期前完成这一维护，不能把 nginx 自动更新等同于所有后端都已更新。创建 `/etc/followingcar-relay/maintenance.hold` 可暂停维护状态检查。客户端证书有效期 365 天，需在到期前另行更新；不得通过关闭 TLS 校验规避过期。

停止远程功能只需停用上述独立服务。原局域网 SSH、手动控制、跟随脚本不依赖中转。保留 `/etc/followingcar-relay/backups/` 中部署前配置，以便恢复独立服务；不要恢复或覆盖电机寄存器。

## 验证

```sh
python3 -m unittest discover -s relay -p 'test_*.py' -v
node --test relay/web/whep.test.cjs
```

验证匿名 HTTPS 被拒绝、正确客户端证书通过、错误客户端/服务器证书拒绝、frps 后端仅回环监听、板端固定 SSH 公钥仍匹配；实际控制测试仅使用 dryRun。

部署正式独立视频网关后，模拟视频测试使用 `tests/synthetic_video_board.py --upstream-only --duration 600`。它先确认控制空闲、原视频服务未运行和 `8554` 端口空闲，再启动临时 RTSP 与合成编码源；正式网关仍在 `19889`，因此测试覆盖实际的网关、WHEP 和 TURN 路径。脚本有时限，退出只清理自己创建的进程，不调用原相机或电机。TCP 回退策略测试不依赖真实电机，覆盖初始注册超时、API 故障、部分注册和保持 TCP。

Android 模拟器需要单独导入测试设备身份，不能在 APK 内放真实私钥。相关联调测试均为显式 opt-in；手动和跟随桥接仅使用 `dryRun=true`。`RemoteRelayFaultIntegrationTest` 在发布私有 ready barrier 后，由宿主机只中断专用 `followingcar-frpc`，随后恢复该服务。不能停止板端 SSH、Wi-Fi 或跟随服务来代替这一注入。

## 当前海外线路限制

2026-10-29 的模拟器测试经 Windows 当前 Mihomo/Fake-IP 网络路径（域名在模拟器解析为 `198.18.0.77`）。QUIC 只读 echo 的 12 个样本 p50 为 361ms、p95 为 774ms；TCP 对照 p50 为 354ms、p95 为 1329ms。保留 QUIC 首选，不能用中位数掩盖超时尾部，也不能将这组样本当作手机移动网络的表现。

自动跟随独立模拟会话的 5 次心跳 ACK 为 361–368ms，并以 `app_stop_requested`、退出码 0 正常结束。两种传输的手动模拟均在首个有效 ACK 前触发 600ms 看门狗，目前不具备这条测试线路的手动驾驶验收结论。App 已改为收到首条零速 ACK 后才解锁输入；这能避免把陈旧 READY 显示为就绪，但不能消除物理网络延迟。后续应在实际手机网络及更近的中转节点重新验证。

计量使用端到端控制 ACK 的 p50/p95/p99 和连续回执间隔，视频用 WebRTC candidate 类型、RTT、解码帧数及停帧情况。不得用不同设备的单调时钟相减宣称端到端单向延迟，也不能用放宽 600ms/2s 停车保护来隐藏网络问题。

依据：[frp v0.71.1 发布](https://github.com/fatedier/frp/releases/tag/v0.71.1)、[frp 端口及 TLS 配置](https://gofrp.org/en/docs/reference/server-configures/)、[nginx stream 客户端证书校验](https://nginx.org/en/docs/stream/ngx_stream_ssl_module.html)、[MediaMTX WebRTC/TURN](https://mediamtx.org/docs/features/webrtc-specific-features)。MediaMTX v1.21.3 的 `AUTH_SECRET` 临时 TURN 凭据实际有效期为 24 小时；这里没有声称缩短成几分钟。
