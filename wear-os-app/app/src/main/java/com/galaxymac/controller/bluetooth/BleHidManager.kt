package com.galaxymac.controller.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.*

/**
 * BleHidManager
 * Exposes the Galaxy Watch as a Native Bluetooth Low Energy Human Interface Device (BLE HID Mouse)
 * using the standard Bluetooth SIG HID over GATT Profile (HOGP).
 *
 * When paired, macOS recognizes the watch as a native Bluetooth Mouse, allowing direct
 * cursor navigation, left/right clicks, drag/selection, and bezel scrolling without any
 * server running on the Mac!
 */
@SuppressLint("MissingPermission")
class BleHidManager(private val context: Context) {
    private val TAG = "BleHidManager"

    // Bluetooth SIG Standard Service UUIDs
    val UUID_SERVICE_HID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    val UUID_SERVICE_DEV_INFO = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val UUID_SERVICE_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

    // Bluetooth SIG Characteristic UUIDs
    val UUID_CHAR_REPORT_MAP = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_REPORT = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_HID_INFO = UUID.fromString("00002a4a-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_PROTOCOL_MODE = UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_CONTROL_POINT = UUID.fromString("00002a4c-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_PNP_ID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")

    // Descriptors
    val UUID_DESC_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val UUID_DESC_REPORT_REF = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")

    /**
     * Standard USB HID Mouse Descriptor
     * 3 Buttons (Left, Right, Middle) + Relative X (-127..127) + Relative Y (-127..127) + Wheel (-127..127)
     */
    private val MOUSE_REPORT_MAP = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
        0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
        0x09.toByte(), 0x01.toByte(), //   USAGE (Pointer)
        0xA1.toByte(), 0x00.toByte(), //   COLLECTION (Physical)
        // Buttons (3 buttons: Left, Right, Middle)
        0x05.toByte(), 0x09.toByte(), //     USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(), //     USAGE_MINIMUM (Button 1)
        0x29.toByte(), 0x03.toByte(), //     USAGE_MAXIMUM (Button 3)
        0x15.toByte(), 0x00.toByte(), //     LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(), //     LOGICAL_MAXIMUM (1)
        0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
        0x75.toByte(), 0x01.toByte(), //     REPORT_SIZE (1)
        0x81.toByte(), 0x02.toByte(), //     INPUT (Data,Var,Abs)
        // Padding (5 bits)
        0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
        0x75.toByte(), 0x05.toByte(), //     REPORT_SIZE (5)
        0x81.toByte(), 0x01.toByte(), //     INPUT (Cnst,Ary,Abs)
        // Movement (X, Y) and Wheel (Z)
        0x05.toByte(), 0x01.toByte(), //     USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), //     USAGE (X)
        0x09.toByte(), 0x31.toByte(), //     USAGE (Y)
        0x09.toByte(), 0x38.toByte(), //     USAGE (Wheel)
        0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
        0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
        0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
        0xC0.toByte(),               //   END_COLLECTION
        0xC0.toByte()                // END_COLLECTION
    )

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var gattServer: BluetoothGattServer? = null
    private var leAdvertiser: BluetoothLeAdvertiser? = null

    private var reportCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null

    val isAdvertising = MutableStateFlow(false)
    val isConnected = MutableStateFlow(false)
    val connectedDeviceName = MutableStateFlow<String?>(null)
    val bluetoothSupported = MutableStateFlow(bluetoothAdapter != null)

