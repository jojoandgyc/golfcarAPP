# 手机管理 Wi-Fi Direct 组，开发板加入

> 以下保留 1.4 手机 GO 方案的初始部署记录。随后首次实际入组尝试在创建 profile 阶段遇到 NetworkManager 1.36.6 的 UUID 修改限制，尚未切换网络。修复已纳入仓库 helper 及独立部署的 1.5 蓝牙配网服务；当前自动配网使用普通本地热点，见 [最新部署与实机结果](pairing-deployment-2026-10-29.md)。原 1.4 路径的历史副本不作为新配网服务入口。

本文对应新增的 `board/app_wifi_direct_join.py`。2026-10-29 已独立部署到 `/home/topeet/.local/share/followingcar/app_wifi_direct_join_1_4.py`，SHA-256 为 `89c62195636ecf92f4a62d64d34f2b6a3b446e536e948cd517735df8d22f67a2`；`--check` 已确认控制空闲。尚未执行实际入组，现有网络仍为 `172.16.16.82`，原 profile UUID 为 `2f73c1af-5e3c-42f5-8093-e0a7f656e37f`。部署未修改正在使用的控制桥接脚本、驱动或运行参数。

## 已确认的板端条件

2026-10-29 的只读检查结果：

- 当前 SSH 地址为 `172.16.16.82`，使用 `wlan0` 连接现有热点；这不是加入手机 GO 后的固定地址。
- 网卡为 Realtek `rtl8723du`，支持 2.4 GHz，不支持 5 GHz。NetworkManager 版本为 1.36.6，wpa_supplicant 为 2.10，两者正在管理当前 STA 连接。
- 驱动通过 nl80211 声明 `STATION`、`AP`、`P2P_CLIENT`、`P2P_GO`，但未声明可并行的接口组合；驱动配置明确为 `CONFIG_IFACE_NUMBER = 1`。
- `p2p-dev-wlan0` 是 NetworkManager 中未托管的占位设备，不能据此认为已经有可用的并行 P2P 接口。板端未安装 `iw`，上述模式通过只读 `NL80211_CMD_GET_WIPHY` 查询取得。
- 初次只读检查时，当前运行目录与手动控制脚本目录未发现现成的 P2P 入组 helper；随后按本文开头的独立路径部署。本次板端检查没有执行扫描、发现、建组、入组或任何服务重启。

因此，本方案采用**手机创建并管理 Wi-Fi Direct GO，开发板作为普通 WPA2 Wi-Fi 客户端加入该 GO 的标准 SSID**。这是 Wi-Fi Direct 组的兼容客户端接入，不是板端同时运行 STA 和原生 P2P，也不依赖修改驱动。手机显示的组必须使用 **2.4 GHz**。

切换这张网卡会替换现有 STA 连接，当前 SSH 可能随即中断。要保持原管理网络同时入组，需要另外的网卡或有线管理连接；本 helper 不声称单接口能无缝并行。

## 默认检查与实际入组

部署脚本后，默认执行和 `--check`、`--status` 均只读：不扫描、不创建连接、不读取凭据、不写结果文件。使用 root 检查才能完整判断其他用户的控制进程与串口占用；普通用户仍可取得网络信息，但会明确报告控制占用未能完整确认。

```bash
sudo -n python3 /home/topeet/.local/share/followingcar/app_wifi_direct_join_1_4.py --check
```

结果包含当前网络、驱动接口数量、不能并行的原因和控制是否确认空闲。检查采用以下顺序：

1. 确认两把现有锁文件存在且是普通文件，再调用固定运行目录的 `app_follow_bridge.py --inspect`。
2. 按既有顺序取得 `.app_joystick.lock` 和 `.follow_session.lock`，再次检查实际 bridge、worker、continuous follow、手动及已知电机／IMU 控制进程。
3. 核对 `/dev/ttyS0` 及存在时的 `/dev/ttyS3` 实际占用。检查失败、权限不足、锁不存在、控制忙或状态无法确认时，一律拒绝切换。现有网络处于连接中等过渡状态，或显示已连接却缺失 profile UUID，也不进行切换。

