package st.ghx.lens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaRecorder
import android.util.Size
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import st.ghx.lens.ui.theme.GHXSTLensTheme
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

enum class LensStreamState {
    OFFLINE,
    SERVICE_ACTIVE
}

enum class ZoomPreset(val ratio: Float, val label: String, val shortLabel: String, val description: String) {
    ULTRAWIDE(0.6f, "Ultra-wide", "0.6x", "Shows more of the room when the selected lens supports it"),
    WIDE(1.0f, "Normal", "1x", "Main camera view"),
    CLOSE_15(1.5f, "Closer", "1.5x", "Small crop for a tighter view"),
    TELE_2(2.0f, "Zoom", "2x", "Closer crop or tele lens where supported"),
    TELE_3(3.0f, "Tight", "3x", "Tight view for close-up shots")
}

enum class LensStreamProfile(
    val id: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val receiverArgs: String
) {
    ULTRA_LOW_360P60_2M(
        id = "ultra_low_360p60_2m",
        displayName = "360p60 • Ultra Low",
        width = 640,
        height = 360,
        fps = 60,
        receiverArgs = "--width 640 --height 360 --fps 60 --preview-width 640 --preview-height 360 --decoder-threads 1"
    ),

    BASIC_720P30_5M(
        id = "basic_720p30_5m",
        displayName = "720p30",
        width = 1280,
        height = 720,
        fps = 30,
        receiverArgs = "--width 1280 --height 720 --fps 30 --preview-width 1280 --preview-height 720 --decoder-threads 1"
    ),

    BALANCED_720P60_8M(
        id = "balanced_720p60_8m",
        displayName = "720p60",
        width = 1280,
        height = 720,
        fps = 60,
        receiverArgs = "--width 1280 --height 720 --fps 60 --preview-width 1280 --preview-height 720 --decoder-threads 1"
    ),

    QUALITY_1080P30_8M(
        id = "quality_1080p30_8m",
        displayName = "1080p30",
        width = 1920,
        height = 1080,
        fps = 30,
        receiverArgs = "--width 1920 --height 1080 --fps 30 --preview-width 1280 --preview-height 720 --decoder-threads 1"
    ),

    QUALITY_1080P60_16M(
        id = "quality_1080p60_16m",
        displayName = "1080p60",
        width = 1920,
        height = 1080,
        fps = 60,
        receiverArgs = "--width 1920 --height 1080 --fps 60 --preview-width 1280 --preview-height 720 --decoder-threads 1"
    ),

    ULTRA_4K30_45M(
        id = "ultra_4k30_45m",
        displayName = "4K30",
        width = 3840,
        height = 2160,
        fps = 30,
        receiverArgs = "--width 3840 --height 2160 --fps 30 --preview-width 1280 --preview-height 720 --decoder-threads 1"
    ),

    ULTRA_4K60_70M(
        id = "ultra_4k60_70m",
        displayName = "4K60",
        width = 3840,
        height = 2160,
        fps = 60,
        receiverArgs = "--width 3840 --height 2160 --fps 60 --preview-width 1280 --preview-height 720 --decoder-threads 0"
    )

}

enum class LensStreamCodec(
    val id: String,
    val displayName: String,
    val shortName: String,
    val description: String
) {
    H264(
        id = "h264",
        displayName = "H.264 • Compatible",
        shortName = "H.264",
        description = "Best compatibility. Higher bitrate."
    ),
    H265(
        id = "h265",
        displayName = "H.265 • Lower Bitrate",
        shortName = "H.265",
        description = "Experimental HEVC test. Lower bitrate for 4K/OBS."
    )
}

data class LensCameraOption(
    val id: String,
    val displayName: String,
    val lensFacingLabel: String,
    val lensFacing: Int?,
    val details: String,
    val isBack: Boolean,
    val lensBadge: String,
    val lensDescription: String,
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val zoomRangeLabel: String,
    val outputSizeKeys: Set<String>,
    val highSpeedSizeKeys: Set<String>
)

