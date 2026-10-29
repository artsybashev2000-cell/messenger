package com.example.btmessenger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Обмен текстом между двумя телефонами по Bluetooth (RFCOMM).
 * Каждый телефон одновременно слушает входящие подключения и может сам подключиться к другому.
 * Сообщение передаётся как UTF-строка с длиной (DataOutputStream.writeUTF).
 */
@SuppressLint("MissingPermission")
class BtChatService(
    private val adapter: BluetoothAdapter,
    private val listener: Listener
) {
    enum class ChatState { IDLE, LISTENING, CONNECTING, CONNECTED }

    interface Listener {
        fun onStateChanged(state: ChatState, deviceName: String?)
        fun onMessage(text: String, fromMe: Boolean)
        fun onError(text: String)
    }

    companion object {
        private val APP_UUID: UUID = UUID.fromString("8f2a1c4e-6b3d-4e7a-9a51-2d0c5b7e1f34")
        private const val SERVICE_NAME = "BtMessenger"
    }

    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var released = false

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    @Volatile
    var currentState: ChatState = ChatState.IDLE
        private set

    private fun post(block: () -> Unit) {
        ui.post { if (!released) block() }
    }

    private fun setState(s: ChatState, deviceName: String? = null) {
        currentState = s
        post { listener.onStateChanged(s, deviceName) }
    }

    private fun nameOf(d: BluetoothDevice): String =
        try {
            d.name ?: d.address
        } catch (e: SecurityException) {
            d.address
        }

    /** Сбросить всё и ждать входящее подключение. */
    @Synchronized
    fun listen() {
        connectThread?.cancel(); connectThread = null
        connectedThread?.cancel(); connectedThread = null
        if (acceptThread == null) {
            acceptThread = AcceptThread().also { it.start() }
        }
        setState(ChatState.LISTENING)
    }

    /** Подключиться к выбранному устройству. */
    @Synchronized
    fun connect(device: BluetoothDevice) {
        connectThread?.cancel(); connectThread = null
        connectedThread?.cancel(); connectedThread = null
        if (acceptThread == null) {
            acceptThread = AcceptThread().also { it.start() }
        }
        connectThread = ConnectThread(device).also { it.start() }
        setState(ChatState.CONNECTING, nameOf(device))
    }

    @Synchronized
    private fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        connectThread?.cancel(); connectThread = null
        connectedThread?.cancel(); connectedThread = null
        acceptThread?.cancel(); acceptThread = null
        connectedThread = ConnectedThread(socket).also { it.start() }
        setState(ChatState.CONNECTED, nameOf(device))
    }

    @Synchronized
    private fun stopAll() {
        connectThread?.cancel(); connectThread = null
        connectedThread?.cancel(); connectedThread = null
        acceptThread?.cancel(); acceptThread = null
    }

    /** Вызвать при закрытии экрана. Колбэки после этого не приходят. */
    fun release() {
        released = true
        stopAll()
    }

    fun send(text: String) {
        val t = synchronized(this) { connectedThread } ?: return
        t.write(text)
    }

    @Synchronized
    private fun connectionFailed(t: ConnectThread) {
        if (connectThread !== t) return
        connectThread = null
        post { listener.onError("Не удалось подключиться") }
        listen()
    }

    @Synchronized
    private fun connectionLost(t: ConnectedThread) {
        if (connectedThread !== t) return
        connectedThread = null
        t.cancel()
        post { listener.onError("Соединение потеряно") }
        listen()
    }

    // ---------- Ожидание входящего подключения ----------
    private inner class AcceptThread : Thread() {
        private val server: BluetoothServerSocket? = try {
            adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
        } catch (e: Exception) {
            null
        }

        override fun run() {
            val srv = server
            if (srv == null) {
                post { listener.onError("Не удалось открыть приём подключений") }
            } else {
                while (true) {
                    val socket = try {
                        srv.accept()
                    } catch (e: IOException) {
                        break
                    }
                    synchronized(this@BtChatService) {
                        if (currentState == ChatState.LISTENING || currentState == ChatState.CONNECTING) {
                            connected(socket, socket.remoteDevice)
                        } else {
                            try { socket.close() } catch (e: IOException) { }
                        }
                    }
                }
            }
            synchronized(this@BtChatService) {
                if (acceptThread === this@AcceptThread) acceptThread = null
            }
        }

        fun cancel() {
            try { server?.close() } catch (e: IOException) { }
        }
    }

    // ---------- Исходящее подключение ----------
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? = try {
            device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
        } catch (e: Exception) {
            null
        }

        override fun run() {
            val s = socket
            if (s == null) {
                connectionFailed(this)
                return
            }
            try { adapter.cancelDiscovery() } catch (e: SecurityException) { }
            try {
                s.connect()
            } catch (e: IOException) {
                try { s.close() } catch (e2: IOException) { }
                connectionFailed(this)
                return
            }
            synchronized(this@BtChatService) {
                if (connectThread !== this@ConnectThread) {
                    try { s.close() } catch (e: IOException) { }
                    return
                }
                connectThread = null
                connected(s, device)
            }
        }

        fun cancel() {
            try { socket?.close() } catch (e: IOException) { }
        }
    }

    // ---------- Активное соединение ----------
    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val input: DataInputStream? = try {
            DataInputStream(socket.inputStream)
        } catch (e: IOException) {
            null
        }
        private val output: DataOutputStream? = try {
            DataOutputStream(socket.outputStream)
        } catch (e: IOException) {
            null
        }
        private val writer = Executors.newSingleThreadExecutor()

        override fun run() {
            val inp = input
            if (inp == null) {
                connectionLost(this)
                return
            }
            while (true) {
                val text = try {
                    inp.readUTF()
                } catch (e: IOException) {
                    break
                }
                post { listener.onMessage(text, false) }
            }
            connectionLost(this)
        }

        fun write(text: String) {
            try {
                writer.execute {
                    try {
                        val out = output ?: throw IOException("нет потока")
                        out.writeUTF(text)
                        out.flush()
                        post { listener.onMessage(text, true) }
                    } catch (e: IOException) {
                        post { listener.onError("Не удалось отправить сообщение") }
                    }
                }
            } catch (e: RejectedExecutionException) {
                post { listener.onError("Нет соединения") }
            }
        }

        fun cancel() {
            writer.shutdownNow()
            try { socket.close() } catch (e: IOException) { }
        }
    }
}
