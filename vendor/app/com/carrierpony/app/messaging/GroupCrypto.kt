// GroupCrypto.kt
// CarrierPony Android
//
// Seal and open a group message, ported from iOS Core/Messaging/GroupCrypto.swift.
// A message is signed once by the sender (OpenPGP Ed25519 detached signature) and
// encrypted once under the current epoch key (AES-GCM-256). The identical sealed
// payload is fanned out to every member. On open, the epoch key decrypts it and the
// sender's signature is verified against the group roster, so a member cannot forge
// a message from another.
//
// Wire compatibility with iOS CryptoKit: the "ct" field is base64 of the combined
// box nonce(12) || ciphertext || tag(16), exactly what CryptoKit's AES.GCM
// SealedBox.combined produces, so an iOS and an Android member can share a group.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.CryptoEngine
import com.carrierpony.app.crypto.Fingerprint
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

sealed class GroupCryptoException(message: String) : Exception(message) {
    class BadBox : GroupCryptoException("Malformed group box")
    class NoKey : GroupCryptoException("No key for that epoch")
    class NotMember : GroupCryptoException("Sender is not in the roster")
    class BadSignature : GroupCryptoException("Sender signature did not verify")
}

object GroupCrypto {

    /** Sign [container] and encrypt it under [key], returning the wire payload. */
    fun seal(container: ByteArray, groupID: String, sender: Fingerprint, epoch: Int, key: SecretKey, crypto: CryptoEngine): ByteArray {
        val sig = crypto.detachedSignature(container)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ctTag = cipher.doFinal(container)
        val combined = iv + ctTag
        val box = JSONObject()
        box.put("groupID", groupID)
        box.put("sender", sender.hex)
        box.put("epoch", epoch)
        box.put("sig", sig)
        box.put("ct", Base64.Default.encode(combined))
        return box.toString().toByteArray(Charsets.UTF_8)
    }

    /** Decrypt with the epoch key and verify the sender's signature against the
     *  roster. Returns the sender and the plaintext container. */
    fun open(payload: ByteArray, group: ChatGroup, keyFor: (Int) -> SecretKey?, crypto: CryptoEngine): Pair<Fingerprint, ByteArray> {
        val box = try { JSONObject(String(payload, Charsets.UTF_8)) } catch (e: Exception) { throw GroupCryptoException.BadBox() }
        val sender = Fingerprint.from(box.optString("sender", "")) ?: throw GroupCryptoException.BadBox()
        val epoch = box.optInt("epoch", -1)
        val sig = box.optString("sig", "")
        val combined = try { Base64.Default.decode(box.getString("ct")) } catch (e: Exception) { throw GroupCryptoException.BadBox() }
        val member = group.member(sender) ?: throw GroupCryptoException.NotMember()
        val key = keyFor(epoch) ?: throw GroupCryptoException.NoKey()
        if (combined.size < 12) throw GroupCryptoException.BadBox()
        val iv = combined.copyOfRange(0, 12)
        val body = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val container = try { cipher.doFinal(body) } catch (e: Exception) { throw GroupCryptoException.BadBox() }
        if (!crypto.verifyDetached(sig, container, member.publicKey)) throw GroupCryptoException.BadSignature()
        return sender to container
    }

    /** Peek the groupID from a sealed payload without decrypting, for routing a
     *  message whose group we may not know yet. */
    fun peekGroupID(payload: ByteArray): String? = try {
        JSONObject(String(payload, Charsets.UTF_8)).optString("groupID", "").ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}
