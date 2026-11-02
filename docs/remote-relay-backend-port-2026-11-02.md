# 海外中转后端端口冲突与恢复（2026-11-02）

## 现场与原因

海外服务器 `43.162.101.137` 的视频播放协商由 Nginx `8443` 转给服务器本机 frps `18889`，再到开发板视频网关 `127.0.0.1:19889`。`18889` 是动态注册的代理端口，服务器上没有需要额外安装的常驻 WHEP 程序。独立视频网关已在开发板运行。

检查时，`18889` 暂无监听，原小车控制代理 `22022` 被 `codex-mobile-relay` 的 SSH 反向转发占用。该账户的现有密钥明确允许在 `127.0.0.1:22022` 建立转发，故两套服务的端口规划发生冲突。frps 日志反复出现 `followingcar.board-ssh` 的 `port unavailable`；板端 frpc 日志同时出现 DNS 超时及代理注册失败。稍后两个小车代理自动重新注册，但不改端口仍可能再次发生冲突。

## 修复

在服务器的 `/etc/followingcar-relay/settings.json` 设置 `ssh_backend_port=22024`，对应更新 frps `allowPorts`、Nginx 控制后端和开发板的 QUIC/TCP frpc 配置。视频代理仍使用服务器 `18889`、板端 `19889`。App 对外入口仍是 `car.nujabes.top:2443` 与 `:8443`，播放器路径仍是 `/player/` 和 `/rgbd/whep`。`codex-mobile-relay` 保留 `22022`；国内独立中转的容器内端口没有调整。

源码 `relay/configuration.py` 增加可校验的 `ssh_backend_port` 配置项；服务器的 `tools/`、`source/` 及板端的 `tools/` 安装源已同步。服务器重新导出 `board-001.boardremote`，将来的板端重装会沿用 `22024`。未改板端自动跟随、电机配置、Wi-Fi 和视频采集程序。

变更前备份：

- 服务器：`/etc/followingcar-relay/backups/20261102T125147Z-backend-port/`
- 开发板：`/etc/followingcar-relay/backups/20261102T125215Z-backend-port/`

板端控制占用锁检查为空闲、服务器 `2443/8443` 无已建立 App 会话后，先校验新 frps、frpc 与 Nginx 配置，再重启独立 `followingcar-frps`、`followingcar-frpc`，最后向独立 Nginx 主进程发送 HUP 载入新后端。此过程未重启 Coturn、原视频服务或车辆控制程序。

## 验证与边界

- `relay` 的 23 项 Python 配置及回退测试通过；新测试确认 `22024` 同时进入 frps 允许端口、板端 frpc 和 Nginx 路由，并拒绝与公开监听端口重叠。
- 服务器 `22024` 与 `18889` 当前均由 frps 在 `127.0.0.1` 监听；`22022` 由另一套 SSH 转发监听，端口已分离。frps、Nginx、Coturn 与板端 frpc、独立视频网关均为 active。
- 从服务器经 `22024` 读取到的 SSH ED25519 主机指纹，与直接读取开发板公钥的指纹一致。
- 使用已有模拟器客户端证书通过服务器 mTLS 入口测试：`2443` 返回开发板 SSH banner，`8443/player/` 返回 200，`8443/rgbd/whep` 的 OPTIONS 返回 204。开发板网关本机 WHEP GET 返回预期的 405。
- 检查结束时原 `rgbd-video`、`rgbd-mediamtx` 仍未启动，控制占用检查仍为空闲。本轮验证了播放协商通路，未启用摄像头，也未验证真实画面解码或实车控制。

App 使用的公网端口、域名、客户端证书及 URL 没有变化，因此不需要修改或重装手机 App。原视频生产服务关闭时，即使协商通路正常，播放器也没有实际摄像头画面。
