package com.carrierpony.app.nostr

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * secp256k1 point math and BIP340 (schnorr) signing, in pure JDK BigInteger + SHA-256
 * so there is no native blob and it stays F-Droid-buildable. We only SIGN (Nostr
 * relays verify, and the payload self-authenticates), so no verification is needed.
 * Validated against the official BIP340 test vectors in NostrCryptoTest.
 */
object Secp256k1 {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val G = Point(Gx, Gy)
    private val THREE = BigInteger.valueOf(3)

    private data class Point(val x: BigInteger, val y: BigInteger)

    private fun add(p1: Point?, p2: Point?): Point? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x && p1.y != p2.y) return null
        val lam = if (p1 == p2) {
            (THREE * p1.x * p1.x * (BigInteger.TWO * p1.y).modInverse(P)).mod(P)
        } else {
            ((p2.y - p1.y) * (p2.x - p1.x).mod(P).modInverse(P)).mod(P)
        }
        val x3 = (lam * lam - p1.x - p2.x).mod(P)
        val y3 = (lam * (p1.x - x3) - p1.y).mod(P)
        return Point(x3, y3)
    }

    private fun mul(pt: Point, k: BigInteger): Point? {
        var r: Point? = null
        var p: Point? = pt
        var kk = k
        while (kk.signum() > 0) {
            if (kk.testBit(0)) r = add(r, p)
            p = add(p, p)
            kk = kk.shiftRight(1)
        }
        return r
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (x in parts) md.update(x)
        return md.digest()
    }

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(t, t, msg)
    }

    private fun bytes32(x: BigInteger): ByteArray {
        val b = x.toByteArray()
        val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    private fun bi(b: ByteArray) = BigInteger(1, b)

    /** 32-byte x-only public key for a 32-byte secret key. */
    fun xOnlyPubkey(seckey: ByteArray): ByteArray = bytes32(mul(G, bi(seckey))!!.x)

    /** BIP340 schnorr signature (64 bytes) over a 32-byte message. */
    fun schnorrSign(seckey: ByteArray, msg: ByteArray, aux: ByteArray): ByteArray {
        val d0 = bi(seckey)
        require(d0 >= BigInteger.ONE && d0 <= N - BigInteger.ONE) { "invalid secret key" }
        val pt = mul(G, d0)!!
        val d = if (pt.y.testBit(0)) N - d0 else d0
        val pX = bytes32(pt.x)
        val auxHash = taggedHash("BIP0340/aux", aux)
        val dBytes = bytes32(d)
        val t = ByteArray(32) { (dBytes[it].toInt() xor auxHash[it].toInt()).toByte() }
        val k0 = bi(taggedHash("BIP0340/nonce", t + pX + msg)).mod(N)
        require(k0.signum() != 0) { "bad nonce" }
        val rPt = mul(G, k0)!!
        val k = if (rPt.y.testBit(0)) N - k0 else k0
        val rX = bytes32(rPt.x)
        val e = bi(taggedHash("BIP0340/challenge", rX + pX + msg)).mod(N)
        return rX + bytes32((k + e * d).mod(N))
    }

    /** A fresh secret key scalar in [1, n-1]. */
    fun randomSecretKey(): ByteArray {
        val rnd = SecureRandom()
        while (true) {
            val b = ByteArray(32).also { rnd.nextBytes(it) }
            val v = bi(b)
            if (v >= BigInteger.ONE && v <= N - BigInteger.ONE) return b
        }
    }

    fun randomAux(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
}
