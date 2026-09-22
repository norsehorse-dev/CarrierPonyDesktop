// SingleInstanceTest.kt
// D12: the first acquire wins, a second one raises the primary's window and yields, and a stale
// port file with nothing behind it fails safe to a fresh primary.

package com.carrierpony.desktop

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {

    @Test
    fun secondLaunchRaisesThePrimaryAndYields() {
        val dir = Files.createTempDirectory("cp-instance")
        val primary = SingleInstance(dir)
        val raised = CountDownLatch(1)
        primary.focusWindow = { raised.countDown() }
        assertTrue(primary.acquire(), "first launch is the primary")
        assertTrue(Files.exists(dir.resolve(".instance.port")))

        val second = SingleInstance(dir)
        assertFalse(second.acquire(), "second launch yields to the running window")
        assertTrue(raised.await(5, TimeUnit.SECONDS), "the primary was asked to raise its window")
        primary.release()
    }

    @Test
    fun stalePortFileFailsSafeToANewPrimary() {
        val dir = Files.createTempDirectory("cp-instance")
        // Someone holds the lock but nothing listens on the recorded port: a primary that is
        // wedged, or a port file left by a crash on a busy machine. The launch must still open.
        val holder = java.nio.channels.FileChannel.open(
            dir.resolve(".instance.lock"),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE
        )
        val held = holder.tryLock()
        assertTrue(held != null)
        Files.writeString(dir.resolve(".instance.port"), "1")
        val next = SingleInstance(dir)
        assertTrue(next.acquire(), "with no one listening, the launch runs as its own instance")
        held?.release()
        holder.close()
    }
}
