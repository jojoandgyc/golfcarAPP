# 1.5 蓝牙一键配网验证与部署

日期：2026-10-29（Asia/Taipei）。当前手机为 Samsung SM-S9280 / Android 15。本轮没有启动实车电机，没有修改原自动跟随 bridge、worker、心跳或失焦停车规则。

## 部署版本

- App：`1.5-pairing`，versionCode `6`，已覆盖安装并保留原账号。
- 源码部署标签：`v1.5-pairing`，与本次 APK 及板端脚本对应。
- APK SHA-256：`660aa39282b116b2998deed54a52d90ddfde13ab2d8ef312cdffdd3ba3a180aa`。
- 板端正常服务：`followingcar-pairing.service`，active / enabled。
- 服务脚本：`/opt/followingcar-pairing/app_bluetooth_provision.py`，SHA-256 `2444bf151b8cf3c8a818e9e4bd31ea04ac1dfce41b30b4a9c4892164b627073b`。
- 事务 helper：同目录 `app_wifi_direct_join.py`，SHA-256 `85420b12d3ebe23d3334d6c67c6b65b61b4cf8f4b1995091ebd466c5054e436d`。
- 最近板端备份：`/opt/followingcar-pairing/backup-20261029T113142Z`。
- 首次限时登记已结束，正常服务不再接管默认配对 Agent；手机的蓝牙 bond/trust 保留，后续无需再次登记。

## 真实链路验证

1. Android 公开 LocalOnlyHotspot API 实机探测通过。系统提供 WPA3 SAE transition 配置，板端以 WPA2 接入；App 不读取或更改系统共享流量热点的密码。
2. 第一次完整配网成功，requestId `e951388d-cb51-4787-add3-a2bc45f0e373`：蓝牙认证、创建热点、发送配置、板端 DHCP、手机核对实际热点接口与地址、固定主机密钥校验、SSH 登录。
3. 测试用回退任务在控制空闲时恢复原 Wi-Fi。随后只读查询原请求正确返回 `network_restored` / `definitive=true`，历史成功没有继续充当当前有效连接。
4. 正常服务下第二次配网通过，没有再次进入首次登记窗口。requestId `dc759c4e-3429-41f9-82d0-c1d4cb3b7381`，19:33:46 至 19:34:00 完成板端网络事务。
5. 本次实际板端地址 `192.168.107.121/24`、手机热点地址 `192.168.107.132`，当前 AP 频率 `2412 MHz`。这些是本次系统分配值，App 不硬编码它们。
6. 手机回到桌面后热点仍保持，重新打开 App 时 SSH 仍连接。主动断开 SSH 后，热点继续保留；点击“核对并重新连接”，通过蓝牙只读核对原请求并重新登录 SSH，网络事务记录仍只有两次，没有重复发送凭据或再次切网。
7. 全程板端 `--inspect` 确认控制空闲。最终测试回退定时器已取消，临时登记服务已停止，正常服务运行；没有启动跟随或手动驾驶。

App 关闭或系统停止热点仍可能导致连接中断，既有停车、首次故障保留和未确认控制锁继续生效。本轮后台测试只覆盖空闲控制，没有进行实车中断实验。

## 自动验证

- 最终 App JVM 测试：84 项，0 失败、0 错误。
- 界面首轮：27 项通过（配对、分页、跟随、Wi-Fi Direct）；最终界面修改后再跑 10 项配对／分页测试，全部通过。
- 手机本地热点能力测试：1 项通过，测试后释放自己的热点。
- 板端 Linux：65 项通过（39 项蓝牙配网与 26 项网络事务）。包含控制占用拒绝、断连、重复请求、未知结果恢复、成功缓存过期、私有文件读写及 page scan 生命周期。
- `git diff --check` 通过。真实配对截图保存在本地构建报告 `app/build/reports/pairing/phone-paired.png`，不随代码上传。

实机检查修正了两个环境问题：NetworkManager 1.36.6 不允许在 `connection add` 时修改自动分配的 UUID，现改用无密码的私有 keyfile 加载；蓝牙 hci0 虽已上电，但原先没有 page scan，现由配网服务按生命周期管理 connectable。另修正首次权限授权后，把只读 Wi-Fi Direct 状态刷新误当网络变更的 App 时序问题。

## 本地模拟器

Google APIs Android 15 / API 35 模拟器已安装，SDK 位于 `G:\AndroidSDK`，AVD 位于 `E:\AndroidAVD`，运行目录与缓存位于 `E:\AndroidEmulatorHome`，下载临时目录为 `E:\AndroidEmulatorTemp`。没有把 SDK、系统镜像或 AVD 下载到 C 盘。蓝牙与板端无线互通以真机结果为准。

详细使用和恢复说明见 [蓝牙配网](bluetooth-pairing.md)。
