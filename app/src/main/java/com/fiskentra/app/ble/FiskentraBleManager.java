package com.fiskentra.app.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class FiskentraBleManager {
    public interface Listener {
        void onStatus(String status);
        void onDeviceFound(String name, String address, int rssi);
        void onConnected(String name, String address);
        void onButtonCandidate(byte[] payload);
    }

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning;

    public FiskentraBleManager(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean isSupported() { return adapter != null; }
    public boolean isEnabled() { return adapter != null && adapter.isEnabled(); }

    public boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public void scan() {
        if (!isSupported()) { listener.onStatus("Bluetooth LE not supported"); return; }
        if (!hasPermissions()) { listener.onStatus("Bluetooth permission needed"); return; }
        if (!isEnabled()) { listener.onStatus("Turn Bluetooth on first"); return; }
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { listener.onStatus("BLE scanner unavailable"); return; }
        seen.clear();
        scanning = true;
        listener.onStatus("Scanning nearby BLE devices…");
        scanner.startScan(scanCallback);
        handler.postDelayed(this::stopScan, 12000L);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanning && scanner != null && hasPermissions()) scanner.stopScan(scanCallback);
        if (scanning) listener.onStatus("Scan finished");
        scanning = false;
    }

    @SuppressLint("MissingPermission")
    public void connect(String address) {
        if (!hasPermissions() || adapter == null) return;
        stopScan();
        if (gatt != null) { gatt.close(); gatt = null; }
        listener.onStatus("Connecting…");
        BluetoothDevice device = adapter.getRemoteDevice(address);
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressLint("MissingPermission")
    public void close() {
        stopScan();
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            if (!seen.add(address)) return;
            String name = "BLE device";
            if (hasPermissions()) {
                try { if (device.getName() != null) name = device.getName(); } catch (SecurityException ignored) { }
            }
            if (result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            listener.onDeviceFound(name, address, result.getRssi());
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            listener.onStatus(String.format(Locale.US, "BLE scan error %d", errorCode));
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                String name = "BLE device";
                try { if (current.getDevice().getName() != null) name = current.getDevice().getName(); } catch (SecurityException ignored) { }
                listener.onConnected(name, current.getDevice().getAddress());
                listener.onStatus("Connected · discovering button channel");
                if (hasPermissions()) current.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onStatus("Device disconnected");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            if (!hasPermissions()) return;
            for (BluetoothGattService service : current.getServices()) {
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    int props = c.getProperties();
                    if ((props & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0) continue;
                    try {
                        current.setCharacteristicNotification(c, true);
                        BluetoothGattDescriptor descriptor = c.getDescriptor(CCCD);
                        if (descriptor != null) {
                            byte[] value = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                            if (Build.VERSION.SDK_INT >= 33) current.writeDescriptor(descriptor, value);
                            else { descriptor.setValue(value); current.writeDescriptor(descriptor); }
                        }
                    } catch (SecurityException ignored) { }
                }
            }
            listener.onStatus("Connected · listening for notifications");
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c, byte[] value) {
            listener.onButtonCandidate(value == null ? new byte[0] : value.clone());
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            listener.onButtonCandidate(value == null ? new byte[0] : value.clone());
        }
    };
}
