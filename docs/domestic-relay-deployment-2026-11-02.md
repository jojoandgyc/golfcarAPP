# 国内中转部署记录（2026-11-02）

国内入口为 `world-agents.com`（`117.50.34.249`），与原海外入口
`car.nujabes.top`（`43.162.101.137`）并行保留。本轮已完成国内容器部署、
开发板独立隧道注册、外网 mTLS SSH 连接和合成视频播放验证；尚无手机端
验收结果，没有运行真实摄像头或实车电机测试。

## 国内服务器

部署目录为 `/data/golfcar/`，Compose 项目名 `followingcar-domestic`。
只新增 `frps`、`nginx`、`turn` 三个容器，不替换宿主原服务或海外服务。
现场三个容器均运行，检查时重启计数均为 0，服务器原服务保持运行。

| 组件 | 公网端口 | 配置与程序位置 |
|---|---|---|
| Nginx mTLS 控制入口 | `2443/TCP` | `/data/golfcar/config/nginx.conf`；镜像 `nginx:1.27-alpine` |
| 同一 Nginx 的播放器与 WHEP | `9443/TCP` | `/data/golfcar/web/`；入口 `/player/`、`/rgbd/whep` |
| frps | `7443/TCP+UDP` | `/data/golfcar/config/frps.toml`、`/data/golfcar/bin/frps`；版本 `0.71.1` |
| Coturn | `3478/TCP+UDP`、`5349/TCP`、`62000–62099/UDP` | `/data/golfcar/config/coturn.conf`；基于 Alpine 3.20 构建 |

宿主已有的 `8443` 不参与本部署。专属 Docker bridge 为
`172.28.240.0/24`：Coturn `.10`、frps `.11`、Nginx `.12`。
frps 的 `22022`（SSH）与 `18889`（WHEP）仅绑定容器地址
`172.28.240.11`，不映射宿主端口。它们与海外服务器的后端端口互不影响。
Docker 发布公网端口会增加自身转发规则；这里的隔离不表示未产生 Docker
网络规则。

容器使用非 root UID、只读根文件系统、无额外 capability、资源上限和
`json-file` 轮转日志（每容器 `5 MB × 3`）。`secrets/` 仅按需只读挂载；
`private/` 保存独立身份材料，不挂载给容器，不进入构建上下文或 Git。
查看状态与日志使用 `/data/golfcar/` 下的 `docker compose ps` 和
`docker compose logs --tail=100 frps nginx turn`。

## 开发板独立实例

新增服务为 `followingcar-cn-frpc.service` 和
`followingcar-cn-video-gateway.service`，均已运行。程序位于
`/opt/followingcar-cn/`，配置位于 `/etc/followingcar-cn/`。
原海外 frpc、视频网关继续保留，未覆盖跟随程序、视频生产服务、Wi-Fi
配置或已调好的电机参数。

- 国内 frpc 连接 `world-agents.com:7443`，只代理板内 SSH `22` 和新视频
  网关 WHEP `127.0.0.1:19890`。网关 ICE 端口为 `18190/UDP`。
- 网关按需拉取 `rtsp://127.0.0.1:8554/rgbd` 已编码画面，不转码、不自行
  启动摄像头；WebRTC 媒体经国内 Coturn，控制与信令经 frp。
- 独立 supervisor 升级已应用；现场日志确认 QUIC 启动以及
  `board-cn-ssh`、`board-cn-whep` 两条代理注册。
- 初始注册 10 秒仍为空且认证状态接口明确确认时，才回退 TLS/TCP；
  任一代理曾注册后，本次服务生命周期不再主动切换。状态接口错误不等同
  空注册。管理接口为 `127.0.0.1:17401`，回退状态只写入
  `/var/lib/followingcar-cn/transport-selection.json`。

中转恢复不重放控制命令或自动恢复驾驶；现有心跳、ACK 超时和停车规则
保持原值。