真正入组必须显式指定 `--join`。这个动作表示用户要求切换无线连接，不会再添加一次交互式审批。SSID 和 PSK 从 stdin JSON 输入；helper 不接受凭据命令行参数，也不会输出 PSK。下例仅包含占位值，使用时替换为手机 GO 页面提供的值；实际 App 应把 JSON 直接写入 SSH stdin，不拼接进 shell 命令或历史记录。

```bash
sudo -n python3 /home/topeet/.local/share/followingcar/app_wifi_direct_join_1_4.py --join <<'JSON'
{"ssid":"DIRECT-Ab-FollowingCar","psk":"REPLACE_WITH_GO_PASSWORD"}
JSON
```

仅接受 `DIRECT-` 加两个英数字的组名，SSID 最长 32 个 UTF-8 字节。PSK 必须为 8–63 个 ASCII 可打印字符或 64 位十六进制密钥，不接受换行与额外 JSON 字段。接口固定为 `wlan0`，发现额外 Wi-Fi 接口或设备不符合预期时拒绝执行。

本轮接入限定 ASCII GO 名称，App 随机生成的组名满足此条件。helper 子进程使用 `LC_ALL=C`，中文 SSID 可能显示为 `???`；输入校验虽接受 Unicode，后续验证失败仍按未知结果拒绝／清理，不会报告成功。非 ASCII 支持需后续使用 `C.UTF-8` 实际验证。

入组期间持续持有两把控制锁，防止检查之后再启动遵守同一锁协议的控制会话。脚本仅创建一个独立 UUID 的 NetworkManager profile，设定 `autoconnect=false`、2.4 GHz 和 WPA2。PSK 通过 `/run` 下权限为 `0600` 的临时 `passwd-file` 交给 nmcli，不进入 nmcli argv；profile 使用 `psk-flags=2`，不持久保存 PSK。临时密码文件在正常结束及可捕获失败时删除。

不会直接操作 wpa_supplicant、停止任何控制进程、关闭其他设备、重启服务、删除既有 profile，或更改既有 profile 的 autoconnect 设置。失败清理仅针对本次新生成且事先确认不存在的 UUID，并再次比对专属 profile 名、接口和 SSID；添加结果不确定或 UUID 被其他配置占用时，不直接删除。仅对已确认属于本次的 profile 停用／删除，随后在持锁期间进行有界轮询，确认本次 UUID 不再活动且设备退出过渡状态。清理无法确认时明确报告，供人工核对，不把 delete 命令成功等同于网络切换已结束。

## 手机到 SSH 的使用顺序

1. 手机 App 创建并保持 Wi-Fi Direct GO，选择 2.4 GHz，显示该组的 SSID 和密码。
2. 在开发板仍可管理且控制已空闲时执行 `--check`，随后明确执行 `--join`。运行中的跟随或手动控制会阻止切换。
3. 等待板端取得组内 IPv4 地址。手机使用系统报告的当前成员 IP，或由用户手填板端新地址。手填地址必须位于 GO 接口真实 IPv4 前缀内，且不能是手机自身、网络地址或广播地址；旧局域网地址 `172.16.16.82` 不会因为手机已建组就被当作 Direct 地址接受。App 从组对应的实际网络接口读取前缀，读取失败时不猜 `/24`，只允许系统已确认的成员 IP。
4. 使用新地址连接 SSH。重新连接不会自动启动跟随；仍须完成控制占用恢复并由用户手动启动。

普通 Wi-Fi 客户端不保证出现在 Android 原生 P2P peer 发现列表中。手机侧应保留标准 SSID 入组说明和手填板端 IP 入口；成员列表中的 MAC 地址也不能直接当作 IP 使用。

App 只在 SSH 断开且控制空闲时发出无线变更。系统接受请求不代表动作已完成；已发出的操作超时、发生结果未知的异常或确认过程失效时，保留未确认标记，即使页面重建也不会直接解除。此时必须明确点击“刷新状态”，取得新的组／连接／无线状态及发现状态后，才允许新的无线操作或 SSH 连接；成员发现列表读取失败不影响已确认的组，生命周期和广播刷新不解除标记。

Direct 连接绑定其已确认的手机组。组丢失、替换或权限失效时，App 记录首因，请求停止跟随／停用手动控制并断开 SSH；正在建立的 Direct 连接及其重试也会取消，每次连接前后都会核对原组。没有终止回执时保持原控制会话的未确认锁；恢复无线组不自动连接 SSH、重建控制会话或启动行驶。

