// DesktopWanDirectBridge.kt
// CarrierPony Desktop. Placeholder twin of Android's WanDirectBridge (PonyDirect over WebRTC,
// excluded). WAN-direct is on the post-1.0 roadmap; the vendored WanDirectTransport compiles
// against this class by name and never finds a path.

package com.carrierpony.app.net

class WanDirectBridge {
    fun canReach(peerHex: String): Boolean = false
    suspend fun sendDirect(payload: ByteArray, peerHex: String): Boolean = false
}
