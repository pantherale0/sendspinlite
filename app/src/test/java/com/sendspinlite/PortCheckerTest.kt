package com.sendspinlite

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class PortCheckerTest {
    @Test
    fun checkPort_runsSocketConnectOnIoDispatcher() =
        runBlocking {
            val callerDispatcher =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "port-check-caller")
                }.asCoroutineDispatcher()
            val ioDispatcher =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "port-check-io")
                }.asCoroutineDispatcher()

            try {
                val connectorThread = AtomicReference<String>()
                val callerThread =
                    withContext(callerDispatcher) {
                        val callerThread = Thread.currentThread().name
                        val result =
                            PortChecker.checkPort(
                                host = "127.0.0.1",
                                port = 1234,
                                ioDispatcher = ioDispatcher,
                                socketConnector =
                                    PortChecker.SocketConnector { _, _, _ ->
                                        connectorThread.set(Thread.currentThread().name)
                                    },
                            )

                        assertThat(result)
                            .isInstanceOf(PortChecker.PortCheckResult.PortOpen::class.java)
                        callerThread
                    }

                assertThat(connectorThread.get()).startsWith("port-check-io")
                assertThat(connectorThread.get()).isNotEqualTo(callerThread)
            } finally {
                callerDispatcher.close()
                ioDispatcher.close()
            }
        }
}
