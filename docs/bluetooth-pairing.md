# 蓝牙配对与手机本地热点

App 使用 Android 公开的 LocalOnlyHotspot API 创建供小车通信的热点，随后通过已认证的 Bluetooth RFCOMM 连接传送 SSID 与密码。该热点用于本机控制和视频，不承诺共享手机移动数据。Android 35 的系统选择热点参数，Android 36 及以上才有公开的自定义配置 API；板端 `rtl8723du` 仅支持 2.4 GHz，实际联调必须验证兼容性。

首次 Android 蓝牙配对可能显示系统确认框，App 不绕过这个确认。后续已配对设备可直接建立安全 RFCOMM 通道。当前设备地址由现场只读检查取得，App 的目标板配置仍允许以后更换。

连接页的“检查蓝牙连接”只连接上述服务并发送 `status`，成功后显示“蓝牙服务正常，可开始配网”。它不会创建热点、发送配网凭据或连接 SSH；已有热点或配网结果未确认时不提供这个入口。诊断日志区分连接、发送和读取，保留失败发生时的阶段、耗时、配对状态和异常类型，不保存异常消息、帧内容或凭据。

## 板端服务与首次登记

新增服务独立于自动跟随运行目录，不修改桥接层、worker、蓝牙守护进程或 NetworkManager 服务。文件安装于 `/opt/followingcar-pairing/`：

- `app_bluetooth_provision.py`：协议、BlueZ Profile 和有限操作历史。
- `app_wifi_direct_join.py`：复用既有控制锁、占用检查及 NetworkManager 事务；蓝牙入口允许一般热点名称，原 `--join` CLI 仍仅接受 `DIRECT-` 名称。
- `/etc/systemd/system/followingcar-pairing.service`：正常启动不开放初始配对窗口。

`/etc/followingcar-pairing.json` 必须 root 所有且权限 `0600`，内容仅有 `boardId` 和 `allowedDevices`。`boardId` 使用 `followingcar-` 前缀；设备名单为明确登记的手机 Bluetooth MAC，最多 8 个，不能使用任意设备通配符。名单不是蓝牙密钥；最终连接还必须满足 BlueZ 的已配对、已信任和认证要求。

服务注册自定义 UUID `6d4f3a10-8f71-4c5e-9c2a-7b36f0e1d842`，使用 RFCOMM 通道 22，声明 `RequireAuthentication=true`、`RequireAuthorization=true`。不会自动打开可发现模式、修改适配器电源或扫描周边设备。服务启动时确认 hci0 已开机并支持 BR/EDR，必要时开启 `connectable`（page scan），允许已知地址的手机发起连接。退出时只还原本实例从关闭改为开启的 page scan；启动前已开启的状态不被关闭。现场已发现仅 `Powered=yes` 而没有 page scan 会导致手机配对失败，蓝牙守护进程也收不到请求。通道被其他服务占用时启动失败，不抢占其他服务。

首次登记是独立、明确的部署步骤。在控制空闲时先停止本配对服务，用同一配置启动 `--enrollment-seconds 300`。只有在这个最多 300 秒的窗口内，临时默认 BlueZ Agent 才接受名单中指定手机的首次确认，并在配对完成后只为该手机设置信任。其他 MAC 和旧式 PIN 配对被拒绝。这个有限设备名单的自动确认**不等同于用户核对了两端数字、也不声称具有数字比较的中间人防护**；手机仍显示系统确认。正常服务不接管系统默认 Agent。

窗口结束会撤销临时 Agent，已注册的配网 Profile 保留。完成首次登记后应退出登记进程，重新启动正常 systemd 服务。部署时只登记用户连接的手机，不把周边蓝牙设备自动加入名单。

只读检查：

```bash
sudo /usr/bin/python3 -B /opt/followingcar-pairing/app_bluetooth_provision.py --check
```

这个命令检查私有配置及控制占用，不扫描、配对、建热点或改变 Wi-Fi。

## v1 协议

