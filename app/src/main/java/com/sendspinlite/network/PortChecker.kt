package com.sendspinlite.network

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object PortChecker {
    private const val TAG = "PortChecker"
    private const val TIMEOUT_MS = 3000 // 3 second timeout

    internal fun interface SocketConnector {
        fun connect(
            host: String,
            port: Int,
            timeoutMs: Int,
        )
    }

    private object DefaultSocketConnector : SocketConnector {
        override fun connect(
            host: String,
            port: Int,
            timeoutMs: Int,
        ) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
        }
    }

    sealed class PortCheckResult {
        data class PortOpen(val host: String, val port: Int) : PortCheckResult()

        data class PortClosed(val host: String, val port: Int) : PortCheckResult()

        data class ServerUnreachable(val host: String, val port: Int, val error: String) : PortCheckResult()
    }

    suspend fun checkPort(
        host: String,
        port: Int,
    ): PortCheckResult =
        checkPort(
            host = host,
            port = port,
            ioDispatcher = Dispatchers.IO,
            socketConnector = DefaultSocketConnector,
        )

    internal suspend fun checkPort(
        host: String,
        port: Int,
        ioDispatcher: CoroutineDispatcher,
        socketConnector: SocketConnector,
    ): PortCheckResult {
        return try {
            withContext(ioDispatcher) {
                checkPortBlocking(host, port, socketConnector)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error checking port $port on $host: ${e.message}", e)
            PortCheckResult.ServerUnreachable(host, port, e.message ?: "Unknown error")
        }
    }

    private fun checkPortBlocking(
        host: String,
        port: Int,
        socketConnector: SocketConnector,
    ): PortCheckResult {
        return try {
            Log.i(TAG, "Checking if port $port is open on $host...")
            socketConnector.connect(host, port, TIMEOUT_MS)
            Log.i(TAG, "Port $port is OPEN on $host")
            PortCheckResult.PortOpen(host, port)
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Connection timed out to $host:$port (port likely closed)")
            PortCheckResult.PortClosed(host, port)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to $host:$port: ${e.message}")
            PortCheckResult.PortClosed(host, port)
        }
    }
}
