"""LZ-30EMA_2EC_N manual V1.0: every defined non-reserved configuration field.

Register addresses are zero-based. Conflicting ranges are explicitly intersected
with the physical word width, never wrapped or silently sent to another address.
"""
from dataclasses import dataclass, asdict


@dataclass(frozen=True)
class Field:
    id: str
    address: int
    label: str
    group: str
    unit: str = ''
    words: int = 1
    signed: bool = False
    divisor: int = 1
    minimum: int = 0
    maximum: int = 65535
    choices: tuple = ()
    note: str = ''
    supports_temporary: bool = True
    packed_phase: bool = False

    @property
    def writable(self): return True
    @property
    def reason(self): return None
    @property
    def scale(self): return 1 / self.divisor


TEMPORARY_DOUBLE_NOTE = '临时设置分两帧 FC06 写入，过程中保持中间值合法；异常后结果可能不完整，必须重新读取，不自动重试。'
FIELDS = [
    Field('max_output_current', 0x58, '最大输出电流（单路）', 'limits', 'A', divisor=100, minimum=50, maximum=6000),
    Field('max_forward_speed', 0x59, '正转最大速度', 'limits', 'RPM', 2, maximum=110000, note=TEMPORARY_DOUBLE_NOTE),
    Field('max_reverse_speed', 0x5B, '反转最大速度', 'limits', 'RPM', 2, maximum=110000, note=TEMPORARY_DOUBLE_NOTE),
    Field('min_speed', 0x5D, '最小转速', 'limits', 'RPM', 2, maximum=110000, note=TEMPORARY_DOUBLE_NOTE),
    Field('closed_loop_acceleration', 0x5F, '闭环加速度', 'limits', 'RPM/s', minimum=10),
    Field('closed_loop_deceleration', 0x60, '闭环减速度', 'limits', 'RPM/s', minimum=10),
    Field('automatic_reversal_mode', 0x64, '自动换向模式', 'motor', maximum=1, choices=((0, '正常换向'), (1, '急停换向'))),
    Field('hall_electrical_angle', 0x65, '霍尔电角度', 'motor', maximum=1, choices=((0, '60°'), (1, '120°'))),
    Field('sensor_type', 0x66, '传感器类型', 'motor', maximum=1, choices=((0, '霍尔'), (1, '霍尔 + AB 编码器'))),
]
for side, offset, name in (('right', 0, '右'), ('left', 9, '左')):
    FIELDS.extend([
        Field(f'{side}_pole_pairs', 0x68 + offset, f'{name}电机极对数', side, note='手册允许 0–65535；0 的实际含义未说明。'),
        *[Field(f'{side}_phase_{a}_{a+1}', 0x69 + offset + i, f'{name}电机第 {a}/{a+1} 相相序', side,
                maximum=0x0606, packed_phase=True,
                note=f'低字节为第 {a} 相，高字节为第 {a+1} 相；两个字节各为 0–6。')
          for i, a in enumerate((1, 3, 5))],
        Field(f'{side}_phase_learning_state', 0x6C + offset, f'{name}电机相序学习状态', side,
              maximum=1, choices=((0, '未学习'), (1, '已经学习')),
              note='写入标志不会执行相序学习；学习操作位于测试控制。'),
        Field(f'{side}_parking_current', 0x6D + offset, f'{name}电机电子驻车最大电流', side, 'A', divisor=100,
              maximum=6000, note='必须不超过最大输出电流。手册 0 值原文为“0：不停机锁相”，不据此宣称机械抱闸状态。'),
        Field(f'{side}_foc_compensation_angle', 0x6E + offset, f'{name}电机 FOC 相位补偿角', side, '°',
              signed=True, divisor=100, minimum=-18000, maximum=18000),
        Field(f'{side}_foc_advance_angle', 0x6F + offset, f'{name}电机 FOC 超前角', side, '°',
              signed=True, divisor=100, minimum=-18000, maximum=18000),
        Field(f'{side}_reverse', 0x70 + offset, f'{name}电机反向', side, maximum=1, choices=((0, '无'), (1, '反向'))),
    ])
