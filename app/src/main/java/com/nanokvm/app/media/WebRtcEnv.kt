package com.nanokvm.app.media

import android.content.Context
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * Process-wide WebRTC bootstrap: one [EglBase] + one [PeerConnectionFactory],
 * created once on first use.
 *
 * The video renderer (`SurfaceViewRenderer`) and the hardware decoder factory must
 * share one EGL context — otherwise decoded `TextureBuffer`s cannot be drawn. Both
 * resolve their context through this singleton; `ensure()` is idempotent and safe
 * to call from any thread (first call wins).
 */
object WebRtcEnv {
    private const val TAG = "NanokvmWebRtc"

    private val lock = Any()

    @Volatile
    private var ready = false

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory

    fun ensure(appContext: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            val app = appContext.applicationContext
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app)
                    .createInitializationOptions()
            )
            eglBase = EglBase.create(null, EglBase.CONFIG_PLAIN)
            val shared = eglBase.eglBaseContext
            factory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(shared))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(shared, true, true))
                .createPeerConnectionFactory()
            ready = true
            Log.i(TAG, "PeerConnectionFactory initialized (egl shared=$shared)")
        }
    }

    /** EGL context shared by every SurfaceViewRenderer and the decoder factory. */
    fun eglContext(): EglBase.Context {
        check(ready) { "WebRtcEnv.ensure() not called" }
        return eglBase.eglBaseContext
    }

    fun peerConnectionFactory(): PeerConnectionFactory {
        check(ready) { "WebRtcEnv.ensure() not called" }
        return factory
    }
}
