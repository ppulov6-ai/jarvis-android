package io.clawdroid.assistant

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.ktor.client.HttpClient
import io.clawdroid.PermissionRequestActivity
import io.clawdroid.backend.api.R
import io.clawdroid.core.data.remote.WebSocketClient
import io.clawdroid.core.data.repository.AssistantConnectionImpl
import io.clawdroid.core.domain.repository.AssistantConnection
import io.clawdroid.core.domain.repository.TtsSettingsRepository
import io.clawdroid.core.ui.theme.ClawDroidTheme
import io.clawdroid.feature.chat.assistant.AssistantManager
import io.clawdroid.feature.chat.assistant.CosmosOrb
import androidx.compose.runtime.LaunchedEffect
import io.clawdroid.feature.chat.voice.CameraCaptureManager
import io.clawdroid.feature.chat.voice.ScreenCaptureManager
import io.clawdroid.feature.chat.voice.ScreenshotSource
import io.clawdroid.feature.chat.voice.SpeechRecognizerWrapper
import io.clawdroid.feature.chat.voice.TextToSpeechWrapper
import io.clawdroid.receiver.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AssistantService : LifecycleService(), SavedStateRegistryOwner {

    private val gatewaySettings: io.clawdroid.backend.api.GatewaySettingsStore by inject()
    private val httpClient: HttpClient by inject()
    private val ttsSettingsRepo: TtsSettingsRepository by inject()
    private val screenshotSource: ScreenshotSource by inject()
    private val deviceController: DeviceController by inject()

    private lateinit var serviceScope: CoroutineScope
    private lateinit var connection: AssistantConnection
    private lateinit var assistantManager: AssistantManager
    private lateinit var toolRequestHandler: ToolRequestHandler
    private lateinit var ttsWrapper: TextToSpeechWrapper
    private lateinit var sttWrapper: SpeechRecognizerWrapper
    private lateinit var cameraCaptureManager: CameraCaptureManager
    private lateinit var screenCaptureManager: ScreenCaptureManager

    private var showAccessibilityGuide by mutableStateOf(false)
    private var orbExpanded by mutableStateOf(false)
    private var overlayView: View? = null
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val permission = intent.getStringExtra(PermissionRequestActivity.EXTRA_PERMISSION)
            val granted = intent.getBooleanExtra(PermissionRequestActivity.EXTRA_GRANTED, false)
            if (permission == Manifest.permission.CAMERA && granted) {
                startForeground(NOTIFICATION_ID, buildNotification(), computeForegroundTypes())
                assistantManager.toggleCamera()
            }
        }
    }

    override fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()

        io.clawdroid.diagnostics.DiagnosticEvents.initialize(applicationContext)
        io.clawdroid.diagnostics.DiagnosticEvents.record("assistant", "started")
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        connection = AssistantConnectionImpl(httpClient, applicationContext,
            apiKeyProvider = { gatewaySettings.settings.value.apiKey })

        toolRequestHandler = ToolRequestHandler(
            context = applicationContext,
            deviceController = deviceController,
            screenshotSource = screenshotSource,
            setOverlayVisibility = { visible -> setOverlayVisible(visible) },
            onAccessibilityNeeded = { showAccessibilityGuide = true },
            onStop = { shutdown() }
        )
        (connection as AssistantConnectionImpl).onToolRequest = { request ->
            val response = toolRequestHandler.handle(request)
            if (response.success) {
                response.result ?: ""
            } else {
                "error: ${response.error ?: "Неизвестная ошибка"}"
            }
        }
        (connection as AssistantConnectionImpl).onExit = { farewell ->
            handleExitCommand(farewell)
        }

        sttWrapper = SpeechRecognizerWrapper(this)
        ttsWrapper = TextToSpeechWrapper(this, ttsSettingsRepo.ttsConfig)
        cameraCaptureManager = CameraCaptureManager(this)
        screenCaptureManager = ScreenCaptureManager(screenshotSource, applicationContext) { visible ->
            setOverlayVisible(visible)
        }

        assistantManager = AssistantManager(
            sttWrapper = sttWrapper,
            ttsWrapper = ttsWrapper,
            connection = connection,
            cameraCaptureManager = cameraCaptureManager,
            screenCaptureManager = screenCaptureManager,
            contentResolver = contentResolver
        )

        ContextCompat.registerReceiver(
            this,
            permissionReceiver,
            IntentFilter(PermissionRequestActivity.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            computeForegroundTypes()
        )

        // Resolve wsUrl from the main WebSocketClient
        val mainWsClient: WebSocketClient by inject()
        connection.connect(mainWsClient.wsUrl)

        addOverlay()
        assistantManager.start(serviceScope)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        removeOverlay()
        assistantManager.destroy()
        ttsWrapper.destroy()
        connection.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun handleCameraToggle() {
        if (assistantManager.state.value.isCameraActive) {
            assistantManager.toggleCamera()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startForeground(NOTIFICATION_ID, buildNotification(), computeForegroundTypes())
            assistantManager.toggleCamera()
        } else {
            startActivity(PermissionRequestActivity.intent(this, Manifest.permission.CAMERA))
        }
    }

    private fun handleScreenCaptureToggle() {
        if (assistantManager.state.value.isScreenCaptureActive) {
            assistantManager.toggleScreenCapture()
            return
        }
        if (screenCaptureManager.isAvailable) {
            // Turn off camera first if active
            if (assistantManager.state.value.isCameraActive) {
                assistantManager.toggleCamera()
            }
            assistantManager.toggleScreenCapture()
        } else {
            showAccessibilityGuide = true
        }
    }

    private fun shutdown() {
        io.clawdroid.diagnostics.DiagnosticEvents.record("assistant", "stopped")
        if (::assistantManager.isInitialized) assistantManager.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleExitCommand(farewell: String?) {
        if (!farewell.isNullOrBlank()) {
            serviceScope.launch {
                ttsWrapper.speak(farewell)
                shutdown()
            }
        } else {
            shutdown()
        }
    }

    private fun updateOrbLayout(dx: Float = 0f, dy: Float = 0f) {
        val view = overlayView ?: return
        val lp = view.layoutParams as WindowManager.LayoutParams
        val density = resources.displayMetrics.density
        val expanded = orbExpanded || showAccessibilityGuide
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val bounds = metrics.bounds
        val availableWidth = (bounds.width() - insets.left - insets.right).coerceAtLeast(1)
        val size = fitOrbSize(((if (expanded) 320 else 96) * density).toInt(),
            ((if (expanded) 480 else 118) * density).toInt(), availableWidth,
            bounds.height() - insets.top - insets.bottom)
        lp.width = size.width
        lp.height = size.height
        val position = clampOrbPosition(lp.x + dx.toInt() - insets.left, lp.y + dy.toInt(), lp.width, lp.height,
            availableWidth, bounds.height(), insets.top, insets.bottom)
        lp.x = position.x + insets.left
        lp.y = position.y
        windowManager.updateViewLayout(view, lp)
    }

    private fun setOverlayVisible(visible: Boolean) {
        val view = overlayView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (visible) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            view.visibility = View.VISIBLE
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            view.visibility = View.INVISIBLE
        }
        windowManager.updateViewLayout(view, lp)
    }

    private fun addOverlay() {
        if (overlayView != null) return

        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (96 * density).toInt(),
            (118 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = windowManager.currentWindowMetrics.bounds.width() - width - (12 * density).toInt()
            y = (windowManager.currentWindowMetrics.bounds.height() * .6f).toInt()
        }
        val wrapper = FrameLayout(this@AssistantService)

        wrapper.setViewTreeLifecycleOwner(this)
        wrapper.setViewTreeSavedStateRegistryOwner(this)

        ComposeView(this).apply {
            setContent {
                ClawDroidTheme {
                    val state by assistantManager.state.collectAsState()
                    LaunchedEffect(orbExpanded, showAccessibilityGuide) { updateOrbLayout() }
                    Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.TopCenter) {
                        CosmosOrb(
                            state = state,
                            expanded = orbExpanded,
                            cameraCaptureManager = cameraCaptureManager,
                            onExpand = {
                                if (orbExpanded && state.isCameraActive) handleCameraToggle()
                                orbExpanded = !orbExpanded
                            },
                            onDrag = { dx, dy -> updateOrbLayout(dx, dy) },
                            onStop = { shutdown() },
                            onPause = { assistantManager.toggleListeningPause() },
                            onInterrupt = { assistantManager.interrupt() },
                            onCamera = { handleCameraToggle() },
                            onScreen = { handleScreenCaptureToggle() }
                        )
                    }

                    if (showAccessibilityGuide) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { showAccessibilityGuide = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.extraLarge,
                                tonalElevation = 6.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {}
                            ) {
                                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                    Text(
                                        text = getString(io.clawdroid.R.string.assistant_accessibility_title),
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                    Text(
                                        text = getString(io.clawdroid.R.string.assistant_accessibility_body),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 16.dp)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp)
                                    ) {
                                        TextButton(onClick = { showAccessibilityGuide = false }) {
                                            Text(getString(io.clawdroid.R.string.action_cancel))
                                        }
                                        TextButton(onClick = {
                                            showAccessibilityGuide = false
                                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                android.net.Uri.parse("package:$packageName"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }) {
                                            Text("О приложении")
                                        }
                                        TextButton(onClick = {
                                            showAccessibilityGuide = false
                                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            startActivity(intent)
                                        }) {
                                            Text(getString(io.clawdroid.R.string.assistant_accessibility_open_settings))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            wrapper.addView(this, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        windowManager.addView(wrapper, params)
        overlayView = wrapper
        updateOrbLayout()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrbLayout()
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    @android.annotation.SuppressLint("InlinedApi")
    private fun computeForegroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return types
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.ASSISTANT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Джарвис")
            .setContentText(getString(io.clawdroid.R.string.assistant_notification_listening))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
    }
}
