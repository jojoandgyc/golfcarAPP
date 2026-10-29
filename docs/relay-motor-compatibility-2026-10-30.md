# 完整电机接口的海外中转检查

检查日期：2026-10-30（UTC+8），服务器 `43.162.101.137` / `car.nujabes.top`。对应 App `1.8-motor-full`、versionCode 11，代码基线 `69d5fcd`。

结论：电机 JSON v2 不要求修改正在运行的服务器中转协议、端口、证书或超时配置。现场运行文件和生成配置均匹配仓库；发现并修正了服务器中未运行的旧安装源文件，未重启中转或改动小车程序。小车隧道在本次检查期间未注册，端到端验证尚未完成。

## 为什么运行服务无需升级

- 控制与配置走 App JSch → mTLS `2443` → nginx stream → frps 回环 `22022` → frpc → 板端 SSH。服务器传递 SSH 字节流，不解析电机 JSON，不维护电机寄存器或指令白名单。
- 全量快照约 36 KiB，App 本次将电机响应上限设为 128 KiB；nginx 控制入口没有对应 JSON 消息大小限制。视频 HTTPS `8443` 的 HTTP 128 KiB 限制和 20 秒超时不作用于控制连接。
- 电机请求预算上限 300 秒、学习操作上限 60 秒；现场控制 stream 的 `proxy_timeout 1h` 足够。QUIC 的 30 秒 idle 检查配合传输 keepalive，与电机命令执行预算不同；App 仍有 SSH 存活请求。
- 取消或连接关闭由原 SSH 连接传递；动作收尾由板端桥接负责，App 保留未知结果锁。服务器不重放命令，不自动恢复驾驶。
- `.carremote` 导入包仍使用自己的 schema 1，与电机 schema 2 无关，不需要重新签发客户端身份。

真正需要配套安装的电机实现位于开发板：`app_motor_config_bridge.py` 与新增的 `motor_register_catalog.py`，并保留原 `app_control_guard.py`。本次没有向开发板部署这些文件。

## 现场检查结果

| 项目 | 结果 |
| --- | --- |
| `followingcar-frps` | active / running，frp 0.71.1，PID 603438 |
| `followingcar-nginx` | active / running，PID 603506 |
| `followingcar-turn` | active / running，PID 603573 |
| 证书维护 timer | active / waiting，无 pending 标记 |
| 控制入口 | `2443`，强制 mTLS，TLS 1.2/1.3，`tcp_nodelay on` |
| 控制后端 | 配置为 `127.0.0.1:22022`，stream timeout 1 小时 |
| 运行 nginx / frps 配置 | 与仓库根据现场公开 settings 渲染出的内容摘要一致 |
| 服务器证书到期 | 2027-01-27 12:14:07 UTC |
| 运行代码、源目录、测试、播放器及许可文件 | 同步后检查的 21 个文件均与仓库一致（统一 LF 后比较 SHA-256） |

运行用 `tools/configuration.py`、`tools/provision.py`、`tools/certificate_maintenance.py`、`tools/transport_supervisor.py` 以及四个播放器文件原本已正确，无需替换。

## 同步的旧文件

旧 `source/` 中证书维护脚本仍有“观察空闲后自动重启 frps/Coturn”的逻辑；其副本还会清除 pending 标记。当前实际维护服务运行的是已修正的 `tools/` 版本，未使用该旧副本。

旧安装器也没有当前双传输配置、认证管理接口和独立视频网关端口；旧 supervisor 使用带前缀的代理名称，可能导致以后重新部署时错误判断注册状态。因此将以下 **10 个未运行的安装源／测试文件**同步到仓库基线：

- `source/`：`configuration.py`、`provision.py`、`certificate_maintenance.py`、`transport_supervisor.py`、`install_board.py`、`test_certificate_maintenance.py`、`test_transport_supervisor.py`。
- `tools/`：`install_board.py`、`test_certificate_maintenance.py`、`test_transport_supervisor.py`。

备份位于服务器：

```text
/etc/followingcar-relay/backups/20261029T173138Z-source-audit/
```

目录包含原文件和 `manifest.json`，后者记录修改前后 SHA-256。同步前验证文件未发生并发改变、目标不是符号链接、相关安装脚本没有在运行；逐个原子替换，保留原属主和权限。

未执行 `prepare-server`、`activate-server`、`renew-certificate` 或安装器，也未修改 `/etc` 中的运行配置、凭据、路由、防火墙或 VPN。三个中转进程修改前后 PID 和启动时间相同，均保持 active；没有执行重启或 reload。源文件同步不接触开发板。

## 验证与当前限制

- 本机 Linux 中转回归：22 项通过。
- 服务器同步后直接运行源目录中的中转回归：22 项通过。
- 新增 `RemoteMotorRelayCompatibilityTest`，测试 APK 已编译通过。它只执行固定的内存回显／等待命令，准备验证 8 KiB 输入、128 KiB 响应、65 秒无输出等待，以及可选取消后重连；不调用控制桥接、sudo、相机或串口，不写板端文件。
- 本次服务器没有 `22022` / `18889` 已注册后端监听，也没有中转入口已建立连接；局域网 `172.16.16.82:22` 尝试在 SSH 登录前被关闭。只能确认隧道当时未注册，不能据此判断小车断电、Wi-Fi 故障或 frpc 的具体原因。
- 因此没有执行上述端到端测试，不能将“服务器兼容性检查通过”写成“小车远程电机接口已联调通过”。未重启或改动开发板网络、自动跟随与电机参数来尝试恢复连接。

小车恢复原有联网后，可在已保存模拟器专属远程身份的 `emulator-5554` 上执行：

```powershell
& 'G:\AndroidSDK\platform-tools\adb.exe' -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& 'G:\AndroidSDK\platform-tools\adb.exe' -s emulator-5554 shell am instrument -w -r -e class com.example.followingcar.RemoteMotorRelayCompatibilityTest -e remoteMotorRelayReadOnly true -e remoteMotorRelayCancel true com.example.followingcar.test/androidx.test.runner.AndroidJUnitRunner
```

结果只记录端点、字节数、耗时和通过状态，保存为 App 外部私有目录中的 `remote-motor-relay-compatibility.json`。本次本机审核数据、同步回执与测试日志位于 `app/build/reports/relay-motor-audit-2026-10-30/`，不含凭据，不纳入 Git。
