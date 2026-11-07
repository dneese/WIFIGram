package app.wifigramm.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Finds the other phones of the room in the local network.
 *
 * Every three seconds the phone sends a small encrypted beacon to the
 * broadcast address of the network and to a multicast group. Only a phone
 * that knows the password of the room can read it, so the room stays closed.
 */
class Discovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val roomKey: ByteArray,
    private val peerId: String,
    private val nickname: String,
    private val sendEnabled: Boolean,
    private val onPeerFound: (peerId: String, nickname: String, address: InetAddress, port: Int) -> Unit
) {

    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            try {
                val multicast = (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("wifigramm-discovery")
                    ?.apply { setReferenceCounted(true) }
                multicastLock = multicast
                try { multicast?.acquire() } catch (_: Exception) { }

                val datagramSocket = DatagramSocket(null)
                datagramSocket.reuseAddress = true
                datagramSocket.broadcast = true
                datagramSocket.soTimeout = 500
                datagramSocket.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                socket = datagramSocket

                val receiver = scope.launch(Dispatchers.IO) { receiveLoop(datagramSocket) }
                val sender = scope.launch(Dispatchers.IO) { sendLoop(datagramSocket) }
                receiver.join()
                sender.join()
            } catch (_: Exception) {
                // The network is not ready, the room retries later
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
        if (!sendEnabled) return
        while (scope.isActive) {
            try {
                val beacon = Protocol.encodeBeacon(
                    Protocol.Beacon(
                        peerId = peerId,
                        nickname = nickname,
                        port = Protocol.TCP_PORT,
                        address = localAddress(),
                        timestamp = System.currentTimeMillis()
                    )
                )
                val nonce = Crypto.randomBytes(12)
                val cipher = Crypto.encrypt(roomKey, nonce, beacon, byteArrayOf(Protocol.TYPE_BEACON.toByte()))
                val frame = Crypto.concat(
                    byteArrayOf(Protocol.TYPE_BEACON.toByte()),
                    nonce,
                    cipher
                )

                for (address in broadcastAddresses()) {
                    val packet = DatagramPacket(frame, frame.size, address, Protocol.DISCOVERY_PORT)
                    try { datagramSocket.send(packet) } catch (_: Exception) { }
                }

                // The multicast group reaches the phones in the other networks
                // of the same router, for example the guests network
                try {
                    val group = InetAddress.getByName(Protocol.MULTICAST_GROUP)
                    val packet = DatagramPacket(frame, frame.size, group, Protocol.DISCOVERY_PORT)
                    datagramSocket.send(packet)
                } catch (_: Exception) { }
            } catch (_: Exception) {
            }
            delay(3000)
        }
    }

    private suspend fun receiveLoop(datagramSocket: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                datagramSocket.receive(packet)
            } catch (_: Exception) {
                continue
            }
            val frame = packet.data.copyOf(packet.length)
            if (frame.size < 14 || frame[0].toInt() != Protocol.TYPE_BEACON) continue
            val nonce = frame.copyOfRange(1, 13)
            val cipher = frame.copyOfRange(13, frame.size)
            val payload = Crypto.decrypt(roomKey, nonce, cipher, byteArrayOf(Protocol.TYPE_BEACON.toByte()))
            if (payload == null) continue
            val beacon = Protocol.decodeBeacon(payload) ?: continue
            if (beacon.peerId == peerId) continue
            val age = System.currentTimeMillis() - beacon.timestamp
            if (age < -60000 || age > 60000) continue
            onPeerFound(beacon.peerId, beacon.nickname, packet.address, beacon.port)
        }
    }

    /** The address of the phone in the network, the other phones need it. */
    private fun localAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                for (address in network.inetAddresses) {
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: ""
                    }
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
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
}
