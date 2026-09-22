// JmDnsLanMdns.kt
// CarrierPony Desktop. LanMdns over JmDNS (org.jmdns, Apache-2.0, pure Java). Same service type
// Android and iOS use, so a desktop shows up in their "nearby" lists and they in its.
//
// JmmDNS, not JmDNS: it runs one responder per network interface, so a Mac with Wi-Fi plus a
// VPN tunnel or a Docker bridge advertises and listens on all of them, and a phone on the Wi-Fi
// is heard whichever interface enumerates first. A single JmDNS bound to the wrong interface
// was the first version of this file and showed the phone only now and then.

package com.carrierpony.app.net

import java.net.InetAddress
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

class JmDnsLanMdns : LanMdns {

    private val type = "_carrierpony._tcp.local."
    @Volatile private var dns: JmmDNS? = null
    private var advertised = false

    private fun instance(): JmmDNS = synchronized(this) {
        dns ?: JmmDNS.Factory.getInstance().also {
            dns = it
            android.util.Log.d("CPLAN", "mdns interfaces: ${runCatching { it.inetAddresses.map { a -> a.hostAddress } }.getOrDefault(emptyList())}")
        }
    }

    override fun advertise(name: String, port: Int) {
        synchronized(this) {
            if (advertised) return
            instance().registerService(ServiceInfo.create(type, name, port, ""))
            advertised = true
        }
    }

    override fun browse(listener: LanMdns.Listener) {
        val d = instance()
        d.addServiceListener(type, object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                android.util.Log.d("CPLAN", "mdns added ${event.name}")
                // Ask for the address; serviceResolved reports it. Off the JmDNS thread, since the
                // request can block up to its timeout.
                Thread { runCatching { event.dns.requestServiceInfo(event.type, event.name, 3000) } }.start()
            }
            override fun serviceRemoved(event: ServiceEvent) {
                android.util.Log.d("CPLAN", "mdns removed ${event.name}")
                listener.lost(event.name)
            }
            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val v4 = info.inet4Addresses.map { it as InetAddress }
                android.util.Log.d("CPLAN", "mdns resolved ${event.name} v4=${v4.map { it.hostAddress }} v6=${info.inet6Addresses.size} port=${info.port}")
                val host = v4.firstOrNull() ?: info.inetAddresses.firstOrNull() ?: return
                if (info.port > 0) listener.found(event.name, host, info.port)
            }
        })
    }

    override fun close() {
        synchronized(this) {
            runCatching { dns?.unregisterAllServices() }
            runCatching { dns?.close() }
            dns = null; advertised = false
        }
    }
}