板端配网服务同时管理支持的 `fast-connectable` 设置：启动时开启快速接听，正常退出时先恢复本实例修改的快速接听标志，再恢复本实例修改的 `connectable`。已有开启状态不归本服务所有；不支持快速接听的适配器继续使用普通接听。快速接听增加扫描占空比和蓝牙耗电，换取更短的连接建立时间，详见 [BlueZ 管理协议](https://github.com/bluez/bluez/blob/master/doc/mgmt-protocol.rst)。该设置不改变 Wi-Fi 网络、电机参数、可发现性或已绑定设备。2026-10-30 的现场对照与部署记录见 [蓝牙连接修复记录](bluetooth-connection-fix-2026-10-30.md)。

每帧为 UTF-8 JSON 加换行，最多 4096 字节；拒绝重复字段、额外字段、未知命令和非规范 UUID。没有任意命令执行入口。密码仅存在内存和激活期间 `/run` 的 `0600` 临时密码文件，不放入 argv、错误、协议回复、持久历史或日志。

状态请求：

```json
{"v":1,"requestId":"<规范UUID>","cmd":"status","queryRequestId":"<可选的先前操作UUID>"}
```

回复 `type=status`，包含 `boardId`、`controlAvailable`、`band="2.4GHz"`、`operationInProgress` 和 `lastResult`。`lastResult` 仅返回所查询操作；没有对应记录时为 `null`，不会拿其他配对结果替代。

2026-10-30 恢复增强保持 `v=1`，增加可选的 `recoveryRequestId` 及 `recoveryState=clear|pending|unavailable`。`clear` 只在控制空闲、没有活动/未知事务、当前 WLAN 确认为稳定的外部网络或严格稳定断开、旧自有热点不会自行回切且两次观察一致时给出。它仅允许解除旧恢复记录，不代表任何地址已通过 SSH 地址核验。查询不存在的 UUID 时，仅在上述证明成立后先保存 `request_expired` 确定失败记录，再返回；同 UUID 迟来的配网提交只返回既有结果。

配网请求使用同样的 `v` 和 `requestId`，`cmd=provision`，额外字段只有 `ssid`、`psk`。服务按顺序回复 `type=progress,stage=accepted`、`stage=connecting`，最后回复 `type=result`。不回显热点名称或密码。

成功结果包含 `ok=true`、`definitive=true`、`ip`、`gateway`、`prefixLength`。`gateway` 是 DHCP 报告的手机热点地址；本地热点未提供默认路由时，使用实际 DHCP server identifier。App 必须独立确认它与新建热点接口的本机地址和前缀一致，才把板端地址交给 SSH，不能仅相信远端提供的地址。

失败结果只有固定错误码。`definitive=false` 表示网络结果仍不确定，App 保留热点并查询状态；不能当作“没有切换网络”。`networkChanged` 表示是否可能已开始切换，不代表连接成功。连接途中断线、重启后的未完成记录、地址无法确认均保持不确定状态。前置拒绝和明确未进入网络激活的失败才能报告确定失败。

## 控制与网络边界

状态查询仅短暂检查两把既有控制锁。配网提交先检查控制空闲，再由独立任务重新取得两把控制锁并确认进程和串口无占用，持锁完成 NetworkManager 激活及必要的自身清理。运行中的自动跟随、手动控制或无法确认的占用会拒绝配网；不会停止这些程序来腾出网络。

服务只创建自身随机 UUID 的临时 NetworkManager profile，`autoconnect=false`、WPA2、2.4 GHz、`psk-flags=2`。使用私有 `/run/NetworkManager/system-connections` keyfile 加载，兼容现场 NetworkManager 1.36.6；不再使用该版本不允许修改 UUID 的 `nmcli connection add ... connection.uuid` 形式。失败仅删除已核实归属的本次 profile，不删除已有 Wi-Fi 配置。

已接受任务与 Bluetooth socket 生命周期分离。手机离开或蓝牙断开后，已接受任务仍完成，随后可用同一个 `requestId` 查询。最近 16 次操作保存在 root 私有 `/var/lib/followingcar-pairing/state.json`，重复提交已记录的 UUID 只返回原结果，不再次切网。运行中的任务跨进程重启标记为 `outcome_unknown_after_restart`，不会保存凭据后自动重试。保留的 NetworkManager 事务信息在同目录 `network-last-result.json`，供现场核对，但不含密码。

服务不自动恢复原 Wi-Fi。恢复必须在控制空闲时明确执行，并核对先前 profile UUID。用户关闭热点或 App 进程退出可能使板端失去网络，现有控制心跳失效和停车处理继续生效；无线连接恢复不自动重新启动行驶。

未知结果可以只读核实。App 再查询同一 `requestId` 时，服务仅使用请求编号完全匹配的私有网络事务记录；控制空闲且当前确为原 profile、已有 IPv4、本次新 profile 不再活动时，返回确定的 `network_restored` 失败。若当前确为本次新 profile、归属及热点地址均核实，则返回确定成功。其他请求的记录、控制忙、网络身份或活动状态未核实、清理不明确，都不能解除未知状态。核实不重新执行配网，并保留原始固定错误码 `firstError`。

当前已经接入另一个稳定网络时，不再强制要求其 UUID 与当初的原 profile 相同：服务核实控制空闲、当前 UUID 活动且有有效 IPv4、本次热点 UUID 不活动，旧 profile 已删除或仍归本服务且 `autoconnect=no`，再二次确认观察一致，返回 `network_superseded` 确定失败。该结果不把新网络当作手机热点，不切换、删除或改写当前网络。App 自动释放自己的失效热点并恢复入口；若用户本次明确点击“一键配对”，在核实旧事务结束后可以继续正常的新配网。

手机进程退出后，若小车附近没有原 Wi-Fi，服务也可核实旧热点结束：必须是受管 `wlan0`、稳定 `state=30`、无当前 profile/IPv4、旧热点不活动且不会自动回切、控制及 worker 均空闲，并二次确认网络及活动集合一致，才返回 `hotspot_disconnected`。正在连接、正在断开、失败状态、残留地址或观察变化都不能代替这个证明。App 在没有实际热点 reservation 时不会拿历史成功地址连接 SSH，会等待板端核实旧热点断开。

App 的最小恢复记录位于 `noBackupFilesDir/pairing-recovery/`，仅含目标蓝牙地址、规范 UUID、阶段、时间和固定首错码，不含 SSID、密码、IP 或命令内容。发送凭据前在独立 IO 线程原子提交；提交失败不发送。恢复加载期间不能新配网，已知未发送记录按 UUID 清理，损坏记录必须通过板端恢复证明核实。系统停止热点后仍保留待确认状态。蓝牙建链/只读查询最多三次（750/1500 ms 退避），凭据只发送一次，断线后最多 90 秒自动查询原事务。用户取消会立即关闭本次 socket，不清掉可能已执行的记录。

同一已认证手机的新 RFCOMM 连接可以替换其旧连接；旧连接已排队但未接受的帧失效，已接受的 worker 不受 socket 断开影响。服务正常释放 BlueZ Profile 后由 systemd 自动重启；只关联蓝牙服务，不因 NetworkManager 变化重启正在进行的网络事务。SSH 自动连接强制使用刚核实的本地热点，保留原远程配置和 LAN 默认地址，不自动启动车辆。

## 验证

```powershell
python -m unittest discover -s board -p "test_app_bluetooth_provision.py" -v
python -m unittest discover -s board -p "test_app_wifi_direct_join.py" -v
```

测试覆盖控制忙、接受后控制变忙、重复请求、断连继续、结果查询、NM 失败、自身清理、写盘失败、未知地址、有限历史和输入过滤。POSIX 文件权限与重启恢复测试需在 Linux 运行；Windows 明确跳过该项。

2026-10-29 已在开发板通过 65 项 Linux 离线测试（配网 39、既有网络事务 26）。真实手机完成首次蓝牙登记、创建热点、传送凭据、板端取得地址、手机核对热点接口并建立 SSH。本次首次观测到的板端地址为 `192.168.107.121`，手机热点地址为 `192.168.107.132/24`；这些是 DHCP 现场值，不是固定配置。测试的受控回退随后恢复原 Wi-Fi，按同一请求编号查询已正确返回 `network_restored`，没有复用旧成功地址。

当前独立部署的服务为 `followingcar-pairing.service`，正常启动关闭首次登记窗口，保留已建立的手机配对关系。部署文件校验：

- `app_bluetooth_provision.py` SHA-256：`2444bf151b8cf3c8a818e9e4bd31ea04ac1dfce41b30b4a9c4892164b627073b`。
- `app_wifi_direct_join.py` SHA-256：`85420b12d3ebe23d3334d6c67c6b65b61b4cf8f4b1995091ebd466c5054e436d`。

上述联调没有启动真实电机。板端的跟随程序、控制桥接脚本和原 Wi-Fi profile 均未被替换。

参考：[Android 本地热点 API](https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspot(android.net.wifi.WifiManager.LocalOnlyHotspotCallback,%20android.os.Handler))、[BlueZ Profile API](https://bluez.readthedocs.io/en/latest/profile-api/)。
