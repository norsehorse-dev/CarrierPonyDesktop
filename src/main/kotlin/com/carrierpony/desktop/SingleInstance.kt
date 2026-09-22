// SingleInstance.kt
// CarrierPony Desktop. D12: one window per data directory.
//
// A second launch (a Dock click while the app sits in the tray, a second shortcut, a login item
// firing after a manual start) must not open a second window over the same vault: two processes
// polling the same relay with the same device id would each take the other's envelopes. The
// first process to hold an exclusive lock on dataDir/.instance.lock is the primary. It opens a
// loopback ServerSocket and writes the port to dataDir/.instance.port. A later process cannot
// take the lock, connects to that port so the primary raises its window, and exits. If the IPC
// fails (stale port file, nothing listening), the later process fails safe and runs as its own
// instance; a second window beats a launch that does nothing.
//
// CLI verbs never touch this: `carrierpony inbox` beside a running window is normal use.

package com.carrierpony.desktop

import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class SingleInstance(private val dataDir: Path) {

    /** Set by the window once it exists; a forwarded launch calls it to raise the window. */
    @Volatile var focusWindow: (() -> Unit)? = null

    // Held for the process lifetime so the OS keeps the lock; never released explicitly.
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /**
     * True when this process is the primary and should open the window. False when a primary
     * already runs and has been told to raise its window; the caller should exit quietly.
     */
    fun acquire(): Boolean {
        val lockPath = dataDir.resolve(LOCK_FILE)
        val portPath = dataDir.resolve(PORT_FILE)
        val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        val acquired = try { channel.tryLock() } catch (e: Exception) { null }
        if (acquired != null) {
            lockChannel = channel
            lock = acquired
            startServer(portPath)
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { Files.deleteIfExists(portPath) } })
            return true
        }
        channel.close()
        return !raisePrimary(portPath)
    }

    /** Test hook: stop serving and drop the lock so a second acquire in the same JVM can win. */
    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { lockChannel?.close() }
        server = null; lock = null; lockChannel = null
    }

    private fun startServer(portPath: Path) {
        val s = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        server = s
        Files.writeString(portPath, s.localPort.toString())
        val t = Thread {
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (e: Exception) { break }
                socket.use { runCatching { it.getInputStream().read() } }
                focusWindow?.invoke()
            }
        }
        t.isDaemon = true
        t.name = "carrierpony-single-instance"
        t.start()
    }

    private fun raisePrimary(portPath: Path): Boolean {
        val port = runCatching { Files.readString(portPath).trim().toInt() }.getOrNull() ?: return false
        return try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).use { it.write("raise\n"); it.flush() }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val LOCK_FILE = ".instance.lock"
        const val PORT_FILE = ".instance.port"
    }
}