class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotificationPermission = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            GHXSTLensTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    LensApp(
                        hasCameraPermission = hasCameraPermission,
                        hasNotificationPermission = hasNotificationPermission,
                        onRequestCameraPermission = {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LensApp(
    hasCameraPermission: Boolean,
    hasNotificationPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val context = LocalContext.current

    var previewEnabled by remember { mutableStateOf(true) }
    var streamState by remember { mutableStateOf(LensStreamState.OFFLINE) }
    var deviceIp by remember { mutableStateOf("Detecting...") }
    var selectedProfile by remember { mutableStateOf(LensStreamProfile.BALANCED_720P60_8M) }
    var selectedCodec by remember { mutableStateOf(LensStreamCodec.H264) }
    var cameraOptions by remember { mutableStateOf<List<LensCameraOption>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var selectedZoomPreset by remember { mutableStateOf(ZoomPreset.WIDE) }

    val selectedCameraOption = cameraOptions.firstOrNull { it.id == selectedCameraId }
    val streamPort = 9000
    val streamEnabled = streamState != LensStreamState.OFFLINE

    val cameraSystemActive = hasCameraPermission && (previewEnabled || streamEnabled)

    val streamMode = when {
        streamEnabled -> "Service Only"
        previewEnabled -> "Preview Only"
        else -> "Standby"
    }

    LaunchedEffect(Unit) {
        while (true) {
            deviceIp = getBestLocalIpAddress(context)
            delay(2000)
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            cameraOptions = getLensCameraOptions(context)
            selectedCameraId = cameraOptions.firstOrNull { it.isBack }?.id
                ?: cameraOptions.firstOrNull()?.id
        }
    }

    LaunchedEffect(selectedCameraOption?.id) {
        val camera = selectedCameraOption ?: return@LaunchedEffect
        val validZooms = availableZoomPresets(camera)
        if (validZooms.isNotEmpty() && selectedZoomPreset !in validZooms) {
            selectedZoomPreset = validZooms.firstOrNull { it == ZoomPreset.WIDE }
                ?: validZooms.first()
        }

        val validProfiles = availableProfilesForCamera(camera)
        if (validProfiles.isNotEmpty() && selectedProfile !in validProfiles) {
            selectedProfile = validProfiles.firstOrNull { it == LensStreamProfile.BALANCED_720P60_8M }
                ?: validProfiles.firstOrNull { it == LensStreamProfile.QUALITY_1080P30_8M }
                        ?: validProfiles.first()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            hasCameraPermission && previewEnabled -> {
                CameraPreview(
                    selectedCameraOption = selectedCameraOption,
                    selectedZoomRatio = selectedZoomPreset.ratio
                )
            }

            hasCameraPermission && streamEnabled && !previewEnabled -> {
                StreamOnlyBackground()
            }

            hasCameraPermission && !cameraSystemActive -> {
                StandbyBackground()
            }

            !hasCameraPermission -> {
                PermissionBackground()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            HeaderCard(
                ipAddress = deviceIp,
                streamPort = streamPort,
                streamMode = streamMode,
                streamState = streamState,
                selectedProfile = selectedProfile,
                selectedCodec = selectedCodec,
                selectedCameraOption = selectedCameraOption,
                selectedZoomPreset = selectedZoomPreset
            )

            Spacer(modifier = Modifier.weight(1f))

            if (hasCameraPermission) {
                ControlPanel(
                    previewEnabled = previewEnabled,
                    streamState = streamState,
                    selectedProfile = selectedProfile,
                    selectedCodec = selectedCodec,
                    selectedCameraOption = selectedCameraOption,
                    selectedZoomPreset = selectedZoomPreset,
                    cameraOptions = cameraOptions,
                    hasNotificationPermission = hasNotificationPermission,
                    ipAddress = deviceIp,
                    streamPort = streamPort,
                    onSelectProfile = { profile ->
                        if (!streamEnabled) {
                            selectedProfile = profile
                        }
                    },
                    onSelectCodec = { codec ->
                        if (!streamEnabled) {
                            selectedCodec = codec
                        }
                    },
                    onSelectCamera = { cameraId ->
                        if (!streamEnabled) {
                            selectedCameraId = cameraId
                        }
                    },
                    onSelectZoom = { zoomPreset ->
                        if (!streamEnabled) {
                            selectedZoomPreset = zoomPreset
                        }
                    },
                    onTogglePreview = {
                        if (!streamEnabled) {
                            previewEnabled = !previewEnabled
                        }
                    },
                    onToggleStream = {
                        if (streamState == LensStreamState.OFFLINE) {
                            if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                onRequestNotificationPermission()
                            }

                            // Current stable architecture:
                            // The foreground service owns the camera while streaming.
                            // Keep local preview disabled to avoid CameraX unbind/rebind conflicts.
                            previewEnabled = false

                            startLensStreamService(context, selectedProfile, selectedCodec, selectedCameraId, selectedZoomPreset.ratio)
                            streamState = LensStreamState.SERVICE_ACTIVE
                        } else {
                            stopLensStreamService(context)
                            streamState = LensStreamState.OFFLINE
                        }
                    },
                    onRequestNotificationPermission = onRequestNotificationPermission
                )
            } else {
                PermissionPanel(onRequestPermission = onRequestCameraPermission)
            }
        }
    }
}

@Composable
fun HeaderCard(
    ipAddress: String,
    streamPort: Int,
    streamMode: String,
    streamState: LensStreamState,
    selectedProfile: LensStreamProfile,
    selectedCodec: LensStreamCodec,
    selectedCameraOption: LensCameraOption?,
    selectedZoomPreset: ZoomPreset
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xD905070A)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniLogoBadge()

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GHXST Lens",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusDot(active = streamState == LensStreamState.SERVICE_ACTIVE)
                        Text(
                            text = if (streamState == LensStreamState.SERVICE_ACTIVE) "LIVE" else "READY",
                            color = streamStateColor(streamState),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "v0.3B-UI17 • Simple Modes + HEVC • $streamMode",
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "$ipAddress:$streamPort",
                    color = Color(0xFF6EE7B7),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "${profileShortLabel(selectedProfile)} • ${selectedCodec.shortName} • ${selectedViewLabel(selectedCameraOption, selectedZoomPreset)}",
                    color = Color(0xFFCBD5E1),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ControlPanel(
    previewEnabled: Boolean,
    streamState: LensStreamState,
    selectedProfile: LensStreamProfile,
    selectedCodec: LensStreamCodec,
    selectedCameraOption: LensCameraOption?,
    selectedZoomPreset: ZoomPreset,
    cameraOptions: List<LensCameraOption>,
    hasNotificationPermission: Boolean,
    ipAddress: String,
    streamPort: Int,
    onSelectProfile: (LensStreamProfile) -> Unit,
    onSelectCodec: (LensStreamCodec) -> Unit,
    onSelectCamera: (String) -> Unit,
    onSelectZoom: (ZoomPreset) -> Unit,
    onTogglePreview: () -> Unit,
    onToggleStream: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val streamEnabled = streamState != LensStreamState.OFFLINE
    val selectedProfileSupport = profileSupportStatus(selectedProfile, selectedCameraOption)
    val selectedProfileUsable = selectedProfileSupport.usable
    var activeSheet by remember { mutableStateOf<String?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xEA080C12)),
        shape = RoundedCornerShape(30.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StreamSummaryRow(
                selectedProfile = selectedProfile,
                selectedCodec = selectedCodec,
                selectedCameraOption = selectedCameraOption,
                selectedZoomPreset = selectedZoomPreset,
                ipAddress = ipAddress,
                streamPort = streamPort,
                streamEnabled = streamEnabled
            )

            LensZoomQuickAccessRow(
                selectedCameraOption = selectedCameraOption,
                selectedZoomPreset = selectedZoomPreset,
                enabled = !streamEnabled,
                onLensClick = { activeSheet = "camera" },
                onZoomClick = { activeSheet = "camera" }
            )

            Button(
                onClick = onToggleStream,
                enabled = streamEnabled || selectedProfileUsable,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (streamEnabled) Color(0xFFEF4444) else Color(0xFF22C55E)
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
            ) {
                Text(
                    text = when {
                        streamEnabled -> "Stop Stream"
                        !selectedProfileUsable -> "Unavailable"
                        else -> "Start Stream"
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (!streamEnabled && !selectedProfileUsable) {
                Text(
                    text = selectedProfileSupport.reason,
                    color = Color(0xFFFCA5A5),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconActionButton(
                    icon = "VIEW",
                    label = "Lens",
                    enabled = !streamEnabled,
                    selected = false,
                    onClick = { activeSheet = "camera" },
                    modifier = Modifier.weight(1f)
                )

                IconActionButton(
                    icon = "HD",
                    label = "Quality",
                    enabled = !streamEnabled,
                    selected = false,
                    onClick = { activeSheet = "profile" },
                    modifier = Modifier.weight(1f)
                )

                IconActionButton(
                    icon = selectedCodec.shortName,
                    label = "Codec",
                    enabled = !streamEnabled,
                    selected = selectedCodec == LensStreamCodec.H265,
                    onClick = { activeSheet = "codec" },
                    modifier = Modifier.weight(1f)
                )

                IconActionButton(
                    icon = if (previewEnabled) "ON" else "OFF",
                    label = "Preview",
                    enabled = !streamEnabled,
                    selected = previewEnabled,
                    onClick = onTogglePreview,
                    modifier = Modifier.weight(1f)
                )
            }

            if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(
                    onClick = onRequestNotificationPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Allow Notifications")
                }
            }
        }
    }

    when (activeSheet) {
        "camera" -> SettingsDialog(
            title = "Lens & Zoom",
            subtitle = if (streamEnabled) "Lens and zoom are locked while streaming." else "Choose the camera lens first. Ultra-wide is treated as a lens when the phone exposes it, then zoom is used for closer/tighter shots.",
            onDismiss = { activeSheet = null }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                QuickLensSelector(
                    selectedCameraOption = selectedCameraOption,
                    cameraOptions = cameraOptions,
                    enabled = !streamEnabled,
                    onSelectCamera = { cameraId ->
                        onSelectCamera(cameraId)
                        onSelectZoom(ZoomPreset.WIDE)
                    }
                )

                CompactZoomSelector(
                    selectedZoomPreset = selectedZoomPreset,
                    selectedCameraOption = selectedCameraOption,
                    enabled = !streamEnabled,
                    onSelectZoom = { zoomPreset -> onSelectZoom(zoomPreset) }
                )

                CompactCameraSelector(
                    selectedCameraOption = selectedCameraOption,
                    cameraOptions = cameraOptions,
                    enabled = !streamEnabled,
                    showDetails = false,
                    onSelectCamera = { cameraId ->
                        onSelectCamera(cameraId)
                        onSelectZoom(ZoomPreset.WIDE)
                    }
                )
            }
        }

        "profile" -> SettingsDialog(
            title = "Choose Quality",
            subtitle = if (streamEnabled) "Quality is locked while streaming." else "Choose the resolution/FPS. Codec is selected separately.",
            onDismiss = { activeSheet = null }
        ) {
            CompactProfileSelector(
                selectedProfile = selectedProfile,
                selectedCameraOption = selectedCameraOption,
                enabled = !streamEnabled,
                onSelectProfile = { profile ->
                    onSelectProfile(profile)
                    activeSheet = null
                }
            )
        }

        "codec" -> SettingsDialog(
            title = "Choose Codec",
            subtitle = if (streamEnabled) "Codec is locked while streaming." else "Use H.264 as the safe default. Test H.265/HEVC to reduce bitrate before OBS work.",
            onDismiss = { activeSheet = null }
        ) {
            CompactCodecSelector(
                selectedCodec = selectedCodec,
                enabled = !streamEnabled,
                onSelectCodec = { codec ->
                    onSelectCodec(codec)
                    activeSheet = null
                }
            )
        }

        "tools" -> SettingsDialog(
            title = "Info & Diagnostics",
            subtitle = "Receiver command and selected camera details.",
            onDismiss = { activeSheet = null }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Button(
                        onClick = onRequestNotificationPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Allow Notifications")
                    }
                }

                ReceiverCommandCard(selectedProfile = selectedProfile)

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xAA020617)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Selected lens",
                            color = Color(0xFFCBD5E1),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = selectedViewLabel(selectedCameraOption, selectedZoomPreset),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = selectedCameraOption?.details ?: "No camera details available yet.",
                            color = Color(0xFF94A3B8),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StreamSummaryRow(
    selectedProfile: LensStreamProfile,
    selectedCodec: LensStreamCodec,
    selectedCameraOption: LensCameraOption?,
    selectedZoomPreset: ZoomPreset,
    ipAddress: String,
    streamPort: Int,
    streamEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogoBadge()

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (streamEnabled) "Streaming" else "Ready",
                color = if (streamEnabled) Color(0xFF22C55E) else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = profileShortLabel(selectedProfile),
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = selectedViewLabel(selectedCameraOption, selectedZoomPreset),
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = "$ipAddress:$streamPort",
            color = Color(0xFF6EE7B7),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun IconActionButton(
    icon: String,
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF1D4ED8) else Color(0xFF0F172A),
            disabledContainerColor = Color(0xFF0B1120),
            contentColor = Color.White,
            disabledContentColor = Color(0xFF64748B)
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.heightIn(min = 64.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = if (selected) Color(0xFF60A5FA) else Color(0xFF111827),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = icon,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    color = if (selected) Color(0xFF031226) else Color(0xFF67E8F9)
                )
            }

            Spacer(modifier = Modifier.size(3.dp))

            Text(
                text = label,
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

fun profileShortLabel(profile: LensStreamProfile): String {
    return profile.displayName
        .replace(" / 2K30", "")
        .replace(" / 2K60", "")
        .replace(" • ", " · ")
}

@Composable
fun MiniLogoBadge() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Color(0xFF020617), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Color(0xFF0F172A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "G",
                color = Color(0xFF67E8F9),
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 18.sp
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(9.dp)
                .background(Color(0xFF22C55E), CircleShape)
        )
    }
}

@Composable
fun SettingsDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF080C12)),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtitle,
                            color = Color(0xFF94A3B8),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Done")
                    }
                }

                content()
            }
        }
    }
}

