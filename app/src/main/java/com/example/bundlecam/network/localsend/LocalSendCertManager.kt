package com.example.bundlecam.network.localsend

import android.content.Context
import android.util.Log
import okhttp3.tls.HeldCertificate
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "Recon/LocalSendCert"
private const val CERT_DIR_NAME = "localsend"
private const val CERT_FILE_NAME = "cert.pem"

/**
 * Per-install self-signed cert used as the LocalSend HTTPS identity. The fingerprint
 * (lowercase hex SHA-256 of the leaf cert's DER bytes) advertised in our discovery
 * announcements IS the cert's hash — peers receiving our announce later verify this
 * matches the leaf cert presented during the TLS handshake.
 *
 * The cert is generated lazily on first call to [getOrCreate] and cached on disk in
 * `filesDir/localsend/cert.pem` (PEM-encoded leaf cert + PKCS#8 private key concatenated
 * — `HeldCertificate.decode` reads both blocks from one string). Clearing app data wipes
 * the cert and a new identity is generated on next call.
 */
class LocalSendCertManager(context: Context) {
    private val baseDir: File = File(context.applicationContext.filesDir, CERT_DIR_NAME)

    @Volatile
    private var cached: HeldCertificate? = null
    private val lock = Any()

    /** Returns the per-install certificate, generating it on first call. */
    fun getOrCreate(): HeldCertificate {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val pemFile = File(baseDir, CERT_FILE_NAME)
            val loaded = if (pemFile.exists()) {
                runCatching { HeldCertificate.decode(pemFile.readText()) }
                    .onFailure { Log.w(TAG, "failed to load existing cert; regenerating", it) }
                    .getOrNull()
            } else null
            val final = loaded ?: generateAndSave(pemFile)
            cached = final
            return final
        }
    }

    /** Lowercase hex SHA-256 of the leaf cert in DER form. */
    fun fingerprintHex(): String = computeFingerprintHex(getOrCreate().certificate.encoded)

    private fun generateAndSave(pemFile: File): HeldCertificate {
        baseDir.mkdirs()
        // ECDSA P-256 (HeldCertificate default) — smaller cert + faster handshake than
        // RSA-2048, accepted by every modern TLS stack including the Dart/Rust LocalSend
        // desktops. 100-year validity so the per-install identity outlives any plausible
        // device lifetime; fingerprint pinning makes expiry checks moot anyway.
        val held = HeldCertificate.Builder()
            .commonName("Recon")
            .organizationalUnit("Recon")
            .duration(36500, TimeUnit.DAYS)
            .build()
        val pem = held.certificatePem() + held.privateKeyPkcs8Pem()
        pemFile.writeText(pem)
        Log.i(TAG, "generated self-signed cert at ${pemFile.absolutePath}")
        return held
    }

    companion object {
        /** Pure helper — testable without Android dependencies. */
        fun computeFingerprintHex(derBytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(derBytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
