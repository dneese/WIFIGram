package app.wifigram.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Finds the other phones in the local network.
 *
 * Every two seconds the phone says "I am here" to the broadcast address of
 * the network and to a multicast group. Nothing is hidden, the station is
 * open to everybody who has the app.
 */
class Discovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val peerId: String,
    private val name: String,
    private val statusProvider: () -> String,
    private val onPeerFound: (peerId: String, name: String, address: InetAddress, port: Int, status: String) -> Unit
) {

    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            try {
                val multicast = (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("wifigram-discovery")
                    ?.apply { setReferenceCounted(true) }
                multicastLock = multicast
                try { multicast?.acquire() } catch (_: Exception) { }

                val datagramSocket = DatagramSocket(null)
                datagramSocket.reuseAddress = true
                datagramSocket.broadcast = true
                datagramSocket.soTimeout = 1000
                datagramSocket.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                socket = datagramSocket

                scope.launch(Dispatchers.IO) { receiveLoop(datagramSocket) }
                scope.launch(Dispatchers.IO) { sendLoop(datagramSocket) }
            } catch (_: Exception) {
                // The network is not ready, the search starts again later
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        try { multicastLock?.release() } catch (_: Exception) { }
        multicastLock = null
    }

    private suspend fun sendLoop(datagramSocket: DatagramSocket) {
        while (scope.isActive) {
            try {
                val frame = Protocol.encodeBeacon(peerId, name, Protocol.TCP_PORT, statusProvider())
                for (address in broadcastAddresses()) {
                    val packet = DatagramPacket(frame, frame.size, address, Protocol.DISCOVERY_PORT)
                    try { datagramSocket.send(packet) } catch (_: Exception) { }
                }
                try {
                    val group = InetAddress.getByName(Protocol.MULTICAST_GROUP)
                    val packet = DatagramPacket(frame, frame.size, group, Protocol.DISCOVERY_PORT)
                    datagramSocket.send(packet)
                } catch (_: Exception) { }
            } catch (_: Exception) {
            }
            delay(2000)
        }
    }

    private suspend fun receiveLoop(datagramSocket: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    datagramSocket.receive(packet)
                } catch (_: Exception) {
                    continue
                }
                val payload = packet.data.copyOf(packet.length)
                val beacon = Protocol.decodeBeacon(payload) ?: continue
                if (beacon.peerId == peerId) continue
                onPeerFound(beacon.peerId, beacon.name, packet.address, beacon.port, beacon.status)
            } catch (_: Exception) {
                // A strange packet must not stop the search
            }
        }
    }

    /** The broadcast address of every network of the phone. */
    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) { }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val broadcast = broadcastAddress(network)
                if (broadcast != null && !result.contains(broadcast)) result.add(broadcast)
            }
        } catch (_: Exception) { }
        return result
    }

    /** The broadcast address of one network, counted from its netmask. */
    private fun broadcastAddress(network: NetworkInterface): InetAddress? {
        return try {
            val addresses = network.interfaceAddresses ?: return null
            for (info in addresses) {
                val address = info.address
                if (address is java.net.Inet4Address) {
                    val bytes = address.address
                    val prefix = info.networkPrefixLength
                    val result = ByteArray(4)
                    for (index in 0..3) {
                        val hostBits = (prefix - index * 8).coerceIn(0, 8)
                        val hostMask = (0xFF shl (8 - hostBits)) and 0xFF
                        result[index] = (bytes[index].toInt() or (hostMask.inv() and 0xFF)).toByte()
                    }
                    return InetAddress.getByAddress(result)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
