package app.wifigramm.net

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pGroupInfo
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The WiFi Direct part of the room.
 *
 * One phone creates the group, the other ones join it. After that the phones
 * talk to each other over the direct connection, without router and without
 * internet.
 */
class DirectBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onReady: (isOwner: Boolean) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private val manager: WifiP2pManager? =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var stopped = false

    fun startOwner() {
        val p2p = manager ?: run {
            onStatus("wifi-direct-unavailable")
            return
        }
        val groupInfo = WifiP2pGroupInfo()
        groupInfo.addNetwork(
            WifiP2pDnsSdServiceInfo.newInstance(
                "wifigramm",
                "wifigramm",
                45312.toString()
            )
        )
        val config = WifiP2pConfig.Builder()
            .setNetworkName("WIFIGRAMM")
            .setGroupOwner(true)
            .build()

        onStatus("wifi-direct-group")
        try {
            p2p.createGroup(channel, config, object : WifiP2pManager.GroupListener {
                override fun onGroupStarted(group: WifiP2pGroup?) {
                    scope.launch(Dispatchers.Main) {
                        onReady(true)
                    }
                }

                override fun onGroupFailed(group: WifiP2pGroup?) {
                    scope.launch(Dispatchers.Main) {
                        onStatus("wifi-direct-failed")
                    }
                }
            })
        } catch (_: Exception) {
            onStatus("wifi-direct-failed")
        }
    }

    fun startClient() {
        val p2p = manager ?: run {
            onStatus("wifi-direct-unavailable")
            return
        }
        onStatus("wifi-direct-search")
        try {
            p2p.discoverPeers(channel, object : WifiP2pManager.PeerListListener {
                override fun onPeersAvailable(peers: MutableList<WifiP2pDevice>?) {
                    val owner = peers?.firstOrNull { it.isGroupOwner } ?: return
                    try {
                        p2p.connect(
                            channel,
                            owner,
                            object : WifiP2pManager.ConnectionListener {
                                override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {}

                                override fun onGroupInfoAvailable(group: WifiP2pGroup?) {}

                                override fun onDiscoveryStarted() {}

                                override fun onDiscoveryStopped() {}

                                override fun onPeerConnected(device: WifiP2pDevice?) {
                                    scope.launch(Dispatchers.Main) {
                                        onReady(false)
                                    }
                                }

                                override fun onPeerDisconnected(device: WifiP2pDevice?) {}
                            }
                        )
                    } catch (_: Exception) {
                    }
                }

                override fun onDiscoveryFailed(errorCode: Int) {
                    scope.launch(Dispatchers.Main) {
                        onStatus("wifi-direct-failed")
                    }
                }
            })
        } catch (_: Exception) {
            onStatus("wifi-direct-failed")
        }
    }

    fun stop() {
        stopped = true
        val p2p = manager ?: return
        try { p2p.removeGroup(channel, null) } catch (_: Exception) { }
        try { p2p.stopPeerDiscovery(channel) } catch (_: Exception) { }
    }

    init {
        val p2p = manager
        if (p2p != null) {
            channel = p2p.initialize(context, context.mainLooper, { status ->
                if (!stopped) onStatus(status)
            })
        }
    }
}
