# 电机协议 v2 桥接

`board/app_motor_config_bridge.py` 覆盖本版手册全部非保留接口：**69 个可读写配置字段、22 个设备／实时只读字段、8 个控制接口，共 99 个逻辑接口**。逻辑接口可能占两个寄存器，不能把这个数量当成寄存器字数。完整地址、编码与功能码见 [99 接口清单](motor-register-interface-inventory.md)，来源歧义见 [协议审核](motor-configuration-protocol-review.md)。

本次交付仅开发和模拟验证，**未打开实车串口、未改实车参数、未部署到开发板**。支持写入和动作的代码不等于已执行相应实车操作。

## 入口、文件与控制占用

App 生产入口仍为：

```text
sudo -n /usr/bin/python3 -B /home/topeet/lianzhan/scripts/app_motor_config_bridge.py --request
```

后续部署 v2 时须同时安装 `app_motor_config_bridge.py`、`motor_register_catalog.py`，并保留同目录既有 `app_control_guard.py`；不要只替换一个脚本。v2 请求／响应的 `schemaVersion` 为 2，不把旧 v1 回执伪装成完整覆盖。

标准输入为一个最多 32768 字节的 JSON 对象，以 EOF 结束。标准输出只有一行 JSON；错误仅使用固定码，不回显任意异常文本或输入。`--check` 仅检查控制空闲，不打开串口。

真实请求需要 root，先通过既有控制占用检查，再持有 joystick／follow 锁并检查实际控制进程及串口持有者。串口打开后、配置 termios 或发送字节前，再确认仅本进程持有；不清除其他进程已有的独占标志。配置与动作共用这套守卫，不会抢占自动跟随或手动控制。

物理串口固定为 `/dev/ttyS0`，不接受任意路径、命令、寄存器地址或功能码。可选 `connection` 仅允许如下受限串口设置：

```json
{"slave":1,"baudRate":115200,"parity":"N","stopBits":1}
```

从站 1–254；波特率 9600／19200／38400／57600／115200；校验组合仅 N1、E1、O1、N2。省略时使用板端既有默认值 1／115200／N1。一台双路驱动器使用一个从站地址，左右由寄存器区分。更改此连接设置只改变本次主站通讯格式，不会自行写入驱动器参数。

## 快照

```json
{"schemaVersion":2,"op":"snapshot","requestId":"example-read","connection":{"slave":1,"baudRate":115200,"parity":"N","stopBits":1}}
```

`snapshot` 包括 UTC `capturedAt`、`connection`、设备信息、69 项 `fields`、19 项 `observation` 及状态门控 `gate`。设备对象对应另 3 项只读接口，合计 22 项。每个配置字段给出 `id/address/words/signed/divisor/raw/value/group/unit/minRaw/maxRaw/choices/supportsTemporary/packedPhase/note`，以及可写声明。

所有 `raw` 是 0–65535 的整数数组，高字在前。s16／s32 用补码解释；相序每个字的低字节为较早一相、高字节为后一相，每字节都只能为 0–6。显示值由原始字和精确除数计算，不能依赖浮点舍入后再写回。

配置按 10 个不跨保留区的连续块批读，连同设备信息、两次实时观察共 21 次 FC03。读取不发送停止、驻车、抱闸或参数写入。`snapshotId` 是 schema、串口连接设置、设备信息和全部配置原始字的摘要，不包含会随时变化的实时转速；写前仍要重新观察状态。

## 配置写入

```json
{
  "schemaVersion":2,
  "op":"write",
  "requestId":"example-write",
  "snapshotId":"使用先前快照返回的64位十六进制摘要",
  "writeMode":"persistent",
  "changes":[{"id":"max_output_current","expectedRaw":[1000],"raw":[1200]}]
}
```

69 项配置均有实际写入路径，包括硬件／相序参数、驻车、系统与保护、抱闸输出、RS485／CAN／TTL 和全部 19 项 PID。保留地址不进入清单。请求只传白名单字段 ID、预期旧字和目标字；所有静态范围、枚举、编码及字段组合在打开串口前检查。`expectedRaw` 允许保留实际读到的越界旧值以便修复；新的目标值必须合法，不会为适配界面而自动修正原读值。

- `persistent`：FC10，即使只有一个字也用 FC10。手册规定它是保存写入。
- `temporary`：文档支持的 50 项配置可用 FC06，其中 47 项是单字、3 项是双字速度配置。19 项 PID 仅支持 FC10。
- 相同值跳过写入；每项写后 FC03 逐字比较。回读通过不代表验证了断电保持。

正转最大速度 `0x0059`、反转最大速度 `0x005B`、最小速度 `0x005D` 的临时设置为两帧 FC06，非原子操作。桥接在独占锁内选择使中间组合值仍合法的高／低字次序，例如 65000 → 100000 先低后高，110000 → 65535 先高后低；每帧确认 ACK 并回读两字，两帧之间还重新检查零速／零 PWM／通讯来源及旧值。任一 ACK、回读或门控失败即停止，结果为 `write_outcome_unknown`，不回滚、不自动重试；不能按任意单字地址绕过逻辑字段。若越界旧值无法经过合法中间值到达目标，在发帧前拒绝临时写，仍可采用完整 FC10 修复。

写前重新读取完整快照，比较摘要及每项预期旧字。普通参数写入要求两次观察左右转速与 PWM 为零，且为通讯控制；每项前再检查状态和旧值。`disabledVerified` 始终为 `null`，这些观察不等于硬件禁能或车辆停稳保证。

