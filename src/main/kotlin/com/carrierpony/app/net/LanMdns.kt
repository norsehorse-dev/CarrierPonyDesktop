// LanMdns.kt
// CarrierPony Desktop. The one seam between LAN-direct and the network: advertise this node as
// `_carrierpony._tcp` and hear about other nodes. Android's LanDiscovery does this through NSD;
// desktop does it through JmDNS (JmDnsLanMdns.kt). Tests plug in an in-memory bus, so the
// handshake, identify sweep and delivery in DesktopLanDiscovery run against real sockets on
// 127.0.0.1 with no multicast at all.

package com.carrierpony.app.net

import java.net.InetAddress

interface LanMdns : AutoCloseable {
    interface Listener {
        /** A node appeared and resolved to an address. Called from any thread, possibly twice. */
        fun found(name: String, host: InetAddress, port: Int)
        fun lost(name: String)
    }

    /** Announce this node (name is its random id) on [port]; idempotent. */
    fun advertise(name: String, port: Int)

    /** Start browsing. Nodes present before the call are reported too. */
    fun browse(listener: Listener)

    /** No discovery at all: tests, and the CLI, where a one-shot command must not open
     *  multicast sockets or wait for a responder to shut down. */
    object None : LanMdns {
        override fun advertise(name: String, port: Int) {}
        override fun browse(listener: Listener) {}
        override fun close() {}
    }
}
