package com.example.bundlecam.network.localsend

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trust manager that pins the peer's leaf cert SHA-256 to the fingerprint advertised in
 * the peer's discovery announcement.
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
    private val expectedFingerprintHex: String,
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
        val actual = LocalSendCertManager.computeFingerprintHex(leaf.encoded)
        if (!actual.equals(expectedFingerprintHex, ignoreCase = true)) {
            throw CertificateException(
                "fingerprint mismatch: expected $expectedFingerprintHex, got $actual",
            )
        }
    }

    // Client side: we don't run a server, so a client-cert chain shouldn't ever be
    // presented to us. If one is, treat as a no-op (don't throw — would break TLS
    // for setups that present an unrequested client cert).
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
 * Returns true for any hostname — LocalSend peers are reached by IP address, not DNS,
 * and the cert's CN is the alias which doesn't match the peer's IP. Identity is
 * enforced via fingerprint pinning in [FingerprintPinningTrustManager], not hostname.
 */
val LocalSendHostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }
