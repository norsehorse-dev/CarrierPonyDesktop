// AttachmentsTest.kt
// Picking, pasting, saving and thumbnails, plus one full trip: a file sent from one desktop
// arrives on another, is sealed on disk there, and saves back out byte for byte.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.net.LanDiscovery
import com.carrierpony.app.net.LanMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentsTest {

    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    private fun png(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    @Test
    fun mimeComesFromTheExtensionTableFirst() {
        val dir = Files.createTempDirectory("cp-att")
        assertEquals("image/jpeg", Attachments.mimeOf(dir.resolve("Holiday.JPG")))
        assertEquals("application/pdf", Attachments.mimeOf(dir.resolve("a.b.pdf")))
        assertEquals("application/octet-stream", Attachments.mimeOf(dir.resolve("mystery.zzzq")))
    }

    @Test
    fun filesBecomeAttachmentsAndFoldersAreRefused() {
        val dir = Files.createTempDirectory("cp-att")
        val file = dir.resolve("notes.txt"); Files.write(file, "hello".toByteArray())
        val one = Attachments.fromFiles(listOf(file)).single()
        assertEquals("notes.txt", one.filename); assertEquals("text/plain", one.mime)
        assertContentEquals("hello".toByteArray(), one.data)
        assertFailsWith<Attachments.Rejected> { Attachments.fromFiles(listOf(dir)) }
        assertFailsWith<Attachments.Rejected> { Attachments.fromFiles(listOf(dir.resolve("missing.txt"))) }
    }

    @Test
    fun theSizeLimitCountsWhatIsAlreadyQueued() {
        val dir = Files.createTempDirectory("cp-att")
        val file = dir.resolve("small.bin"); Files.write(file, ByteArray(10))
        assertEquals(1, Attachments.fromFiles(listOf(file), alreadyPending = AppConfig.maxAttachmentBytes - 10L).size)
        val e = assertFailsWith<Attachments.Rejected> { Attachments.fromFiles(listOf(file), alreadyPending = AppConfig.maxAttachmentBytes - 9L) }
        assertTrue(e.message!!.contains("50 MB"))
    }

    @Test
    fun aPastedImageBecomesAPng() {
        val a = Attachments.fromImage(BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB))
        assertEquals("image/png", a.mime)
        assertTrue(a.filename.startsWith("pasted-") && a.filename.endsWith(".png"))
        val back = ImageIO.read(a.data.inputStream())
        assertEquals(40, back.width); assertEquals(30, back.height)
    }

    @Test
    fun thumbnailsShrinkButNeverGrowAndIgnoreNonImages() {
        val big = Attachments.thumbnail(png(1600, 800), 320)!!
        assertEquals(320, big.width); assertEquals(160, big.height)
        val small = Attachments.thumbnail(png(64, 64), 320)!!
        assertEquals(64, small.width)
        assertNull(Attachments.thumbnail("not an image".toByteArray(), 320))
    }

    @Test
    fun uniqueNameNeverCollides() {
        val dir = Files.createTempDirectory("cp-att")
        assertEquals("photo.png", Attachments.uniqueName(dir, "photo.png").fileName.toString())
        Files.write(dir.resolve("photo.png"), byteArrayOf(1))
        assertEquals("photo (1).png", Attachments.uniqueName(dir, "photo.png").fileName.toString())
        Files.write(dir.resolve("photo (1).png"), byteArrayOf(1))
        assertEquals("photo (2).png", Attachments.uniqueName(dir, "photo.png").fileName.toString())
        Files.write(dir.resolve("README"), byteArrayOf(1))
        assertEquals("README (1)", Attachments.uniqueName(dir, "README").fileName.toString())
    }

    @Test
    fun aFileTravelsBetweenDesktopsIsSealedOnDiskAndSavesBackOut(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                fun open(name: String, device: String): Pair<DesktopSession, java.nio.file.Path> {
                    val dir = Files.createTempDirectory("cp-att-$name")
                    val vault = Vault(DesktopStorage.vaultFile(dir)).apply { create("pw".toCharArray(), cheap) }
                    val accounts = DesktopAccounts(DesktopStorage(dir, vault), DesktopPrefs(dir.resolve("prefs.json")))
                    return accounts.session(accounts.create(name), scope, relayBaseURL = relay.baseURL, deviceID = device, lanDiscovery = LanDiscovery(LanMdns.None) { false }) to dir
                }
                val (alice, _) = open("Alice", "a".repeat(32))
                val (bob, bobDir) = open("Bob", "b".repeat(32))
                fun contact(s: DesktopSession, n: String) = Contact(s.identity.fingerprint, PublicKey(s.identity.fingerprint, s.identity.armoredPublicKey), n, TrustLevel.VERIFIED)
                alice.contacts.add(contact(bob, "Bob")); bob.contacts.add(contact(alice, "Alice"))
                alice.start(3600); bob.start(3600)

                val src = Files.createTempDirectory("cp-att-src")
                val picture = src.resolve("picture.png"); Files.write(picture, png(800, 600))
                val secret = "the quick brown fox ".repeat(500).toByteArray()
                val doc = src.resolve("report.txt"); Files.write(doc, secret)

                // AttachmentStore is process-wide; whichever session was built last owns the directory.
                alice.store.send(text = "two files", attachments = Attachments.fromFiles(listOf(picture, doc)), to = alice.contacts.contact(bob.identity.fingerprint)!!)
                bob.store.refresh()

                val received = bob.store.conversations.value.values.flatMap { it.messages }
                    .first { it.direction == MessageDirection.INCOMING && it.attachments.size == 2 }
                val gotDoc = received.attachments.first { it.filename == "report.txt" }
                val gotPicture = received.attachments.first { it.filename == "picture.png" }
                assertTrue(gotPicture.isImage); assertFalse(gotDoc.isImage)

                val onDisk = Files.readAllBytes(AttachmentStore.file(gotDoc.localPath).toPath())
                assertTrue(String(onDisk, Charsets.ISO_8859_1).startsWith("CPAR1\n"), "stored attachments must be sealed")
                assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("quick brown fox"))

                val downloads = Files.createTempDirectory("cp-att-dl")
                val saved = assertNotNull(Attachments.saveTo(gotDoc, downloads))
                assertContentEquals(secret, Files.readAllBytes(saved))
                assertEquals("report (1).txt", Attachments.saveTo(gotDoc, downloads)!!.fileName.toString())

                assertEquals(320, Attachments.thumbnail(gotPicture, 320)!!.width)
                assertNull(Attachments.thumbnail(gotDoc, 320))

                val temp = assertNotNull(Attachments.temporaryCopyForOpening(gotDoc))
                assertContentEquals(secret, Files.readAllBytes(temp))
            } finally {
                scope.cancel()
            }
        }
    }
}
