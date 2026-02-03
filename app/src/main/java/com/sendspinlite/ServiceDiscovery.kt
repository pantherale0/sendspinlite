package com.sendspinlite

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredServer(
    val name: String,
    val url: String,
    val host: String,
    val port: Int
)

class ServiceDiscovery(private val context: Context) {
    private val tag = "ServiceDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private val resolvedServers = mutableMapOf<String, DiscoveredServer>()
    
    // Track if discovery has found and connected to a server
    private var discoveryComplete = false

    fun startDiscovery() {
        if (discoveryComplete) {
            Log.i(tag, "Discovery already complete, not starting again")
            return
        }
        
        Log.i(tag, "Starting mDNS discovery for _sendspin-server._tcp.")

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(tag, "Discovery started for: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(tag, "Service discovered: ${service.serviceName}")
                // Resolve the service to get full details
                // All services found are _sendspin-server._tcp. so we resolve them all
                @Suppress("DEPRECATION")
                nsdManager.resolveService(service, getResolveListener())
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(tag, "Service lost: ${service.serviceName}")
                // Don't remove if discovery is complete (we're using this server)
                if (!discoveryComplete) {
                    resolvedServers.remove(service.serviceName)
                    _discoveredServers.value = resolvedServers.values.toList()
                }
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.i(tag, "Discovery stopped for: $regType")
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(tag, "Discovery failed to start: $regType, error: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(tag, "Discovery failed to stop: $regType, error: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(
                "_sendspin-server._tcp.",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            Log.i(tag, "Discovery request submitted successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start discovery", e)
        }
    }

    private fun getResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "Failed to resolve service: ${service.serviceName}, error code: $errorCode")
            }

            override fun onServiceResolved(service: NsdServiceInfo) {
                // Stop discovery once we have at least one server
                if (!discoveryComplete && _discoveredServers.value.isNotEmpty()) {
                    discoveryComplete = true
                    Log.i(tag, "First server found and resolved, stopping discovery")
                    stopDiscovery()
                    return
                }
                
                Log.i(tag, "Service resolved: ${service.serviceName}")

                @Suppress("DEPRECATION")
                val host = service.host?.hostAddress
                Log.d(tag, "Resolved host: $host, port: ${service.port}")
                
                if (host == null) {
                    Log.w(tag, "Service ${service.serviceName} has no host address")
                    return
                }

                val port = service.port
                val properties = service.attributes

                // Get path from TXT records, default to /sendspin
                val pathBytes = properties?.get("path")
                val path = if (pathBytes != null) {
                    String(pathBytes, Charsets.UTF_8)
                } else {
                    "/sendspin"
                }

                val finalPath = if (path.isEmpty()) "/sendspin" else if (path.startsWith("/")) path else "/$path"
                val url = "ws://$host:$port$finalPath"

                val discoveredServer = DiscoveredServer(
                    name = service.serviceName.removeSuffix("._sendspin-server._tcp."),
                    url = url,
                    host = host,
                    port = port
                )

                resolvedServers[service.serviceName] = discoveredServer
                _discoveredServers.value = resolvedServers.values.toList()

                Log.i(tag, "Added server: ${discoveredServer.name} at $url. Total servers: ${_discoveredServers.value.size}")
                
                // Stop discovery once we have the first server
                if (!discoveryComplete) {
                    discoveryComplete = true
                    Log.i(tag, "First server resolved, stopping discovery")
                    stopDiscovery()
                }
            }
        }
    }

    fun stopDiscovery() {
        Log.i(tag, "Stopping mDNS discovery")
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(tag, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
        // Don't clear servers - they stay available if we need to reconnect
    }
    
    fun resetDiscovery() {
        Log.i(tag, "Resetting discovery")
        discoveryComplete = false
        resolvedServers.clear()
        _discoveredServers.value = emptyList()
        startDiscovery()
    }

    fun getServerUrl(name: String): String? {
        return resolvedServers.values.find { it.name == name }?.url
    }
}