    private var currentButtons: Byte = 0x00
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Start the BLE GATT Server and begin advertising as a Bluetooth Mouse
     */
    fun startBleHidServer(): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled or not supported.")
            return false
        }

        try {
            // Set custom Bluetooth adapter name if supported
            bluetoothAdapter.name = "Galaxy Watch Air Mouse"
        } catch (e: Exception) {
            Log.w(TAG, "Could not set Bluetooth adapter name: ${e.message}")
        }

        setupGattServer()
        startAdvertising()
        return true
    }

    private fun setupGattServer() {
        if (gattServer != null) return

        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT Server")
            return
        }

        // 1. HID Service
        val hidService = BluetoothGattService(UUID_SERVICE_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Protocol Mode Characteristic
        val protocolMode = BluetoothGattCharacteristic(
            UUID_CHAR_PROTOCOL_MODE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            value = byteArrayOf(0x01) // Report Protocol
        }
        hidService.addCharacteristic(protocolMode)

        // HID Information Characteristic
        val hidInfo = BluetoothGattCharacteristic(
            UUID_CHAR_HID_INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = byteArrayOf(0x01, 0x01, 0x00, 0x02) // v1.11, Country 0, Flags: Normally connectable
        }
        hidService.addCharacteristic(hidInfo)

        // Report Map Characteristic
        val reportMap = BluetoothGattCharacteristic(
            UUID_CHAR_REPORT_MAP,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = MOUSE_REPORT_MAP
        }
        hidService.addCharacteristic(reportMap)

        // HID Report Characteristic (Input Report)
        reportCharacteristic = BluetoothGattCharacteristic(
            UUID_CHAR_REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            value = byteArrayOf(0x00, 0x00, 0x00, 0x00)
            addDescriptor(BluetoothGattDescriptor(UUID_DESC_CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE).apply {
                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            })
            addDescriptor(BluetoothGattDescriptor(UUID_DESC_REPORT_REF, BluetoothGattDescriptor.PERMISSION_READ).apply {
                value = byteArrayOf(0x00, 0x01) // Report ID 0, Input Report 1
            })
        }
        hidService.addCharacteristic(reportCharacteristic)

        // HID Control Point
        val controlPoint = BluetoothGattCharacteristic(
            UUID_CHAR_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        hidService.addCharacteristic(controlPoint)

        gattServer?.addService(hidService)

        // 2. Battery Service
        val batteryService = BluetoothGattService(UUID_SERVICE_BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val batteryLevel = BluetoothGattCharacteristic(
            UUID_CHAR_BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = byteArrayOf(95) // 95%
        }
        batteryService.addCharacteristic(batteryLevel)
        gattServer?.addService(batteryService)

        // 3. Device Information Service
        val devInfoService = BluetoothGattService(UUID_SERVICE_DEV_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val pnpId = BluetoothGattCharacteristic(
            UUID_CHAR_PNP_ID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = byteArrayOf(0x02, 0xE8.toByte(), 0x04, 0x01, 0x00, 0x00, 0x01) // Vendor: Samsung
        }
        devInfoService.addCharacteristic(pnpId)
        gattServer?.addService(devInfoService)

        Log.i(TAG, "GATT Server configured with HID, Battery, and Device Information Services.")
    }

    private fun startAdvertising() {
        leAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser not available on this device.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(UUID_SERVICE_HID))
            .build()

        leAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    fun stopBleHidServer() {
        try {
            leAdvertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
            gattServer = null
            isConnected.value = false
            isAdvertising.value = false
            connectedDevice = null
            Log.i(TAG, "BLE HID Server stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE HID server: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising.value = true
            Log.i(TAG, "🎉 BLE HID Advertising started successfully as 'Galaxy Watch Air Mouse'")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising.value = false
            Log.e(TAG, "BLE HID Advertising failed with error code: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                isConnected.value = true
                connectedDeviceName.value = device?.name ?: device?.address ?: "Mac"
                Log.i(TAG, "✨ [Bluetooth HID] Connected to host: ${connectedDeviceName.value}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                isConnected.value = false
                connectedDeviceName.value = null
                Log.i(TAG, "👋 [Bluetooth HID] Host disconnected. Resuming advertising...")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val value = characteristic?.value ?: byteArrayOf()
            val response = if (offset < value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            val value = descriptor?.value ?: byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            descriptor?.value = value
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    /**
     * Sends a 4-byte Native HID Mouse Report to the connected Mac
     * [buttons, dx, dy, wheel]
     */
    fun sendMouseReport(buttons: Byte, dx: Byte, dy: Byte, wheel: Byte) {
        val dev = connectedDevice ?: return
        val char = reportCharacteristic ?: return

        val report = byteArrayOf(buttons, dx, dy, wheel)
        char.value = report

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(dev, char, false, report)
            } else {
                gattServer?.notifyCharacteristicChanged(dev, char, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send BLE HID report: ${e.message}")
        }
    }

    fun sendMouseMove(dx: Byte, dy: Byte) {
        sendMouseReport(currentButtons, dx, dy, 0)
    }

    fun sendLeftClick() {
        scope.launch {
            currentButtons = (currentButtons.toInt() or 0x01).toByte()
            sendMouseReport(currentButtons, 0, 0, 0)
            delay(25)
            currentButtons = (currentButtons.toInt() and 0x01.inv()).toByte()
            sendMouseReport(currentButtons, 0, 0, 0)
        }
    }

    fun sendRightClick() {
        scope.launch {
            currentButtons = (currentButtons.toInt() or 0x02).toByte()
            sendMouseReport(currentButtons, 0, 0, 0)
            delay(25)
            currentButtons = (currentButtons.toInt() and 0x02.inv()).toByte()
            sendMouseReport(currentButtons, 0, 0, 0)
        }
    }

    fun startDrag() {
        currentButtons = (currentButtons.toInt() or 0x01).toByte()
        sendMouseReport(currentButtons, 0, 0, 0)
    }

    fun endDrag() {
        currentButtons = (currentButtons.toInt() and 0x01.inv()).toByte()
        sendMouseReport(currentButtons, 0, 0, 0)
    }

    fun sendScroll(wheel: Byte) {
        sendMouseReport(currentButtons, 0, 0, wheel)
    }
}
