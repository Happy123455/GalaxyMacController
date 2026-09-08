package com.galaxymac.controller.network

import android.os.Build
import android.util.Log
import com.galaxymac.controller.model.ConnectionStatus
import com.galaxymac.controller.model.ControlPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class UdpClient(
    private val scope: CoroutineScope,
    private val port: Int = 8765
) {
    private val TAG = "UdpClient"

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _targetServerInfo = MutableStateFlow("Searching...")
    val targetServerInfo: StateFlow<String> = _targetServerInfo

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var discoveryJob: Job? = null
    private var listenerJob: Job? = null
    private var lastReceivedHeartbeat: Long = 0

    fun start() {
        if (socket == null || socket?.isClosed == true) {
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1500
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create DatagramSocket", e)
                return
            }
        }

        startDiscovery()
        startListener()
    }

    fun stop() {
        discoveryJob?.cancel()
        listenerJob?.cancel()
        socket?.close()
        socket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _targetServerInfo.value = "Disconnected"
    }

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch(Dispatchers.IO) {
            _connectionStatus.value = ConnectionStatus.SEARCHING
            _targetServerInfo.value = "Broadcasting discovery..."

            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val packetData = ControlPacket.Discover(deviceName).toJson().toByteArray()

            while (isActive) {
                if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                    val targets = listOf(
                        "192.168.31.232",
                        "192.168.31.255",
                        "255.255.255.255"
                    )
                    for (targetIp in targets) {
                        try {
                            val addr = InetAddress.getByName(targetIp)
                            val packet = DatagramPacket(packetData, packetData.size, addr, port)
                            socket?.send(packet)
                        } catch (e: Exception) {
                            // ignore individual target errors
                        }
                    }
                    delay(1000)
                } else {
                    // Check if server is still alive
                    if (System.currentTimeMillis() - lastReceivedHeartbeat > 6000) {
                        Log.w(TAG, "Server heartbeat timed out, rediscovering...")
                        _connectionStatus.value = ConnectionStatus.SEARCHING
                        _targetServerInfo.value = "Reconnecting..."
                        serverAddress = null
                    } else {
                        // Send keepalive ping
                        sendPacket(ControlPacket.Ping())
                    }
                    delay(2000)
                }
            }
        }
    }

    private fun startListener() {
        listenerJob?.cancel()
        listenerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val message = String(packet.data, 0, packet.length)
                    val json = JSONObject(message)

                    val type = json.optString("type")
                    if (type == "mac_ack" || type == "pong") {
                        val serverName = json.optString("server_name", "Mac")
                        val senderIp = packet.address

                        serverAddress = senderIp
                        lastReceivedHeartbeat = System.currentTimeMillis()
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        _targetServerInfo.value = "$serverName (${senderIp.hostAddress})"
                        Log.i(TAG, "Connected to Mac at ${senderIp.hostAddress}")
                    }
                } catch (e: SocketTimeoutException) {
                    // Normal timeout, loop again
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in listener", e)
                    }
                }
            }
        }
    }

    fun sendPacket(packet: ControlPacket) {
        val target = serverAddress ?: return
        val currentSocket = socket ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val data = packet.toJson().toByteArray()
                val dPacket = DatagramPacket(data, data.size, target, port)
                currentSocket.send(dPacket)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send packet: ${e.message}")
            }
        }
    }

    fun setManualServerIp(ipString: String) {
        scope.launch(Dispatchers.IO) {
            try {
                serverAddress = InetAddress.getByName(ipString)
                lastReceivedHeartbeat = System.currentTimeMillis()
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _targetServerInfo.value = "Manual: $ipString"
                sendPacket(ControlPacket.Ping())
            } catch (e: Exception) {
                Log.e(TAG, "Invalid IP address: $ipString", e)
            }
        }
    }
}
