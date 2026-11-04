# 局域网地址恢复

针对同一 IPv4 被其他 MAC 回应的故障，地址分配改为 DHCP，App 连接时按小车身份寻找当前地址。发现记录、IP 和 MAC 都不能替代 SSH 主机公钥认证。

连接顺序：

1. 尝试上次地址，单次最多 4 秒。密码错误停止恢复，错误主机公钥只拒绝该候选地址。
2. 在现有 Wi-Fi 网络中进行最多 6 秒的 `_followingcar._tcp` DNS-SD 发现，最多尝试 6 个去重地址，优先 IPv4。广播 TXT 中的设备编号和公钥指纹仅用于筛选；每个连接仍核验内置 SSH 公钥。
3. 如果只发现 IPv6，先认证小车，再用固定只读命令查询 `wlan0` 当前 IPv4。仅尝试手机当前 Wi-Fi 前缀内的地址，再次核验 SSH 公钥；IPv4 失败则恢复已验证的 IPv6 连接。
4. 局域网候选均失败时，尝试已配置的公网入口一次。界面显示实际使用的地址和路由；取消会停止后续尝试。

每个局域网 socket 绑定发现它的 Wi-Fi Network，避免无互联网 Wi-Fi 被默认蜂窝或 VPN 路由替代。配对热点与 Wi-Fi Direct 保留原有的组路由和确认流程。App 不扫描整个 IP 网段，不更换手机 Wi-Fi，不写死 ARP 表，不接受未知主机公钥。

恢复仅建立连接，之后保留原有状态读取与控制占用检查。手动控制、自动跟随、停车设置、终端命令均不会自动重放；未确认的控制退出和首次故障记录不会被清除。IPv6 救援连接的 WebView 视频使用已配置的公网视频路径；恢复 IPv4 后视频恢复原有局域网路径。

板端只增加 `/etc/avahi/services/followingcar-rk-140761.service`，复用已有 Avahi 服务并 reload。服务公开设备编号、SSH 公钥指纹及端口，不包含登录凭据。

`board/app_lan_recovery.py` 是本车一次性地址迁移工具，部署在 `/opt/followingcar-network/`。默认 `--plan` 只读；`--launch-dhcp --request-id UUID` 启动独立 systemd 任务。实际迁移全程持有现有两把控制锁，先将目标 profile 原文件备份到 `/var/lib/followingcar-network/UUID/`，再修改该 UUID 的 IPv4 自动分配、原手填地址与网关、地址冲突检测，以及固定现有永久 MAC。SSID、密码、DNS、IPv6 和其他 profile 保持原值。有效 DHCP 租约与服务器两次观察一致后才提交，失败恢复原文件；无法确认回滚时明确记录，拒绝再次迁移。

当前 Wi-Fi profile 已从固定 `.82` 迁移到 DHCP，并启用 3 秒地址冲突检测。IP 仍可能随路由器租约变化，App 不依赖固定 `.116`。如果 AP 屏蔽发现、IPv6 和公网均不可用，App 会报告未连接并保留停止状态；兜底机制不能替代可用的网络路径。
