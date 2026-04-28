package com.example.bundlecam

import com.example.bundlecam.network.localsend.LocalSendCertManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSendFingerprintTest {

    @Test
    fun empty_input_produces_known_sha256() {
        // SHA-256 of zero bytes — RFC 6234 reference value.
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, LocalSendCertManager.computeFingerprintHex(ByteArray(0)))
    }

    @Test
    fun abc_input_produces_known_sha256() {
        // SHA-256("abc") — FIPS 180-4 Appendix B test vector.
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, LocalSendCertManager.computeFingerprintHex("abc".toByteArray()))
    }

    @Test
    fun output_is_64_hex_characters() {
        val fp = LocalSendCertManager.computeFingerprintHex(byteArrayOf(0x42))
        assertEquals(64, fp.length)
        assertTrue("must be lowercase hex: $fp", fp.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun output_is_lowercase_only() {
        val fp = LocalSendCertManager.computeFingerprintHex(byteArrayOf(-1, -1, -1, -1))
        assertEquals(fp.lowercase(), fp)
    }

    @Test
    fun different_inputs_produce_different_outputs() {
        val a = LocalSendCertManager.computeFingerprintHex(byteArrayOf(1))
        val b = LocalSendCertManager.computeFingerprintHex(byteArrayOf(2))
        assertTrue("collision in trivial fingerprints", a != b)
    }
}
