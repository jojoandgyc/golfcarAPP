# 国内中转的独立板端副本

这套配置只新增 `followingcar-cn-frpc.service` 和
`followingcar-cn-video-gateway.service`。程序、凭据和配置分别放在
`/opt/followingcar-cn/`、`/etc/followingcar-cn/`；不会覆盖现有
`followingcar-frpc.service`、`followingcar-video-gateway.service`、跟随脚本、
视频生产服务或 Wi-Fi 配置。安装脚本默认只准备文件，`--activate` 才启动新服务。

输入是国内服务器生成的 `board-001.boardremote`，schema 1，必须由 root 持有且权限
`0600`。不要将它加入 Git。安装脚本会核对既有 frpc、MediaMTX 二进制 SHA-256，
复制到新目录；不修改既有程序。新 frpc 首选 QUIC，服务器地址
`world-agents.com:7443`，通过系统 CA 校验服务器证书；新服务不复用旧 frpc 的
`17400` 管理端口或 transport supervisor 状态文件。

新视频网关在板内监听 `127.0.0.1:19890`（WHEP）及 `18190/UDP`（ICE），
按需读取已编码的 `rtsp://127.0.0.1:8554/rgbd`。它不会启动摄像头。
新 TURN 认证使用国内服务器 bundle 中独立的 `turnAuthSecret`，播放器来源限制为
`https://world-agents.com:9443`。新 frpc 仅代理本机 SSH `22` 和新网关
`19890`，远端代理端口 `22022` 与 `18889` 只在国内 frps 容器网络中使用。

新 frpc 的本地状态接口只监听 `127.0.0.1:17401`，使用由独立 token 派生的
管理密码。独立 supervisor 默认 QUIC：启动后满 10 秒、认证接口明确确认两条
代理均未注册时，才切换到 TLS/TCP。任一代理曾注册，就在本次服务生命周期
保持当前传输；状态接口错误不会被误判为“空注册”。同一次开机的回退记录仅写入
`/var/lib/followingcar-cn/transport-selection.json`，不触碰海外 frpc 的状态。

在开发板上，把 bundle 以私有权限放到新命名空间之外的暂存路径，检查控制空闲，
然后执行：

```sh
sudo python3 /path/to/relay/domestic/board/install.py --bundle /path/to/board-001.boardremote
sudo python3 /path/to/relay/domestic/board/install.py --bundle /path/to/board-001.boardremote --activate
```

首次执行只新增账户、目录、配置和 unit，并验证两个 frpc 配置及 systemd unit。
激活前再次检查两个新端口未被占用；激活时通过现有只读控制占用检查器核实
电机及控制锁空闲。激活顺序是新视频网关、新 frpc。若激活失败，只回滚新服务。
当前手动视频生产服务关闭时，`8554` 不监听是正常状态；网关仍可等待下一次播放。

已部署过“直接启动 QUIC frpc”旧版的开发板，使用专用一次性升级脚本。
它先检查现有国内 frpc 的 unit 和两份配置与首版完全一致，检查控制会话空闲，
随后只短暂停止 `followingcar-cn-frpc`，写入私有管理端口和 supervisor，
再启动该新服务。异常时恢复原配置并尝试恢复旧服务；既有海外 frpc 和两个视频
服务均不在操作范围内。请在计划好的控制空闲窗口执行：

```sh
sudo python3 /path/to/relay/domestic/board/upgrade_supervisor.py --check
sudo python3 /path/to/relay/domestic/board/upgrade_supervisor.py
systemctl is-active followingcar-cn-frpc.service
journalctl -u followingcar-cn-frpc.service -n 30 --no-pager
```

`--check` 只读。现在已注册成功的国内 QUIC 会话不需要为了升级而立即中断。
升级后，日志中的 `cn_relay.registration` 可确认是否已注册；它不代表车辆
控制会话已自动恢复。

如需撤回本次并行连接，仅处理这两个新服务：

```sh
sudo systemctl disable --now followingcar-cn-frpc.service followingcar-cn-video-gateway.service
```

本地不接触开发板的配置验证：

```sh
python3 -m unittest discover -s relay/domestic/board -p 'test_*.py'
```