最大输出电流受设备报告能力限制，任一侧驻车电流不得超过最大输出电流。批量组合先验证最终值，再安排执行顺序：降低驻车电流先于降低上限，提高上限先于提高驻车电流，使中间状态也合法。逐项结果仍按原请求顺序返回。没有擅自增加手册未说明的最小速度／正反向上限关系。

RS485 从站、波特率或校验变更必须单项提交。进入发送前即设置 `reconnectRequired=true`；收到 ACK 后为 `acknowledged_unverified`，不再用旧链路回读，也不继续写其他项，返回 `communication_reconnect_required`。若 ACK 丢失则保持 `write_outcome_unknown`，仍要求重连。用户应依据目标设置重新连接并读取，而不是自动重试改址。CAN／TTL 参数通过当前 RS485 写入，不自行切换到那些总线。

## 操作命令

```json
{
  "schemaVersion":2,
  "op":"action",
  "requestId":"example-action",
  "snapshotId":"使用先前快照返回的64位十六进制摘要",
  "actionId":"right_target_speed",
  "value":20,
  "durationMs":1000,
  "confirmed":true
}
```

动作有独立确认及结果，不混入参数保存：右／左停止、右／左速度目标、右／左抱闸、恢复出厂、相序学习／取消测试。单字命令用 FC06，双字速度用 FC10。动作语义与系统模式相关，不能仅按左右按钮名称推断执行效果。

- 速度测试持续 100–3000 ms；必须满足当前闭环模式和已配置速度上限。差速模式下左目标是转弯速度，收尾必须发送右停止 `0x0040`；独立模式的左目标才使用 `0x0044`。
- 左／右相序学习分别是 `phase_learning` 的值 1／2，持续 100–60000 ms，最终尝试写值 0 取消。值 0 直接取消，持续时间为 0。
- 其他动作持续时间必须为 0。停止允许 0 正常、1 紧急、2 自由；抱闸允许 0／1；恢复出厂仅允许 1。
- 速度、学习、抱闸、恢复出厂等新动作仍要求空闲／通讯控制和当时零速零 PWM。停止与取消不被非零速度或学习状态拒绝，但仍须独占控制锁并取得匹配快照。若完整快照读取已失败，本入口不盲发停止；这不是独立的硬件急停通道。
- 同源／差速模式下左停止无效，桥接拒绝这个无效调用，不把 ACK 宣称为停车。

速度和学习在正常到时、异常、SIGINT／SIGTERM／SIGHUP 或父进程丢失时，都会尝试有界的停止／取消。原命令失败不会重发；原协议会话受损时只为收尾创建新的回执读取器。SIGKILL、断电或物理串口损坏无法保证收尾完成，不能据此宣称任何情况下都会停车。

`actionResult` 包含 `actionId/value/durationMs/outcome/cleanupConfirmed/reconnectRequired/error`。`outcome` 只取 `not_attempted`、`write_outcome_unknown`、`acknowledged`；最后一个仅代表命令 ACK，不能写成“相序学习完成”“恢复出厂已完成”或“车轮停稳”。`cleanupConfirmed=true` 也只表示停止／取消 ACK 被确认。

恢复出厂在进入发送前就标记必须重连，ACK 丢失也不撤销。收到 ACK 的结果可为 `ok=true`、`snapshot=null`，含义是命令回执已收到，不是新默认参数已回读验证。

## 错误与部分结果

| 配置状态 | 含义 |
| --- | --- |
| `verified` | 当前回读匹配，或原值相同而未写入 |
| `acknowledged_unverified` | 收到写 ACK，尚未确认回读或链路已改变 |
| `write_outcome_unknown` | 进入发送阶段但未取得可靠 ACK，或两帧临时写出现失败，可能部分或全部生效 |
| `failed` | 本项发送前检查失败 |
| `not_attempted` | 本项尚未执行 |

`writeMayHaveOccurred=true` 时不能解释为“参数未改变”。任何项失败即停止后续写入；保留先前结果，不自动回滚或重试整个批次。全量后读失败时 `snapshot=null`。CRC、从站、响应功能／长度及 FC06／FC10 回显检查失败会废弃当前普通事务会话，只有有界动作的收尾例外。

文档异常码 01、02、03、04、05、06、08、0A、0B 均能识别；05“已接收但处理较久”不视为完成，不自动重发。

## 无硬件验证

```text
python3 -B board/app_motor_config_bridge.py --request --simulate
python3 -m unittest discover -s board -p test_app_motor_config_bridge.py -q
```

模拟模式复用真实请求校验、字段编码、Modbus 帧和结果路径，但不导入真实 guard、不获取实车锁、不打开串口、不进行真实等待；每个进程从固定内存种子开始。额外的 `simulationWrites` 记录写功能码、地址和原始字，供验证每个接口及动作收尾。它不表示实车读数或持久化测试。

当前覆盖包括逐项 69 字段写入、22／8 地址覆盖、无保留区批读、所有 Modbus 异常码、signed／相序／PID、50 项临时与 69 项保存写入、双字 FC06 合法中间值与帧间失败、通信失联、部分写入、current／parking 顺序、动作到期／失去 owner／ACK 超时／收尾失败及模拟路径隔离。最终 Windows／WSL 及 Android 模拟器结果见 [本轮验证报告](motor-full-validation-2026-10-30.md)，不混同实机验证。
