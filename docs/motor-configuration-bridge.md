# 电机配置桥接

`board/app_motor_config_bridge.py` 是独立配置入口，和 `board/app_control_guard.py` 放在同一目录。它不导入 GUI，不调用 GUI 的退出函数，不发送速度、停止、抱闸、相序学习或恢复出厂指令。现有自动跟随和手动控制脚本保持原样。

## 入口和实际设备

生产部署位置由 App 固定为 `/home/topeet/lianzhan/scripts/app_motor_config_bridge.py`，通过已有 SSH 执行：

```text
sudo -n /usr/bin/python3 -B /home/topeet/lianzhan/scripts/app_motor_config_bridge.py --request
```

标准输入只接受一个最多 8192 字节的 JSON 对象，以 EOF 结束；标准输出只返回一行 JSON。默认模式等同于 `--request`。完整进程和串口占用检查需要 root；不允许客户端传入串口、地址、波特率、寄存器地址或任意命令。

固定设备是 `/dev/ttyS0`、115200、8N1、Modbus 地址 1。同一个控制器包含左右两路电机，并非两个从站。

`--check` 只检查控制是否空闲，不打开串口。每次真实请求都先检查现有 follow bridge 的 `--inspect`，然后按既有顺序持有 joystick 和 follow 两把锁，检查实际控制进程及 ttyS0/存在时的 ttyS3 占用。串口打开后、设置 termios 或发送任何字节之前，再检查当前持有者仅本进程。已有独占标记不会被清除；本次设置的独占标记在退出时撤销。不能确认时拒绝。

## 请求与结果

读取请求：

```json
{"schemaVersion":1,"op":"snapshot","requestId":"example-read"}
```

读取结果的 `snapshot` 包括 UTC `capturedAt`、设备标识和版本、18 个配置字段、左右实际转速/PWM/异常及总线状态。每个字段含 `id`、`raw`、`value`、单位、倍率、字数、范围及 `writable`。相序原始数据是三个 16 位字，`value` 为 `null`。所有 `raw` 均为 0～65535 的整数数组，32 位数采用高字在前。

写请求附上先前实际读取的 `snapshotId` 和修改列表：

```json
{
  "schemaVersion":1,
  "op":"write",
  "requestId":"example-write",
  "snapshotId":"使用实际读取返回的64位十六进制摘要",
  "changes":[{"id":"max_output_current","expectedRaw":[1000],"raw":[1200]}]
}
```

`snapshotId` 是 schema、设备信息和全部配置原始值的稳定摘要，不依赖跨进程内存令牌。写前重新读取并比较摘要和每项旧值；不同则要求刷新。所有输入和静态范围在打开串口前验证。实际写入只允许下列五项，其他字段只读：

| 字段 ID | 原始范围 | 单位及倍率 |
|---|---:|---|
| `max_output_current` | 50～6000 | ×0.01 A |
| `max_forward_speed` | 0～110000 | RPM，两个字 |
| `max_reverse_speed` | 0～110000 | RPM，两个字 |
| `closed_loop_acceleration` | 10～65535 | RPM/s |
| `closed_loop_deceleration` | 10～65535 | RPM/s |

最大输出电流还不能超过设备报告的最大电流，也不能低于当前任一侧驻车电流。没有添加手册未规定的最小速度组合约束。驻车、系统模式、通讯设置、相序以及文档范围有歧义的差速转速/堵转时间均不开放写入。

首次写前采样两次，间隔 120 ms，要求左右实际速度和 PWM 均为零，控制来源为通讯。每项写入前再读取一次状态和该项旧值。**这些只是当时的状态观测，协议没有禁能反馈位，`disabledVerified` 始终为 `null`，不能称为已禁能或物理停车保证。** 页面不能用旧快照保证下一次写入，板端会独立重新判断。

每项采用 FC10 写入，再用 FC03 回读。手册规定 FC10 为保存写入；回读只验证当前寄存器，不能宣称做过断电重启保存验证。不重复写相同值，不自动重试，不自动回滚；任何一项失败立即停止后续项。

结果逐项包含 `requestedRaw`、`actualRaw`、错误码和状态：

| 状态 | 含义 |
|---|---|
| `verified` | 当前回读匹配，或写前已是同值而跳过写入 |
| `acknowledged_unverified` | 收到写回执，但回读失败或不匹配 |
| `write_outcome_unknown` | 已进入发送阶段，未取得可靠写回执；可能已经改变设备 |
| `failed` | 该项写前检查失败，未发送该项写指令 |
| `not_attempted` | 尚未尝试，通常因为先前检查或上一项失败 |

`writeMayHaveOccurred=true` 时，失败不能解释为“参数未改变”。批量操作可能部分完成，必须保留各项结果。全量后读失败时 `snapshot=null`，不把旧读数冒充新读数。异常只输出固定错误码，不输出任意异常文本。串口超时、错误 CRC、错误从站/长度/回执会废弃当前协议会话，不继续尝试下一条事务。

## 无硬件联调

```text
python3 -B board/app_motor_config_bridge.py --request --simulate
```

传入相同 JSON 格式。`simulation:true` 表示纯内存模拟；此模式不导入真实控制 guard、不持实车锁、不打开串口，每次进程都从固定模拟寄存器重新开始。可先读取模拟 `snapshotId`，再用下一次进程执行修改并验证同一请求内的回读。模拟结果不代表实车读数，App 普通界面不开放此开关。

模拟串口测试覆盖 CRC/响应类型/超时、部分写入、回读、参数白名单和范围、驻车电流组合约束、快照冲突、并发占用、sudo 启动包装进程、串口打开竞态及独占标记清理。Linux 锁测试只使用临时文件；不会访问真实电机串口。

首次交付只允许 `--check` 和模拟联调；真实读取、尤其实际参数写入，是另一次明确的现场操作，不能因为模拟通过就声称已完成实机参数验证。