@Composable
fun SimpleSetupRow(
    selectedProfile: LensStreamProfile,
    selectedCameraOption: LensCameraOption?,
    ipAddress: String,
    streamPort: Int,
    streamEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogoBadge()

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (streamEnabled) "Streaming" else "Ready",
                color = if (streamEnabled) Color(0xFF22C55E) else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = selectedProfile.displayName,
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = selectedCameraOption?.displayName ?: "Detecting camera...",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text(
            text = "$ipAddress:$streamPort",
            color = Color(0xFF6EE7B7),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun LogoBadge() {
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(Color(0xFF020617), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF0F172A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFF111827), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "G",
                    color = Color(0xFF67E8F9),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 20.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(10.dp)
                .background(Color(0xFF22C55E), CircleShape)
        )
    }
}

@Composable
fun CurrentSetupCard(
    selectedProfile: LensStreamProfile,
    selectedCameraOption: LensCameraOption?,
    streamEnabled: Boolean,
    ipAddress: String,
    streamPort: Int
) {
    val selectedProfileSupport = profileSupportStatus(selectedProfile, selectedCameraOption)
    val selectedProfileUsable = selectedProfileSupport.usable

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xAA020617)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (!streamEnabled && !selectedProfileUsable) {
                Text(
                    text = selectedProfileSupport.reason,
                    color = Color(0xFFFCA5A5),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (streamEnabled) "Streaming" else "Ready to stream",
                    color = if (streamEnabled) Color(0xFF22C55E) else Color(0xFFCBD5E1),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "$ipAddress:$streamPort",
                    color = Color(0xFF6EE7B7),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = selectedProfile.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = selectedCameraOption?.displayName ?: "Detecting Camera2 lenses...",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun CompactZoomSelector(
    selectedZoomPreset: ZoomPreset,
    selectedCameraOption: LensCameraOption?,
    enabled: Boolean,
    onSelectZoom: (ZoomPreset) -> Unit
) {
    val visibleZoomPresets = availableZoomPresets(selectedCameraOption)
    val zoomRangeText = selectedCameraOption?.zoomRangeLabel ?: "detecting zoom range"

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = if (enabled) "How wide / close?" else "Zoom locked while streaming",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "This lens supports $zoomRangeText. Preview and stream use the same zoom.",
            color = Color(0xFF94A3B8),
            style = MaterialTheme.typography.bodySmall
        )

        if (selectedCameraOption != null && ZoomPreset.ULTRAWIDE !in visibleZoomPresets) {
            Text(
                text = "Ultra-wide only appears when the selected phone camera really supports below 1x.",
                color = Color(0xFFFBBF24),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            visibleZoomPresets.forEach { preset ->
                val selected = preset == selectedZoomPreset
                Button(
                    onClick = { onSelectZoom(preset) },
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF2563EB) else Color(0xFF0F172A),
                        disabledContainerColor = if (selected) Color(0xFF1D4ED8) else Color(0xFF0B1120),
                        contentColor = Color.White,
                        disabledContentColor = Color(0xFF64748B)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "${preset.shortLabel}  ${preset.label}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                            Text(
                                text = preset.description,
                                color = if (selected) Color(0xFFE0F2FE) else Color(0xFF94A3B8),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = if (selected) "Selected" else "",
                            color = Color(0xFFA7F3D0),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Text(
            text = "Active lens: " + (selectedCameraOption?.lensBadge ?: "detecting"),
            color = Color(0xFFCBD5E1),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun ReceiverCommandCard(selectedProfile: LensStreamProfile) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xAA020617)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Receiver command",
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "python .\\ghxst_lens_cv_receiver.py ${selectedProfile.receiverArgs} --no-overlay",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun LensZoomQuickAccessRow(
    selectedCameraOption: LensCameraOption?,
    selectedZoomPreset: ZoomPreset,
    enabled: Boolean,
    onLensClick: () -> Unit,
    onZoomClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onLensClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF111827),
                disabledContainerColor = Color(0xFF0B1120),
                contentColor = Color.White,
                disabledContentColor = Color(0xFF94A3B8)
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 58.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Lens",
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = selectedCameraOption?.displayName ?: "Detecting...",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Button(
            onClick = onZoomClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF111827),
                disabledContainerColor = Color(0xFF0B1120),
                contentColor = Color.White,
                disabledContentColor = Color(0xFF94A3B8)
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 58.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Zoom",
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = zoomDisplayLabel(selectedCameraOption, selectedZoomPreset),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun QuickLensSelector(
    selectedCameraOption: LensCameraOption?,
    cameraOptions: List<LensCameraOption>,
    enabled: Boolean,
    onSelectCamera: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (enabled) "Pick lens" else "Pick lens • locked while streaming",
            color = Color(0xFFCBD5E1),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )

        if (cameraOptions.isEmpty()) {
            Text(
                text = "Detecting Camera2 lenses...",
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.bodySmall
            )
            return
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            cameraOptions.forEach { camera ->
                val selected = camera.id == selectedCameraOption?.id
                val ultraWideHint = camera.displayName.contains("Ultra-wide", ignoreCase = true)

                Button(
                    onClick = { onSelectCamera(camera.id) },
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            selected && ultraWideHint -> Color(0xFF0E7490)
                            selected -> Color(0xFF7C3AED)
                            ultraWideHint -> Color(0xFF164E63)
                            else -> Color(0xFF1E293B)
                        },
                        disabledContainerColor = if (selected) Color(0xFF334155) else Color(0xFF111827),
                        disabledContentColor = Color(0xFF94A3B8)
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 62.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lensButtonTitle(camera),
                                color = if (enabled || selected) Color.White else Color(0xFF94A3B8),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = lensButtonSubtitle(camera),
                                color = if (ultraWideHint) Color(0xFFA7F3D0) else Color(0xFFCBD5E1),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = if (selected) "ON" else "Select",
                            color = if (selected) Color(0xFFA7F3D0) else Color(0xFF67E8F9),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompactCameraSelector(
    selectedCameraOption: LensCameraOption?,
    cameraOptions: List<LensCameraOption>,
    enabled: Boolean,
    showDetails: Boolean,
    onSelectCamera: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (enabled) "Camera view" else "Camera view • locked while streaming",
            color = Color(0xFFCBD5E1),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )

        if (cameraOptions.isEmpty()) {
            Text(
                text = "Detecting Camera2 lenses...",
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                cameraOptions.forEach { camera ->
                    val selected = camera.id == selectedCameraOption?.id

                    Button(
                        onClick = { onSelectCamera(camera.id) },
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF7C3AED) else Color(0xFF1E293B),
                            disabledContainerColor = if (selected) Color(0xFF5B21B6) else Color(0xFF111827),
                            disabledContentColor = Color(0xFF94A3B8)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = camera.displayName,
                                color = if (enabled || selected) Color.White else Color(0xFF94A3B8),
                                fontWeight = FontWeight.SemiBold
                            )

                            Text(
                                text = camera.lensDescription,
                                color = Color(0xFFA7F3D0),
                                style = MaterialTheme.typography.labelSmall
                            )

                            Text(
                                text = "Supported zoom: ${camera.zoomRangeLabel}",
                                color = Color(0xFF67E8F9),
                                style = MaterialTheme.typography.labelSmall
                            )

                            if (showDetails || selected) {
                                Text(
                                    text = if (showDetails) camera.details else camera.details.take(92),
                                    color = Color(0xFFCBD5E1),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactProfileSelector(
    selectedProfile: LensStreamProfile,
    selectedCameraOption: LensCameraOption?,
    enabled: Boolean,
    onSelectProfile: (LensStreamProfile) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (enabled) "Stream quality" else "Stream quality • locked while streaming",
            color = Color(0xFFCBD5E1),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            availableProfilesForCamera(selectedCameraOption).forEach { profile ->
                val selected = profile == selectedProfile
                val support = profileSupportStatus(profile, selectedCameraOption)
                val canSelect = enabled && support.usable

                Button(
                    onClick = { onSelectProfile(profile) },
                    enabled = canSelect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF2563EB) else Color(0xFF1E293B),
                        disabledContainerColor = if (selected) Color(0xFF1D4ED8) else Color(0xFF111827),
                        disabledContentColor = Color(0xFF94A3B8)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = profile.displayName,
                            color = if (canSelect || selected) Color.White else Color(0xFF94A3B8),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = support.label,
                            color = if (support.usable) Color(0xFFA7F3D0) else Color(0xFFFCA5A5),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompactCodecSelector(
    selectedCodec: LensStreamCodec,
    enabled: Boolean,
    onSelectCodec: (LensStreamCodec) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LensStreamCodec.entries.forEach { codec ->
            val selected = codec == selectedCodec
            Button(
                onClick = { onSelectCodec(codec) },
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Color(0xFF7C3AED) else Color(0xFF1E293B),
                    disabledContainerColor = if (selected) Color(0xFF5B21B6) else Color(0xFF111827),
                    disabledContentColor = Color(0xFF94A3B8)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = codec.displayName,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = codec.description,
                        color = if (codec == LensStreamCodec.H265) Color(0xFFA7F3D0) else Color(0xFFCBD5E1),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(
                color = if (active) Color(0xFF22C55E) else Color(0xFFEF4444),
                shape = CircleShape
            )
    )
}

@Composable
fun PermissionPanel(onRequestPermission: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xDD10151D)
        ),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Camera permission required",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "GHXST Lens needs camera access to start the preview.",
                color = Color(0xFF9AA4B2)
            )

            Button(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Allow Camera")
            }
        }
    }
}

@Composable
fun StreamOnlyBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusDot(active = true)

            Text(
                text = "Stream service active",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Preview disabled to reduce display usage",
                color = Color(0xFF9AA4B2),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun StandbyBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Camera standby",
            color = Color(0xFF9AA4B2),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun PermissionBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Waiting for camera permission",
            color = Color(0xFF9AA4B2),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun CameraPreview(
    selectedCameraOption: LensCameraOption?,
    selectedZoomRatio: Float
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(selectedCameraOption?.id, selectedZoomRatio) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val requestedCameraId = selectedCameraOption?.id

                val exactCameraSelector = if (!requestedCameraId.isNullOrBlank()) {
                    try {
                        CameraSelector.Builder()
                            .addCameraFilter { cameraInfos ->
                                cameraInfos.filter { cameraInfo ->
                                    try {
                                        Camera2CameraInfo.from(cameraInfo).cameraId == requestedCameraId
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                            }
                            .build()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                val fallbackSelector = when (selectedCameraOption?.lensFacing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()

                val camera = try {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        exactCameraSelector ?: fallbackSelector,
                        preview
                    )
                } catch (exactException: Exception) {
                    Log.w("GHXSTLens", "Exact Camera2 preview bind failed, falling back", exactException)
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        fallbackSelector,
                        preview
                    )
                }

                val zoomState = camera.cameraInfo.zoomState.value
                val minZoom = zoomState?.minZoomRatio ?: selectedCameraOption?.minZoomRatio ?: 1.0f
                val maxZoom = zoomState?.maxZoomRatio ?: selectedCameraOption?.maxZoomRatio ?: 1.0f
                val previewZoom = selectedZoomRatio.coerceIn(minZoom, maxZoom)

                try {
                    camera.cameraControl.setZoomRatio(previewZoom)
                    Log.d(
                        "GHXSTLens",
                        "Preview zoom requested=$selectedZoomRatio applied=$previewZoom range=$minZoom-$maxZoom camera=${selectedCameraOption?.id}"
                    )
                } catch (zoomException: Exception) {
                    Log.w("GHXSTLens", "Preview zoom failed", zoomException)
                }
            } catch (exception: Exception) {
                Log.e("GHXSTLens", "Camera preview failed", exception)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                try {
                    cameraProviderFuture.get().unbindAll()
                } catch (exception: Exception) {
                    Log.e("GHXSTLens", "Camera cleanup failed", exception)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

fun streamStateLabel(streamState: LensStreamState): String {
    return when (streamState) {
        LensStreamState.OFFLINE -> "Offline"
        LensStreamState.SERVICE_ACTIVE -> "Service Active"
    }
}

@Composable
fun streamStateColor(streamState: LensStreamState): Color {
    return when (streamState) {
        LensStreamState.OFFLINE -> Color(0xFFF97316)
        LensStreamState.SERVICE_ACTIVE -> Color(0xFF22C55E)
    }
}

fun startLensStreamService(
    context: Context,
    profile: LensStreamProfile,
    codec: LensStreamCodec,
    cameraId: String?,
    zoomRatio: Float
) {
    val intent = Intent(context, LensStreamService::class.java).apply {
        action = LensStreamService.ACTION_START_STREAM
        putExtra(LensStreamService.EXTRA_STREAM_PROFILE, profile.id)
        putExtra(LensStreamService.EXTRA_STREAM_CODEC, codec.id)
        if (!cameraId.isNullOrBlank()) {
            putExtra(LensStreamService.EXTRA_CAMERA_ID, cameraId)
        }
        putExtra(LensStreamService.EXTRA_ZOOM_RATIO, zoomRatio)
    }

    ContextCompat.startForegroundService(context, intent)
}

fun stopLensStreamService(context: Context) {
    val intent = Intent(context, LensStreamService::class.java).apply {
        action = LensStreamService.ACTION_STOP_STREAM
    }

    context.startService(intent)
}


data class CameraBuildInfo(
    val id: String,
    val characteristics: CameraCharacteristics,
    val lensFacing: Int?,
    val lensFacingLabel: String,
    val equivalent35mm: Float?,
    val allEquivalent35mm: List<Float>
)

data class UiZoomRange(
    val minRatio: Float,
    val maxRatio: Float,
    val label: String
)

fun getUiZoomRange(characteristics: CameraCharacteristics): UiZoomRange {
    val ratioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
    } else {
        null
    }

    if (ratioRange != null) {
        return UiZoomRange(
            minRatio = ratioRange.lower,
            maxRatio = ratioRange.upper,
            label = "${formatZoomFactor(ratioRange.lower)}-${formatZoomFactor(ratioRange.upper)}"
        )
    }

    val maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
    return UiZoomRange(
        minRatio = 1.0f,
        maxRatio = maxDigitalZoom,
        label = "1x-${formatZoomFactor(maxDigitalZoom)}"
    )
}

fun getLensCameraOptions(context: Context): List<LensCameraOption> {
    return try {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val buildInfo = cameraManager.cameraIdList.map { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val lensFacingLabel = when (lensFacing) {
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> "Unknown"
            }

            CameraBuildInfo(
                id = cameraId,
                characteristics = characteristics,
                lensFacing = lensFacing,
                lensFacingLabel = lensFacingLabel,
                equivalent35mm = estimate35mmEquivalentFocalLength(characteristics),
                allEquivalent35mm = estimateAll35mmEquivalentFocalLengths(characteristics)
            )
        }

        val mainBackEquivalent = buildInfo
            .filter { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
            .flatMap { if (it.allEquivalent35mm.isNotEmpty()) it.allEquivalent35mm else listOfNotNull(it.equivalent35mm) }
            .minByOrNull { kotlin.math.abs(it - 24.0f) }

        buildInfo.map { info ->
            val capabilities = buildUiCameraCapabilitySummary(info.characteristics)
            val lensBadge = buildLensBadge(info, mainBackEquivalent)
            val lensDescription = buildLensDescription(info, mainBackEquivalent)
            val uiZoomRange = getUiZoomRange(info.characteristics)
            val displayName = lensBadge

            LensCameraOption(
                id = info.id,
                displayName = displayName,
                lensFacingLabel = info.lensFacingLabel,
                lensFacing = info.lensFacing,
                details = "Camera ID ${info.id} • $capabilities",
                isBack = info.lensFacing == CameraCharacteristics.LENS_FACING_BACK,
                lensBadge = lensBadge,
                lensDescription = lensDescription,
                minZoomRatio = uiZoomRange.minRatio,
                maxZoomRatio = uiZoomRange.maxRatio,
                zoomRangeLabel = uiZoomRange.label,
                outputSizeKeys = getCameraOutputSizeKeys(info.characteristics),
                highSpeedSizeKeys = getCameraHighSpeedSizeKeys(info.characteristics)
            )
        }.sortedWith(
            compareByDescending<LensCameraOption> { it.isBack }
                .thenBy { lensSortPriority(it.lensBadge) }
                .thenBy { it.id }
        )
    } catch (exception: Exception) {
        Log.e("GHXSTLens", "Camera2 option lookup failed", exception)
        emptyList()
    }
}

fun estimate35mmEquivalentFocalLength(characteristics: CameraCharacteristics): Float? {
    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
    val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val minFocalLength = focalLengths?.minOrNull() ?: return null

    if (physicalSize == null || physicalSize.width <= 0f) {
        return null
    }

    return minFocalLength * 36.0f / physicalSize.width
}

fun estimateAll35mmEquivalentFocalLengths(characteristics: CameraCharacteristics): List<Float> {
    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?: return emptyList()
    val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        ?: return emptyList()

    if (physicalSize.width <= 0f) {
        return emptyList()
    }

    return focalLengths
        .map { focalLength -> focalLength * 36.0f / physicalSize.width }
        .filter { it > 0f }
        .distinctBy { kotlin.math.round(it).toInt() }
        .sorted()
}

fun hasLogicalUltraWideZoom(characteristics: CameraCharacteristics): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return false
    }

    val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        ?: return false

    return zoomRange.lower < 0.95f
}

fun buildLensBadge(info: CameraBuildInfo, mainBackEquivalent: Float?): String {
    if (info.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
        return "Front"
    }

    if (info.lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
        return "External"
    }

    val equivalent = info.equivalent35mm
    val hasLogicalUltraWide = hasLogicalUltraWideZoom(info.characteristics)

    if (hasLogicalUltraWide) {
        return "0.6x Ultra-wide / 1x Wide"
    }

    if (equivalent == null || mainBackEquivalent == null || mainBackEquivalent <= 0f) {
        return "${info.lensFacingLabel} Lens"
    }

    val relative = equivalent / mainBackEquivalent
    val rounded = when {
        relative < 0.75f -> 0.6f
        relative < 1.25f -> 1.0f
        relative < 1.75f -> 1.5f
        relative < 2.5f -> 2.0f
        relative < 3.5f -> 3.0f
        else -> relative
    }

    val lensName = when {
        relative < 0.75f -> "Ultra-wide"
        relative < 1.25f -> "Wide"
        relative < 2.5f -> "Tele"
        else -> "Tele"
    }

    return "${formatZoomFactor(rounded)} $lensName"
}

fun buildLensDescription(info: CameraBuildInfo, mainBackEquivalent: Float?): String {
    val equivalent = info.equivalent35mm
    val allFocalText = if (info.allEquivalent35mm.isNotEmpty()) {
        info.allEquivalent35mm.joinToString("/") { String.format(Locale.US, "%.0fmm", it) } + " equivalent"
    } else if (equivalent != null) {
        String.format(Locale.US, "%.0fmm equivalent", equivalent)
    } else {
        "camera details unknown"
    }

    val useText = when {
        info.lensFacing == CameraCharacteristics.LENS_FACING_FRONT -> "Selfie / front view"
        hasLogicalUltraWideZoom(info.characteristics) -> "Best for ultra-wide and normal shots"
        mainBackEquivalent == null -> info.lensFacingLabel
        equivalent != null && mainBackEquivalent > 0f && equivalent / mainBackEquivalent < 0.75f -> "Best for wide room shots"
        equivalent != null && mainBackEquivalent > 0f && equivalent / mainBackEquivalent < 1.25f -> "Best default camera"
        equivalent != null && mainBackEquivalent > 0f -> "Best for tighter close-up shots"
        else -> "Phone camera"
    }

    return "$useText • $allFocalText"
}

fun formatZoomFactor(value: Float): String {
    return if (kotlin.math.abs(value - value.toInt()) < 0.05f) {
        "${value.toInt()}x"
    } else {
        String.format(Locale.US, "%.1fx", value)
    }
}

fun lensSortPriority(lensBadge: String): Int {
    return when {
        lensBadge.contains("Ultra-wide", ignoreCase = true) -> 0
        lensBadge.contains("Wide", ignoreCase = true) -> 1
        lensBadge.contains("Tele", ignoreCase = true) -> 2
        lensBadge.contains("Front", ignoreCase = true) -> 9
        else -> 5
    }
}

fun buildUiCameraCapabilitySummary(characteristics: CameraCharacteristics): String {
    return try {
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return "Camera2 capability map unavailable"

        val highSpeedSizes = streamMap.highSpeedVideoSizes
            .sortedWith(compareBy<Size> { it.width * it.height }.thenBy { it.width })
            .joinToString(" / ") { "${it.width}x${it.height}" }
            .ifBlank { "No high-speed sizes" }

        val common720 = Size(1280, 720)
        val common1080 = Size(1920, 1080)

        val hs720 = safeHighSpeedRanges(streamMap, common720)
        val hs1080 = safeHighSpeedRanges(streamMap, common1080)

        "HS: $highSpeedSizes | 720p: $hs720 | 1080p: $hs1080"
    } catch (exception: Exception) {
        "Camera2 capability check failed"
    }
}

fun safeHighSpeedRanges(streamMap: android.hardware.camera2.params.StreamConfigurationMap, size: Size): String {
    return try {
        streamMap.getHighSpeedVideoFpsRangesFor(size)
            .joinToString("/") { "${it.lower}-${it.upper}" }
            .ifBlank { "none" }
    } catch (_: Exception) {
        "none"
    }
}

fun getCameraOutputSizeKeys(characteristics: CameraCharacteristics): Set<String> {
    return try {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptySet()

        val mediaCodecSizes = streamMap.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
        val mediaRecorderSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            streamMap.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            streamMap.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        }

        (mediaCodecSizes + mediaRecorderSizes)
            .map { "${it.width}x${it.height}" }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

fun getCameraHighSpeedSizeKeys(characteristics: CameraCharacteristics): Set<String> {
    return try {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptySet()

        streamMap.highSpeedVideoSizes
            .map { "${it.width}x${it.height}" }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

fun availableProfilesForCamera(cameraOption: LensCameraOption?): List<LensStreamProfile> {
    if (cameraOption == null) {
        return LensStreamProfile.entries
    }

    return LensStreamProfile.entries.filter { profile ->
        profileSupportStatus(profile, cameraOption).usable
    }
}

data class ProfileSupportStatus(
    val usable: Boolean,
    val label: String,
    val reason: String
)

fun profileSupportStatus(
    profile: LensStreamProfile,
    cameraOption: LensCameraOption?
): ProfileSupportStatus {
    if (cameraOption == null) {
        return ProfileSupportStatus(usable = true, label = "Detecting lens", reason = "Waiting for camera capabilities")
    }

    val sizeKey = "${profile.width}x${profile.height}"
    val cameraLabel = cameraOption.displayName
    val hasOutputSize = cameraOption.outputSizeKeys.contains(sizeKey)
    val hasHighSpeedSize = cameraOption.highSpeedSizeKeys.contains(sizeKey)

    if (!hasOutputSize) {
        return ProfileSupportStatus(
            usable = false,
            label = "Unavailable on this lens",
            reason = "$cameraLabel does not expose $sizeKey as a camera output."
        )
    }

    if (profile.fps >= 60 && !hasHighSpeedSize) {
        return ProfileSupportStatus(
            usable = false,
            label = "60 FPS unavailable",
            reason = "$cameraLabel does not expose $sizeKey as a Camera2 high-speed mode."
        )
    }

    return ProfileSupportStatus(
        usable = true,
        label = when {
            profile.width >= 3840 && profile.fps >= 60 -> "Experimental high-speed"
            profile.width >= 3840 -> "HQ"
            profile.fps >= 60 -> "Low latency"
            else -> "Stable"
        },
        reason = "Ready"
    )
}

fun isUltraWideLens(cameraOption: LensCameraOption?): Boolean {
    return cameraOption?.displayName?.contains("Ultra-wide", ignoreCase = true) == true
}

fun selectedViewLabel(cameraOption: LensCameraOption?, zoomPreset: ZoomPreset): String {
    val lens = cameraOption?.displayName ?: "Detecting camera..."
    val zoom = zoomDisplayLabel(cameraOption, zoomPreset)
    return if (zoomPreset == ZoomPreset.WIDE) lens else "$lens • $zoom"
}

fun zoomDisplayLabel(cameraOption: LensCameraOption?, zoomPreset: ZoomPreset): String {
    if (zoomPreset == ZoomPreset.WIDE && isUltraWideLens(cameraOption)) {
        return "No crop"
    }
    return "${zoomPreset.shortLabel} ${zoomPreset.label}"
}

fun lensButtonTitle(cameraOption: LensCameraOption): String {
    return when {
        cameraOption.displayName.contains("Ultra-wide / 1x", ignoreCase = true) -> "0.6x / 1x Auto Lens"
        cameraOption.displayName.contains("Ultra-wide", ignoreCase = true) -> "0.6x Ultra-wide"
        cameraOption.displayName.contains("Wide", ignoreCase = true) -> "1x Wide"
        cameraOption.displayName.contains("Tele", ignoreCase = true) -> cameraOption.displayName
        cameraOption.displayName.contains("Front", ignoreCase = true) -> "Front Camera"
        else -> cameraOption.displayName
    }
}

fun lensButtonSubtitle(cameraOption: LensCameraOption): String {
    val cleanDescription = cameraOption.lensDescription
    return when {
        cameraOption.displayName.contains("Ultra-wide", ignoreCase = true) -> "Widest view available • ${cameraOption.zoomRangeLabel}"
        cameraOption.displayName.contains("Wide", ignoreCase = true) -> "Best default view • ${cameraOption.zoomRangeLabel}"
        cameraOption.displayName.contains("Tele", ignoreCase = true) -> "Closer physical lens • ${cameraOption.zoomRangeLabel}"
        else -> cleanDescription
    }
}

fun availableZoomPresets(cameraOption: LensCameraOption?): List<ZoomPreset> {
    if (cameraOption == null) {
        return ZoomPreset.entries
    }

    return ZoomPreset.entries.filter { preset ->
        preset.ratio >= cameraOption.minZoomRatio - 0.03f &&
                preset.ratio <= cameraOption.maxZoomRatio + 0.03f
    }.ifEmpty {
        listOf(ZoomPreset.WIDE)
    }
}

fun getBestLocalIpAddress(context: Context): String {
    val connectivityIp = getIpFromConnectivityManager(context)

    if (connectivityIp != null) {
        Log.d("GHXSTLens", "Using ConnectivityManager IP: $connectivityIp")
        return connectivityIp
    }

    val networkInterfaceIp = getIpFromNetworkInterfaces()

    if (networkInterfaceIp != null) {
        Log.d("GHXSTLens", "Using NetworkInterface IP: $networkInterfaceIp")
        return networkInterfaceIp
    }

    Log.w("GHXSTLens", "No usable IPv4 address found")
    return "Unavailable"
}

fun getIpFromConnectivityManager(context: Context): String? {
    return try {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null

        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null

        val foundIps = linkProperties.linkAddresses
            .mapNotNull { linkAddress ->
                val address = linkAddress.address

                if (address is Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress
                } else {
                    null
                }
            }

        foundIps.forEach { ip ->
            Log.d("GHXSTLens", "ConnectivityManager found IPv4: $ip")
        }

        foundIps.firstOrNull { ip -> isPrivateLanIp(ip) }
            ?: foundIps.firstOrNull()
    } catch (exception: Exception) {
        Log.e("GHXSTLens", "ConnectivityManager IP lookup failed", exception)
        null
    }
}

fun getIpFromNetworkInterfaces(): String? {
    return try {
        val networkInterfaces = NetworkInterface.getNetworkInterfaces()

        if (networkInterfaces == null) {
            Log.w("GHXSTLens", "NetworkInterface.getNetworkInterfaces() returned null")
            return null
        }

        val interfaces = Collections.list(networkInterfaces)
        val foundIps = mutableListOf<String>()

        for (networkInterface in interfaces) {
            try {
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val addressesEnumeration = networkInterface.inetAddresses ?: continue
                val addresses = Collections.list(addressesEnumeration)

                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        val interfaceName = networkInterface.name.lowercase()

                        Log.d(
                            "GHXSTLens",
                            "NetworkInterface found IPv4: $ip on interface $interfaceName"
                        )

                        foundIps.add(ip)
                    }
                }
            } catch (interfaceException: Exception) {
                Log.w(
                    "GHXSTLens",
                    "Skipped network interface: ${networkInterface.name}",
                    interfaceException
                )
            }
        }

        foundIps.firstOrNull { ip -> isPrivateLanIp(ip) }
            ?: foundIps.firstOrNull()
    } catch (exception: Exception) {
        Log.e("GHXSTLens", "NetworkInterface IP lookup failed", exception)
        null
    }
}

fun isPrivateLanIp(ip: String): Boolean {
    return ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            ip.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
}