## SSH 中断、结果记录与恢复

本 helper **不自动后台化，也不启动 systemd transient service**。经 SSH 切换同一网卡时，调用端可能断线。`SIGHUP` 仅记录调用端断开，继续持锁完成已请求的入组；`SIGTERM`／`SIGINT` 记录取消请求，在本次有界 nmcli 命令结束后进入仅清理自身 profile 的路径，避免在异步激活中途直接释放锁。信号处理器本身不执行网络操作。`SIGKILL` 等强制终止无法覆盖；需要可靠完成跨断线任务的接入层，仍应审查受控的 detached 执行方式，或通过串口／独立管理链路操作。不能把 SSH 中断或 nmcli 启动成功当成入组成功。

`--join` 会在网络变更前原子写入脱敏结果，并在激活前记录 `activating`：

```text
/home/topeet/.local/state/followingcar-wifi-direct/last-result.json
```

目录权限为 `0700`，文件为 `0600`，供 `topeet` 读取。记录包含新旧 profile UUID、执行阶段、错误代码以及确认后的地址，不包含 PSK、原始 stdin 或命令输出。只有确认 `wlan0` 激活的是本次 UUID 且已有 IPv4 地址，才记录 `connected`。结果文件无法在变更前写入时，不进行切换。已经确认入组后，终态写入或临时密码文件清理失败会单独标记，不因此再次拆掉成功连接的新网络。

若最后停在 `activating`、没有终态或读取失败，结果是**未确认**。先通过可用管理链路只读检查 NetworkManager 和控制状态；不要自动重试、重复建组或自动启动控制。进程被强制终止时可能留下 root 私有的临时密码文件，后续应按已确认的本次操作人工处理，不进行目录范围清理。

恢复原网络必须在控制空闲时显式执行。结果中的 `previousConnectionUuid` 可用于确定原 profile；核对后再由用户运行：

```bash
sudo -n nmcli connection up uuid <previousConnectionUuid> ifname wlan0
```

helper 本身不会调用此恢复命令。既有 NetworkManager 自动连接策略保持原样，因此系统自身的重连行为不由本 helper 改写。新 GO profile 不自动连接，重启不会由该 profile 自动切走现有网络；再次入组仍需提供凭据并明确执行。

## 离线验证与参考

截至上述部署记录，实机手机已成功创建 GO，显示 0 个客户端；模拟器已验证创建 GO、切到后台仍保留组，以及用户显式解散。板端 helper 的只读 `--check` 已通过，实际 `--join` 尚未执行，因此尚不能把手机建组成功视为开发板入组或 Direct SSH 联调通过。

```powershell
python -m unittest discover -s board -p "test_app_wifi_direct_join.py" -v
```

测试使用伪 NetworkManager 和临时文件，不扫描无线网络、不运行真实入组、不打开电机或相机。它们验证默认只读、凭据过滤、控制忙／未知拒绝、持锁期间变更、私有密码文件、仅清理自身 UUID 及结果记录。

NetworkManager 的 [`connection up ... passwd-file`](https://networkmanager.dev/docs/api/latest/nmcli.html) 支持通过私有文件提供激活凭据；[WPA PSK 与 secret flags](https://networkmanager.dev/docs/api/latest/settings-802-11-wireless-security.html) 说明了 PSK 格式与存储标志。原生 P2P 的 [`wifi-p2p.peer`](https://networkmanager.dev/docs/api/latest/settings-wifi-p2p.html) 是另一种组连接方式，本兼容 helper 不调用它。

Android 官方 [`WifiP2pManager.createGroup`](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager#createGroup(android.net.wifi.p2p.WifiP2pManager.Channel,%20android.net.wifi.p2p.WifiP2pConfig,%20android.net.wifi.p2p.WifiP2pManager.ActionListener)) 说明手机会成为 GO，并可接受 P2P 设备和普通 Wi-Fi 客户端；回调成功仅说明请求已提交，需要继续查询组状态。API 35 的 [`WifiP2pDevice.getIpAddress()`](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pDevice#getIpAddress()) 提供已连接客户端的 IP，可从组成员信息读取，不能用组主地址代替。
