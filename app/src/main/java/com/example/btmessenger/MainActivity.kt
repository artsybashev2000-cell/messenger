package com.example.btmessenger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.btmessenger.BtChatService.ChatState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), BtChatService.Listener {

    private var btAdapter: BluetoothAdapter? = null
    private var service: BtChatService? = null
    private var receiverRegistered = false

    private lateinit var statusText: TextView
    private lateinit var devicePanel: View
    private lateinit var chatPanel: View
    private lateinit var btnScan: Button
    private lateinit var deviceList: ListView
    private lateinit var messageList: ListView
    private lateinit var input: EditText

    private lateinit var deviceAdapter: ArrayAdapter<String>
    private lateinit var messageAdapter: ArrayAdapter<String>

    private val devices = mutableListOf<BluetoothDevice>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                ensureBluetoothOn()
            } else {
                setStatus("Без разрешений Bluetooth приложение не работает")
            }
        }

    private val enableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (btAdapter?.isEnabled == true) startChat() else setStatus("Включите Bluetooth")
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> intent.foundDevice()?.let { addDevice(it) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    btnScan.isEnabled = true
                    btnScan.text = "Искать"
                    if (devices.isEmpty()) {
                        toast("Ничего не найдено. На втором телефоне должно быть нажато «Стать видимым»")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        devicePanel = findViewById(R.id.devicePanel)
        chatPanel = findViewById(R.id.chatPanel)
        btnScan = findViewById(R.id.btnScan)
        deviceList = findViewById(R.id.deviceList)
        messageList = findViewById(R.id.messageList)
        input = findViewById(R.id.input)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        deviceList.adapter = deviceAdapter
        messageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        messageList.adapter = messageAdapter

        findViewById<Button>(R.id.btnDiscoverable).setOnClickListener { makeDiscoverable() }
        btnScan.setOnClickListener { startScan() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { service?.listen() }
        deviceList.setOnItemClickListener { _, _, position, _ -> connectTo(position) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        showChat(false)

        btAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (btAdapter == null) {
            setStatus("На этом устройстве нет Bluetooth")
            return
        }
        checkPermissions()
    }

    // ---------- Разрешения и включение Bluetooth ----------
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun checkPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ensureBluetoothOn() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun ensureBluetoothOn() {
        if (btAdapter?.isEnabled == true) {
            startChat()
        } else {
            enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private fun startChat() {
        val adapter = btAdapter ?: return
        if (service == null) service = BtChatService(adapter, this)
        service?.listen()
        registerDiscoveryReceiver()
        loadPairedDevices()
    }

    // ---------- Список устройств ----------
    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(this, discoveryReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun loadPairedDevices() {
        devices.clear()
        deviceAdapter.clear()
        btAdapter?.bondedDevices?.forEach { addDevice(it) }
    }

    private fun addDevice(d: BluetoothDevice) {
        if (devices.any { it.address == d.address }) return
        devices.add(d)
        val title = d.name ?: "Без имени"
        val mark = if (d.bondState == BluetoothDevice.BOND_BONDED) " (сопряжено)" else ""
        deviceAdapter.add("$title$mark\n${d.address}")
    }

    @Suppress("DEPRECATION")
    private fun Intent.foundDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun makeDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        startActivity(intent)
    }

    private fun startScan() {
        val adapter = btAdapter ?: return
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        if (adapter.startDiscovery()) {
            btnScan.isEnabled = false
            btnScan.text = "Поиск…"
        } else {
            toast("Не удалось начать поиск")
        }
    }

    private fun connectTo(position: Int) {
        val device = devices.getOrNull(position) ?: return
        btAdapter?.cancelDiscovery()
        service?.connect(device)
    }

    // ---------- Чат ----------
    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        service?.send(text)
        input.setText("")
    }

    private fun showChat(show: Boolean) {
        chatPanel.visibility = if (show) View.VISIBLE else View.GONE
        devicePanel.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // ---------- BtChatService.Listener ----------
    override fun onStateChanged(state: ChatState, deviceName: String?) {
        when (state) {
            ChatState.IDLE -> setStatus("Остановлено")
            ChatState.LISTENING -> {
                showChat(false)
                setStatus("Не подключено")
            }
            ChatState.CONNECTING -> setStatus("Подключаюсь к $deviceName…")
            ChatState.CONNECTED -> {
                messageAdapter.clear()
                showChat(true)
                setStatus("Связь с $deviceName")
            }
        }
    }

    override fun onMessage(text: String, fromMe: Boolean) {
        val who = if (fromMe) "Я" else "Собеседник"
        messageAdapter.add("${timeFormat.format(Date())}  $who: $text")
        messageList.setSelection(messageAdapter.count - 1)
    }

    override fun onError(text: String) {
        toast(text)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            unregisterReceiver(discoveryReceiver)
            receiverRegistered = false
        }
        try { btAdapter?.cancelDiscovery() } catch (e: SecurityException) { }
        service?.release()
    }
}
