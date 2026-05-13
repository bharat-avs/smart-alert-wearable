package com.example.safety;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.telephony.SmsManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.location.LocationManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    // Predictive Risk UI Variables
    private androidx.cardview.widget.CardView riskBannerCard;
    private android.widget.TextView riskTitleText;
    private android.widget.TextView riskReasonText;

    private static final String ESP32_MAC_ADDRESS = "1C:C3:AB:A0:73:72";
    private EditText etContacts;
    private CheckBox cbPolice;
    private CheckBox cbAmbulance;
    private TextView tvStatus;
    private String lastSosMessage = "";
    private FusedLocationProviderClient fusedLocationClient;
    private SharedPreferences prefs;

    // Timer Variables
    private Button btnCancel;
    private TextView tvTimer;
    private CountDownTimer sosCountdown;
    private boolean isSosTriggered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Link the Predictive Risk UI elements
        riskBannerCard = findViewById(R.id.riskBannerCard);
        riskTitleText = findViewById(R.id.riskTitleText);
        riskReasonText = findViewById(R.id.riskReasonText);

        // 2. Link the standard SOS App elements
        etContacts = findViewById(R.id.etContacts);
        cbPolice = findViewById(R.id.cbPolice);
        cbAmbulance = findViewById(R.id.cbAmbulance);
        tvStatus = findViewById(R.id.tvStatus);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnConnect = findViewById(R.id.btnConnect);
        btnCancel = findViewById(R.id.btnCancel);
        tvTimer = findViewById(R.id.tvTimer);

        // 3. Initialize services
        prefs = getSharedPreferences("SafetyPrefs", MODE_PRIVATE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        etContacts.setText(prefs.getString("contacts", ""));
        cbPolice.setChecked(prefs.getBoolean("police", false));
        cbAmbulance.setChecked(prefs.getBoolean("ambulance", false));

        createNotificationChannel();
        requestPermissions();
        checkGPSAndNotify();

        // 4. Test the predictor (Simulating Outer Ring Road for the demo)
        android.location.Location dummyLocation = new android.location.Location("");
        dummyLocation.setLatitude(12.9250);
        dummyLocation.setLongitude(77.6840);
        updateRiskUI(dummyLocation);

        // 5. Set up button listeners
        btnSave.setOnClickListener(v -> {
            prefs.edit()
                    .putString("contacts", etContacts.getText().toString())
                    .putBoolean("police", cbPolice.isChecked())
                    .putBoolean("ambulance", cbAmbulance.isChecked())
                    .apply();
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show();
        });

        btnConnect.setOnClickListener(v -> connectToESP32());
        btnCancel.setOnClickListener(v -> cancelSOS());
    }

    // --- HELPER METHOD FOR RISK PREDICTOR ---
    private void updateRiskUI(android.location.Location location) {
        RiskPredictor.RiskResult analysis = RiskPredictor.evaluateRisk(location);

        if (analysis.isHighRisk) {
            riskBannerCard.setCardBackgroundColor(android.graphics.Color.parseColor("#FF5722")); // Danger Orange
            riskTitleText.setText(analysis.title);
            riskReasonText.setText(analysis.reason);
        } else {
            riskBannerCard.setCardBackgroundColor(android.graphics.Color.parseColor("#4CAF50")); // Safe Green
            riskTitleText.setText(analysis.title);
            riskReasonText.setText(analysis.reason);
        }
    }

    // --- STANDARD APP METHODS ---
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("SOS_CHANNEL", "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifications for SOS triggers");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void requestPermissions() {
        String[] perms = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS
        };
        ActivityCompat.requestPermissions(this, perms, 1);
    }

    @SuppressLint("MissingPermission")
    private void connectToESP32() {
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null || !btAdapter.isEnabled()) {
            tvStatus.setText("Status: Please enable Bluetooth");
            return;
        }

        tvStatus.setText("Status: connecting directly to wearable... ");
        try {
            BluetoothDevice device = btAdapter.getRemoteDevice(ESP32_MAC_ADDRESS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                device.connectGatt(this, false, gattCallback);
            }
        } catch (IllegalArgumentException e) {
            tvStatus.setText("Status: Error - Invalid MAC Address");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> tvStatus.setText("Status: Connected to Wearable!"));
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> tvStatus.setText("Status: Disconnected"));
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                UUID UART_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
                UUID TX_CHAR = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

                if (gatt.getService(UART_SERVICE) != null) {
                    BluetoothGattCharacteristic tx = gatt.getService(UART_SERVICE).getCharacteristic(TX_CHAR);
                    gatt.setCharacteristicNotification(tx, true);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            String message = new String(characteristic.getValue());
            if (message.contains("SOS")) {
                lastSosMessage = message;
                runOnUiThread(() -> {
                    if (!isSosTriggered) {
                        startSosCountdown();
                    }
                });
            }
        }
    };

    private void startSosCountdown() {
        isSosTriggered = true;
        btnCancel.setVisibility(View.VISIBLE);
        tvTimer.setVisibility(View.VISIBLE);
        tvStatus.setText("Status: SOS DETECTED! Waiting 30s to verify...");

        showNotification("SOS Detected!", "Sending texts in 30 seconds. Tap Cancel in app if false alarm.");

        sosCountdown = new CountDownTimer(30000, 1000) {
            public void onTick(long millisUntilFinished) {
                tvTimer.setText("Sending in: " + millisUntilFinished / 1000 + "s");
            }

            public void onFinish() {
                btnCancel.setVisibility(View.GONE);
                tvTimer.setVisibility(View.GONE);
                tvStatus.setText("Status: Timer finished! Fetching location...");
                triggerSOS();
                isSosTriggered = false;
            }
        }.start();
    }

    private void cancelSOS() {
        if (sosCountdown != null) {
            sosCountdown.cancel();
        }
        isSosTriggered = false;
        btnCancel.setVisibility(View.GONE);
        tvTimer.setVisibility(View.GONE);
        tvStatus.setText("Status: SOS Cancelled by user.");
        Toast.makeText(this, "Emergency Alert Cancelled", Toast.LENGTH_SHORT).show();
    }

    private void showNotification(String title, String content) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "SOS_CHANNEL")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        if (manager != null) {
            manager.notify(1, builder.build());
        }
    }

    @SuppressLint("MissingPermission")
    private void triggerSOS() {
        showNotification("SOS Sent!", "Emergency contacts have been notified.");

        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            sendSMS(location);
        }).addOnFailureListener(e -> {
            sendSMS(null);
        });
    }

    private void sendSMS(Location location) {
        String contactsStr = prefs.getString("contacts", "");
        boolean notifyPolice = prefs.getBoolean("police", false);

        String message = "EMERGENCY: I need help!";

        if (location != null) {
            String mapLink = "http://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
            message += " I am at: " + mapLink;
            runOnUiThread(() -> tvStatus.setText("Status: SOS Sent Successfully with GPS!"));
        } else {
            message += " (GPS is disabled on device, exact location unknown!)";
            runOnUiThread(() -> tvStatus.setText("Status: SOS Sent, but GPS was missing!"));
        }

        SmsManager smsManager = SmsManager.getDefault();

        if (!contactsStr.isEmpty()) {
            String[] contacts = contactsStr.split(",");
            for (String number : contacts) {
                smsManager.sendTextMessage(number.trim(), null, message, null, null);
            }
        }

        if (notifyPolice) {
            smsManager.sendTextMessage("100", null, message, null, null);
        }

        // --- AMBULANCE LOGIC IS NOW SAFELY INSIDE THE METHOD ---
        boolean notifyAmbulance = prefs.getBoolean("ambulance", false);
        if (notifyAmbulance && lastSosMessage.contains("Heart Rate")) {
            String medicalMessage = "MEDICAL EMERGENCY: User incapacitated (Heart Rate Anomaly detected). Need Ambulance immediately! ";
            if (location != null) {
                medicalMessage += "http://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
            }
            smsManager.sendTextMessage("108", null, medicalMessage, null, null);
        }
    }

    private void checkGPSAndNotify() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "SOS_CHANNEL")
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("GPS is Disabled")
                    .setContentText("Please turn on Location for accurate SOS coordinates.")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);

            if (manager != null) {
                manager.notify(2, builder.build());
            }
        }
    }
}