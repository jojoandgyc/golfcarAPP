# 自动跟随断线恢复验证与部署

版本：`1.2-recovery` / Android `versionCode=3`。基于 `43e2a0e` 诊断版本增加网络异常处理；不修改跟随 worker、100 RPM 判定、心跳周期、ACK 期限或失焦停车规则。

## 已修复的缺口

1. 跟随等待 ACK 超时后，旧代码只关闭通道，JSch 的缓存连接状态可能继续显示已连接。现在 ACK/ready 超时立即使捕获的旧连接失效并关闭真实 socket。单独通道 EOF／协议错误仍与整个 SSH 断开区分。
2. JSch 清理可能等待内部写锁。现在 socket 先关闭，JSch 清理移至后台；旧通道迟到的超时／清理不会关闭新连接。
3. 断网后清除旧配置、视频状态和实时观测；无终止回执时保持未确认锁。重新连接后自动执行一次只读占用检查，明确空闲才解锁；不重启跟随，不重新驱动电机。
4. 未确认会话独立保存最小恢复记录（编号及脱敏首错），不受最近 10 条历史轮转影响，排除系统备份。未确认期间的 ZIP 元数据包含该证据；确认结束后清除。
5. 断开处理增加 130 秒外层清理期限和 finally 收尾，保留原有跟随 12 秒、视频 45／60 秒分阶段等待。取消／失败不会永久留下 busy 状态，也不会误报停车成功。

## 板端 Codex 协助审查

板端 Codex 0.153.4 仅对 `/tmp` 三份代码副本做只读静态审查，使用低优先级、read-only 沙箱及临时会话；没有操作生产跟随进程、服务或硬件。它发现新增日志代码中的控制线程堆栈格式化、首次故障并发竞争和 stderr 阻塞风险。

已修复日志代码：控制线程只截取有界原始帧元数据，不读取源码、不调用异常的自定义字符串方法；后台格式化／脱敏。首因按单调时间及序号排序。告警使用非阻塞输出，初始化失败的告警也在独立线程中处理。worker 与停车参数未修改。

## 验证

- 37 项 JVM 测试通过。
- 手机 28 项选定测试通过：13 项传输故障、10 项跟随 UI、2 项恢复持久化／取消清理、2 项真实 SSH 故障注入、1 项视频与手动／跟随模拟联动。
- 板端 Linux 独立临时副本 69 项测试全部通过，无跳过；包含慢异常格式化、满 stderr 管道、慢报告、并发首因、EOF、watchdog、锁继承和日志限额。
- 本次所有跟随和手动电机操作均 `dryRun=true`。没有运行实车电机测试，没有更改手机 Wi-Fi、板端防火墙或 SSH 服务。

网络测试使用手机内的专用 TCP 转发连接：一场景丢弃该连接的双向数据，另一场景关闭该连接的板端 socket。其他连接不受影响。每场景先确认板端空闲，故障后验证 App 断开／锁定、首次异常、板端最早原因、清理完成、worker PID 消失、重连只读检查，以及无自动重启。测试结束恢复手机原 IP／端口，恢复快照已清空。

| 最终场景 | runId | App 首错 | 板端首次原因 | worker 退出码 |
| --- | --- | --- | --- | --- |
| SSH EOF | 5e17523df18850ef36aa93e77ff740ed | follow.transaction_failed | input_eof | -15 |
| 网络黑洞 | 65d28a7bf3b6c681511c96da7f87b037 | follow.ack_timeout | heartbeat_timeout | 0 |

日志用 `test.network_fault_injected`、`TEST_SSH_EOF` 和 `TEST_NETWORK_BLACKHOLE` 标记模拟故障。首次复测的 SSH EOF 会话 `be823daa18ffc1a0f8359a002472ea4e` 也出现 -15：日志证实桥接层两次发送 S／SIGTERM，worker 已进入 STOPPING，随后以 SIGTERM 结束。相关进程都已消失。

最初“必须退出码 0”的网络测试断言因此失败。验证现改为确认退出事实：仅允许 0 或 -15；-15 额外要求同 PID 的 STOP 写入、SIGTERM、STOPPING 在退出之前，以及清理完成。仍保留 FAULT、停车 ACK 未知和机械停稳未知，不把信号退出称为优雅停车。正常主动停止的退出码 0 要求未放宽，worker 没有为测试而修改。

## 部署

- 手机最终 APK：2026-10-29 16:28:34 +08 覆盖安装，版本 `1.2-recovery`；保留用户凭据。
- APK SHA-256：`809b38f61b2cb047cfe79b753ecd3000312fed66e424e5651b636f6a79fa8d5d`。
- 板端：2026-10-29 16:30:54 +08，空闲检查并持有控制锁核验后，备份并原子更新日志模块及 bridge；worker 未改。
- 备份：`/home/topeet/Desktop/rk_car_runtime_module/.app_bridge_backups/20261029T083054Z/`。
- 部署清单：`/home/topeet/Desktop/rk_car_runtime_module/.app_diagnostics_deployment.json`。

| 板端文件 | SHA-256 |
| --- | --- |
| app_diagnostic_log.py | e24f9d692489120cb384f0cb1e0a53a095311abd6481231b07d7e07e20ec371b |
| app_follow_bridge.py | dc541c6d94c5532993d7f0acf48e155ee5e2da78d939b153fb4421ba4f5bbaea |
| app_follow_worker.py（未修改） | fb59d83be42bfd2049df42c143d3abec912e098d7e1eaf02a058569bab8898e5 |

最终复核 `controlAvailable=true`。本次验证证明了通信故障后的控制会话清理与恢复流程，不证明真实车辆机械停稳，也不能反推早前缺失日志的首次退出原因。