RANGE_NOTE = '手册上限超过单个 16 位寄存器；仅接受可编码的 0–65535，上限冲突仍需厂家确认。'
FIELDS.extend([
    Field('overvoltage_threshold', 0x84, '过压保护电压', 'system', 'V', minimum=8, maximum=60),
    Field('undervoltage_threshold', 0x85, '欠压保护电压', 'system', 'V', minimum=8, maximum=60),
    Field('voltage_protection_enabled', 0x86, '电压保护', 'system', maximum=1, choices=((0, '不保护'), (1, '保护'))),
    Field('system_mode', 0x87, '系统模式', 'system', maximum=4, choices=((1, '独立速度闭环'), (3, '同源速度闭环'), (4, '差速闭环')),
          note='配置值 0/2 在本版手册中保留；状态寄存器可能报告这两个旧模式。'),
    Field('foc_closed_loop_mode', 0x89, 'FOC 闭环模式', 'system', maximum=1,
          choices=((0, '单速度闭环'), (1, '速度电流双闭环'))),
    Field('differential_turn_max_speed', 0x8A, '差速转弯最大速度', 'system', 'RPM', note='手册写 0–110000。' + RANGE_NOTE),
    Field('stall_stop_time', 0x8B, '堵转停机时间', 'system', 'ms', note='手册写 0–66635。' + RANGE_NOTE),
    Field('phase_overcurrent_threshold', 0x8C, '相过流关闭电流', 'system', 'A', divisor=100, minimum=100, maximum=8000),
])
for side, address, name in (('right', 0xB0, '右'), ('left', 0xB2, '左')):
    FIELDS.extend([
        Field(f'{side}_brake_output_type', address, f'{name}电机抱闸输出类型', 'brakes', maximum=2,
              choices=((0, '不输出'), (1, '停机自动刹车'), (2, '通讯控制'))),
        Field(f'{side}_stop_brake_enabled', address + 1, f'{name}电机停车刹车', 'brakes', maximum=1,
              choices=((0, '不生效'), (1, '停止刹车生效'))),
    ])
BAUD_CHOICES = tuple(enumerate(('9600', '19200', '38400', '57600', '115200')))
PARITY_CHOICES = ((0, 'N1'), (1, 'E1'), (2, 'O1'), (3, 'N2'))
for bus, address, label in (('rs485', 0xC8, 'RS485'), ('ttl', 0xD2, 'TTL')):
    FIELDS.extend([
        Field(f'{bus}_address', address, f'{label} 从站地址', 'communication', maximum=255,
              note='配置表为 0–255，帧地址说明为 1–254；0/255 写入后本工具不能据普通单播确认。' +
                   ('修改会影响当前 RS485 链路，请单项保存并使用新参数重新连接。' if bus == 'rs485' else '')),
        Field(f'{bus}_baud_rate_code', address+1, f'{label} 波特率', 'communication', maximum=4, choices=BAUD_CHOICES),
        Field(f'{bus}_parity_code', address+2, f'{label} 校验及停止位', 'communication', maximum=3, choices=PARITY_CHOICES),
        Field(f'{bus}_timeout_s', address+3, f'{label} 通信中断制动时间', 'communication', 's', note='手册单位为秒、上限 66635。' + RANGE_NOTE),
    ])
FIELDS.extend([
    Field('can_node_id', 0xCD, 'CAN 节点 ID', 'communication', maximum=127),
    Field('can_baud_rate_code', 0xCE, 'CAN 波特率', 'communication', maximum=13,
          choices=tuple(enumerate(('10 kbps', '20 kbps', '40 kbps', '50 kbps', '80 kbps', '100 kbps', '125 kbps',
                                   '200 kbps', '250 kbps', '400 kbps', '500 kbps', '600 kbps', '800 kbps', '1 Mbps')))),
    Field('can_timeout_s', 0xCF, 'CAN 通信中断制动时间', 'communication', 's', note='手册单位为秒、上限 66635。' + RANGE_NOTE),
])
for i, (field_id, label) in enumerate([
    ('speed_p', '速度环 P'), ('speed_i', '速度环 I'), ('speed_d', '速度环 D'),
    ('d_axis_p', 'D 轴 P'), ('d_axis_i', 'D 轴 I'), ('d_axis_d', 'D 轴 D'),
    ('q_axis_p', 'Q 轴 P'), ('q_axis_i', 'Q 轴 I'), ('q_axis_d', 'Q 轴 D'),
    ('parking_speed_p', '编码器驻车速度环 P'), ('parking_speed_i', '编码器驻车速度环 I'), ('parking_speed_d', '编码器驻车速度环 D'),
    ('parking_d_axis_p', '编码器驻车 D 轴 P'), ('parking_d_axis_i', '编码器驻车 D 轴 I'), ('parking_d_axis_d', '编码器驻车 D 轴 D'),
    ('parking_q_axis_p', '编码器驻车 Q 轴 P'), ('parking_q_axis_i', '编码器驻车 Q 轴 I'), ('parking_q_axis_d', '编码器驻车 Q 轴 D'),
    ('hall_parking_speed_p', '霍尔驻车速度环 P'),
]):
    suggested = 327680 if i in (0, 3, 6) else 326780
    FIELDS.append(Field('pid_' + field_id, 0xE0 + i*2, label, 'pid', words=2, divisor=1 if i == 18 else 32768,
                        maximum=0xFFFFFFFF, supports_temporary=False,
                        note=f'手册建议原始值 1–{suggested}，是建议而非硬限制。' + ('此项直接使用原始值，不除以 32768。' if i == 18 else '实际值 = 原始值 / 32768。')))