## 证书与身份

当前服务器证书包含 `DNS:world-agents.com`，到期时间为
**2027-01-06 14:25:15 UTC**（北京时间 2027-01-06 22:25:15）。
证书来源为 `/www/server/panel/vhost/cert/world-agents.com/`，容器读取
`/data/golfcar/secrets/tls/` 中的副本。本次对当前证书执行
`provision.py refresh-cert` 返回无需更新；这不是一次实际续期验收。

宝塔续期后需显式运行 `relay/domestic/provision.py refresh-cert`。
脚本校验证书域名、有效期与公私钥匹配，备份旧证书到
`/data/golfcar/private/cert-backups/<UTC时间>/`，再替换运行副本；不会
自动重载或重启容器。随后应在控制和视频均空闲、维护期间无新会话接入的
窗口，检查配置、HUP Nginx，并重启本项目 frps/Coturn 以加载新证书。
详细步骤见[国内容器维护说明](../relay/domestic/README.md)。

App 与开发板采用国内节点独立签发的身份，导入合同沿用 schema 1，视频
端口为 `9443`。本记录不包含口令、令牌、私钥或导入包内容。

## 本轮验证与限制

| 验证 | 实际结果 |
|---|---|
| 容器与隧道 | 三个国内容器运行；开发板两个新增服务运行；两条 QUIC 代理已注册 |
| 控制连接 | 经外网 mTLS 入口成功建立到开发板的 SSH 连接 |
| 手机连接与配置 | Samsung 真机导入国内配置后，经 Wi-Fi 成功连接并读取车端静态配置；关闭 Wi-Fi 后使用无 VPN 的移动网络再次连接成功，随后恢复 Wi-Fi |
| 手动控制模拟 ACK | 手机在 Wi-Fi 下经国内 mTLS／frp QUIC／SSH 到板端 dry-run 桥接，15 次请求到回执 RTT：P50 135 ms、P95 191 ms；未打开电机串口 |
| 跟随模拟 ACK | 同一路径、`dryRun=true` 且 `execute=false`，两轮各 5 次请求到回执 RTT：P50 分别为 128／136 ms，P95 分别为 135／148 ms；板端正常停止、退出码 0 |
| 合成视频 | 开发板发布合成源，电脑 Chrome 经外网 `9443` 和 WebRTC relay 播放；1280×480，10 秒内解码帧由 44 增至 224（180 帧，约 18 fps） |
| 手机合成视频 | 真机 App 在 Wi-Fi 下的独立接收测试通过；30.34 秒内解码 601 帧（19.81 fps），TURN relay，UDP/UDP/UDP，视频 RTT 样本 P50 为 101 ms、P95 为 114 ms；测试未调用视频服务控制 |
| 清理 | 合成源已停止；未启动真实摄像头或电机 |
| 证书刷新 | 当前证书无变化时幂等返回，无自动重启 |

合成视频验证证明本次浏览器到国内节点的信令和媒体链路可用；帧率不等于
摄像头到屏幕的时延，也不是带宽测量。手机测试的 RTT 是 WebRTC 连接统计，
不是摄像头到屏幕的端到端延迟。控制 ACK 的样本窗口较短，且使用模拟桥接，
不能保证长期抖动上限或代表实际电机响应。尚未完成真实摄像头视频持续测试、
画面采集到手机显示的延迟测量或实车驾驶验收。

手机视频首次测试期间自动锁屏且限时合成源到期，显示请求超时；恢复亮屏并
重新启动限时合成源后，独立复测通过。两次测试均未打开摄像头或电机，结束
后原视频服务保持 inactive，RTSP 8554 已关闭。

安装、回退与源码入口：[国内容器](../relay/domestic/README.md)、
[开发板独立实例](../relay/domestic/board/README.md)。撤回时仅停止本项目
容器和两个 `followingcar-cn-*` 服务，保留原海外与局域网路径。
