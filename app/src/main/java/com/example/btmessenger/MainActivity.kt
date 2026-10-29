package com.example.btmessenger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.btmessenger.BtChatService.ChatState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val GEO_PREFIX = "GEO:"

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), BtChatService.Listener {

    private var btAdapter: BluetoothAdapter? = null
    private var service: BtChatService? = null
    private var receiverRegistered = false
    private var scanning = false
    private var inChat = false
    private var peerName: String? = null

    private lateinit var btnBack: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var headerAvatar: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var listPanel: View
    private lateinit var chatPanel: View
    private lateinit var fabScan: ImageButton
    private lateinit var deviceList: RecyclerView
    private lateinit var messageList: RecyclerView
    private lateinit var input: EditText
    private lateinit var searchInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnLocation: ImageButton

    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var messageAdapter: MessageAdapter

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            service?.listen() // «назад» из чата = отключиться
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                ensureBluetoothOn()
            } else {
                setSubtitle("нет разрешений Bluetooth")
                toast("Без разрешений Bluetooth приложение не работает")
            }
        }

    private val enableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (btAdapter?.isEnabled == true) startChat() else setSubtitle("включите Bluetooth")
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) sendLocation() else toast("Нужно разрешение на геопозицию")
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> intent.foundDevice()?.let { addDevice(it) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    fabScan.isEnabled = true
                    fabScan.alpha = 1f
                    refreshStatus()
                    if (deviceAdapter.isEmpty()) {
                        toast("Ничего не найдено. На втором телефоне должно быть нажато «Стать видимым»")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnBack = findViewById(R.id.btnBack)
        btnMore = findViewById(R.id.btnMore)
        headerAvatar = findViewById(R.id.headerAvatar)
        headerTitle = findViewById(R.id.headerTitle)
        headerSubtitle = findViewById(R.id.headerSubtitle)
        listPanel = findViewById(R.id.listPanel)
        chatPanel = findViewById(R.id.chatPanel)
        fabScan = findViewById(R.id.fabScan)
        deviceList = findViewById(R.id.deviceList)
        messageList = findViewById(R.id.messageList)
        input = findViewById(R.id.input)
        searchInput = findViewById(R.id.searchInput)
        btnSend = findViewById(R.id.btnSend)
        btnLocation = findViewById(R.id.btnLocation)

        deviceAdapter = DeviceAdapter { row -> connectTo(row) }
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = deviceAdapter

        messageAdapter = MessageAdapter()
        messageAdapter.onGeoClick = { lat, lon -> openYandexMaps(lat, lon) }
        messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageList.adapter = messageAdapter
        // когда открывается клавиатура, держим последнее сообщение на виду
        messageList.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && messageAdapter.itemCount > 0) {
                messageList.post { messageList.scrollToPosition(messageAdapter.lastIndex()) }
            }
        }

        findViewById<View>(R.id.rowVisible).setOnClickListener { makeDiscoverable() }
        fabScan.setOnClickListener { startScan() }
        btnBack.setOnClickListener { service?.listen() }
        btnMore.setOnClickListener { showMenu() }
        btnSend.setOnClickListener { sendMessage() }
        btnLocation.setOnClickListener { sendLocation() }

        // поиск по списку устройств
        searchInput.doAfterTextChanged { deviceAdapter.setQuery(it?.toString() ?: "") }
        // как в Telegram: пока поле пустое — значок геопозиции, есть текст — кнопка отправки
        input.doAfterTextChanged { updateSendButton() }

        onBackPressedDispatcher.addCallback(this, backCallback)

        showChat(false)

        btAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (btAdapter == null) {
            setSubtitle("на устройстве нет Bluetooth")
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

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val missing = requiredPermissions().filter { !hasPermission(it) }
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
        deviceAdapter.clear()
        btAdapter?.bondedDevices?.forEach { addDevice(it) }
    }

    private fun addDevice(d: BluetoothDevice) {
        val row = DeviceRow(
            device = d,
            name = d.name ?: "Без имени",
            address = d.address,
            bonded = d.bondState == BluetoothDevice.BOND_BONDED,
            isPhone = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
        )
        deviceAdapter.addIfAbsent(row)
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
            scanning = true
            fabScan.isEnabled = false
            fabScan.alpha = 0.5f
            refreshStatus()
        } else {
            toast("Не удалось начать поиск")
        }
    }

    private fun connectTo(row: DeviceRow) {
        btAdapter?.cancelDiscovery()
        service?.connect(row.device)
    }

    private fun showMenu() {
        val popup = PopupMenu(this, btnMore)
        if (inChat) {
            popup.menu.add(0, 1, 0, "Отключиться")
        } else {
            popup.menu.add(0, 2, 0, "Стать видимым")
            popup.menu.add(0, 3, 1, "Обновить список")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> service?.listen()
                2 -> makeDiscoverable()
                3 -> loadPairedDevices()
            }
            true
        }
        popup.show()
    }

    // ---------- Чат ----------
    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        service?.send(text)
        input.setText("")
    }

    private fun updateSendButton() {
        val hasText = input.text.isNotBlank()
        btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
        btnLocation.visibility = if (hasText) View.GONE else View.VISIBLE
    }

    private fun showChat(show: Boolean) {
        inChat = show
        backCallback.isEnabled = show
        listPanel.visibility = if (show) View.GONE else View.VISIBLE
        chatPanel.visibility = if (show) View.VISIBLE else View.GONE
        btnBack.visibility = if (show) View.VISIBLE else View.GONE
        headerAvatar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val name = peerName ?: ""
            headerTitle.text = name
            headerAvatar.text = avatarLetter(name)
            headerAvatar.background = circle(avatarColor(name))
        } else {
            headerTitle.text = "Мессенджер"
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun setSubtitle(text: String, accent: Boolean = false) {
        headerSubtitle.text = text
        headerSubtitle.setTextColor(
            ContextCompat.getColor(this, if (accent) R.color.tg_accent else R.color.tg_secondary)
        )
    }

    private fun refreshStatus() {
        when (service?.currentState) {
            ChatState.CONNECTED -> setSubtitle("подключено", true)
            ChatState.CONNECTING -> setSubtitle("подключение к ${peerName ?: "устройству"}…")
            ChatState.LISTENING -> setSubtitle(if (scanning) "поиск устройств…" else "не подключено")
            else -> {}
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // ---------- Геопозиция ----------
    private fun sendLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = when {
            fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                toast("Включите геолокацию в настройках телефона")
                return
            }
        }
        toast("Определяю местоположение…")
        try {
            // тип сигнала указан явно, иначе компилятор путает две версии getCurrentLocation
            val cancel: android.os.CancellationSignal? = null
            LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(this)) { location: Location? ->
                val loc = location ?: lastKnownLocation(lm)
                if (loc != null) {
                    val text = String.format(Locale.US, "%s%.6f,%.6f", GEO_PREFIX, loc.latitude, loc.longitude)
                    service?.send(text)
                } else {
                    toast("Не удалось определить местоположение. Выйдите на открытое место")
                }
            }
        } catch (e: SecurityException) {
            toast("Нет доступа к геопозиции")
        }
    }

    private fun lastKnownLocation(lm: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p ->
                try {
                    lm.getLastKnownLocation(p)
                } catch (e: SecurityException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            .maxByOrNull { it.time }

    private fun openYandexMaps(lat: Double, lon: Double) {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?pt=$lon,$lat&z=17&l=map"))
        try {
            startActivity(app)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/maps/?pt=$lon,$lat&z=17&l=map")))
            } catch (e2: ActivityNotFoundException) {
                toast("Не удалось открыть карту")
            }
        }
    }

    // ---------- BtChatService.Listener ----------
    override fun onStateChanged(state: ChatState, deviceName: String?) {
        when (state) {
            ChatState.IDLE -> {
            }
            ChatState.LISTENING -> {
                peerName = null
                showChat(false)
            }
            ChatState.CONNECTING -> {
                peerName = deviceName
            }
            ChatState.CONNECTED -> {
                peerName = deviceName
                messageAdapter.clear()
                showChat(true)
            }
        }
        refreshStatus()
    }

    override fun onMessage(text: String, fromMe: Boolean) {
        var body = text
        var geo: Pair<Double, Double>? = null
        if (text.startsWith(GEO_PREFIX)) {
            val parts = text.removePrefix(GEO_PREFIX).split(",")
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lon = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lon != null) {
                body = String.format(Locale.US, "📍 Геопозиция\n%.5f, %.5f", lat, lon)
                geo = Pair(lat, lon)
            }
        }
        messageAdapter.add(ChatMessage(body, fromMe, timeFormat.format(Date()), geo))
        messageList.scrollToPosition(messageAdapter.lastIndex())
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
