package com.example.bundlecam.network.localsend

import okhttp3.tls.HeldCertificate
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trust manager that pins the peer's leaf cert SHA-256 to the fingerprint advertised in
 * the peer's discovery announcement (when [expectedFingerprintHex] is non-null), or
 * accepts any presented self-signed certificate during unpinned discovery probes (when
 * [expectedFingerprintHex] is null, where the peer's fingerprint is learned from the
 * TLS handshake itself).
 *
 * The check runs INSIDE checkServerTrusted (i.e. during the TLS handshake) rather than
 * post-handshake in an OkHttp interceptor. The post-handshake approach left the session
 * in an ambiguous authentication state: Conscrypt on Android, when the trust manager is
 * a pure no-op, marks the session as unverified, which makes
 * `SSLSession.getPeerCertificates()` throw and OkHttp's `Handshake.get` swallows it into
 * an empty cert list — at which point an interceptor reading
 * `response.handshake.peerCertificates` has nothing to verify against and aborts with
 * "no peer certificate".
 *
 * Doing the check here means: cert matches expected fingerprint → return → session is
 * authenticated → peerCertificates populates normally and the connection succeeds. Cert
 * doesn't match → throw → handshake fails fast with a clear cause.
 *
 * Implements [X509ExtendedTrustManager] (not just X509TrustManager) so the JDK doesn't
 * wrap us in `AbstractTrustManagerWrapper`, which adds its OWN hostname-check against
 * the cert's CN/SAN — incompatible with LocalSend's per-install certs whose CN is a
 * device alias, not a peer IP. Hostname semantics are owned by the no-op
 * [LocalSendHostnameVerifier].
 */
class FingerprintPinningTrustManager(
    private val expectedFingerprintHex: String? = null,
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        verify(chain)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
        verify(chain)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
        verify(chain)
    }

    private fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("no peer cert presented")
        val expected = expectedFingerprintHex ?: return
        val actual = LocalSendCertManager.computeFingerprintHex(leaf.encoded)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw CertificateException(
                "fingerprint mismatch: expected $expected, got $actual",
            )
        }
    }

    // Inbound discovery `/register` server sets `wantClientAuth = true` so LocalSend 1.18+
    // peers present their self-signed client certificate during the TLS handshake. Accept
    // any valid chain here so `SSLSession.peerCertificates` populates for fingerprint
    // extraction without requiring a CA hierarchy.
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
    }
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * KeyManager presenting our per-install self-signed [HeldCertificate] for both outbound
 * mTLS client connections and the inbound discovery `/register` HTTPS server.
 *
 * Why a custom [X509ExtendedKeyManager] instead of stock `KeyManagerFactory`:
 * LocalSend 1.18+ (`packages/core/src/http/server`) requires mandatory client certificate
 * authentication (`mandatory_client_auth = true`) and populates its `WebPkiClientVerifier`
 * root store with its own self-signed cert (`CN=LocalSend User`). Consequently, the TLS
 * `CertificateRequest` frame lists `CN=LocalSend User` in `certificate_authorities`
 * (`issuers`). Stock Android/JDK `KeyManagerImpl` filters client certs by matching
 * their issuer against `issuers` — which never matches our self-signed cert (`CN=Recon`),
 * causing `chooseClientAlias` to return `null` and the handshake to abort with TLS alert
 * `certificate_required`. Ignoring `issuers` here ensures our client certificate is
 * always presented.
 */
class LocalSendKeyManager(
    private val heldCertificate: HeldCertificate,
) : X509ExtendedKeyManager() {

    private val keyAlgorithm: String = heldCertificate.keyPair.private.algorithm

    private fun matchesKeyType(keyType: String?): Boolean {
        if (keyType == null) return true
        val upper = keyType.uppercase()
        return when (keyAlgorithm.uppercase()) {
            "EC" -> upper == "EC" || upper == "EC_EC" || upper == "EC_RSA" || upper == "ECDSA"
            "RSA" -> upper == "RSA" || upper == "RSASSA-PSS"
            else -> upper == keyAlgorithm.uppercase()
        }
    }

    private fun matchesAnyKeyType(keyTypes: Array<out String>?): Boolean =
        keyTypes.isNullOrEmpty() || keyTypes.any { matchesKeyType(it) }

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = if (matchesKeyType(keyType)) arrayOf(ALIAS) else null

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = if (matchesAnyKeyType(keyType)) ALIAS else null

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = if (matchesAnyKeyType(keyType)) ALIAS else null

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = if (matchesKeyType(keyType)) arrayOf(ALIAS) else null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = if (matchesKeyType(keyType)) ALIAS else null

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = if (matchesKeyType(keyType)) ALIAS else null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) arrayOf(heldCertificate.certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == ALIAS) heldCertificate.keyPair.private else null

    private companion object {
        const val ALIAS = "localsend"
    }
}

/**
 * Builds an [SSLContext] + [FingerprintPinningTrustManager] pair wired with our mTLS
 * client/server identity (when [heldCertificate] is provided) and optional server
 * fingerprint pinning.
 */
fun buildLocalSendSslContext(
    heldCertificate: HeldCertificate?,
    expectedFingerprintHex: String? = null,
): Pair<SSLContext, FingerprintPinningTrustManager> {
    val tm = FingerprintPinningTrustManager(expectedFingerprintHex)
    val kms: Array<KeyManager>? = heldCertificate?.let { arrayOf(LocalSendKeyManager(it)) }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(kms, arrayOf<TrustManager>(tm), SecureRandom())
    }
    return sslContext to tm
}

/**
 * Returns true for any hostname — LocalSend peers are reached by IP address, not DNS,
 * and the cert's CN is the alias which doesn't match the peer's IP. Identity is
 * enforced via fingerprint pinning in [FingerprintPinningTrustManager], not hostname.
 */
val LocalSendHostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }
