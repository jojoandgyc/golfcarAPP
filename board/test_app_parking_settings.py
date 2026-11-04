"""In-memory unified parking regression tests. Never opens serial or networking."""
import copy,io,json,unittest
import queue
import subprocess
import sys
import threading
from pathlib import Path
from unittest.mock import patch
from contextlib import contextmanager
import app_parking_settings as settings
import app_parking_bridge as idle
import app_joystick_bridge as drive
from test_app_joystick_bridge import FakeClock,FakeClient
RID='12345678-1234-1234-1234-123456789abc'
def req(op='read',**extra):return dict(schemaVersion=2,requestId=RID,op=op,**extra)
class ParkingTests(unittest.TestCase):
 def setUp(self):self.io=settings.SimulatedIO()
 def test_read_current_derived_mode_even_with_pwm_and_nonzero_rpm(self):
  self.io.registers.update({0xC:130,0x16:140,0x13:65535,0x14:65535})
  r=settings.execute(req(),self.io)
  self.assertTrue(r['ok']);self.assertTrue(r['mode']);self.assertEqual(r['modeSource'],'current_readback');self.assertEqual(r['leftRpm'],-1);self.assertEqual(self.io.trace,[])
 def test_currents_only_zero_then_two_fc10_despite_pwm_and_noise(self):
  self.io.registers.update({0xC:130,0x16:140,0xA:1,0x14:1})
  r=settings.execute(req('currents',leftRaw=900,rightRaw=700),self.io)
  self.assertTrue(r['ok']);self.assertEqual(r['leftRpm'],1)
  self.assertEqual(self.io.trace,[(16,0x45,[0,0]),(16,0x41,[0,0]),(16,0x76,[900]),(16,0x6D,[700])])
  self.assertEqual([x['status'] for x in r['results']],['verified','verified']);self.assertEqual(self.io.registers[0x58],1500)
 def test_mode_commands_are_rejected_without_reads_or_any_write(self):
  for enabled in (True,False):
   with self.subTest(enabled=enabled),patch.object(self.io,'read',side_effect=AssertionError('must not read')):
    r=settings.execute(req('mode',enabled=enabled),self.io)
    self.assertFalse(r['ok']);self.assertEqual(r['errorCode'],'parking_mode_requires_currents')
    self.assertFalse(r['writeMayHaveOccurred']);self.assertEqual(self.io.trace,[])
 def test_zero_current_disables_both_sides_without_any_stop_mode_write(self):
  r=settings.execute(req('currents',leftRaw=0,rightRaw=0),self.io)
  self.assertTrue(r['ok']);self.assertFalse(r['mode']);self.assertEqual(r['modeSource'],'current_readback')
  self.assertEqual(self.io.trace,[(16,0x45,[0,0]),(16,0x41,[0,0]),(16,0x76,[0]),(16,0x6D,[0])])
 def test_each_side_mixed_and_positive_state_comes_from_read_values(self):
  for left,right,expected in ((0,0,False),(1,1,True),(800,500,True),(0,500,None),(500,0,None)):
   with self.subTest(left=left,right=right):
    self.io.registers.update({0x76:left,0x6D:right})
    r=settings.execute(req(),self.io)
    self.assertTrue(r['ok']);self.assertIs(r['mode'],expected);self.assertEqual(r['modeSource'],'current_readback')
    self.assertEqual((r['leftRaw'],r['rightRaw']),(left,right));self.assertEqual(self.io.trace,[])
 def test_old_schema_rejected_before_any_io(self):
  for op,values in (('read',{}),('currents',dict(leftRaw=0,rightRaw=0)),('mode',dict(enabled=False))):
   with self.subTest(op=op),patch.object(self.io,'read',side_effect=AssertionError('read')):
    r=settings.execute(dict(req(op,**values),schemaVersion=1),self.io)
    self.assertEqual(r['errorCode'],'unsupported_schema_version');self.assertEqual(r['schemaVersion'],2)
    self.assertEqual(r['parkingSemantics'],'current_zero_disables');self.assertFalse(r['writeMayHaveOccurred'])
  self.assertEqual(self.io.trace,[])
 def test_simulated_changes_are_only_in_memory_and_no_guessed_restore(self):
  self.assertTrue(settings.execute(req('currents',leftRaw=0,rightRaw=0),self.io)['ok'])
  r=settings.execute(req('mode',enabled=True),self.io)
  self.assertFalse(r['ok']);self.assertEqual(self.io.registers[0x76],0);self.assertEqual(self.io.registers[0x6D],0)
  fresh=settings.SimulatedIO();self.assertEqual(fresh.registers[0x76],800)
 def test_invalid_requests_no_io(self):
  for q in (req('currents',leftRaw=True,rightRaw=900),req('currents',leftRaw=-1,rightRaw=1),req('currents',leftRaw=6001,rightRaw=1),req('mode',enabled=1),req('read',enabled=False),req('arbitrary'),dict(req(),schemaVersion=True),dict(req(),requestId='bad')):
   with self.subTest(q=q),patch.object(self.io,'read',side_effect=AssertionError('must not read')):
    r=settings.execute(q,self.io);self.assertFalse(r['ok']);self.assertFalse(r['writeMayHaveOccurred'])
  self.assertEqual(self.io.trace,[])
 def test_fault_source_limit_block_config_but_still_zero_motion(self):
  for address,value,code in ((0x15,2,'motor_fault'),(0xB,2,'motor_fault'),(0x1E,0x39,'external_control_source'),(0x58,850,'current_limit_exceeded'),(2,1400,'invalid_current_limit')):
   with self.subTest(address=address):
    io_=settings.SimulatedIO();io_.registers[address]=value
    r=settings.execute(req('currents',leftRaw=900,rightRaw=900),io_)
    self.assertEqual(r['errorCode'],code);self.assertEqual(io_.trace,[(16,0x45,[0,0]),(16,0x41,[0,0])])
 def test_first_side_write_failure_unknown_other_not_attempted(self):
  self.io.write_current=lambda *_:(_ for _ in ()).throw(TimeoutError())
  r=settings.execute(req('currents',leftRaw=900,rightRaw=700),self.io)
  self.assertEqual([x['status'] for x in r['results']],['unknown','not_attempted']);self.assertIsNone(r['leftRaw']);self.assertEqual(r['rightRaw'],800)
 def test_second_side_failure_preserves_verified_first_no_retry(self):
  original=self.io.write_current
  def fail(side,raw):
   if side=='right':self.io.trace.append((16,0x6D,[raw]));raise TimeoutError()
   original(side,raw)
  self.io.write_current=fail
  r=settings.execute(req('currents',leftRaw=900,rightRaw=700),self.io)
  self.assertFalse(r['ok']);self.assertTrue(r['writeMayHaveOccurred']);self.assertEqual([x['status'] for x in r['results']],['verified','unknown']);self.assertEqual(r['leftRaw'],900);self.assertIsNone(r['rightRaw']);self.assertEqual(len(self.io.trace),4)
 def test_readback_mismatch_no_rollback(self):
  self.io.write_current=lambda side,raw:self.io.trace.append((16,settings.CURRENT_REGISTERS[side],[raw]))
  r=settings.execute(req('currents',leftRaw=900,rightRaw=700),self.io)
  self.assertEqual(r['errorCode'],'readback_mismatch');self.assertEqual(r['results'][0]['actualRaw'],800);self.assertEqual(r['results'][1]['status'],'not_attempted')
 def test_zero_failure_prevents_reads_and_config(self):
  self.io.zero_targets=lambda:(_ for _ in ()).throw(settings.ParkingError('zero_target_unconfirmed'))
  with patch.object(self.io,'read',side_effect=AssertionError('must stop first')):
   r=settings.execute(req('currents',leftRaw=900,rightRaw=900),self.io)
  self.assertEqual(r['errorCode'],'zero_target_unconfirmed');self.assertTrue(r['writeMayHaveOccurred'])
 def test_partial_unknown_write_cannot_claim_either_on_or_off(self):
  self.io.write_current=lambda *_:(_ for _ in ()).throw(TimeoutError())
  r=settings.execute(req('currents',leftRaw=0,rightRaw=0),self.io)
  self.assertIsNone(r['mode']);self.assertEqual(r['modeSource'],'unknown')
  self.assertIsNone(r['leftRaw']);self.assertEqual(r['rightRaw'],800)
 def test_stop_occurs_before_slow_reader_and_deadline_bounds_config(self):
  clock=FakeClock();original=self.io.read
  def slow(a,n):
   self.assertEqual(self.io.trace[:2],[(16,0x45,[0,0]),(16,0x41,[0,0])]);clock.sleep(.4);return original(a,n)
  self.io.read=slow
  with patch.object(settings.time,'monotonic',clock.monotonic):r=settings.execute(req('currents',leftRaw=900,rightRaw=900),self.io)
  self.assertEqual(r['errorCode'],'parking_transaction_timeout');self.assertEqual(len(self.io.trace),2)
 def test_idle_preopen_error_nullable_and_simulation_flag(self):
  r=idle.process(req(),system=None);self.assertFalse(r['ok']);self.assertIsNone(r['leftRaw']);self.assertIsNone(r['leftRpm']);self.assertFalse(r['simulation'])
  self.assertTrue(idle.process(req(),simulate=True,system=object())['simulation'])
 def test_idle_rejects_mode_before_root_check_lock_or_open(self):
  for enabled in (False,True):
   with self.subTest(enabled=enabled):
    result=idle.process(req('mode',enabled=enabled),simulate=False,system=object(),
      io_factory=lambda:(_ for _ in ()).throw(AssertionError('opened')))
    self.assertEqual(result['errorCode'],'parking_mode_requires_currents')
    self.assertFalse(result['writeMayHaveOccurred']);self.assertFalse(result['simulation'])
    self.assertEqual(result['parkingSemantics'],'current_zero_disables')
 def test_only_an_explicit_positive_current_request_can_enable_after_zero(self):
  self.assertFalse(settings.execute(req('currents',leftRaw=0,rightRaw=0),self.io)['mode'])
  self.io.trace.clear()
  for _ in range(3):self.assertFalse(settings.execute(req(),self.io)['mode'])
  self.assertEqual(self.io.trace,[])
  result=settings.execute(req('currents',leftRaw=300,rightRaw=500),self.io)
  self.assertTrue(result['mode']);self.assertEqual((result['leftRaw'],result['rightRaw']),(300,500))
  self.assertEqual(self.io.trace[-2:],[(16,0x76,[300]),(16,0x6D,[500])])
 def test_busy_owner_prevents_open(self):
  class Busy:
   def is_root(self):return True
   @contextmanager
   def idle_guard(self):raise settings.ParkingError('control_busy_or_unconfirmed');yield
  r=idle.process(req(),system=Busy(),io_factory=lambda:(_ for _ in ()).throw(AssertionError('opened')))
  self.assertEqual(r['errorCode'],'control_busy_or_unconfirmed')
 def test_close_error_preserves_successful_or_partial_write_evidence(self):
  for partial in (False,True):
   with self.subTest(partial=partial):
    backend=settings.SimulatedIO();backend.close=lambda:(_ for _ in ()).throw(OSError())
    if partial:
     original=backend.write_current
     def write(side,raw):
      if side=='right':raise TimeoutError()
      original(side,raw)
     backend.write_current=write
    r=idle.process(req('currents',leftRaw=900,rightRaw=700),simulate=True,io_factory=lambda:backend)
    self.assertFalse(r['ok']);self.assertTrue(r['writeMayHaveOccurred']);self.assertTrue(r['simulation']);self.assertEqual(r['results'][0]['status'],'verified');self.assertEqual(r['results'][1]['status'],'unknown' if partial else 'verified')
 def test_duplicate_input_fields_rejected(self):
  with self.assertRaises(settings.ParkingError):idle.read_request(io.BytesIO(b'{"op":"read","op":"mode"}'))
 def test_one_line_request_does_not_consume_later_input(self):
  stream=io.BytesIO(json.dumps(req()).encode()+b'\n'+b'{"unused":true}\n')
  self.assertEqual(idle.read_request(stream),req())
  self.assertEqual(stream.read(),b'{"unused":true}\n')
 def test_same_line_extra_json_and_oversized_line_are_rejected(self):
  for payload,code in ((json.dumps(req()).encode()+b' {}\n','invalid_json'),
                       (b' '*2049+b'\n','request_too_large')):
   with self.subTest(code=code),self.assertRaisesRegex(settings.ParkingError,code):
    idle.read_request(io.BytesIO(payload))
 def test_cli_replies_to_newline_with_stdin_still_open(self):
  process=subprocess.Popen([sys.executable,'-u',str(Path(idle.__file__).resolve()),
      '--request','--simulate'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  replies=queue.Queue()
  reader=threading.Thread(target=lambda:replies.put(process.stdout.readline()),daemon=True)
  try:
   reader.start()
   process.stdin.write(json.dumps(req()).encode()+b'\n');process.stdin.flush()
   self.assertFalse(process.stdin.closed)
   try:line=replies.get(timeout=2)
   except queue.Empty:self.fail('newline request did not receive a reply within 2 seconds while stdin stayed open')
   result=json.loads(line)
   self.assertTrue(result['ok']);self.assertTrue(result['simulation'])
   self.assertEqual(result['requestId'],RID);self.assertEqual(result['op'],'read')
   self.assertFalse(process.stdin.closed)
   self.assertEqual(process.wait(timeout=2),0)
  finally:
   if process.poll() is None:process.kill()
   process.wait(timeout=2)
   for stream in (process.stdin,process.stdout,process.stderr):stream.close()
   reader.join(timeout=2)
class ModbusFrameTests(unittest.TestCase):
 def test_real_codec_simulator_only_emits_allowlisted_writes_and_read_is_pure(self):
  import struct
  from app_motor_config_bridge import Modbus,SimulatedPort
  transport=SimulatedPort();backend=settings.ModbusIO(Modbus(transport,timeout=.15),transport)
  before=copy.deepcopy(transport.persisted)
  r=settings.execute(req(),backend);self.assertTrue(r['ok']);self.assertTrue(all(frame[1]==3 for frame in transport.requests))
  transport.requests.clear()
  r=settings.execute(req('currents',leftRaw=500,rightRaw=600),backend);self.assertTrue(r['ok'])
  writes=[(frame[1],struct.unpack('>H',frame[2:4])[0]) for frame in transport.requests if frame[1]!=3]
  self.assertEqual(writes,[(16,0x45),(16,0x41),(16,0x76),(16,0x6D)])
  for address,value in before.items():
   if address not in (0x76,0x6D):self.assertEqual(transport.persisted[address],value)
  transport.requests.clear();r=settings.execute(req('currents',leftRaw=0,rightRaw=0),backend)
  self.assertTrue(r['ok']);self.assertFalse(r['mode'])
  self.assertFalse(any(frame[1]==6 for frame in transport.requests))
  self.assertEqual((transport.persisted[0x76],transport.persisted[0x6D]),(0,0))
  transport.requests.clear();r=settings.execute(req('mode',enabled=True),backend)
  self.assertFalse(r['ok']);self.assertEqual(transport.requests,[])
 def test_driver_adapter_attempts_both_zero_targets_and_has_no_mode_switch(self):
  class Client:
   def __init__(self):self.calls=[]
   def set_left_speed(self,value):self.calls.append(('left',value));raise TimeoutError()
   def set_right_speed(self,value):self.calls.append(('right',value))
  client=Client();adapter=settings.DriverIO(client)
  with self.assertRaises(settings.ParkingError):adapter.zero_targets()
  self.assertEqual(client.calls,[('left',0),('right',0)])
  self.assertFalse(hasattr(adapter,'stop'))

class OwnerTests(unittest.TestCase):
 def setUp(self):
  self.clock=FakeClock();p=patch.object(drive.time,'monotonic',self.clock.monotonic);p.start();self.addCleanup(p.stop)
  self.motors=drive.Motors();self.session=drive.ProtocolSession(self.motors,port='dry-run',dry_run=True);self.ready=self.session.ready()
 def send(self,op,**extra):return self.session.accept(dict(seq=self.session.next_seq,token=self.session.token,cmd='parking_settings',left=0,right=0,schemaVersion=2,parkingSemantics=settings.PARKING_SEMANTICS,op=op,**extra))[0]
 def neutral(self):return self.session.accept(dict(seq=self.session.next_seq,token=self.session.token,cmd='stop',left=0,right=0))[0]
 def test_capability_and_updated_current_heartbeat(self):
  self.assertTrue(self.ready['unifiedParkingSupported']);self.assertEqual(self.ready['parkingSemantics'],settings.PARKING_SEMANTICS);ack=self.send('currents',leftRaw=900,rightRaw=700)
  self.assertTrue(ack['parkingResult']['ok']);self.assertTrue(ack['parkingResult']['simulation']);self.assertEqual(ack['parkingLeftCurrentAmps'],9);self.assertEqual(self.neutral()['parkingRightCurrentAmps'],7)
 def test_read_during_motion_rejects_with_true_targets_and_no_write(self):
  self.motors.drive(20,-20);ack=self.send('read')
  self.assertEqual((ack['left'],ack['right']),(20,-20));self.assertFalse(ack['targetStopped']);self.assertEqual(ack['parkingResult']['errorCode'],'motion_targets_active');self.assertIsNone(self.motors.parking_simulation)
 def test_pending_currents_do_not_clear_pending_or_held_motion_gate(self):
  self.motors.standstill_pending=True;self.motors.standstill_wait_at=self.clock.now
  ack=self.send('currents',leftRaw=900,rightRaw=700)
  self.assertFalse(ack['parkingEnabled']);self.assertTrue(ack['parkingMode']);self.assertTrue(ack['standstillPending']);self.assertTrue(ack['recoveryRequired'])
  with patch.object(self.motors,'_sample_feedback'),patch.object(self.motors,'release_motion_recovery'):
   for _ in range(3):self.clock.sleep(.21);self.neutral();self.assertTrue(self.motors.emergency_hold)
   self.assertEqual(self.motors.drive(30,-30),(0,0));self.assertTrue(self.motors.standstill_pending)
 def test_zero_current_state_survives_network_emergency(self):
  ack=self.send('currents',leftRaw=0,rightRaw=0)
  self.assertFalse(ack['parkingResult']['mode']);self.assertFalse(ack['parkingMode'])
  self.session.pause_for_network();self.assertTrue(self.motors.emergency_hold)
  ack=self.neutral();self.assertFalse(ack['parkingMode']);self.assertEqual(ack['parkingModeSource'],'current_readback')
 def test_owner_mode_rejection_is_nonfatal_and_does_not_touch_io_or_motion(self):
  self.motors.drive(20,-20)
  ack=self.send('mode',enabled=False)
  self.assertFalse(ack['parkingResult']['ok']);self.assertEqual(ack['parkingResult']['errorCode'],'parking_mode_requires_currents')
  self.assertFalse(ack['parkingResult']['writeMayHaveOccurred']);self.assertEqual((ack['left'],ack['right']),(20,-20))
  self.assertEqual(self.motors.parking_simulation.trace,[])
  self.assertTrue(self.neutral()['targetStopped'])
 def test_owner_missing_schema_or_wrong_semantics_rejected_before_io(self):
  base=dict(seq=1,token=self.session.token,cmd='parking_settings',left=0,right=0,op='currents',leftRaw=0,rightRaw=0)
  for values in (base,dict(base,schemaVersion=2,parkingSemantics='stop_mode')):
   with self.subTest(values=values),self.assertRaises(drive.SessionError):self.session.accept(values)
  self.assertIsNone(self.motors.parking_simulation)
 def test_settings_requires_neutral_before_new_motion(self):
  self.send('currents',leftRaw=900,rightRaw=900);self.assertEqual(self.motors.drive(30,-30),(0,0));self.neutral();self.assertEqual(self.motors.drive(30,-30),(30,-30))
 def test_invalid_token_before_io(self):
  with self.assertRaises(drive.SessionError):self.session.accept(dict(seq=1,token='0'*32,cmd='parking_settings',left=0,right=0,schemaVersion=2,parkingSemantics=settings.PARKING_SEMANTICS,op='mode',enabled=True))
  self.assertIsNone(self.motors.parking_simulation)
 def test_rejected_modes_do_not_extend_motion_or_session_lease(self):
  messages=[];deadlines=[];clock=self.clock;epoch=[None]
  class Output:
   def __init__(self,_fd):pass
   def send(self,message):
    if message['type']=='ready':epoch[0]=clock.now
    messages.append(message)
  class Input:
   def __init__(self,_fd):self.calls=0
   def line(self,deadline,_stopped):
    deadlines.append(deadline-epoch[0]);self.calls+=1
    if self.calls<=2:
     clock.now=epoch[0]+self.calls*.2
     return json.dumps(dict(seq=self.calls,token=messages[-1]['token'],cmd='parking_settings',
       left=0,right=0,schemaVersion=2,parkingSemantics=settings.PARKING_SEMANTICS,
       op='mode',enabled=False)).encode()
    clock.now=deadline
    raise drive.MotionLeaseExpired('deadline')
  with patch.object(drive,'JsonOutput',Output),patch.object(drive,'InputPump',Input):
   result=drive.run_session(self.motors,'dry-run',True,threading.Event(),diagnostic=drive.NullDiagnosticLog())
  self.assertEqual(result,1)
  self.assertEqual([m['type'] for m in messages],['ready','ack','ack','error'])
  self.assertEqual(len(deadlines),4)
  for deadline in deadlines[:3]:self.assertAlmostEqual(deadline,.6)
  self.assertAlmostEqual(deadlines[-1],drive.NETWORK_GRACE_SECONDS)
  self.assertIn('network grace expired',messages[-1]['error'])
 def test_nested_reply_4096_bound(self):
  ack=self.send('currents',leftRaw=900,rightRaw=900);self.assertLessEqual(len(json.dumps(ack,separators=(',',':')).encode())+1,4096)

class HardwareOwnerTests(unittest.TestCase):
 def setUp(self):
  self.clock=FakeClock()
  for p in (patch.object(drive.time,'monotonic',self.clock.monotonic),patch.object(drive.time,'sleep',self.clock.sleep)):
   p.start();self.addCleanup(p.stop)
  class Client(FakeClient):
   def read_holding_registers(client,a,n):
    if (a,n)==(9,13):return super(Client,client).read_holding_registers(a,n)
    if (a,n)==(0,3):return [0x30EA,256,6000]
    if (a,n)==(0x58,1):return [round(client.registers['max_output_current']*100)]
    if (a,n) in ((0x76,1),(0x6D,1)):return [round(client.registers['left_parking_current' if a==0x76 else 'right_parking_current']*100)]
    if (a,n)==(0x1C,5):return [24,1,0x38,0,0]
    if (a,n) in ((8,6),(0x12,6)):
     rpm=client.feedback[1 if a==8 else 0];error=client.feedback[3 if a==8 else 2]
     return [100,(rpm>>16)&65535,rpm&65535,error,100,25]
    raise AssertionError((a,n))
   def write_multiple_registers(client,a,raw):
    client.calls.append(('fc10',a,list(raw)))
    client.registers['left_parking_current' if a==0x76 else 'right_parking_current']=raw[0]/100
  self.client=Client();self.motors=drive.Motors(self.client);self.session=drive.ProtocolSession(self.motors,port='fake',dry_run=False);self.session.ready()
 def send(self,op,**extra):return self.session.accept(dict(seq=self.session.next_seq,token=self.session.token,cmd='parking_settings',left=0,right=0,schemaVersion=2,parkingSemantics=settings.PARKING_SEMANTICS,op=op,**extra))[0]
 def neutral(self):return self.session.accept(dict(seq=self.session.next_seq,token=self.session.token,cmd='stop',left=0,right=0))[0]
 def test_pending_positive_currents_preserve_emergency_refresh_and_block_held_motion(self):
  self.motors.standstill_seen=False;self.motors.standstill_pending=True;self.motors.standstill_wait_at=self.clock.now
  self.client.feedback=(1,-1,0,0);ack=self.send('currents',leftRaw=800,rightRaw=700)
  self.assertFalse(ack['parkingEnabled']);self.assertTrue(ack['parkingMode']);self.assertFalse(ack['stopped']);self.client.calls.clear()
  for _ in range(4):self.clock.sleep(.21);self.neutral()
  modes=[call[2] for call in self.client.calls if len(call)==3 and call[1]=='stop']
  self.assertGreaterEqual(len(modes),4);self.assertTrue(all(mode==1 for mode in modes))
  self.assertEqual(self.motors.drive(40,-40),(0,0));self.assertNotIn(('left',40),self.client.calls)
  self.assertTrue(self.motors.standstill_pending)
 def test_zero_currents_and_network_refresh_only_use_emergency_stop(self):
  self.send('currents',leftRaw=0,rightRaw=0);self.client.calls.clear();self.clock.sleep(.21);self.neutral()
  self.assertIn(('left','stop',1),self.client.calls);self.assertIn(('right','stop',1),self.client.calls)
  self.client.calls.clear();self.session.pause_for_network()
  self.assertIn(('left','stop',1),self.client.calls);self.assertIn(('right','stop',1),self.client.calls)
  self.assertEqual((self.client.registers['left_parking_current'],self.client.registers['right_parking_current']),(0,0))
 def test_hard_feedback_error_still_trips_without_erasing_current_observation(self):
  self.send('currents',leftRaw=800,rightRaw=700);before=dict(self.client.registers)
  self.client.feedback=(0,0,9,0);self.clock.sleep(.11)
  with self.assertRaisesRegex(drive.SessionError,'Motor fault'):self.motors.poll_safety()
  self.assertIn(('right','stop',1),self.client.calls);self.assertEqual(self.client.registers,before)
  self.assertTrue(self.motors.parking_state()['parkingMode'])
 def test_close_after_settings_still_emergency_and_preserves_currents(self):
  self.send('currents',leftRaw=800,rightRaw=700);before=dict(self.client.registers);self.client.calls.clear()
  ack,closing=self.session.accept(dict(seq=self.session.next_seq,token=self.session.token,cmd='close',left=0,right=0))
  self.assertTrue(closing);self.assertTrue(ack['stopped']);self.assertIn(('left','stop',1),self.client.calls);self.assertEqual(self.client.registers,before)
  self.assertTrue(ack['parkingMode']);self.assertEqual(ack['parkingModeSource'],'current_readback')
 def test_mode_and_legacy_switch_rejections_do_not_touch_real_adapter(self):
  before=dict(self.client.registers);self.client.calls.clear()
  with patch.object(self.client,'read_holding_registers',side_effect=AssertionError('unexpected read')):
   ack=self.send('mode',enabled=False)
   self.assertEqual(ack['parkingResult']['errorCode'],'parking_mode_requires_currents')
   with self.assertRaisesRegex(drive.SessionError,'Legacy parking mode'):
    self.session.accept(dict(seq=self.session.next_seq,token=self.session.token,cmd='parking',left=0,right=0,parkingEnabled=False))
  self.assertEqual(self.client.calls,[]);self.assertEqual(self.client.registers,before)
 def test_shared_timeout_partial_ack_is_sent_before_outer_hard_exit(self):
  import threading
  messages=[];sent_at=[];clock=self.clock;client=self.client
  original=client.write_multiple_registers
  def slow_right(a,values):
   original(a,values)
   if a==0x6D:clock.sleep(2.01)
  client.write_multiple_registers=slow_right
  class Output:
   def __init__(self,_fd):pass
   def send(self,message):messages.append(message);sent_at.append(clock.now)
  class Input:
   def __init__(self,_fd):pass
   def line(self,_deadline,_stop):
    return json.dumps(dict(seq=1,token=messages[0]['token'],cmd='parking_settings',left=0,right=0,schemaVersion=2,parkingSemantics=settings.PARKING_SEMANTICS,op='currents',leftRaw=800,rightRaw=700)).encode()
  with patch.object(drive,'JsonOutput',Output),patch.object(drive,'InputPump',Input):
   exit_code=drive.run_session(self.motors,'fake',False,threading.Event(),diagnostic=drive.NullDiagnosticLog())
  self.assertEqual(exit_code,1)
  ack=next(message for message in messages if message['type']=='ack')
  self.assertEqual(ack['parkingResult']['errorCode'],'parking_transaction_timeout')
  self.assertEqual([entry['status'] for entry in ack['parkingResult']['results']],['verified','unknown'])
  self.assertLess(sent_at[messages.index(ack)]-sent_at[0],3)
  self.assertEqual(messages[-1]['type'],'error')
 def test_feedback_age_keeps_time_of_observation_before_write(self):
  original=self.client.write_multiple_registers
  def delayed(a,values):
   original(a,values)
   if a==0x6D:self.clock.sleep(.2)
  self.client.write_multiple_registers=delayed
  ack=self.send('currents',leftRaw=800,rightRaw=700)
  self.assertTrue(ack['parkingResult']['ok']);self.assertGreaterEqual(ack['feedbackAgeMs'],199)
 def test_current_derived_mixed_observation_survives_network_and_close(self):
  ack=self.send('currents',leftRaw=0,rightRaw=700)
  self.assertIsNone(ack['parkingMode']);self.assertEqual(ack['parkingModeSource'],'current_readback')
  self.session.pause_for_network();neutral=self.neutral()
  self.assertIsNone(neutral['parkingMode']);self.assertEqual(neutral['parkingModeSource'],'current_readback')
  self.assertEqual((neutral['parkingLeftCurrentAmps'],neutral['parkingRightCurrentAmps']),(0,7))
 def test_failed_write_marks_mode_unknown_until_a_read_observes_actual_currents(self):
  original=self.client.write_multiple_registers
  def fail(a,raw):
   original(a,raw)
   if a==0x6D:raise TimeoutError()
  self.client.write_multiple_registers=fail
  ack=self.send('currents',leftRaw=0,rightRaw=0)
  self.assertIsNone(ack['parkingResult']['rightRaw']);self.assertIsNone(ack['parkingMode'])
  self.assertEqual(ack['parkingModeSource'],'unknown');self.assertEqual(ack['parkingResult']['modeSource'],'unknown')
  # The independent shared reader can resolve the uncertain register without writes.
  backend=settings.DriverIO(self.client);self.client.calls.clear()
  r=settings.execute(req(),backend)
  self.assertFalse(r['mode']);self.assertEqual(r['modeSource'],'current_readback');self.assertEqual(self.client.calls,[])
 def test_current_change_uses_vendor_raw_fc10_and_followup_actual_values(self):
  self.motors.standstill_seen=False;self.client.feedback=(1,-1,0,0)
  ack=self.send('currents',leftRaw=800,rightRaw=700)
  self.assertTrue(ack['parkingResult']['ok']);self.assertFalse(ack['parkingResult']['simulation'])
  self.assertIn(('fc10',0x76,[800]),self.client.calls);self.assertIn(('fc10',0x6D,[700]),self.client.calls)
  self.assertEqual((ack['parkingLeftCurrentAmps'],ack['parkingRightCurrentAmps']),(8,7))
  self.assertFalse(any(call[0]=='write' for call in self.client.calls))

if __name__=='__main__':unittest.main()