FIELDS = tuple(sorted(FIELDS, key=lambda field: field.address))
FIELD_BY_ID = {field.id: field for field in FIELDS}

ACTION_SPECS = (
    dict(id='right_stop', address=0x40, words=1, minimum=0, maximum=2, label='右电机停止', choices=((0,'正常停止'),(1,'紧急停止'),(2,'自由停止')), bounded=False),
    dict(id='right_target_speed', address=0x41, words=2, minimum=-110000, maximum=110000, label='右 / 直行目标速度', choices=(), bounded=True),
    dict(id='right_brake', address=0x43, words=1, minimum=0, maximum=1, label='右电机抱闸控制', choices=((0,'断开抱闸'),(1,'闭合抱闸')), bounded=False),
    dict(id='left_stop', address=0x44, words=1, minimum=0, maximum=2, label='左电机停止', choices=((0,'正常停止'),(1,'紧急停止'),(2,'自由停止')), bounded=False),
    dict(id='left_target_speed', address=0x45, words=2, minimum=-110000, maximum=110000, label='左 / 转弯目标速度', choices=(), bounded=True),
    dict(id='left_brake', address=0x47, words=1, minimum=0, maximum=1, label='左电机抱闸控制', choices=((0,'打开抱闸'),(1,'关闭抱闸')), bounded=False),
    dict(id='factory_reset', address=0x48, words=1, minimum=1, maximum=1, label='恢复出厂参数', choices=((1,'恢复出厂'),), bounded=False),
    dict(id='phase_learning', address=0x49, words=1, minimum=0, maximum=2, label='相序学习 / 取消测试', choices=((0,'取消测试'),(1,'左电机相序学习'),(2,'右电机相序学习')), bounded=True),
)
ACTION_BY_ID = {item['id']: item for item in ACTION_SPECS}

READ_ONLY_FIELDS = [
    dict(id='device_id', address=0, words=1, signed=False, divisor=1, label='设备标识号', unit=''),
    dict(id='device_version', address=1, words=1, signed=False, divisor=1, label='设备版本', unit='', note='高字节主版本、低字节副版本'),
    dict(id='device_max_current', address=2, words=1, signed=False, divisor=100, label='设备最大电流', unit='A'),
]
for side, base, name in (('right', 8, '右'), ('left', 0x12, '左')):
    for suffix, offset, words, signed, divisor, label, unit in (
        ('current', 0, 1, False, 100, '实时相电流', 'A'),
        ('speed', 1, 2, True, 1, '实时速度', 'RPM'),
        ('error', 3, 1, False, 1, '错误状态', ''),
        ('pwm', 4, 1, False, 10, '实时 PWM', '%'),
        ('temperature', 5, 1, True, 1, '电路板温度', '℃'),
        ('displacement', 7, 2, True, 1, '位移', '°'),
        ('reverse_status', 9, 1, False, 1, '反向状态', ''),
    ):
        READ_ONLY_FIELDS.append(dict(id=f'{side}_{suffix}', address=base+offset, words=words, signed=signed,
            divisor=divisor, label=f'{name}电机{label}', unit=unit,
            note='手册原值 0–10000、每单位 0.1%，存在百分比上限冲突；同时保留原始值。' if suffix == 'pwm' else ''))
for field_id, address, label, unit in (
    ('supply_voltage', 0x1C, '电源电压', 'V'), ('runtime_system_mode', 0x1D, '实时系统模式', ''),
    ('control_mode', 0x1E, '控制模式', ''), ('input_1', 0x1F, 'IN1 电平', ''), ('input_2', 0x20, 'IN2 电平', ''),
):
    READ_ONLY_FIELDS.append(dict(id=field_id, address=address, words=1, signed=False, divisor=1, label=label, unit=unit))


def catalog():
    return {'schemaVersion': 2, 'fields': [dict(id=f.id, address=f.address, label=f.label, group=f.group,
            unit=f.unit, words=f.words, signed=f.signed, divisor=f.divisor, minRaw=f.minimum, maxRaw=f.maximum,
            choices=[{'value': v, 'label': label} for v, label in f.choices], note=f.note,
            supportsTemporary=f.supports_temporary, packedPhase=f.packed_phase) for f in FIELDS],
            'readOnlyFields': READ_ONLY_FIELDS, 'actions': list(ACTION_SPECS)}


if __name__ == '__main__':
    import json
    print(json.dumps(catalog(), ensure_ascii=False, indent=2))
