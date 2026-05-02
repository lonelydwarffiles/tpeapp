package com.tpeapp.review

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import android.view.WindowManager
import com.tpeapp.ble.LovenseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * StreamCoordinator — coordinates the full WebRTC streaming pipeline.
 *
 * Pipeline:
 *  1. [ScreenCapturerAndroid] reads frames from [MediaProjection].
 *  2. Frames are fed into a [VideoSource] backed by the Tensor G4's hardware
 *     H.264/H.265 encoder via [HardwareVideoEncoderFactory].
 *  3. A [PeerConnection] is opened to the partner and signaled through the
 *     Camera-Site WebSocket relay at `/api/tpe/signal/{session_id}`
 *     (URL passed in via [start]).
 *  4. Resolution is adapted dynamically using [VideoSource.adaptOutputFormat]
 *     to maintain a high target frame-rate under constrained bandwidth.
 *
 * All heavy operations (PeerConnectionFactory init, socket I/O, SDP exchange)
 * run on [Dispatchers.IO] via a [SupervisorJob]-scoped coroutine scope so that
 * a single failure cannot silently kill the whole coordinator.
 */
object StreamCoordinator {

    // ------------------------------------------------------------------
    //  Configuration
    // ------------------------------------------------------------------

    private const val TAG            = "StreamCoordinator"
    private const val STREAM_ID      = "tpe_review_stream"
    private const val VIDEO_TRACK_ID = "tpe_video"
    private const val AUDIO_TRACK_ID = "tpe_audio"

    /** Target frame-rate. The adaptive logic will reduce resolution first. */
    private const val TARGET_FPS = 30

    /** Maximum resolution dimension (short side). Reduced if bandwidth is tight. */
    private const val MAX_RESOLUTION = 1080

    private val STUN_SERVERS = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    // ------------------------------------------------------------------
    //  State
    // ------------------------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // readTimeout(0) is correct for persistent WebSocket connections: OkHttp's WebSocket
        // implementation uses its own ping/pong keepalive; a non-zero read timeout would
        // incorrectly close idle-but-healthy connections between signaling messages.
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var eglBase: EglBase?                  = null
    @Volatile private var factory: PeerConnectionFactory?    = null
    @Volatile private var peerConnection: PeerConnection?    = null
    @Volatile private var videoCapturer: ScreenCapturerAndroid? = null
    @Volatile private var videoSource: VideoSource?          = null
    @Volatile private var videoTrack: VideoTrack?            = null
    @Volatile private var audioSource: AudioSource?          = null
    @Volatile private var audioTrack: AudioTrack?            = null
    @Volatile private var surfaceHelper: SurfaceTextureHelper? = null
    @Volatile private var wsSocket: WebSocket?               = null
    @Volatile private var sessionId: String                  = ""
    @Volatile private var signalingUrl: String               = ""
    @Volatile private var appContext: Context?               = null
    @Volatile private var remoteControlEnabled: Boolean      = false

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Initialise the WebRTC pipeline and connect to the signaling server.
     *
     * @param context        Application context.
     * @param resultCode     The result code from [android.app.Activity.RESULT_OK].
     * @param resultData     The permission-grant Intent returned by
     *                       [android.media.projection.MediaProjectionManager.createScreenCaptureIntent].
     * @param partnerSession The session ID of the partner to stream to.
     * @param remoteControlEnabled `true` if the user has consented to allow the partner to
     *                             inject input events on this device.
     */
    fun start(
        context: Context,
        resultCode: Int,
        resultData: Intent,
        partnerSession: String,
        signalingServerUrl: String,
        remoteControlEnabled: Boolean = false
    ) {
        sessionId   = partnerSession
        signalingUrl = signalingServerUrl
        appContext = context.applicationContext
        this.remoteControlEnabled = remoteControlEnabled
        RemoteInputDispatcher.remoteControlEnabled = remoteControlEnabled
        LovenseManager.init(context.applicationContext)
        scope.launch {
            try {
                initWebRtc(context, resultCode, resultData)
                connectSignaling()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start StreamCoordinator", e)
                cleanUp()
            }
        }
    }

    /** Tear down the WebRTC pipeline and disconnect from the signaling server. */
    fun stop() {
        scope.launch { cleanUp() }
    }

    // ------------------------------------------------------------------
    //  WebRTC initialisation
    // ------------------------------------------------------------------

