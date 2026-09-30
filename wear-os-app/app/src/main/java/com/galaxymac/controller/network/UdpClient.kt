package com.galaxymac.controller.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.galaxymac.controller.model.ConnectionStatus
import com.galaxymac.controller.model.ControlPacket
import com.galaxymac.controller.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Link to the Mac companion server. Two transports, picked automatically:
 *
 *  1. ADB tunnel (TCP to 127.0.0.1) — used when `adb reverse tcp:8765 tcp:8765` is active.
 *     Most reliable: works no matter how Wi-Fi / Bluetooth are routed.
 *  2. Wi-Fi UDP — the app explicitly requests the watch's Wi-Fi network. Without this,
 *     a watch that is paired to a phone routes traffic through the phone's Bluetooth
 *     proxy, which can never reach a Mac on the local network.
 */
class UdpClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettings: AppSettings,
    private val port: Int = 8765
) {
    private val TAG = "UdpClient"

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _targetServerInfo = MutableStateFlow("Searching...")
    val targetServerInfo: StateFlow<String> = _targetServerInfo

    private val _networkInfo = MutableStateFlow("Requesting Wi-Fi...")
    val networkInfo: StateFlow<String> = _networkInfo

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    // A single sender thread keeps packets strictly in order and off the sensor thread
    private val sendExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "MacLinkSender").apply { priority = Thread.MAX_PRIORITY } }
    private val sendDispatcher = sendExecutor.asCoroutineDispatcher()
    private val outbox = Channel<ControlPacket>(capacity = 256)

    private val socketLock = Any()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var broadcastAddress: InetAddress? = null
    @Volatile private var serverAddress: InetAddress? = null
    @Volatile private var lastReceivedHeartbeat: Long = 0

    @Volatile private var tcpSocket: Socket? = null
    @Volatile private var tcpOut: OutputStream? = null

    private var discoveryJob: Job? = null
    private var listenerJob: Job? = null
    private var senderJob: Job? = null
    private var tunnelJob: Job? = null
    private var started = false

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Wi-Fi network available: $network")
            wifiNetwork = network
            connectivityManager.getLinkProperties(network)?.let { updateBroadcast(it) }
            rebuildUdpSocket()
            _networkInfo.value = "Wi-Fi"
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == wifiNetwork) updateBroadcast(linkProperties)
        }

        override fun onLost(network: Network) {
            if (network == wifiNetwork) {
                Log.w(TAG, "Wi-Fi network lost")
                wifiNetwork = null
                broadcastAddress = null
                rebuildUdpSocket()
                _networkInfo.value = "Wi-Fi lost"
            }
        }

        override fun onUnavailable() {
            _networkInfo.value = "Wi-Fi unavailable"
        }
    }

    fun start() {
        if (started) return
        started = true

        rebuildUdpSocket()
        requestWifi()

        senderJob = scope.launch(sendDispatcher) {
            for (packet in outbox) {
                transmit(packet)
            }
        }
        startDiscovery()
        startListener()
        startTunnelProbe()
    }

    fun stop() {
        if (!started) return
        started = false
        discoveryJob?.cancel()
        listenerJob?.cancel()
        tunnelJob?.cancel()
        senderJob?.cancel()
        try {
            connectivityManager.unregisterNetworkCallback(wifiCallback)
        } catch (_: Exception) {
        }
        closeTunnel()
        synchronized(socketLock) {
            socket?.close()
            socket = null
        }
        wifiNetwork = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _targetServerInfo.value = "Disconnected"
    }

    /** True when a Mac is reachable over either transport. */
    fun isLinked(): Boolean = _connectionStatus.value == ConnectionStatus.CONNECTED

    fun sendPacket(packet: ControlPacket) {
        if (!started) return
        if (tcpOut == null && serverAddress == null) return
        if (outbox.trySend(packet).isFailure) {
            Log.w(TAG, "Outbox full, dropping ${packet.javaClass.simpleName}")
        }
    }

    fun setManualServerIp(ipString: String) {
        scope.launch(sendDispatcher) {
            try {
                serverAddress = InetAddress.getByName(ipString)
                sendNow(ControlPacket.Discover(deviceName()))
            } catch (e: Exception) {
                Log.e(TAG, "Invalid IP address: $ipString", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Wi-Fi
    // ------------------------------------------------------------------

    private fun requestWifi() {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.requestNetwork(request, wifiCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to request Wi-Fi network", e)
            _networkInfo.value = "Default network"
        }
    }

    private fun updateBroadcast(lp: LinkProperties) {
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return
        val ip = ByteBuffer.wrap(la.address.address).int
        val prefix = la.prefixLength
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val bcast = ip or mask.inv()
        broadcastAddress = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(bcast).array())
        Log.i(TAG, "Watch IP ${la.address.hostAddress}/$prefix, broadcast ${broadcastAddress?.hostAddress}")
    }

    private fun rebuildUdpSocket() {
        synchronized(socketLock) {
            try {
                val fresh = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1000
                }
                // Route through Wi-Fi even while the default network is the phone's Bluetooth proxy
                wifiNetwork?.bindSocket(fresh)
                val old = socket
                socket = fresh
                old?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create DatagramSocket", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Discovery / heartbeat
    // ------------------------------------------------------------------

    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch(sendDispatcher) {
            _connectionStatus.value = ConnectionStatus.SEARCHING
            _targetServerInfo.value = "Broadcasting discovery..."
            val discover = ControlPacket.Discover(deviceName()).toJson().toByteArray()

            while (isActive) {
                if (tcpOut != null) {
                    sendNow(ControlPacket.Ping())
                    checkHeartbeat()
                    delay(1000)
                    continue
                }

                if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                    val targets = LinkedHashSet<InetAddress>()
                    appSettings.lastServerIp.value?.let { runCatching { InetAddress.getByName(it) }.getOrNull()?.let(targets::add) }
                    broadcastAddress?.let(targets::add)
                    targets.add(InetAddress.getByName("255.255.255.255"))
                    val s = socket
                    for (addr in targets) {
                        try {
                            s?.send(DatagramPacket(discover, discover.size, addr, port))
                        } catch (e: Exception) {
                            Log.d(TAG, "Discovery to ${addr.hostAddress} failed: ${e.message}")
                        }
                    }
                    delay(800)
                } else {
                    sendNow(ControlPacket.Ping())
                    checkHeartbeat()
                    delay(1000)
                }
            }
        }
    }

    private fun checkHeartbeat() {
        if (_connectionStatus.value == ConnectionStatus.CONNECTED &&
            System.currentTimeMillis() - lastReceivedHeartbeat > 4000
        ) {
            Log.w(TAG, "Server heartbeat timed out, rediscovering...")
            if (tcpOut != null) closeTunnel()
            serverAddress = null
            _connectionStatus.value = ConnectionStatus.SEARCHING
            _targetServerInfo.value = "Reconnecting..."
        }
    }

    private fun onServerReply(json: JSONObject, sender: InetAddress?, viaTunnel: Boolean) {
        val type = json.optString("type")
        if (type != "mac_ack" && type != "pong") return
        val serverName = json.optString("server_name", "Mac")
        lastReceivedHeartbeat = System.currentTimeMillis()

        if (viaTunnel) {
            _targetServerInfo.value = "$serverName (ADB tunnel)"
        } else if (sender != null) {
            if (tcpOut != null) return // tunnel wins; ignore stray UDP replies
            serverAddress = sender
            sender.hostAddress?.let { appSettings.setLastServerIp(it) }
            _targetServerInfo.value = "$serverName (${sender.hostAddress})"
        }
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
            Log.i(TAG, "Connected to Mac: ${_targetServerInfo.value}")
        }
        _connectionStatus.value = ConnectionStatus.CONNECTED
    }

    private fun startListener() {
        listenerJob?.cancel()
        listenerJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive) {
                val s = socket
                if (s == null || s.isClosed) {
                    delay(200)
                    continue
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    s.receive(packet)
                    val json = JSONObject(String(packet.data, 0, packet.length))
                    onServerReply(json, packet.address, viaTunnel = false)
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (isActive && !s.isClosed) Log.w(TAG, "Listener error: ${e.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // ADB tunnel (TCP)
    // ------------------------------------------------------------------

    private fun startTunnelProbe() {
        tunnelJob?.cancel()
        tunnelJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (isActive) {
                if (tcpSocket == null) {
                    tryOpenTunnel()
                }
                delay(2000)
            }
        }
    }

    private suspend fun tryOpenTunnel() {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300)
        } catch (_: Exception) {
            runCatching { s.close() }
            return
        }
        Log.i(TAG, "ADB tunnel connected")
        tcpSocket = s
        tcpOut = s.getOutputStream()
        _networkInfo.value = "ADB tunnel"
        sendNow(ControlPacket.Discover(deviceName()))

        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                runCatching { JSONObject(line) }.getOrNull()?.let { onServerReply(it, null, viaTunnel = true) }
            }
        } catch (_: Exception) {
        }
        Log.i(TAG, "ADB tunnel closed")
        closeTunnel()
    }

    private fun closeTunnel() {
        val s = tcpSocket
        tcpSocket = null
        tcpOut = null
        runCatching { s?.close() }
        if (s != null) {
            _networkInfo.value = if (wifiNetwork != null) "Wi-Fi" else "No Wi-Fi"
            if (serverAddress == null) {
                _connectionStatus.value = ConnectionStatus.SEARCHING
                _targetServerInfo.value = "Searching..."
            }
        }
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    private fun sendNow(packet: ControlPacket) {
        if (!outbox.trySend(packet).isSuccess) transmit(packet)
    }

    private fun transmit(packet: ControlPacket) {
        val data = packet.toJson()
        val out = tcpOut
        if (out != null) {
            try {
                out.write((data + "\n").toByteArray())
                out.flush()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Tunnel write failed: ${e.message}")
                closeTunnel()
            }
        }
        val target = serverAddress ?: return
        val s = socket ?: return
        try {
            val bytes = data.toByteArray()
            s.send(DatagramPacket(bytes, bytes.size, target, port))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send packet: ${e.message}")
        }
    }
}
