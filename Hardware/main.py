from machine import Pin, ADC, I2C
import bluetooth
import time
import struct
import math

# --- HARDWARE SETUP & PINS ---
BEEPER_PIN = 25  
GAS_PIN = 34
SWITCH_PIN = 27
PULSE_PIN = 35   
I2C_SCL = 21
I2C_SDA = 22     

# Initialize Beeper & Switch
beeper = Pin(BEEPER_PIN, Pin.OUT)
beeper.value(0)
sos_switch = Pin(SWITCH_PIN, Pin.IN, Pin.PULL_UP)

# Initialize ADCs
gas_sensor = ADC(Pin(GAS_PIN))
gas_sensor.atten(ADC.ATTN_11DB)
pulse_sensor = ADC(Pin(PULSE_PIN))
pulse_sensor.atten(ADC.ATTN_11DB)

# Initialize I2C & MPU6050
i2c = I2C(0, scl=Pin(I2C_SCL), sda=Pin(I2C_SDA), freq=400000)
MPU_ADDR = 0x68
try:
    i2c.writeto_mem(MPU_ADDR, 0x6B, b'\x00') # Wake up MPU
    print("MPU6050 initialized.")
except Exception as e:
    print("MPU6050 Error. Check SCL/SDA wiring.")

# --- THRESHOLDS & VARIABLES ---
GAS_THRESHOLD = 5000      # Gas raw value above this triggers anomaly
BPM_HIGH = 120             # High heart rate (Struggle/Danger)
BPM_LOW = 50               # Low heart rate (Unconsciousness/Removed)
MPU_JERK_THRESHOLD = 25000 # High G-force (Push/Fall)

# Anomaly Tracking Window (5 seconds)
ANOMALY_WINDOW_MS = 5000 
gas_anom_time = 0
pulse_anom_time = 0
mpu_anom_time = 0

# BPM Calculation Variables
last_beat_time = 0
bpm = 0
in_peak = False

# --- BLE SETUP ---
BLE_NAME = "ESP32-SOS-ALERT" 
ble = bluetooth.BLE()
ble.active(True)

UART_UUID = bluetooth.UUID('6E400001-B5A3-F393-E0A9-E50E24DCCA9E')
TX_UUID = bluetooth.UUID('6E400003-B5A3-F393-E0A9-E50E24DCCA9E')
RX_UUID = bluetooth.UUID('6E400002-B5A3-F393-E0A9-E50E24DCCA9E')
UART_SERVICE = (UART_UUID, ((TX_UUID, bluetooth.FLAG_NOTIFY), (RX_UUID, bluetooth.FLAG_WRITE),))
((tx_handle, rx_handle),) = ble.gatts_register_services((UART_SERVICE,))

def ble_advertise():
    name_bytes = bytes(BLE_NAME, 'utf-8')
    payload = bytearray([len(name_bytes) + 1, 0x09]) + name_bytes
    ble.gap_advertise(100, adv_data=payload)

def ble_send(message):
    try:
        ble.gatts_notify(0, tx_handle, message + '\n')
    except:
        pass

ble_advertise()

# --- MAIN LOOP ---
print("System Active. Monitoring Sensors...")
last_telemetry_time = time.ticks_ms()

while True:
    current_time = time.ticks_ms()
    
    # 1. READ SENSORS
    gas_val = gas_sensor.read()
    pulse_val = pulse_sensor.read()
    
    try:
        accel_data = i2c.readfrom_mem(MPU_ADDR, 0x3B, 6)
        ax, ay, az = struct.unpack('>hhh', accel_data)
        accel_mag = math.sqrt(ax**2 + ay**2 + az**2) 
    except:
        accel_mag = 0

    # 2. CALCULATE BPM (Simple Peak Detection)
    if pulse_val > 2300 and not in_peak:
        in_peak = True
        time_between_beats = time.ticks_diff(current_time, last_beat_time)
        if time_between_beats > 0:
            raw_bpm = 60000 / time_between_beats
            if 30 < raw_bpm < 220: # Filter out electrical noise
                bpm = raw_bpm
        last_beat_time = current_time
    elif pulse_val < 2000:
        in_peak = False

    # 3. CHECK SENSORS AGAINST THRESHOLDS
    if gas_val > GAS_THRESHOLD:
        gas_anom_time = current_time
        
    if bpm > BPM_HIGH or (bpm < BPM_LOW and bpm > 0):
        pulse_anom_time = current_time
        
    if accel_mag > MPU_JERK_THRESHOLD:
        mpu_anom_time = current_time

    # Determine if anomalies are currently active (happened within the last 5 seconds)
    gas_active = time.ticks_diff(current_time, gas_anom_time) < ANOMALY_WINDOW_MS
    pulse_active = time.ticks_diff(current_time, pulse_anom_time) < ANOMALY_WINDOW_MS
    mpu_active = time.ticks_diff(current_time, mpu_anom_time) < ANOMALY_WINDOW_MS
    
    # Count total active anomalies
    active_anomalies = sum([gas_active, pulse_active, mpu_active])

    # 4. SOS LOGIC & ALERTS
    manual_sos = (sos_switch.value() == 0) # 0 means switch is pressed/ON
    
    if manual_sos:
        print("SOS! MANUAL SWITCH ACTIVATED.")
        ble_send("SOS: MANUAL SWITCH PRESSED!")
        beeper.value(1)
        time.sleep(0.2)
        beeper.value(0)
        time.sleep(0.2)
        
    elif active_anomalies >= 2:
        # --- NEW CODE: Build a string of the specific triggered sensors ---
        trigger_names = []
        if gas_active: trigger_names.append("Gas")
        if pulse_active: trigger_names.append("Heart Rate")
        if mpu_active: trigger_names.append("MPU/Motion")
        names_str = " + ".join(trigger_names)
        
        print(f"SOS! AUTO TRIGGERED by: {names_str}")
        ble_send(f"SOS: AUTO TRIGGER! Sources: {names_str}")
        # ------------------------------------------------------------------
        
        beeper.value(1)
        time.sleep(0.5)
        beeper.value(0)
        time.sleep(0.5)
        
    else:
        # Normal state - keep beeper off
        beeper.value(0)

    # 5. SEND TELEMETRY TO TERMINAL (Every 2 seconds for debugging)
    if time.ticks_diff(current_time, last_telemetry_time) > 2000:
        print(f"BPM: {int(bpm)} | Gas: {gas_val} | MPU Mag: {int(accel_mag)} | Active Anomalies: {active_anomalies}")
        last_telemetry_time = current_time

    # Small delay to stabilize readings and prevent CPU hogging
    time.sleep(0.05)