    private fun initWebRtc(context: Context, resultCode: Int, resultData: Intent) {
        val appContext = context.applicationContext

        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                HardwareVideoEncoderFactory(
                    eglContext,
                    /* enableIntelVp8Encoder = */ true,
                    /* enableH264HighProfile = */ true
                )
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()

        videoSource    = factory!!.createVideoSource(/* isScreencast = */ true)
        surfaceHelper  = SurfaceTextureHelper.create("CaptureThread", eglContext)

        // ScreenCapturerAndroid calls MediaProjectionManager.getMediaProjection()
        // internally during startCapture(). The foreground service must already be
        // in the foreground state when this runs (guaranteed by ScreencastService).
        val capturer = ScreenCapturerAndroid(
            resultData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system")
                    stop()
                }
            }
        )
        capturer.initialize(surfaceHelper, appContext, videoSource!!.capturerObserver)
        capturer.startCapture(targetWidth(appContext), targetHeight(appContext), TARGET_FPS)
        videoCapturer = capturer

        videoTrack = factory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrack!!.setEnabled(true)

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
        }
        audioSource = factory!!.createAudioSource(audioConstraints)
        audioTrack  = factory!!.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        audioTrack!!.setEnabled(false)   // audio off by default for review sessions

        peerConnection = createPeerConnection()
        Log.i(TAG, "WebRTC pipeline initialised")
    }

    private fun createPeerConnection(): PeerConnection {
        val rtcConfig = PeerConnection.RTCConfiguration(STUN_SERVERS).apply {
            sdpSemantics  = PeerConnection.SdpSemantics.UNIFIED_PLAN
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy  = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory!!.createPeerConnection(rtcConfig, PeerConnectionObserver())
            ?: error("createPeerConnection returned null")

        pc.addTrack(videoTrack, listOf(STREAM_ID))
        pc.addTrack(audioTrack, listOf(STREAM_ID))

        if (remoteControlEnabled) {
            // Open a reliable, ordered DataChannel for inbound remote-control events.
            val dcInit = DataChannel.Init().apply {
                ordered = true
                negotiated = false
            }
            // The outbound channel is unused on the broadcaster side; inbound events arrive
            // via PeerConnectionObserver.onDataChannel().
            pc.createDataChannel("remote-control", dcInit)
            // Dedicated DataChannel for receiving Lovense toy commands from the partner.
            // The broadcaster side only receives on this channel; commands flow partner → device.
            pc.createDataChannel("lovense", dcInit)
        }

        return pc
    }

    // ------------------------------------------------------------------
    //  WebSocket signaling (plain OkHttp WebSocket, matches Camera-Site
    //  /api/tpe/signal/{session_id} relay endpoint)
    // ------------------------------------------------------------------

    /**
     * Send a JSON message envelope through the signaling WebSocket.
     * Safe to call from any thread; no-ops silently when not connected.
     */
    private fun signalingEmit(payload: JSONObject) {
        wsSocket?.send(payload.toString())
    }

    private fun connectSignaling() {
        val request = Request.Builder().url(signalingUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Signaling connected — joining session $sessionId")
                // Announce arrival so the relay can route messages.
                signalingEmit(JSONObject().apply { put("type", "join") })
                // Broadcaster always initiates the offer after joining.
                scope.launch { createAndSendOffer() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "offer" -> {
                        val sdp = SessionDescription(
                            SessionDescription.Type.OFFER,
                            msg.getString("sdp")
                        )
                        peerConnection?.setRemoteDescription(SdpObserver("setRemoteOffer") {
                            createAndSendAnswer()
                        }, sdp)
                    }
                    "answer" -> {
                        val sdp = SessionDescription(
                            SessionDescription.Type.ANSWER,
                            msg.getString("sdp")
                        )
                        peerConnection?.setRemoteDescription(SdpObserver("setRemoteAnswer"), sdp)
                    }
                    "ice-candidate" -> {
                        val candidate = IceCandidate(
                            msg.getString("sdpMid"),
                            msg.getInt("sdpMLineIndex"),
                            msg.getString("candidate")
                        )
                        peerConnection?.addIceCandidate(candidate)
                    }
                    else -> Log.d(TAG, "Signaling message ignored: type=${msg.optString("type")}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Signaling closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Signaling error", t)
                scope.launch { cleanUp() }
            }
        }

        wsSocket = wsClient.newWebSocket(request, listener)
    }

    private fun createAndSendOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createOffer(SdpObserver("createOffer") { sdp ->
            peerConnection?.setLocalDescription(SdpObserver("setLocalOffer"), sdp)
            signalingEmit(JSONObject().apply {
                put("type", "offer")
                put("sdp",  sdp.description)
            })
        }, constraints)
    }

    private fun createAndSendAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(SdpObserver("createAnswer") { sdp ->
            peerConnection?.setLocalDescription(SdpObserver("setLocalAnswer"), sdp)
            signalingEmit(JSONObject().apply {
                put("type", "answer")
                put("sdp",  sdp.description)
            })
        }, constraints)
    }

    // ------------------------------------------------------------------
    //  Adaptive resolution helpers
    // ------------------------------------------------------------------

    private fun targetWidth(context: Context): Int {
        val bounds = windowBounds(context)
        val w = bounds.width()
        val h = bounds.height()
        val scale = MAX_RESOLUTION.toFloat() / minOf(w, h).toFloat()
        return if (scale < 1f) (w * scale).toInt() else w
    }

    private fun targetHeight(context: Context): Int {
        val bounds = windowBounds(context)
        val w = bounds.width()
        val h = bounds.height()
        val scale = MAX_RESOLUTION.toFloat() / minOf(w, h).toFloat()
        return if (scale < 1f) (h * scale).toInt() else h
    }

    private fun windowBounds(context: Context): android.graphics.Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.currentWindowMetrics.bounds
    }

    // ------------------------------------------------------------------
    //  Cleanup
    // ------------------------------------------------------------------

    private fun cleanUp() {
        runCatching { wsSocket?.close(1000, "cleanup") }
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { peerConnection?.close() }
        runCatching { factory?.dispose() }
        runCatching { eglBase?.release() }
        runCatching { LovenseManager.disconnect() }
        wsSocket       = null
        videoCapturer  = null
        videoSource    = null
        videoTrack     = null
        audioSource    = null
        audioTrack     = null
        surfaceHelper  = null
        peerConnection = null
        factory        = null
        eglBase        = null
        appContext     = null
        signalingUrl   = ""
        remoteControlEnabled = false
        RemoteInputDispatcher.remoteControlEnabled = false
        Log.i(TAG, "StreamCoordinator cleaned up")
    }

    // ------------------------------------------------------------------
    //  PeerConnection observer
    // ------------------------------------------------------------------

    private class PeerConnectionObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            StreamCoordinator.signalingEmit(JSONObject().apply {
                put("type",         "ice-candidate")
                put("sdpMid",        candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate",     candidate.sdp)
            })
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ICE state → $state")
            if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                state == PeerConnection.IceConnectionState.FAILED) {
                Log.w(TAG, "ICE disconnected/failed — stopping stream")
                stop()
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState)     { }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>)     { }
        override fun onAddStream(stream: MediaStream)                            { }
        override fun onRemoveStream(stream: MediaStream)                         { }
        override fun onDataChannel(dc: org.webrtc.DataChannel) {
            if (!remoteControlEnabled) return
            Log.i(TAG, "DataChannel opened: ${dc.label()}")
            dc.registerObserver(object : org.webrtc.DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {
                    Log.d(TAG, "DataChannel[${dc.label()}] state → ${dc.state()}")
                }
                override fun onMessage(buffer: org.webrtc.DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val json = String(bytes, Charset.forName("UTF-8"))
                    when (dc.label()) {
                        "lovense" -> LovenseManager.onDataChannelMessage(json)
                        else -> {
                            val ctx = appContext ?: return
                            RemoteInputDispatcher.dispatch(ctx, json)
                        }
                    }
                }
            })
        }
        override fun onRenegotiationNeeded()                                     { }
        override fun onIceConnectionReceivingChange(receiving: Boolean)          { }
    }

    // ------------------------------------------------------------------
    //  SDP observer helper
    // ------------------------------------------------------------------

    private class SdpObserver(
        private val label: String,
        private val onSuccess: ((SessionDescription) -> Unit)? = null
    ) : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            Log.d(TAG, "[$label] onCreateSuccess type=${sdp.type}")
            onSuccess?.invoke(sdp)
        }
        override fun onSetSuccess()                         { Log.d(TAG, "[$label] onSetSuccess") }
        override fun onCreateFailure(error: String)         { Log.e(TAG, "[$label] onCreateFailure: $error") }
        override fun onSetFailure(error: String)            { Log.e(TAG, "[$label] onSetFailure: $error") }
    }
}
