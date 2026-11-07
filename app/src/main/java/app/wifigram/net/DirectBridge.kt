package app.wifigram.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The WiFi Direct part of the room.
 *
 * One phone creates the group, the other ones join it. After that the phones
 * talk to each other over the direct connection, without router and without
 * internet.
 *
 * Only the public API of the platform is used: the listeners that Android
 * hides are replaced by a receiver of the WiFi Direct broadcasts.
 */
@android.annotation.SuppressLint("MissingPermission")
class DirectBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onReady: (isOwner: Boolean) -> Unit,
    private val onOwnerAddress: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private companion object {
        const val NETWORK_NAME = "WIFIGRAM"
    }

    private val manager: WifiP2pManager? =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var discoveryJob: Job? = null
    private var stopped = false
    private var connecting = false
    private var ownerFound = false

    private val channelListener = object : WifiP2pManager.ChannelListener {
        override fun onChannelDisconnected() {
            if (!stopped) onStatus("wifi-direct-channel-lost")
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val list = intent.getParcelableExtra<WifiP2pDeviceList>(
                        WifiP2pManager.EXTRA_P2P_DEVICE_LIST
                    )
                    val devices = list?.deviceList?.toList() ?: emptyList()
                    if (!hostMode && !connecting && devices.isNotEmpty()) {
                        connectTo(devices.first())
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = intent.getParcelableExtra<WifiP2pInfo>(
                        WifiP2pManager.EXTRA_WIFI_P2P_INFO
                    )
                    val group = intent.getParcelableExtra<WifiP2pGroup>(
                        WifiP2pManager.EXTRA_WIFI_P2P_GROUP
                    )
                    handleConnection(info, group)
                }
            }
        }
    }

    private var hostMode = false

    init {
        val p2p = manager
        if (p2p != null) {
            try {
                channel = p2p.initialize(context, context.mainLooper, channelListener)
            } catch (_: Exception) {
                channel = null
            }
            try {
                val filter = IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                }
                context.registerReceiver(p2pReceiver, filter)
                receiver = p2pReceiver
            } catch (_: Exception) {
                receiver = null
            }
        }
    }

    /** The phone that creates the group. */
    fun startOwner() {
        hostMode = true
        val p2p = manager ?: run {
            onStatus("wifi-direct-unavailable")
            return
        }
        val wifiChannel = channel ?: run {
            onStatus("wifi-direct-unavailable")
            return
        }
        onStatus("wifi-direct-group")
        try {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(NETWORK_NAME)
                .build()
            p2p.createGroup(wifiChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    scope.launch(Dispatchers.Main) {
                        onStatus("wifi-direct-group-ready")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    scope.launch(Dispatchers.Main) {
                        onStatus("wifi-direct-failed")
                    }
                }
            })
        } catch (_: Exception) {
            onStatus("wifi-direct-failed")
        }
    }

    /** The phone that joins the group. */
    fun startClient() {
        hostMode = false
        val p2p = manager ?: run {
            onStatus("wifi-direct-unavailable")
            return
        }
        val wifiChannel = channel ?: run {
            onStatus("wifi-direct-unavailable")
            return
        }
        onStatus("wifi-direct-search")
        discoveryJob = scope.launch(Dispatchers.IO) {
            while (isActive && !stopped && !ownerFound) {
                try {
                    p2p.discoverPeers(wifiChannel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {}

                        override fun onFailure(errorCode: Int) {}
                    })
                } catch (_: Exception) {
                }
                delay(4000)
            }
        }
    }

    private fun connectTo(device: WifiP2pDevice) {
        val p2p = manager ?: return
        val wifiChannel = channel ?: return
        connecting = true
        try {
            val builder = WifiP2pConfig.Builder().setNetworkName(NETWORK_NAME)
            try {
                builder.setDeviceAddress(android.net.MacAddress.fromString(device.deviceAddress))
            } catch (_: Exception) {
            }
            val config = builder.build()
            p2p.connect(wifiChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    scope.launch(Dispatchers.Main) {
                        onStatus("wifi-direct-connecting")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    connecting = false
                    scope.launch(Dispatchers.Main) {
                        onStatus("wifi-direct-failed")
                    }
                }
            })
        } catch (_: Exception) {
            connecting = false
        }
    }

    private fun handleConnection(info: WifiP2pInfo?, group: WifiP2pGroup?) {
        if (group == null) return
        if (group.isGroupOwner) {
            if (!ownerFound) {
                ownerFound = true
                scope.launch(Dispatchers.Main) {
                    onReady(true)
                }
            }
            return
        }
        // The client needs the address of the owner
        val address = try {
            info?.groupOwnerAddress?.hostAddress
        } catch (_: Exception) {
            null
        }
        if (!address.isNullOrBlank()) {
            ownerFound = true
            connecting = false
            scope.launch(Dispatchers.Main) {
                onOwnerAddress(address)
                onReady(false)
            }
        }
    }

    fun stop() {
        stopped = true
        ownerFound = false
        discoveryJob?.cancel()
        discoveryJob = null
        val p2p = manager
        val wifiChannel = channel
        if (p2p != null && wifiChannel != null) {
            try { p2p.removeGroup(wifiChannel, null) } catch (_: Exception) { }
            try { p2p.stopPeerDiscovery(wifiChannel, null) } catch (_: Exception) { }
        }
        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) { }
        receiver = null
    }
}
