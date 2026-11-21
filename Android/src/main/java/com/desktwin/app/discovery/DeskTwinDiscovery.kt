package com.desktwin.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.desktwin.app.diagnostics.DeskTwinDiagnostics
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

private const val SERVICE_TYPE = "_desktwin._tcp."
data class DiscoveredDevice(val name: String, val ip: String, val port: Int)
@Suppress("DEPRECATION")
class DeskTwinDiscovery(private val context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun discover() = callbackFlow<DiscoveredDevice> {
        val operationId = DeskTwinDiagnostics.operationId()
        DeskTwinDiagnostics.record("discovery", "started", "mdns", operationId)

        // Acquire WifiManager MulticastLock so Android vendors (Samsung, Xiaomi, etc.)
        // do not filter out mDNS UDP multicast packets while discovery is running.
        val multicastLock = wifiManager?.createMulticastLock("DeskTwinDiscovery")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                DeskTwinDiagnostics.record("discovery", "listening", "mdns", operationId)
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                try {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                            DeskTwinDiagnostics.record("discovery", "resolve_failed", "code=$code", operationId)
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = info.host
                            val host = address?.hostAddress
                            if (address !is Inet4Address || host.isNullOrBlank()
                                || address.isAnyLocalAddress || address.isLoopbackAddress
                                || address.isLinkLocalAddress || address.isMulticastAddress
                            ) {
                                DeskTwinDiagnostics.record("discovery", "resolve_failed", "unusable_host", operationId)
                                return
                            }
                            val result = trySend(DiscoveredDevice(info.serviceName, host, info.port))
                            DeskTwinDiagnostics.record(
                                "discovery",
                                if (result.isSuccess) "device_resolved" else "result_rejected",
                                "port=${info.port}",
                                operationId
                            )
                        }
                    })
                } catch (error: Exception) {
                    DeskTwinDiagnostics.recordThrowable("discovery_resolve", error, operationId)
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                DeskTwinDiagnostics.record("discovery", "service_lost", "mdns", operationId)
            }
            override fun onDiscoveryStopped(type: String) {
                DeskTwinDiagnostics.record("discovery", "stopped", "mdns", operationId)
            }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                DeskTwinDiagnostics.record("discovery", "start_failed", "code=$code", operationId)
                close(RuntimeException("Discovery start failed: $code"))
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {
                Log.w("DeskTwinDiscovery", "stop failed: $code")
                DeskTwinDiagnostics.record("discovery", "stop_failed", "code=$code", operationId)
            }
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            DeskTwinDiagnostics.recordThrowable("discovery_start", error, operationId)
            close(error)
        }
        awaitClose {
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
            runCatching { manager.stopServiceDiscovery(listener) }
                .onFailure { DeskTwinDiagnostics.recordThrowable("discovery_stop", it, operationId) }
        }
    }
}
