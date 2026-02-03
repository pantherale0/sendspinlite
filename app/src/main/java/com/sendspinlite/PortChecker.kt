package com.sendspinlite

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object PortChecker {
    private const val TAG = "PortChecker"
    private const val TIMEOUT_MS = 3000  // 3 second timeout

    sealed class PortCheckResult {
        data class PortOpen(val host: String, val port: Int) : PortCheckResult()
        data class PortClosed(val host: String, val port: Int) : PortCheckResult()
        data class ServerUnreachable(val host: String, val port: Int, val error: String) : PortCheckResult()
    }

    suspend fun checkPort(host: String, port: Int): PortCheckResult {
        return try {
            val socket = Socket()
            try {
                Log.i(TAG, "Checking if port $port is open on $host...")
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                Log.i(TAG, "Port $port is OPEN on $host")
                PortCheckResult.PortOpen(host, port)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Connection timed out to $host:$port (port likely closed)")
                PortCheckResult.PortClosed(host, port)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to $host:$port: ${e.message}")
                PortCheckResult.PortClosed(host, port)
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking port $port on $host: ${e.message}", e)
            PortCheckResult.ServerUnreachable(host, port, e.message ?: "Unknown error")
        }
    }
}
