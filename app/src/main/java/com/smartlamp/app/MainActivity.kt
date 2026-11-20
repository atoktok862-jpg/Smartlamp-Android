package com.smartlamp.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceUuid: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val writeUuid: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null
    private var isConnecting = false
    private var userInitiatedDisconnect = false
    private var autoConnectPending = false
    private var scanCallback: ScanCallback? = null
    private val discoveredDevices = linkedMapOf<String, BluetoothDevice>()
    private var pendingWriteAfterPermission: String? = null
    private var pendingAction: (() -> Unit)? = null

    private val prefs by lazy { getSharedPreferences("smartlamp_ble", Context.MODE_PRIVATE) }

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (bluetoothAdapter?.isEnabled == true) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            jsStatus("Bluetooth выключен")
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            jsStatus("Нужны разрешения Bluetooth")
            jsDisconnected("Нет разрешений Bluetooth")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(BleBridge(), "AndroidBle")

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("file:///android_asset/index.html")
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (hasBlePermissions() && bluetoothAdapter?.isEnabled == true && bluetoothGatt == null && !isConnecting) {
            tryAutoConnectInternal(false)
        }
    }

    override fun onDestroy() {
        stopScanInternal()
        closeGatt()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasBlePermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensurePermissionsAndBluetooth(onReady: () -> Unit) {
        if (!hasBlePermissions()) {
            pendingAction = { ensurePermissionsAndBluetooth(onReady) }
            permissionLauncher.launch(requiredPermissions())
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            jsStatus("Bluetooth не поддерживается")
            return
        }
        if (!adapter.isEnabled) {
            pendingAction = onReady
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        onReady()
    }

    private fun isTargetName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.trim().uppercase(Locale.US)
        return n == "ELK-BLEDDM" || n == "ELK-BLEDOM" || n.startsWith("ELK-")
    }

    private fun savedAddress(): String? = prefs.getString("last_addr", null)
    private fun saveAddress(addr: String, name: String?) {
        prefs.edit().putString("last_addr", addr).putString("last_name", name ?: "").apply()
    }

    private fun tryAutoConnectInternal(notify: Boolean) {
        if (bluetoothGatt != null || isConnecting) return
        val addr = savedAddress() ?: return
        val adapter = bluetoothAdapter ?: return
        try {
            val device = adapter.getRemoteDevice(addr)
            if (notify) jsStatus("Автоподключение...")
            connectToDevice(device, false)
        } catch (_: Exception) {
            if (notify) jsStatus("Устройство не найдено")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, fromUser: Boolean) {
        if (isConnecting) return
        stopScanInternal()
        isConnecting = true
        userInitiatedDisconnect = false
        jsStatus("Подключение к ${device.name ?: "ленте"}...")
        closeGatt()
        mainHandler.post {
            bluetoothGatt = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(this, false, gattCallback)
                }
            } catch (_: Exception) {
                null
            }
            if (bluetoothGatt == null) {
                isConnecting = false
                jsDisconnected("Не удалось подключиться")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanForUser() {
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            jsStatus("Сканер недоступен")
            return
        }
        discoveredDevices.clear()
        // try bonded first
        try {
            adapter.bondedDevices?.forEach { d ->
                if (isTargetName(d.name)) discoveredDevices[d.address] = d
            }
        } catch (_: Exception) {}
        if (discoveredDevices.size == 1) {
            connectToDevice(discoveredDevices.values.first(), true)
            return
        } else if (discoveredDevices.size > 1) {
            showDeviceChooser()
            return
        }

        jsStatus("Поиск лампы...")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = try { device.name } catch (_: Exception) { null }
                if (isTargetName(name)) {
                    discoveredDevices[device.address] = device
                }
            }
            override fun onScanFailed(errorCode: Int) {
                mainHandler.post { jsStatus("Ошибка сканирования: $errorCode") }
            }
        }
        scanCallback = cb
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, cb)
        } catch (_: Exception) {
            try { scanner.startScan(cb) } catch (_: Exception) { jsStatus("Не удалось начать поиск"); return }
        }
        mainHandler.postDelayed({
            stopScanInternal()
            if (bluetoothGatt != null || isConnecting) return@postDelayed
            when {
                discoveredDevices.isEmpty() -> jsDisconnected("Лампа не найдена. Включите её и попробуйте снова")
                discoveredDevices.size == 1 -> connectToDevice(discoveredDevices.values.first(), true)
                else -> showDeviceChooser()
            }
        }, 8000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        val cb = scanCallback ?: return
        scanCallback = null
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    private fun showDeviceChooser() {
        val devices = discoveredDevices.values.toList()
        val labels = devices.map { d ->
            val n = try { d.name } catch (_: Exception) { null } ?: "ELK"
            "$n (${d.address})"
        }.toTypedArray()
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Выберите лампу")
                .setItems(labels) { _, which -> connectToDevice(devices[which], true) }
                .setNegativeButton("Отмена") { _, _ -> jsDisconnected("Подключение отменено") }
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try { bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null
        writeCharacteristic = null
        connectedDevice = null
        isConnecting = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnecting = true
                mainHandler.post { jsStatus("Поиск сервиса...") }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasUser = userInitiatedDisconnect
                val addr = gatt.device?.address
                val name = try { gatt.device?.name } catch (_: Exception) { null }
                closeGatt()
                if (wasUser) {
                    mainHandler.post { jsDisconnected("Лента отключена") }
                } else {
                    mainHandler.post { jsDisconnected("Соединение потеряно") }
                    // auto reconnect
                    mainHandler.postDelayed({
                        if (bluetoothGatt == null && !isConnecting && hasBlePermissions() && bluetoothAdapter?.isEnabled == true) {
                            val a = addr ?: savedAddress()
                            if (a != null) {
                                try {
                                    val dev = bluetoothAdapter?.getRemoteDevice(a)
                                    if (dev != null) connectToDevice(dev, false)
                                } catch (_: Exception) {}
                            }
                        }
                    }, 2000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUuid)
            val chr = service?.getCharacteristic(writeUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || chr == null) {
                isConnecting = false
                mainHandler.post { jsDisconnected("Сервис лампы не найден") }
                gatt.disconnect()
                return
            }
            writeCharacteristic = chr
            connectedDevice = gatt.device
            isConnecting = false
            val name = try { gatt.device.name } catch (_: Exception) { null } ?: "ELK-BLEDDM"
            saveAddress(gatt.device.address, name)
            mainHandler.post { jsConnected(name) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeHex(hex: String): Boolean {
        val gatt = bluetoothGatt ?: return false
        val chr = writeCharacteristic ?: return false
        return try {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(chr, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS || true
            } else {
                @Suppress("DEPRECATION")
                run {
                    chr.value = bytes
                    chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    gatt.writeCharacteristic(chr)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun evalJs(script: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webView.evaluateJavascript(script, null)
        } else {
            mainHandler.post { webView.evaluateJavascript(script, null) }
        }
    }

    private fun jsConnected(name: String) {
        val safe = name.replace("\\", "\\\\").replace("'", "\\'")
        evalJs("window.onNativeBleConnected && window.onNativeBleConnected('$safe')")
    }
    private fun jsDisconnected(msg: String) {
        val safe = msg.replace("\\", "\\\\").replace("'", "\\'")
        evalJs("window.onNativeBleDisconnected && window.onNativeBleDisconnected('$safe')")
    }
    private fun jsStatus(msg: String) {
        val safe = msg.replace("\\", "\\\\").replace("'", "\\'")
        evalJs("window.onNativeBleStatus && window.onNativeBleStatus('$safe')")
    }

    inner class BleBridge {
        @JavascriptInterface fun isNative(): Boolean = true

        @JavascriptInterface
        fun requestConnect() {
            runOnUiThread {
                ensurePermissionsAndBluetooth { startScanForUser() }
            }
        }

        @JavascriptInterface
        fun tryAutoConnect() {
            runOnUiThread {
                if (!hasBlePermissions() || bluetoothAdapter?.isEnabled != true) return@runOnUiThread
                if (bluetoothGatt == null && !isConnecting) tryAutoConnectInternal(true)
                else if (bluetoothGatt != null && writeCharacteristic != null) {
                    val name = try { connectedDevice?.name } catch (_: Exception) { null } ?: "ELK"
                    jsConnected(name)
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                userInitiatedDisconnect = true
                stopScanInternal()
                try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
                closeGatt()
                jsDisconnected("Лента отключена")
            }
        }

        @JavascriptInterface
        fun write(hex: String): Boolean {
            return writeHex(hex)
        }

        @JavascriptInterface
        fun isConnected(): Boolean = bluetoothGatt != null && writeCharacteristic != null
    }
}