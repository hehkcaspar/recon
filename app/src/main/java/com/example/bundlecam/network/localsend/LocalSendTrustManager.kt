package com.example.bundlecam.network.localsend

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/**
 * No-op trust manager — LocalSend peers present per-install self-signed certs that
 * cannot be validated by any CA, so the X509TrustManager is a passthrough and trust is
 * enforced post-handshake by [FingerprintPinningInterceptor] comparing the leaf cert's
 * SHA-256 against the fingerprint advertised in the peer's discovery announcement.
 */
class LocalSendTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // No-op: we present a cert but never validate clients (we're the sender).
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // No-op: trust is fingerprint-pinned in the interceptor, not chain-validated here.
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Returns true for any hostname — LocalSend peers are reached by IP address, not DNS,
 * and the cert's CN is the alias which doesn't match the peer's IP. Identity is enforced
 * via fingerprint, not hostname.
 */
val LocalSendHostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }

/** Typed tag attached to a [Request] to convey the expected fingerprint to the interceptor. */
data class ExpectedFingerprint(val hex: String)

/** Helper for builders. */
fun Request.Builder.expectFingerprint(hex: String): Request.Builder =
    tag(ExpectedFingerprint::class.java, ExpectedFingerprint(hex))

/**
 * Verifies the server cert's SHA-256 fingerprint matches the value supplied via the
 * [ExpectedFingerprint] request tag. Throws [SSLPeerUnverifiedException] on mismatch so
 * the call fails just like a CA-validation failure would.
 *
 * If no [ExpectedFingerprint] tag is present (e.g. a request that doesn't go through
 * the LocalSend client), the interceptor passes through — but in practice every request
 * issued through the LocalSend OkHttpClient should carry the tag.
 */
class FingerprintPinningInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val expected = request.tag(ExpectedFingerprint::class.java)?.hex
        val response = chain.proceed(request)
        if (expected != null) {
            val handshake = response.handshake
                ?: run {
                    response.close()
                    throw SSLPeerUnverifiedException("no TLS handshake on ${request.url}")
                }
            val leaf = handshake.peerCertificates.firstOrNull() as? X509Certificate
                ?: run {
                    response.close()
                    throw SSLPeerUnverifiedException("no peer certificate on ${request.url}")
                }
            val actual = LocalSendCertManager.computeFingerprintHex(leaf.encoded)
            if (!actual.equals(expected, ignoreCase = true)) {
                response.close()
                throw SSLPeerUnverifiedException(
                    "fingerprint mismatch on ${request.url}: expected $expected, got $actual"
                )
            }
        }
        return response
    }
}
