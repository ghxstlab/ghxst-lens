package st.ghx.lens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LensStreamService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    @Volatile
    private var serviceRunning = false

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    private val clientSockets = CopyOnWriteArrayList<Socket>()

    private val activeCamera2Session = AtomicReference<Camera2StreamSession?>(null)

    @Volatile
    private var clientRestartReason: String? = null

    @Volatile
    private var lastControlStatus: String = "idle"

    @Volatile
    private var activeProfile: StreamProfile = DEFAULT_STREAM_PROFILE

    @Volatile
    private var activeCodec: StreamCodec = StreamCodec.H264

    @Volatile
    private var requestedCameraId: String? = null

    @Volatile
    private var requestedZoomRatio: Float = 1.0f

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()

        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        createNotificationChannel()

        Log.d(TAG, "LensStreamService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_STREAM -> {
                Log.d(TAG, "Stop stream requested")

                stopTcpServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()

                return START_NOT_STICKY
            }

            ACTION_START_STREAM, null -> {
                activeProfile = StreamProfile.fromId(
                    intent?.getStringExtra(EXTRA_STREAM_PROFILE)
                )
                activeCodec = StreamCodec.fromId(
                    intent?.getStringExtra(EXTRA_STREAM_CODEC)
                )
                requestedCameraId = intent?.getStringExtra(EXTRA_CAMERA_ID)
                    ?.takeIf { it.isNotBlank() }
                requestedZoomRatio = intent?.getFloatExtra(EXTRA_ZOOM_RATIO, 1.0f)
                    ?.takeIf { it.isFinite() && it > 0f }
                    ?: 1.0f

                Log.d(
                    TAG,
                    "Start stream requested with profile=${activeProfile.profileName} codec=${activeCodec.label} " +
                            "${activeProfile.width}x${activeProfile.height}@${activeProfile.fps} " +
                            "bitrate=${videoBitrate} cameraId=${requestedCameraId ?: "auto"} " +
                            "zoom=${requestedZoomRatio}"
                )

                lifecycleRegistry.currentState = Lifecycle.State.STARTED

                startAsForegroundService()
                startTcpServer()

                return START_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown service action: ${intent.action}")

                lifecycleRegistry.currentState = Lifecycle.State.STARTED

                startAsForegroundService()
                startTcpServer()

                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "LensStreamService destroyed")

        stopTcpServer()

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startAsForegroundService() {
        val notification = buildStreamNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.d(TAG, "Foreground service active")
    }

    private val videoWidth: Int
        get() = activeProfile.width

    private val videoHeight: Int
        get() = activeProfile.height

    private val videoFps: Int
        get() = activeProfile.fps

    private val videoBitrate: Int
        get() = when (activeCodec) {
            StreamCodec.H265 -> when (activeProfile) {
                StreamProfile.BALANCED_720P60_8M -> 7_000_000
                StreamProfile.QUALITY_1080P60_16M -> 20_000_000
                StreamProfile.ULTRA_4K30_45M -> 25_000_000
                StreamProfile.ULTRA_4K60_70M -> 42_000_000
                else -> activeCodec.bitrateFor(activeProfile.bitrate)
            }
            else -> activeCodec.bitrateFor(activeProfile.bitrate)
        }

    private val videoIFrameIntervalSeconds: Int
        get() = activeProfile.iframeIntervalSeconds

    private val streamStatus: String
        get() = activeProfile.status.replace("h264", activeCodec.label)

    private val streamProfileName: String
        get() = activeProfile.profileName

    private val streamCodecLabel: String
        get() = activeCodec.label

    private val streamCodecMimeType: String
        get() = activeCodec.mimeType

    private val streamName: String
        get() = "GHXST Lens Camera2 Direct ${activeCodec.displayName} Stream"

    private fun buildStreamNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("GHXST Lens stream active")
            .setContentText("$streamCodecLabel ${videoWidth}x${videoHeight}@${videoFps} on TCP $STREAM_PORT")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "GHXST Lens Stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when GHXST Lens stream service is running"
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(channel)
    }

    private fun startTcpServer() {
        if (serviceRunning) {
            Log.d(TAG, "TCP server already running")
            return
        }

        serviceRunning = true

        serverThread = thread(
            start = true,
            name = "GHXST-Lens-TCP-Server"
        ) {
            try {
                serverSocket = ServerSocket(STREAM_PORT).apply {
                    reuseAddress = true
                }

                Log.d(TAG, "Camera TCP server listening on port $STREAM_PORT")

                while (serviceRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break

                        clientSocket.tcpNoDelay = true
                        clientSocket.keepAlive = true

                        Log.d(
                            TAG,
                            "Client connected: ${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                        )

                        clientSockets.add(clientSocket)
                        handleClient(clientSocket)
                    } catch (socketException: SocketException) {
                        if (serviceRunning) {
                            Log.e(TAG, "TCP server socket error", socketException)
                        } else {
                            Log.d(TAG, "TCP server socket closed")
                        }
                    } catch (exception: Exception) {
                        Log.e(TAG, "TCP accept failed", exception)
                    }
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to start TCP server on port $STREAM_PORT", exception)
            } finally {
                stopTcpServer()
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        thread(
            start = true,
            name = "GHXST-Lens-Camera-Video-Client-${clientSocket.port}"
        ) {
            try {
                BufferedOutputStream(
                    clientSocket.getOutputStream(),
                    NETWORK_OUTPUT_BUFFER_SIZE
                ).use { output ->
                    val controlThread = startControlReader(clientSocket)

                    sendPacket(
                        output = output,
                        packetType = PACKET_TYPE_HELLO,
                        payload = "$streamName $STREAM_VERSION".toByteArray(
                            StandardCharsets.UTF_8
                        )
                    )

                    try {
                        runCamera2ToVideoSurfaceEncoder(output)
                    } finally {
                        controlThread.interrupt()
                    }
                }
            } catch (socketException: SocketException) {
                Log.w(
                    TAG,
                    "Client socket closed: ${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                )
            } catch (exception: Exception) {
                Log.w(
                    TAG,
                    "Client disconnected: ${clientSocket.inetAddress.hostAddress}:${clientSocket.port}",
                    exception
                )
            } finally {
                clientSockets.remove(clientSocket)

                try {
                    clientSocket.close()
                } catch (_: Exception) {
                }

                Log.d(TAG, "Client cleanup complete")
            }
        }
    }

    private fun startControlReader(clientSocket: Socket): Thread {
        return thread(
            start = true,
            isDaemon = true,
            name = "GHXST-Lens-Control-${clientSocket.port}"
        ) {
            try {
                BufferedReader(
                    InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8)
                ).use { reader ->
                    while (serviceRunning && !clientSocket.isClosed) {
                        val line = reader.readLine() ?: break
                        handleControlLine(line)
                    }
                }
            } catch (socketException: SocketException) {
                if (serviceRunning && !clientSocket.isClosed) {
                    Log.w(TAG, "Control socket closed", socketException)
                }
            } catch (exception: Exception) {
                if (serviceRunning && !clientSocket.isClosed) {
                    Log.w(TAG, "Control reader stopped", exception)
                }
            }
        }
    }

    private fun handleControlLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isBlank()) {
            return
        }

        if (!line.startsWith("GHXC/1")) {
            Log.d(TAG, "Ignoring unknown control line: $line")
            return
        }

        val parts = line.split(Regex("\\s+"))
        val command = parts.getOrNull(1)?.uppercase() ?: ""
        val args = parseControlArgs(parts.drop(2))

        when (command) {
            "SET_ZOOM" -> {
                val requested = args["ratio"]?.toFloatOrNull()
                if (requested == null || !requested.isFinite() || requested <= 0f) {
                    lastControlStatus = "error_invalid_zoom"
                    Log.w(TAG, "Invalid SET_ZOOM command: $line")
                    return
                }

                applyLiveZoom(requested)
            }

            "SET_STREAM" -> {
                applyStreamControlArgs(args, restartClient = args["restart"] != "false")
            }

            "SET_PROFILE" -> {
                applyStreamControlArgs(args, restartClient = true)
            }

            "SET_CODEC" -> {
                applyStreamControlArgs(args, restartClient = true)
            }

            "SET_CAMERA" -> {
                applyStreamControlArgs(args, restartClient = true)
            }

            "PING" -> {
                lastControlStatus = "ok_pong"
                Log.d(TAG, "Control ping received")
            }

            else -> {
                lastControlStatus = "error_unknown_command_$command"
                Log.w(TAG, "Unknown GHXST control command: $line")
            }
        }
    }

    private fun parseControlArgs(parts: List<String>): Map<String, String> {
        val args = mutableMapOf<String, String>()

        parts.forEach { part ->
            val index = part.indexOf('=')
            if (index <= 0 || index >= part.lastIndex) {
                return@forEach
            }

            val key = part.substring(0, index).trim().lowercase()
            val value = part.substring(index + 1).trim()
            if (key.isNotBlank()) {
                args[key] = value
            }
        }

        return args
    }

    private fun sanitizeHeartbeatValue(value: String): String {
        return value
            .replace(";", ",")
            .replace("\n", " ")
            .replace("\r", " ")
    }

    private fun applyStreamControlArgs(args: Map<String, String>, restartClient: Boolean) {
        val oldProfile = activeProfile
        val oldCodec = activeCodec
        val oldCameraId = requestedCameraId ?: "auto"
        val oldZoom = requestedZoomRatio

        args["profile"]?.takeIf { it.isNotBlank() }?.let { profileId ->
            activeProfile = StreamProfile.fromControlId(profileId)
        }

        args["codec"]?.takeIf { it.isNotBlank() }?.let { codecId ->
            activeCodec = StreamCodec.fromId(codecId)
        }

        args["camera_id"]?.takeIf { it.isNotBlank() }?.let { cameraId ->
            requestedCameraId = cameraId.takeUnless { it.equals("auto", ignoreCase = true) }
        }

        args["zoom"]?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let { zoom ->
            requestedZoomRatio = zoom.coerceIn(1.0f, 100.0f)
        }

        val newCameraId = requestedCameraId ?: "auto"
        val changed = oldProfile != activeProfile || oldCodec != activeCodec || oldCameraId != newCameraId || oldZoom != requestedZoomRatio

        lastControlStatus = "stream_settings_${if (changed) "changed" else "unchanged"}_profile_${activeProfile.id}_codec_${activeCodec.id}_camera_${sanitizeHeartbeatValue(newCameraId)}"

        Log.d(
            TAG,
            "Control stream settings profile=${activeProfile.id} codec=${activeCodec.id} camera=$newCameraId restart=$restartClient changed=$changed"
        )

        if (restartClient && changed) {
            requestClientReconnect("stream_settings_changed")
        }
    }

    private fun requestClientReconnect(reason: String) {
        clientRestartReason = reason
        lastControlStatus = "reconnect_requested_$reason"

        clientSockets.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        Log.d(TAG, "Requested OBS/client reconnect: $reason")
    }

    private fun applyLiveZoom(requestedRatio: Float) {
        val safeRequested = requestedRatio.coerceIn(1.0f, 100.0f)
        requestedZoomRatio = safeRequested

        val session = activeCamera2Session.get()
        if (session == null) {
            lastControlStatus = "zoom_pending_requested_${safeRequested}x"
            Log.d(TAG, "Zoom command stored for next session: $safeRequested")
            return
        }

        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(session.cameraId)
            val zoomInfo = buildCamera2ZoomInfo(characteristics, safeRequested)

            lastControlStatus = "zoom_applying_requested_${zoomInfo.requestedRatio}x"

            session.cameraHandler.post {
                try {
                    startRepeatingEncoderRequest(
                        cameraDevice = session.cameraDevice,
                        captureSession = session.captureSession,
                        encoderInputSurface = session.encoderInputSurface,
                        captureMode = session.mode,
                        zoomInfo = zoomInfo,
                        cameraHandler = session.cameraHandler
                    )

                    activeCamera2Session.set(session.copy(zoomInfo = zoomInfo))
                    lastControlStatus = "zoom_ok_requested_${zoomInfo.requestedRatio}x_applied_${zoomInfo.appliedRatio}x"

                    Log.d(
                        TAG,
                        "Live zoom applied requested=${zoomInfo.requestedRatio} applied=${zoomInfo.appliedRatio} method=${zoomInfo.method}"
                    )
                } catch (exception: Exception) {
                    lastControlStatus = "zoom_error_${sanitizeHeartbeatValue(exception.message ?: exception.javaClass.simpleName)}"
                    Log.w(TAG, "Failed to apply live zoom", exception)
                }
            }
        } catch (exception: Exception) {
            lastControlStatus = "zoom_error_${sanitizeHeartbeatValue(exception.message ?: exception.javaClass.simpleName)}"
            Log.w(TAG, "Failed to prepare live zoom", exception)
        }
    }

    private fun sendStreamErrorHeartbeat(
        output: BufferedOutputStream,
        errorCode: String,
        message: String
    ) {
        val safeMessage = message
            .replace(";", ",")
            .replace("\n", " ")
            .replace("\r", " ")

        val heartbeatText =
            "counter=0;" +
                    "status=error;" +
                    "version=$STREAM_VERSION;" +
                    "profile=$streamProfileName;" +
                    "port=$STREAM_PORT;" +
                    "width=$videoWidth;" +
                    "height=$videoHeight;" +
                    "fps=$videoFps;" +
                    "bitrate=$videoBitrate;" +
                    "zoom_requested=$requestedZoomRatio;" +
                    "codec=$streamCodecLabel;" +
                    "input=$STREAM_INPUT;" +
                    "error=$errorCode;" +
                    "message=$safeMessage;" +
                    "mode=$STREAM_MODE"

        try {
            sendPacket(
                output = output,
                packetType = PACKET_TYPE_HEARTBEAT,
                payload = heartbeatText.toByteArray(StandardCharsets.UTF_8)
            )
            output.flush()
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to send stream error heartbeat", exception)
        }
    }

    private fun runCamera2ToVideoSurfaceEncoder(output: BufferedOutputStream) {
        var encoder: MediaCodec? = null
        var encoderInputSurface: Surface? = null
        var camera2Session: Camera2StreamSession? = null
        val frameIndexCounter = AtomicLong(0L)

        try {
            val encoderCapabilities = buildVideoEncoderCapabilitySummary(
                width = videoWidth,
                height = videoHeight,
                fps = videoFps,
                bitrate = videoBitrate
            )

            sendStartupHeartbeat(
                output = output,
                stage = "starting_hq_probe",
                message = "Starting ${videoWidth}x${videoHeight}@${videoFps}. $encoderCapabilities"
            )

            encoder = createConfiguredVideoEncoder()
            encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            Log.d(TAG, "Camera2 direct Video encoder started with input surface encoder=${encoder.name}")

            sendStartupHeartbeat(
                output = output,
                stage = "encoder_ready",
                message = "Encoder ${encoder.name} created input surface for ${videoWidth}x${videoHeight}@${videoFps}"
            )

            camera2Session = startCamera2EncoderSession(encoderInputSurface)
            activeCamera2Session.set(camera2Session)

            sendStartupHeartbeat(
                output = output,
                stage = "camera_ready",
                message = "Camera ${camera2Session.cameraId} active mode=${camera2Session.mode.label} fps=${camera2Session.mode.fpsRange}"
            )

            val bufferInfo = MediaCodec.BufferInfo()

            var heartbeatCounter = 0
            var lastHeartbeatMs = 0L
            var lastFrameLogMs = System.currentTimeMillis()
            var encodedFrameCounter = 0
            var lastEncodedFrameCounter = 0
            var recentEncodedFpsEstimate = 0

            while (serviceRunning) {
                clientRestartReason?.let { reason ->
                    Log.d(TAG, "Restarting active client stream due to control request: $reason")
                    clientRestartReason = null
                    return
                }

                val drainedFrames = drainEncoderOutput(
                    encoder = encoder,
                    bufferInfo = bufferInfo,
                    output = output,
                    frameIndexCounter = frameIndexCounter
                )

                encodedFrameCounter += drainedFrames

                val nowMs = System.currentTimeMillis()

                if (nowMs - lastHeartbeatMs >= 1000L) {
                    val activeSessionSnapshot = activeCamera2Session.get() ?: camera2Session
                    val camera2ModeLabel = activeSessionSnapshot?.mode?.label ?: "unknown"
                    val camera2FpsRange = activeSessionSnapshot?.mode?.fpsRange?.toString() ?: "unknown"
                    val camera2Id = activeSessionSnapshot?.cameraId ?: "unknown"
                    val camera2Capabilities = activeSessionSnapshot?.mode?.capabilitiesSummary ?: "unknown"
                    val zoomRequested = activeSessionSnapshot?.zoomInfo?.requestedRatio ?: requestedZoomRatio
                    val zoomApplied = activeSessionSnapshot?.zoomInfo?.appliedRatio ?: requestedZoomRatio
                    val zoomMethod = activeSessionSnapshot?.zoomInfo?.method ?: "unknown"
                    val zoomCapabilities = activeSessionSnapshot?.zoomInfo?.capabilitiesSummary ?: "unknown"
                    val safeControlStatus = sanitizeHeartbeatValue(lastControlStatus)

                    val heartbeatText =
                        "counter=$heartbeatCounter;" +
                                "status=$streamStatus;" +
                                "version=$STREAM_VERSION;" +
                                "profile=$streamProfileName;" +
                                "port=$STREAM_PORT;" +
                                "width=$videoWidth;" +
                                "height=$videoHeight;" +
                                "fps=$videoFps;" +
                                "bitrate=$videoBitrate;" +
                                "iframe_interval=$videoIFrameIntervalSeconds;" +
                                "codec=$streamCodecLabel;" +
                                "input=$STREAM_INPUT;" +
                                "camera2_id=$camera2Id;" +
                                "camera_requested=${requestedCameraId ?: "auto"};" +
                                "camera2_mode=$camera2ModeLabel;" +
                                "camera2_fps_range=$camera2FpsRange;" +
                                "camera2_capabilities=$camera2Capabilities;" +
                                "zoom_requested=$zoomRequested;" +
                                "zoom_applied=$zoomApplied;" +
                                "zoom_method=$zoomMethod;" +
                                "zoom_capabilities=$zoomCapabilities;" +
                                "control_status=$safeControlStatus;" +
                                "encoded_fps_estimate=$recentEncodedFpsEstimate;" +
                                "encoder_latency=low_delay_no_bframes;" +
                                "mode=$STREAM_MODE"

                    sendPacket(
                        output = output,
                        packetType = PACKET_TYPE_HEARTBEAT,
                        payload = heartbeatText.toByteArray(StandardCharsets.UTF_8)
                    )

                    heartbeatCounter++
                    lastHeartbeatMs = nowMs
                }

                if (nowMs - lastFrameLogMs >= 1000L) {
                    val framesThisSecond = encodedFrameCounter - lastEncodedFrameCounter
                    recentEncodedFpsEstimate = framesThisSecond

                    Log.d(
                        TAG,
                        "Camera2 direct H.264 encoded frames total=$encodedFrameCounter fps_estimate=$framesThisSecond"
                    )

                    lastEncodedFrameCounter = encodedFrameCounter
                    lastFrameLogMs = nowMs
                }

                Thread.sleep(2L)
            }
        } catch (throwable: Throwable) {
            val rootMessage = throwable.cause?.message ?: throwable.message ?: throwable.javaClass.simpleName
            val cameraCaps = buildSelectedCameraCapabilitySummary(
                width = videoWidth,
                height = videoHeight,
                fps = videoFps
            )
            val encoderCaps = buildVideoEncoderCapabilitySummary(
                width = videoWidth,
                height = videoHeight,
                fps = videoFps,
                bitrate = videoBitrate
            )

            Log.e(TAG, "Camera2/encoder stream failed for ${videoWidth}x${videoHeight}@${videoFps}", throwable)
            sendStreamErrorHeartbeat(
                output = output,
                errorCode = "camera2_encoder_start_failed",
                message = "${videoWidth}x${videoHeight}@${videoFps} failed: $rootMessage | camera=$cameraCaps | encoder=$encoderCaps"
            )
            Thread.sleep(500L)
        } finally {
            activeCamera2Session.set(null)
            stopCamera2Session(camera2Session)

            try {
                if (encoder != null) {
                    drainEncoderOutput(
                        encoder = encoder,
                        bufferInfo = MediaCodec.BufferInfo(),
                        output = output,
                        frameIndexCounter = frameIndexCounter
                    )
                }
            } catch (_: Exception) {
            }

            try {
                encoderInputSurface?.release()
            } catch (_: Exception) {
            }

            try {
                encoder?.stop()
            } catch (_: Exception) {
            }

            try {
                encoder?.release()
            } catch (_: Exception) {
            }

            Log.d(TAG, "Camera2 direct H.264 surface encoder stopped")
        }
    }

    private fun sendStartupHeartbeat(
        output: BufferedOutputStream,
        stage: String,
        message: String
    ) {
        val safeMessage = message
            .replace(";", ",")
            .replace("\n", " ")
            .replace("\r", " ")

        val heartbeatText =
            "counter=0;" +
                    "status=starting;" +
                    "version=$STREAM_VERSION;" +
                    "profile=$streamProfileName;" +
                    "port=$STREAM_PORT;" +
                    "width=$videoWidth;" +
                    "height=$videoHeight;" +
                    "fps=$videoFps;" +
                    "bitrate=$videoBitrate;" +
                    "codec=$streamCodecLabel;" +
                    "input=$STREAM_INPUT;" +
                    "camera_requested=${requestedCameraId ?: "auto"};" +
                    "stage=$stage;" +
                    "message=$safeMessage;" +
                    "mode=$STREAM_MODE"

        try {
            sendPacket(
                output = output,
                packetType = PACKET_TYPE_HEARTBEAT,
                payload = heartbeatText.toByteArray(StandardCharsets.UTF_8)
            )
            output.flush()
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to send startup heartbeat", exception)
        }
    }

    private fun buildVideoEncoderCapabilitySummary(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): String {
        return try {
            val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { codecInfo -> codecInfo.isEncoder }
                .filter { codecInfo -> codecInfo.supportedTypes.any { it.equals(streamCodecMimeType, ignoreCase = true) } }

            if (codecInfos.isEmpty()) {
                return "${activeCodec.label}_encoders=none"
            }

            codecInfos.joinToString(separator = "|") { codecInfo ->
                try {
                    val caps = codecInfo.getCapabilitiesForType(streamCodecMimeType)
                    val videoCaps = caps.videoCapabilities

                    if (videoCaps == null) {
                        "${codecInfo.name}:video_caps=null"
                    } else {
                        val sizeRateSupported = videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())
                        val sizeSupported = videoCaps.isSizeSupported(width, height)
                        val widthAlignment = videoCaps.widthAlignment
                        val heightAlignment = videoCaps.heightAlignment
                        val bitrateSupported = videoCaps.bitrateRange.contains(bitrate)

                        "${codecInfo.name}:size=$sizeSupported,rate=$sizeRateSupported," +
                                "align=${widthAlignment}x${heightAlignment},bitrate=$bitrateSupported"
                    }
                } catch (exception: Exception) {
                    "${codecInfo.name}:check_failed=${exception.javaClass.simpleName}"
                }
            }
        } catch (exception: Exception) {
            "${activeCodec.label}_encoder_check_failed=${exception.javaClass.simpleName}"
        }
    }

    private fun createConfiguredVideoEncoder(): MediaCodec {
        val lowLatencyFormat = buildVideoEncoderFormat(includeLowLatencyHints = true)

        try {
            val encoder = MediaCodec.createEncoderByType(streamCodecMimeType)

            Log.d(TAG, "Configuring Video encoder low-latency format: $lowLatencyFormat")

            encoder.configure(
                lowLatencyFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            Log.d(TAG, "Video encoder configured with low-latency hints")
            return encoder
        } catch (exception: Exception) {
            Log.w(TAG, "Low-latency Video encoder configure failed. Retrying safe baseline.", exception)
        }

        val baselineFormat = buildVideoEncoderFormat(includeLowLatencyHints = false)
        val baselineEncoder = MediaCodec.createEncoderByType(streamCodecMimeType)

        Log.d(TAG, "Configuring Video encoder baseline format: $baselineFormat")

        baselineEncoder.configure(
            baselineFormat,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        return baselineEncoder
    }

    private fun buildVideoEncoderFormat(includeLowLatencyHints: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(
            streamCodecMimeType,
            videoWidth,
            videoHeight
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )

            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, videoFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, videoIFrameIntervalSeconds)

            // CBR is usually safe and helps avoid bitrate spikes/buffering.
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )

            if (includeLowLatencyHints) {
                // Keep this block intentionally small. LT11 failed because too many aggressive
                // hints were added at once. These two are the safest latency-related settings:
                // 1) low-latency encoder mode where supported
                // 2) no B-frames so the decoder does not need frame reordering
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
        }
    }

    private data class Camera2StreamSession(
        val cameraThread: HandlerThread,
        val cameraHandler: Handler,
        val cameraDevice: CameraDevice,
        val captureSession: CameraCaptureSession,
        val encoderInputSurface: Surface,
        val mode: Camera2CaptureMode,
        val cameraId: String,
        val zoomInfo: Camera2ZoomInfo
    )

    private data class Camera2CaptureMode(
        val useConstrainedHighSpeed: Boolean,
        val requestedSize: Size,
        val fpsRange: Range<Int>,
        val label: String,
        val capabilitiesSummary: String
    )

    private data class Camera2ZoomInfo(
        val requestedRatio: Float,
        val appliedRatio: Float,
        val method: String,
        val capabilitiesSummary: String,
        val cropRegion: Rect? = null
    )

    @SuppressLint("MissingPermission")
    private fun startCamera2EncoderSession(encoderInputSurface: Surface): Camera2StreamSession {
        val cameraThread = HandlerThread("GHXST-Lens-Camera2-Thread").apply {
            start()
        }
        val cameraHandler = Handler(cameraThread.looper)

        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = chooseCameraId(cameraManager, requestedCameraId)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val captureMode = chooseCamera2CaptureMode(characteristics, videoWidth, videoHeight, videoFps)
            val zoomInfo = buildCamera2ZoomInfo(characteristics, requestedZoomRatio)

            Log.d(
                TAG,
                "Camera2 selected cameraId=$cameraId profile=$streamProfileName " +
                        "${videoWidth}x${videoHeight}@$videoFps mode=${captureMode.label} " +
                        "fpsRange=${captureMode.fpsRange} zoom=${zoomInfo.appliedRatio} method=${zoomInfo.method}"
            )

            val cameraDevice = openCameraDevice(
                cameraManager = cameraManager,
                cameraId = cameraId,
                cameraHandler = cameraHandler
            )

            val captureSession = if (captureMode.useConstrainedHighSpeed) {
                createConstrainedHighSpeedEncoderCaptureSession(
                    cameraDevice = cameraDevice,
                    encoderInputSurface = encoderInputSurface,
                    cameraHandler = cameraHandler
                )
            } else {
                createEncoderCaptureSession(
                    cameraDevice = cameraDevice,
                    encoderInputSurface = encoderInputSurface,
                    cameraHandler = cameraHandler
                )
            }

            startRepeatingEncoderRequest(
                cameraDevice = cameraDevice,
                captureSession = captureSession,
                encoderInputSurface = encoderInputSurface,
                captureMode = captureMode,
                zoomInfo = zoomInfo,
                cameraHandler = cameraHandler
            )

            Log.d(TAG, "Camera2 direct capture session is streaming into encoder surface")

            return Camera2StreamSession(
                cameraThread = cameraThread,
                cameraHandler = cameraHandler,
                cameraDevice = cameraDevice,
                captureSession = captureSession,
                encoderInputSurface = encoderInputSurface,
                mode = captureMode,
                cameraId = cameraId,
                zoomInfo = zoomInfo
            )
        } catch (throwable: Throwable) {
            try {
                cameraThread.quitSafely()
            } catch (_: Exception) {
            }

            throw IllegalStateException("Camera2 direct encoder session failed", throwable)
        }
    }

    private fun chooseCameraId(cameraManager: CameraManager, requestedId: String?): String {
        val selector = requestedId?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it != "auto" }
        val allCameraIds = cameraManager.cameraIdList.toList()

        if (selector != null && selector in allCameraIds) {
            return selector
        }

        fun lensFacing(cameraId: String): Int? {
            return try {
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING)
            } catch (_: Exception) {
                null
            }
        }

        fun focalLength(cameraId: String): Float {
            return try {
                val lengths = cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                lengths?.minOrNull() ?: Float.MAX_VALUE
            } catch (_: Exception) {
                Float.MAX_VALUE
            }
        }

        fun supportsCurrentProfile(cameraId: String): Boolean {
            return try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                cameraSupportsProfile(
                    characteristics = characteristics,
                    width = videoWidth,
                    height = videoHeight,
                    fps = videoFps
                )
            } catch (_: Exception) {
                false
            }
        }

        val backCameras = allCameraIds
            .filter { lensFacing(it) == CameraCharacteristics.LENS_FACING_BACK }
            .sortedBy { focalLength(it) }

        val frontCameras = allCameraIds
            .filter { lensFacing(it) == CameraCharacteristics.LENS_FACING_FRONT }
            .sortedBy { focalLength(it) }

        fun supportedOrFallback(candidates: List<String>): List<String> {
            val supported = candidates.filter { supportsCurrentProfile(it) }
            return supported.ifEmpty { candidates }
        }

        val selected = when (selector) {
            "front" -> supportedOrFallback(frontCameras).firstOrNull()
            "back_ultra_wide", "ultrawide", "ultra_wide" -> supportedOrFallback(backCameras).firstOrNull()
            "back_telephoto", "telephoto" -> supportedOrFallback(backCameras).lastOrNull()
            "back_main", "back_wide", "main", "wide" -> {
                val candidates = supportedOrFallback(backCameras)
                when {
                    candidates.isEmpty() -> null
                    candidates.size >= 3 -> candidates[candidates.size / 2]
                    else -> candidates.last()
                }
            }
            else -> supportedOrFallback(backCameras).firstOrNull()
        }

        if (selected != null) {
            Log.d(
                TAG,
                "Camera selector=${selector ?: "auto"} selected cameraId=$selected " +
                        "profile=$streamProfileName ${videoWidth}x${videoHeight}@$videoFps " +
                        "supports=${supportsCurrentProfile(selected)}"
            )
            return selected
        }

        return allCameraIds.firstOrNull()
            ?: throw IllegalStateException("No Camera2 devices found")
    }

    private fun cameraSupportsProfile(
        characteristics: CameraCharacteristics,
        width: Int,
        height: Int,
        fps: Int
    ): Boolean {
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return false

        val requestedSize = Size(width, height)

        if (fps >= 60) {
            val highSpeedSizes = try {
                streamMap.highSpeedVideoSizes.toList()
            } catch (_: Exception) {
                emptyList()
            }

            if (requestedSize !in highSpeedSizes) {
                return false
            }

            val ranges = try {
                streamMap.getHighSpeedVideoFpsRangesFor(requestedSize).toList()
            } catch (_: Exception) {
                emptyList()
            }

            return ranges.any { it.upper >= fps }
        }

        val outputSizes = try {
            streamMap.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty() +
                    streamMap.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        return requestedSize in outputSizes
    }

    private fun chooseCamera2CaptureMode(
        characteristics: CameraCharacteristics,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int
    ): Camera2CaptureMode {
        val requestedSize = Size(requestedWidth, requestedHeight)
        val capabilitiesSummary = buildCamera2CapabilitySummary(characteristics, requestedSize)

        if (requestedFps >= 60) {
            val highSpeedRange = chooseHighSpeedFpsRange(
                characteristics = characteristics,
                requestedSize = requestedSize,
                requestedFps = requestedFps
            )

            // Constrained high-speed sessions are only reliable for sizes the camera explicitly
            // advertises. For HQ modes such as 1440p/4K60, many phones can still run a normal
            // RECORD repeating request at a 30-60 AE range even when constrained high-speed is absent.
            // So use high-speed only when advertised; otherwise continue through standard mode.
            if (highSpeedRange != null) {
                return Camera2CaptureMode(
                    useConstrainedHighSpeed = true,
                    requestedSize = requestedSize,
                    fpsRange = highSpeedRange,
                    label = "constrained_high_speed",
                    capabilitiesSummary = capabilitiesSummary
                )
            }

            val standardRange = chooseStandardFpsRange(characteristics, requestedFps)
            if (standardRange.upper >= requestedFps) {
                Log.w(
                    TAG,
                    "Requested ${requestedWidth}x${requestedHeight}@$requestedFps is not listed as constrained high-speed. " +
                            "Trying standard repeating with fpsRange=$standardRange. capabilities=$capabilitiesSummary"
                )
                return Camera2CaptureMode(
                    useConstrainedHighSpeed = false,
                    requestedSize = requestedSize,
                    fpsRange = standardRange,
                    label = "standard_repeating_60fps_fallback",
                    capabilitiesSummary = capabilitiesSummary
                )
            }

            throw IllegalStateException(
                "Requested ${requestedWidth}x${requestedHeight}@$requestedFps requires constrained high-speed Camera2, " +
                        "but the selected lens does not advertise it for ${requestedSize.width}x${requestedSize.height}. " +
                        "Use Auto/Back main or test a 30 FPS profile. " +
                        "capabilities=$capabilitiesSummary"
            )
        }

        return Camera2CaptureMode(
            useConstrainedHighSpeed = false,
            requestedSize = requestedSize,
            fpsRange = chooseStandardFpsRange(characteristics, requestedFps),
            label = "standard_repeating",
            capabilitiesSummary = capabilitiesSummary
        )
    }

    private fun buildCamera2CapabilitySummary(
        characteristics: CameraCharacteristics,
        requestedSize: Size
    ): String {
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return "stream_map=missing"

        val highSpeedSizes = try {
            streamMap.highSpeedVideoSizes.toList()
        } catch (exception: Exception) {
            emptyList()
        }

        val highSpeedRangesForSize = try {
            if (requestedSize in highSpeedSizes) {
                streamMap.getHighSpeedVideoFpsRangesFor(requestedSize).toList()
            } else {
                emptyList()
            }
        } catch (exception: Exception) {
            emptyList()
        }

        val aeRanges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        )?.toList().orEmpty()

        fun List<Range<Int>>.compactRanges(): String =
            if (isEmpty()) {
                "none"
            } else {
                joinToString(separator = "/") { range -> "${range.lower}-${range.upper}" }
            }

        fun List<Size>.compactSizes(): String =
            if (isEmpty()) {
                "none"
            } else {
                joinToString(separator = "/") { size -> "${size.width}x${size.height}" }
            }

        return "hs_sizes=${highSpeedSizes.compactSizes()}," +
                "hs_ranges_for_${requestedSize.width}x${requestedSize.height}=${highSpeedRangesForSize.compactRanges()}," +
                "ae_ranges=${aeRanges.compactRanges()}"
    }

    private fun buildSelectedCameraCapabilitySummary(
        width: Int,
        height: Int,
        fps: Int
    ): String {
        return try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = chooseCameraId(cameraManager, requestedCameraId)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return "camera_id=$cameraId,stream_map=missing"

            val requestedSize = Size(width, height)

            val mediaCodecSizes = try {
                streamMap.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            val mediaRecorderSizes = try {
                streamMap.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            val surfaceTextureSizes = try {
                streamMap.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            val highSpeedSizes = try {
                streamMap.highSpeedVideoSizes.toList()
            } catch (_: Exception) {
                emptyList()
            }

            val highSpeedRanges = try {
                if (requestedSize in highSpeedSizes) {
                    streamMap.getHighSpeedVideoFpsRangesFor(requestedSize).toList()
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

            fun List<Size>.compactSizes(limit: Int = 16): String =
                if (isEmpty()) {
                    "none"
                } else {
                    sortedWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.width })
                        .take(limit)
                        .joinToString(separator = "/") { size -> "${size.width}x${size.height}" }
                }

            fun List<Range<Int>>.compactRanges(): String =
                if (isEmpty()) {
                    "none"
                } else {
                    joinToString(separator = "/") { range -> "${range.lower}-${range.upper}" }
                }

            "camera_id=$cameraId," +
                    "requested=${width}x${height}@$fps," +
                    "mediacodec_has_requested=${requestedSize in mediaCodecSizes}," +
                    "mediarecorder_has_requested=${requestedSize in mediaRecorderSizes}," +
                    "surfacetexture_has_requested=${requestedSize in surfaceTextureSizes}," +
                    "mediacodec_sizes=${mediaCodecSizes.compactSizes()}," +
                    "mediarecorder_sizes=${mediaRecorderSizes.compactSizes()}," +
                    "surfacetexture_sizes=${surfaceTextureSizes.compactSizes()}," +
                    "hs_has_requested=${requestedSize in highSpeedSizes}," +
                    "hs_ranges=${highSpeedRanges.compactRanges()}"
        } catch (exception: Exception) {
            "selected_camera_check_failed=${exception.javaClass.simpleName}:${exception.message}"
        }
    }

    private fun chooseHighSpeedFpsRange(
        characteristics: CameraCharacteristics,
        requestedSize: Size,
        requestedFps: Int
    ): Range<Int>? {
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return null

        val highSpeedSizes = try {
            streamMap.highSpeedVideoSizes.toList()
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to read high-speed video sizes", exception)
            emptyList()
        }

        if (requestedSize !in highSpeedSizes) {
            Log.w(TAG, "High-speed sizes advertised: $highSpeedSizes. Requested=$requestedSize")
            return null
        }

        val ranges = try {
            streamMap.getHighSpeedVideoFpsRangesFor(requestedSize).toList()
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to read high-speed FPS ranges for $requestedSize", exception)
            emptyList()
        }

        if (ranges.isEmpty()) {
            Log.w(TAG, "No high-speed FPS ranges advertised for $requestedSize")
            return null
        }

        val exactFixed = ranges.firstOrNull { range ->
            range.lower == requestedFps && range.upper == requestedFps
        }

        if (exactFixed != null) {
            return exactFixed
        }

        val exactUpper = ranges
            .filter { range -> range.upper == requestedFps }
            .minByOrNull { range -> range.upper - range.lower }

        if (exactUpper != null) {
            Log.w(TAG, "Using high-speed FPS range $exactUpper for requested FPS $requestedFps")
            return exactUpper
        }

        val containing = ranges
            .filter { range -> range.lower <= requestedFps && range.upper >= requestedFps }
            .minWithOrNull(
                compareBy<Range<Int>> { it.upper - it.lower }
                    .thenBy { kotlin.math.abs(it.upper - requestedFps) }
            )

        if (containing != null) {
            Log.w(TAG, "Using containing high-speed FPS range $containing for requested FPS $requestedFps")
            return containing
        }

        Log.w(TAG, "High-speed FPS ranges for $requestedSize: $ranges. Requested FPS=$requestedFps")
        return null
    }

    private fun chooseStandardFpsRange(
        characteristics: CameraCharacteristics,
        requestedFps: Int
    ): Range<Int> {
        val ranges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        )?.toList().orEmpty()

        if (ranges.isEmpty()) {
            Log.w(TAG, "Camera2 reported no AE FPS ranges. Falling back to $requestedFps-$requestedFps")
            return Range(requestedFps, requestedFps)
        }

        val exact = ranges.firstOrNull { range ->
            range.lower == requestedFps && range.upper == requestedFps
        }

        if (exact != null) {
            return exact
        }

        val fixedUpper = ranges
            .filter { range -> range.upper == requestedFps }
            .minByOrNull { range -> range.upper - range.lower }

        if (fixedUpper != null) {
            Log.w(TAG, "Exact standard FPS range not found for $requestedFps. Using upper-match range $fixedUpper")
            return fixedUpper
        }

        val containing = ranges
            .filter { range -> range.lower <= requestedFps && range.upper >= requestedFps }
            .minWithOrNull(
                compareBy<Range<Int>> { it.upper - it.lower }
                    .thenBy { kotlin.math.abs(it.upper - requestedFps) }
            )

        if (containing != null) {
            Log.w(TAG, "Exact standard FPS range not found for $requestedFps. Using containing range $containing")
            return containing
        }

        val closest = ranges.minByOrNull { range ->
            kotlin.math.abs(range.upper - requestedFps) + kotlin.math.abs(range.lower - requestedFps)
        }

        Log.w(TAG, "No standard FPS range contains $requestedFps. Using closest range $closest from $ranges")

        return closest ?: Range(requestedFps, requestedFps)
    }

    @SuppressLint("MissingPermission")
    private fun openCameraDevice(
        cameraManager: CameraManager,
        cameraId: String,
        cameraHandler: Handler
    ): CameraDevice {
        val latch = CountDownLatch(1)
        val cameraReference = AtomicReference<CameraDevice?>()
        val errorReference = AtomicReference<Throwable?>()

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraReference.set(camera)
                    latch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try {
                        camera.close()
                    } catch (_: Exception) {
                    }
                    errorReference.set(IllegalStateException("Camera2 device disconnected before streaming started"))
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try {
                        camera.close()
                    } catch (_: Exception) {
                    }
                    errorReference.set(IllegalStateException("Camera2 device error=$error"))
                    latch.countDown()
                }
            },
            cameraHandler
        )

        val completed = latch.await(CAMERA2_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!completed) {
            throw IllegalStateException("Timed out opening Camera2 device")
        }

        errorReference.get()?.let { throwable ->
            throw IllegalStateException("Camera2 open failed", throwable)
        }

        return cameraReference.get()
            ?: throw IllegalStateException("Camera2 opened without returning a device")
    }

    private fun createEncoderCaptureSession(
        cameraDevice: CameraDevice,
        encoderInputSurface: Surface,
        cameraHandler: Handler
    ): CameraCaptureSession {
        val latch = CountDownLatch(1)
        val sessionReference = AtomicReference<CameraCaptureSession?>()
        val errorReference = AtomicReference<Throwable?>()

        cameraDevice.createCaptureSession(
            listOf(encoderInputSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    sessionReference.set(session)
                    latch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    errorReference.set(IllegalStateException("Camera2 capture session configure failed"))
                    latch.countDown()
                }
            },
            cameraHandler
        )

        val completed = latch.await(CAMERA2_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!completed) {
            throw IllegalStateException("Timed out creating Camera2 capture session")
        }

        errorReference.get()?.let { throwable ->
            throw IllegalStateException("Camera2 session creation failed", throwable)
        }

        return sessionReference.get()
            ?: throw IllegalStateException("Camera2 session configured without returning a session")
    }

    private fun createConstrainedHighSpeedEncoderCaptureSession(
        cameraDevice: CameraDevice,
        encoderInputSurface: Surface,
        cameraHandler: Handler
    ): CameraCaptureSession {
        val latch = CountDownLatch(1)
        val sessionReference = AtomicReference<CameraCaptureSession?>()
        val errorReference = AtomicReference<Throwable?>()

        cameraDevice.createConstrainedHighSpeedCaptureSession(
            listOf(encoderInputSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    sessionReference.set(session)
                    latch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    errorReference.set(
                        IllegalStateException("Camera2 constrained high-speed capture session configure failed")
                    )
                    latch.countDown()
                }
            },
            cameraHandler
        )

        val completed = latch.await(CAMERA2_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!completed) {
            throw IllegalStateException("Timed out creating Camera2 constrained high-speed capture session")
        }

        errorReference.get()?.let { throwable ->
            throw IllegalStateException("Camera2 constrained high-speed session creation failed", throwable)
        }

        return sessionReference.get()
            ?: throw IllegalStateException("Camera2 constrained high-speed session configured without returning a session")
    }

    private fun buildCamera2ZoomInfo(
        characteristics: CameraCharacteristics,
        requestedRatio: Float
    ): Camera2ZoomInfo {
        val safeRequested = requestedRatio.coerceIn(0.1f, 20.0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null) {
                val applied = safeRequested.coerceIn(zoomRange.lower, zoomRange.upper)
                val method = if (applied == safeRequested) {
                    "control_zoom_ratio"
                } else {
                    "control_zoom_ratio_clamped"
                }

                return Camera2ZoomInfo(
                    requestedRatio = safeRequested,
                    appliedRatio = applied,
                    method = method,
                    capabilitiesSummary = "ratio_range=${zoomRange.lower}-${zoomRange.upper}"
                )
            }
        }

        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f

        if (sensorRect != null && safeRequested >= 1.0f && maxDigitalZoom > 1.0f) {
            val applied = safeRequested.coerceIn(1.0f, maxDigitalZoom)
            val cropWidth = (sensorRect.width() / applied).toInt().coerceAtLeast(1)
            val cropHeight = (sensorRect.height() / applied).toInt().coerceAtLeast(1)
            val left = sensorRect.left + (sensorRect.width() - cropWidth) / 2
            val top = sensorRect.top + (sensorRect.height() - cropHeight) / 2
            val cropRegion = Rect(left, top, left + cropWidth, top + cropHeight)

            return Camera2ZoomInfo(
                requestedRatio = safeRequested,
                appliedRatio = applied,
                method = "scaler_crop_region",
                capabilitiesSummary = "max_digital_zoom=$maxDigitalZoom",
                cropRegion = cropRegion
            )
        }

        return Camera2ZoomInfo(
            requestedRatio = safeRequested,
            appliedRatio = 1.0f,
            method = "none",
            capabilitiesSummary = "ratio_range=unavailable,max_digital_zoom=$maxDigitalZoom"
        )
    }

    private fun applyZoomToCaptureRequest(
        builder: CaptureRequest.Builder,
        zoomInfo: Camera2ZoomInfo
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (zoomInfo.method == "control_zoom_ratio" || zoomInfo.method == "control_zoom_ratio_clamped")
        ) {
            setCaptureOptionSafely(
                builder = builder,
                key = CaptureRequest.CONTROL_ZOOM_RATIO,
                value = zoomInfo.appliedRatio,
                label = "CONTROL_ZOOM_RATIO=${zoomInfo.appliedRatio}"
            )
            return
        }

        zoomInfo.cropRegion?.let { cropRegion ->
            setCaptureOptionSafely(
                builder = builder,
                key = CaptureRequest.SCALER_CROP_REGION,
                value = cropRegion,
                label = "SCALER_CROP_REGION=$cropRegion"
            )
        }
    }

    private fun startRepeatingEncoderRequest(
        cameraDevice: CameraDevice,
        captureSession: CameraCaptureSession,
        encoderInputSurface: Surface,
        captureMode: Camera2CaptureMode,
        zoomInfo: Camera2ZoomInfo,
        cameraHandler: Handler
    ) {
        val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(encoderInputSurface)

            setCaptureOptionSafely(
                builder = this,
                key = CaptureRequest.CONTROL_MODE,
                value = CaptureRequest.CONTROL_MODE_AUTO,
                label = "CONTROL_MODE_AUTO"
            )

            setCaptureOptionSafely(
                builder = this,
                key = CaptureRequest.CONTROL_AE_MODE,
                value = CaptureRequest.CONTROL_AE_MODE_ON,
                label = "CONTROL_AE_MODE_ON"
            )

            setCaptureOptionSafely(
                builder = this,
                key = CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                value = captureMode.fpsRange,
                label = "CONTROL_AE_TARGET_FPS_RANGE=${captureMode.fpsRange}"
            )

            setCaptureOptionSafely(
                builder = this,
                key = CaptureRequest.CONTROL_AF_MODE,
                value = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                label = "CONTROL_AF_MODE_CONTINUOUS_VIDEO"
            )

            setCaptureOptionSafely(
                builder = this,
                key = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                value = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                label = "CONTROL_VIDEO_STABILIZATION_MODE_OFF"
            )

            setCaptureOptionSafely(
                builder = this,
                key = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                value = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                label = "LENS_OPTICAL_STABILIZATION_MODE_OFF"
            )

            applyZoomToCaptureRequest(this, zoomInfo)
        }

        if (captureMode.useConstrainedHighSpeed) {
            val highSpeedSession = captureSession as? CameraConstrainedHighSpeedCaptureSession
                ?: throw IllegalStateException(
                    "Expected CameraConstrainedHighSpeedCaptureSession but got ${captureSession.javaClass.name}"
                )

            val highSpeedRequests = highSpeedSession.createHighSpeedRequestList(
                requestBuilder.build()
            )

            highSpeedSession.setRepeatingBurst(
                highSpeedRequests,
                null,
                cameraHandler
            )

            Log.d(
                TAG,
                "Started Camera2 constrained high-speed repeating burst " +
                        "requests=${highSpeedRequests.size} fpsRange=${captureMode.fpsRange}"
            )
        } else {
            captureSession.setRepeatingRequest(
                requestBuilder.build(),
                null,
                cameraHandler
            )

            Log.d(TAG, "Started Camera2 standard repeating request fpsRange=${captureMode.fpsRange}")
        }
    }

    private fun <T> setCaptureOptionSafely(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>,
        value: T,
        label: String
    ) {
        try {
            builder.set(key, value)
        } catch (exception: Exception) {
            Log.w(TAG, "Camera2 option skipped: $label", exception)
        }
    }

    private fun stopCamera2Session(camera2Session: Camera2StreamSession?) {
        if (camera2Session == null) {
            return
        }

        try {
            camera2Session.captureSession.stopRepeating()
        } catch (_: Exception) {
        }

        try {
            camera2Session.captureSession.abortCaptures()
        } catch (_: Exception) {
        }

        try {
            camera2Session.captureSession.close()
        } catch (_: Exception) {
        }

        try {
            camera2Session.cameraDevice.close()
        } catch (_: Exception) {
        }

        try {
            camera2Session.cameraThread.quitSafely()
            camera2Session.cameraThread.join(1000L)
        } catch (_: Exception) {
        }

        Log.d(TAG, "Camera2 direct session stopped")
    }

    private fun drainEncoderOutput(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        output: BufferedOutputStream,
        frameIndexCounter: AtomicLong
    ): Int {
        var frameCount = 0

        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return frameCount
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = encoder.outputFormat
                    Log.d(TAG, "Encoder output format changed: $outputFormat")

                    sendCodecSpecificDataIfAvailable(
                        output = output,
                        outputFormat = outputFormat
                    )
                }

                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)

                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)

                        val packetType =
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                PACKET_TYPE_VIDEO_CONFIG
                            } else {
                                PACKET_TYPE_VIDEO_FRAME
                            }

                        if (packetType == PACKET_TYPE_VIDEO_FRAME) {
                            val frameIndex = frameIndexCounter.getAndIncrement()
                            val framePayload = buildVideoFramePayload(
                                frameIndex = frameIndex,
                                bufferInfo = bufferInfo,
                                h264Payload = data
                            )

                            sendPacket(
                                output = output,
                                packetType = PACKET_TYPE_VIDEO_FRAME,
                                payload = framePayload
                            )

                            frameCount++
                        } else {
                            sendPacket(
                                output = output,
                                packetType = packetType,
                                payload = data
                            )
                        }
                    }

                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun sendCodecSpecificDataIfAvailable(
        output: BufferedOutputStream,
        outputFormat: MediaFormat
    ) {
        try {
            val csd0 = outputFormat.getByteBuffer("csd-0")
            val csd1 = outputFormat.getByteBuffer("csd-1")
            val csd2 = outputFormat.getByteBuffer("csd-2")

            if (csd0 != null) {
                sendPacket(
                    output = output,
                    packetType = PACKET_TYPE_VIDEO_CONFIG,
                    payload = byteBufferToByteArray(csd0)
                )
                Log.d(TAG, "Sent csd-0 video config")
            }

            if (csd1 != null) {
                sendPacket(
                    output = output,
                    packetType = PACKET_TYPE_VIDEO_CONFIG,
                    payload = byteBufferToByteArray(csd1)
                )
                Log.d(TAG, "Sent csd-1 video config")
            }

            if (csd2 != null) {
                sendPacket(
                    output = output,
                    packetType = PACKET_TYPE_VIDEO_CONFIG,
                    payload = byteBufferToByteArray(csd2)
                )
                Log.d(TAG, "Sent csd-2 video config")
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to send codec-specific data", exception)
        }
    }

    private fun byteBufferToByteArray(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        duplicate.position(0)

        val data = ByteArray(duplicate.remaining())
        duplicate.get(data)

        return data
    }

    private fun buildVideoFramePayload(
        frameIndex: Long,
        bufferInfo: MediaCodec.BufferInfo,
        h264Payload: ByteArray
    ): ByteArray {
        val keyframe =
            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

        val frameFlags: Byte = if (keyframe) {
            FRAME_FLAG_KEYFRAME
        } else {
            0.toByte()
        }

        return ByteBuffer.allocate(FRAME_METADATA_SIZE + h264Payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(FRAME_MAGIC_BYTES)
            .putLong(frameIndex)
            .putLong(bufferInfo.presentationTimeUs)
            .put(frameFlags)
            .putInt(h264Payload.size)
            .put(h264Payload)
            .array()
    }

    private fun sendPacket(
        output: BufferedOutputStream,
        packetType: Byte,
        payload: ByteArray
    ) {
        val timestamp = System.currentTimeMillis()

        val header = ByteBuffer.allocate(PACKET_HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC_BYTES)
            .put(packetType)
            .putLong(timestamp)
            .putInt(payload.size)
            .array()

        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun stopTcpServer() {
        serviceRunning = false

        clientSockets.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        clientSockets.clear()

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null
        serverThread = null

        Log.d(TAG, "TCP server stopped")
    }

    companion object {
        private const val TAG = "GHXSTLensService"

        private const val NOTIFICATION_ID = 9001
        private const val NOTIFICATION_CHANNEL_ID = "ghxst_lens_stream"

        const val STREAM_PORT = 9000

        private const val MIME_TYPE_AVC = "video/avc"
        private const val MIME_TYPE_HEVC = "video/hevc"

        private const val STREAM_VERSION = "v0.3B-CODEC1-HEVC"
        private const val STREAM_INPUT = "camera2_direct_encoder_surface"
        private const val STREAM_MODE = "camera2_direct_to_mediacodec_frame_metadata"

        const val EXTRA_STREAM_PROFILE = "st.ghx.lens.extra.STREAM_PROFILE"
        const val EXTRA_STREAM_CODEC = "st.ghx.lens.extra.STREAM_CODEC"
        const val EXTRA_CAMERA_ID = "st.ghx.lens.extra.CAMERA_ID"
        const val EXTRA_ZOOM_RATIO = "st.ghx.lens.extra.ZOOM_RATIO"

        private val DEFAULT_STREAM_PROFILE = StreamProfile.BALANCED_720P60_8M
        enum class StreamCodec(
            val id: String,
            val label: String,
            val displayName: String,
            val mimeType: String,
            val bitrateMultiplier: Float,
        ) {
            H264(
                id = "h264",
                label = "h264",
                displayName = "H.264",
                mimeType = MIME_TYPE_AVC,
                bitrateMultiplier = 1.0f,
            ),
            H265(
                id = "h265",
                label = "h265",
                displayName = "H.265/HEVC",
                mimeType = MIME_TYPE_HEVC,
                bitrateMultiplier = 0.55f,
            );

            fun bitrateFor(h264BaselineBitrate: Int): Int {
                return (h264BaselineBitrate * bitrateMultiplier).toInt().coerceAtLeast(1_500_000)
            }

            companion object {
                fun fromId(id: String?): StreamCodec {
                    return entries.firstOrNull { it.id == id } ?: H264
                }
            }
        }

        enum class StreamProfile(
            val id: String,
            val profileName: String,
            val displayName: String,
            val width: Int,
            val height: Int,
            val fps: Int,
            val bitrate: Int,
            val iframeIntervalSeconds: Int,
            val status: String,
        ) {
            ULTRA_LOW_360P60_2M(
                id = "ultra_low_360p60_2m",
                profileName = "ultra_low_360p60_2mbps",
                displayName = "Deprecated 360p60 • blocked from OBS controls",
                width = 640,
                height = 360,
                fps = 60,
                bitrate = 2_000_000,
                iframeIntervalSeconds = 1,
                status = "camera_h264_360p60_deprecated_blocked",
            ),

            BASIC_720P30_5M(
                id = "basic_720p30_5m",
                profileName = "basic_720p30_5mbps",
                displayName = "720p30 • 5 Mbps",
                width = 1280,
                height = 720,
                fps = 30,
                bitrate = 5_000_000,
                iframeIntervalSeconds = 1,
                status = "camera_h264_720p30_basic_active",
            ),

            BALANCED_720P60_8M(
                id = "balanced_720p60_8m",
                profileName = "balanced_720p60_8mbps",
                displayName = "720p60 • 8 Mbps",
                width = 1280,
                height = 720,
                fps = 60,
                bitrate = 8_000_000,
                iframeIntervalSeconds = 1,
                status = "camera_h264_720p60_balanced_active",
            ),

            QUALITY_1080P30_8M(
                id = "quality_1080p30_8m",
                profileName = "quality_1080p30_8mbps",
                displayName = "1080p30 • 8 Mbps",
                width = 1920,
                height = 1080,
                fps = 30,
                bitrate = 8_000_000,
                iframeIntervalSeconds = 1,
                status = "camera_h264_1080p30_quality_active",
            ),

            QUALITY_1080P60_16M(
                id = "quality_1080p60_16m",
                profileName = "quality_1080p60_16mbps",
                displayName = "1080p60 • Smooth HQ",
                width = 1920,
                height = 1080,
                fps = 60,
                bitrate = 16_000_000,
                iframeIntervalSeconds = 1,
                status = "camera_h264_1080p60_quality_active",
            ),

            ULTRA_4K30_45M(
                id = "ultra_4k30_45m",
                profileName = "ultra_4k30_45mbps",
                displayName = "4K30 • 45 Mbps",
                width = 3840,
                height = 2160,
                fps = 30,
                bitrate = 45_000_000,
                iframeIntervalSeconds = 1,
                status = "camera_h264_4k30_ultra_active",
            ),

            ULTRA_4K60_70M(
                id = "ultra_4k60_70m",
                profileName = "ultra_4k60_70mbps",
                displayName = "4K60 • 70 Mbps",
                width = 3840,
                height = 2160,
                fps = 60,
                bitrate = 70_000_000,
                iframeIntervalSeconds = 1,
                status = "camera_h264_4k60_ultra_active",
            );

            companion object {
                fun fromId(id: String?): StreamProfile {
                    return entries.firstOrNull { it.id == id } ?: DEFAULT_STREAM_PROFILE
                }

                fun fromControlId(id: String?): StreamProfile {
                    // The old 360p60 ultra-low mode proved unstable on some Camera2/encoder paths.
                    // Keep this guard so older OBS/plugin builds cannot trap the phone in a reconnect loop.
                    if (id.equals("ultra_low_360p60_2m", ignoreCase = true)) {
                        return BASIC_720P30_5M
                    }
                    return fromId(id)
                }
            }
        }


        private const val NETWORK_OUTPUT_BUFFER_SIZE = 128 * 1024
        private const val CAMERA2_OPEN_TIMEOUT_SECONDS = 5L
        private const val CAMERA2_SESSION_TIMEOUT_SECONDS = 5L

        private val MAGIC_BYTES = byteArrayOf(
            'G'.code.toByte(),
            'H'.code.toByte(),
            'X'.code.toByte(),
            'L'.code.toByte()
        )

        private const val PACKET_HEADER_SIZE = 17

        private val FRAME_MAGIC_BYTES = byteArrayOf(
            'G'.code.toByte(),
            'H'.code.toByte(),
            'X'.code.toByte(),
            'F'.code.toByte()
        )

        private const val FRAME_METADATA_SIZE = 25
        private const val FRAME_FLAG_KEYFRAME: Byte = 1

        private const val PACKET_TYPE_HELLO: Byte = 1
        private const val PACKET_TYPE_HEARTBEAT: Byte = 2
        private const val PACKET_TYPE_VIDEO_CONFIG: Byte = 9
        private const val PACKET_TYPE_VIDEO_FRAME: Byte = 10

        const val ACTION_START_STREAM = "st.ghx.lens.action.START_STREAM"
        const val ACTION_STOP_STREAM = "st.ghx.lens.action.STOP_STREAM"
    }
}