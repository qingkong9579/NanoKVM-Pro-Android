package com.nanokvm.app.data.net

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Trust-all TLS for the NanoKVM-Pro self-signed certificate.
 *
 * SECURITY NOTE (MVP, LAN only): the device serves HTTPS/443 with a self-signed
 * cert and no preloadable CA, so for the MVP we accept any peer during the WS/REST
 * handshake. This is intentional and documented; do NOT ship this as the final
 * trust policy. Follow-on work should pin the device's leaf certificate after a
 * trust-on-first-use handshake (the server reads `Cookie: nano-kvm-token` first,
 * so the WS upgrade uses the same JWT as REST — the trust override applies to both
 * since they share one OkHttpClient).
 */
object Tls {
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    val sslSocketFactory = sslContext.socketFactory

    private val hostnameVerifier = HostnameVerifier { _, _ -> true }

    /** OkHttp builder pre-wired with the trust-all socket factory. */
    fun okHttpBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllManager)
            .hostnameVerifier(hostnameVerifier)
            .retryOnConnectionFailure(false)
}