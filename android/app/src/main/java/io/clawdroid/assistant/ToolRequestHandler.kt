package io.clawdroid.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import androidx.core.net.toUri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.clawdroid.core.data.remote.dto.ToolRequest
import io.clawdroid.core.data.remote.dto.ToolResponse
import io.clawdroid.feature.chat.voice.ScreenshotSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import io.clawdroid.assistant.actions.*
import java.io.ByteArrayOutputStream

class ToolRequestHandler(
    private val context: Context,
    private val deviceController: DeviceController,
    private val screenshotSource: ScreenshotSource,
    private val setOverlayVisibility: (Boolean) -> Unit,
    private val onAccessibilityNeeded: () -> Unit,
    private val onStop: () -> Unit = {},
    private val observations: UiObservationRegistry = UiObservationRegistry()
) {

    private val operationMutex = Mutex()

    private val actionHandlers: List<ActionHandler> = listOf(
        AlarmActionHandler(),
        CalendarActionHandler(),
        ContactsActionHandler(),
        CommunicationActionHandler(),
        MediaActionHandler(),
        NavigationActionHandler(),
        DeviceControlActionHandler(),
        SettingsActionHandler(),
        WebActionHandler(),
        ClipboardActionHandler(),
    )

    private val handlerMap: Map<String, ActionHandler> = actionHandlers
        .flatMap { handler -> handler.supportedActions.map { it to handler } }
        .toMap()

    private val permissionRequester = PermissionRequester(context)

    suspend fun handle(request: ToolRequest): ToolResponse {
        val category = when (request.action) {
            "screenshot" -> "screenshot"
            "tap", "swipe", "text", "keyevent", "get_ui_tree" -> "ui_${request.action}"
            else -> "other"
        }
        val started = android.os.SystemClock.elapsedRealtime()
        io.clawdroid.diagnostics.DiagnosticEvents.record("tool", "${category}_started")
        return try {
            val result = operationMutex.withLock { handleInternal(request) }
            io.clawdroid.diagnostics.DiagnosticEvents.record("tool", "${category}_${if (result.success) "success" else "error"}", durationMs = (android.os.SystemClock.elapsedRealtime() - started).coerceIn(0, 86400000))
            result
        } catch (error: CancellationException) {
            io.clawdroid.diagnostics.DiagnosticEvents.record("tool", "${category}_cancelled", durationMs = (android.os.SystemClock.elapsedRealtime() - started).coerceIn(0, 86400000))
            throw error
        }
    }

    private suspend fun handleInternal(request: ToolRequest): ToolResponse {
        return try {
            currentCoroutineContext().ensureActive()
            if (request.action !in setOf("get_ui_tree", "screenshot", "tap", "swipe", "text", "search_apps", "app_info", "search_contacts", "get_contact_detail")) observations.clear()
            val guarded = request.action in setOf("tap", "swipe", "text", "keyevent")
            if (guarded) requireAccessibility(request)?.let { return it }
            val approvedScreen = if (guarded) withOverlayHidden {
                deviceController.captureApprovalScreen()
            } else null
            if (guarded && approvedScreen == null) {
                return ToolResponse(request.requestId, false, error = "Не удалось безопасно зафиксировать экран приложения. Действие отменено")
            }
            if (ActionSafetyPolicy.requiresConfirmation(request.action)) {
                val approved = try {
                    withContext(Dispatchers.Main) { setOverlayVisibility(false) }
                    ActionConfirmation.ask(context, request, onStop, approvedScreen?.description)
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) { setOverlayVisibility(true) }
                }
                currentCoroutineContext().ensureActive()
                if (approved && guarded) {
                    val restored = withOverlayHidden {
                        withTimeoutOrNull(2_000) {
                            while (!ScreenApprovalGuard.matches(approvedScreen, deviceController.captureApprovalScreen())) delay(50)
                            true
                        } == true
                    }
                    if (!restored) return changedScreen(request)
                }
                if (!approved) return ToolResponse(request.requestId, false, error = "Действие отменено: подтверждение не получено")
            }
            when (request.action) {
                // Core actions handled directly
                "search_apps" -> handleSearchApps(request)
                "app_info" -> handleAppInfo(request)
                "launch_app" -> handleLaunchApp(request)
                "screenshot" -> handleScreenshot(request)
                "get_ui_tree" -> handleGetUiTree(request)
                "tap" -> handleTap(request, approvedScreen)
                "swipe" -> handleSwipe(request, approvedScreen)
                "text" -> handleText(request, approvedScreen)
                "keyevent" -> handleKeyEvent(request, approvedScreen)
                "broadcast" -> handleBroadcast(request)
                "intent" -> handleIntent(request)
                // Delegate to category handlers
                else -> {
                    val handler = handlerMap[request.action]
                        ?: return ToolResponse(
                            requestId = request.requestId,
                            success = false,
                            error = "Unknown action: ${request.action}"
                        )
                    ensurePermissions(request, handler)?.let { return it }
                    currentCoroutineContext().ensureActive()
                    handler.handle(request, context)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка выполнения действия")
            ToolResponse(
                requestId = request.requestId,
                success = false,
                error = "Не удалось выполнить действие"
            )
        }
    }

    private suspend fun ensurePermissions(
        request: ToolRequest,
        handler: ActionHandler
    ): ToolResponse? {
        val requirements = handler.requiredPermissions(request.action)
        if (requirements.isEmpty()) return null

        for (req in requirements) {
            when (req) {
                is PermissionRequirement.Runtime -> {
                    val granted = permissionRequester.request(req.permission)
                    if (!granted) {
                        return ToolResponse(
                            requestId = request.requestId,
                            success = false,
                            error = "Permission denied: ${req.description}. Please grant the permission and try again."
                        )
                    }
                }
                is PermissionRequirement.Special -> {
                    if (!req.check(context)) {
                        val intent = req.settingsIntent
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return ToolResponse(
                            requestId = request.requestId,
                            success = false,
                            error = "${req.description} is not granted. Settings screen has been opened. Please grant the permission and try again."
                        )
                    }
                }
            }
        }
        return null
    }

    private fun requireAccessibility(request: ToolRequest): ToolResponse? {
        if (!deviceController.isAvailable) {
            onAccessibilityNeeded()
            return ToolResponse(
                requestId = request.requestId,
                success = false,
                error = "accessibility_required"
            )
        }
        return null
    }

    private suspend fun <T> withOverlayHidden(block: suspend () -> T): T {
        return try {
            withContext(Dispatchers.Main) { setOverlayVisibility(false) }
            delay(150)
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { setOverlayVisibility(true) }
        }
    }

    private fun handleSearchApps(request: ToolRequest): ToolResponse {
        val query = request.params?.get("query")?.jsonPrimitive?.contentOrNull
            ?: return ToolResponse(request.requestId, false, error = "query required")

        val pm = context.packageManager
        val q = query.lowercase()
        val matches = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                val label = pm.getApplicationLabel(app).toString().lowercase()
                label.contains(q) || app.packageName.lowercase().contains(q)
            }
            .map { app ->
                val label = pm.getApplicationLabel(app).toString()
                val launchable = pm.getLaunchIntentForPackage(app.packageName) != null
                val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                buildString {
                    append("$label (${app.packageName})")
                    if (launchable) append(" [launchable]")
                    if (isSystem) append(" [system]")
                }
            }
            .sorted()

        return if (matches.isEmpty()) {
            ToolResponse(request.requestId, true, result = "No apps found matching \"$query\"")
        } else {
            ToolResponse(
                requestId = request.requestId,
                success = true,
                result = "Found ${matches.size} app(s) matching \"$query\":\n${matches.joinToString("\n")}"
            )
        }
    }

    private fun handleAppInfo(request: ToolRequest): ToolResponse {
        val packageName = request.params?.get("package_name")?.jsonPrimitive?.contentOrNull
            ?: return ToolResponse(request.requestId, false, error = "package_name required")

        val pm = context.packageManager
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val appInfo = info.applicationInfo
            val label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName
            val isSystem = appInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0

            val sb = StringBuilder()
            sb.appendLine("App: $label")
            sb.appendLine("Package: $packageName")
            sb.appendLine("Version: ${info.versionName ?: "unknown"}")
            sb.appendLine("System app: $isSystem")
            sb.appendLine("Version code: ${info.longVersionCode}")

            ToolResponse(request.requestId, true, result = sb.toString())
        } catch (e: PackageManager.NameNotFoundException) {
            ToolResponse(request.requestId, false, error = "Package not found: $packageName")
        }
    }

    private fun handleLaunchApp(request: ToolRequest): ToolResponse {
        val packageName = request.params?.get("package_name")?.jsonPrimitive?.contentOrNull
            ?: return ToolResponse(request.requestId, false, error = "package_name required")

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResponse(request.requestId, false, error = "No launch intent for $packageName")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ToolResponse(request.requestId, true, result = "Launched $packageName")
    }

    private suspend fun handleScreenshot(request: ToolRequest): ToolResponse {
        requireAccessibility(request)?.let { return it }

        return withOverlayHidden {
            val bitmap = screenshotSource.takeScreenshot()
                ?: return@withOverlayHidden ToolResponse(request.requestId, false, error = "Screenshot capture failed")
            try {
                val base64 = withContext(Dispatchers.IO) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                }
                ToolResponse(request.requestId, true, result = base64)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun handleGetUiTree(request: ToolRequest): ToolResponse {
        requireAccessibility(request)?.let { return it }

        val resourceId = request.params?.get("resource_id")?.jsonPrimitive?.contentOrNull
        val index = request.params?.get("index")?.jsonPrimitive?.intOrNull ?: 0
        val boundsX = request.params?.get("bounds_x")?.jsonPrimitive?.doubleOrNull
        val boundsY = request.params?.get("bounds_y")?.jsonPrimitive?.doubleOrNull
        val maxDepth = request.params?.get("max_depth")?.jsonPrimitive?.intOrNull?.coerceIn(0, 20) ?: 15
        val maxNodes = request.params?.get("max_nodes")?.jsonPrimitive?.intOrNull?.coerceIn(1, 300) ?: 300

        return withOverlayHidden {
            observations.clear()
            val before = deviceController.captureApprovalScreen()
                ?: return@withOverlayHidden ToolResponse(request.requestId, false, error = "Не удалось прочитать стабильный экран приложения. Остановитесь и сообщите об этом пользователю по-русски.")
            val root = deviceController.getRootNode()
                ?: return@withOverlayHidden ToolResponse(request.requestId, false, error = "Не удалось получить элементы экрана")
            val startNode = resolveStartNode(root, resourceId, index, boundsX, boundsY)
                ?: return@withOverlayHidden ToolResponse(request.requestId, false, error = buildString {
                    if (resourceId != null) append("No node found with resource_id=$resourceId (index=$index)")
                    else append("No node found at bounds ($boundsX, $boundsY)")
                })
            val sb = StringBuilder()
            val nodeCount = intArrayOf(0)
            val paths = mutableSetOf<String>()
            val startPath = findNodePath(root, startNode)
                ?: return@withOverlayHidden ToolResponse(request.requestId, false, error = "Выбранный элемент уже изменился. Получите новое дерево экрана.")
            dumpNode(startNode, sb, 0, maxDepth, maxNodes, nodeCount, startPath, paths)
            if (nodeCount[0] >= maxNodes) {
                sb.appendLine("[truncated: max_nodes=$maxNodes reached]")
            }
            if (!ScreenApprovalGuard.matches(before, deviceController.captureApprovalScreen())) return@withOverlayHidden changedScreen(request)
            val observationId = observations.record(before, paths)
            val size = deviceController.screenSize()
            ToolResponse(request.requestId, true, result = "observation_id=$observationId\nРазмер полного экрана: ${size?.first}x${size?.second}; координаты в физических пикселях. Используйте node_id. После действия получите новое дерево.\n$sb")
        }
    }

    private fun resolveStartNode(
        root: AccessibilityNodeInfo,
        resourceId: String?,
        index: Int,
        boundsX: Double?,
        boundsY: Double?
    ): AccessibilityNodeInfo? {
        if (resourceId != null) {
            val matches = root.findAccessibilityNodeInfosByViewId(resourceId)
            if (matches.isNullOrEmpty()) return null
            return matches.getOrNull(index)
        }
        if (boundsX != null && boundsY != null) {
            return findNodeAtPoint(root, boundsX.toInt(), boundsY.toInt())
        }
        return root
    }

    private fun findNodeAtPoint(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        // Find the deepest (smallest) child that contains the point
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeAtPoint(child, x, y)
            if (found != null) return found
        }
        return node
    }

    private fun findNodePath(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo): String? {
        var count = 0
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int): String? {
            if (++count > 2000 || depth > 49) return null
            if (node == target) return path
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, "$path.$i", depth + 1)?.let { return it }
            }
            return null
        }
        return visit(root, "0", 0)
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        nodeCount: IntArray,
        path: String,
        paths: MutableSet<String>
    ) {
        if (nodeCount[0] >= maxNodes) return
        // Skip invisible nodes
        if (!node.isVisibleToUser) return
        nodeCount[0]++
        val indent = "  ".repeat(depth)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Strip common class name prefixes
        val className = node.className?.toString() ?: "View"
        val shortClass = className
            .removePrefix("android.widget.")
            .removePrefix("android.view.")

        paths.add(path)
        sb.append("${indent}[${shortClass}] node_id=$path")

        // Only output non-empty fields
        if (node.isPassword) {
            sb.append(" [защищённое поле]")
        } else {
            node.text?.takeIf { it.isNotEmpty() }?.let { sb.append(" text=${it.take(1000)}") }
            node.contentDescription?.takeIf { it.isNotEmpty() }?.let { sb.append(" desc=${it.take(1000)}") }
        }
        sb.append(" bounds=$bounds")
        // Only output non-default values: clickable=true (default is false), enabled=false (default is true)
        if (node.isClickable) sb.append(" clickable")
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) sb.append(" supports_click")
        if (node.isEditable) sb.append(" editable")
        if (node.isFocused) sb.append(" focused")
        if (!node.isPassword && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) sb.append(" supports_set_text")
        if (!node.isEnabled) sb.append(" enabled=false")
        node.viewIdResourceName?.let { sb.append(" id=$it") }

        sb.appendLine()

        if (depth >= maxDepth) {
            if (node.childCount > 0) {
                sb.appendLine("${indent}  [truncated: ${node.childCount} children at depth $depth]")
            }
            return
        }
        for (i in 0 until node.childCount) {
            if (nodeCount[0] >= maxNodes) return
            val child = node.getChild(i) ?: continue
            dumpNode(child, sb, depth + 1, maxDepth, maxNodes, nodeCount, "$path.$i", paths)
        }
    }

    private fun observationRejected(request: ToolRequest): ToolResponse {
        io.clawdroid.diagnostics.DiagnosticEvents.record("tool", "ui_observation_rejected")
        return ToolResponse(request.requestId, false, error = "Наблюдение экрана отсутствует, устарело или элемент изменился. Получите get_ui_tree и используйте его observation_id и node_id. Не угадывайте координаты.")
    }

    private fun consumeObservation(request: ToolRequest, screen: ApprovedScreen?, path: String? = null): Boolean =
        observations.consume(request.params?.get("observation_id")?.jsonPrimitive?.contentOrNull, screen, path)

    private suspend fun handleTap(request: ToolRequest, approvedScreen: ApprovedScreen?): ToolResponse {
        requireAccessibility(request)?.let { return it }
        val path = request.params?.get("node_id")?.jsonPrimitive?.contentOrNull
        val x = request.params?.get("x")?.jsonPrimitive?.doubleOrNull?.toFloat()
        val y = request.params?.get("y")?.jsonPrimitive?.doubleOrNull?.toFloat()
        if (request.params?.containsKey("x2") == true || request.params?.containsKey("y2") == true)
            return ToolResponse(request.requestId, false, error = "Нажатие не принимает координаты конца жеста")
        if ((path != null && (request.params?.containsKey("x") == true || request.params?.containsKey("y") == true)) || (path == null && (x == null || y == null)))
            return ToolResponse(request.requestId, false, error = "Укажите node_id либо пару координат x,y свежего дерева экрана")
        return withOverlayHidden {
            val current = deviceController.captureApprovalScreen()
            if (!ScreenApprovalGuard.matches(approvedScreen, current)) return@withOverlayHidden changedScreen(request)
            if (!consumeObservation(request, current, path)) return@withOverlayHidden observationRejected(request)
            val success = if (path != null) {
                val node = deviceController.resolveNode(path, current!!.packageName)
                    ?: return@withOverlayHidden observationRejected(request)
                currentCoroutineContext().ensureActive()
                deviceController.clickNode(node, current.packageName)
            } else {
                if (!coordinatesInDisplay(x!!, y!!, deviceController.screenSize()))
                    return@withOverlayHidden ToolResponse(request.requestId, false, error = "Координаты за пределами полного экрана. Не выполняйте случайные нажатия.")
                currentCoroutineContext().ensureActive()
                deviceController.tap(x, y)
            }
            ToolResponse(request.requestId, success,
                result = if (success) "Android выполнил нажатие. Получите новое дерево и проверьте, что открыт нужный экран." else null,
                error = if (!success) "Android отклонил нажатие на выбранный элемент. Остановитесь; не нажимайте в других местах наугад." else null)
        }
    }

    private suspend fun handleSwipe(request: ToolRequest, approvedScreen: ApprovedScreen?): ToolResponse {
        requireAccessibility(request)?.let { return it }
        val x = request.params?.get("x")?.jsonPrimitive?.doubleOrNull?.toFloat()
        val y = request.params?.get("y")?.jsonPrimitive?.doubleOrNull?.toFloat()
        val x2 = request.params?.get("x2")?.jsonPrimitive?.doubleOrNull?.toFloat()
        val y2 = request.params?.get("y2")?.jsonPrimitive?.doubleOrNull?.toFloat()
        if (request.params?.containsKey("node_id") == true || (request.params?.containsKey("duration_ms") == true && request.params["duration_ms"]?.jsonPrimitive?.longOrNull == null))
            return ToolResponse(request.requestId, false, error = "Некорректные параметры жеста")
        val durationMs = request.params?.get("duration_ms")?.jsonPrimitive?.longOrNull ?: 300L
        if (x == null || y == null || x2 == null || y2 == null || durationMs !in 50..5000)
            return ToolResponse(request.requestId, false, error = "Нужны координаты жеста и длительность от 50 до 5000 мс")
        return withOverlayHidden {
            val current = deviceController.captureApprovalScreen()
            if (!ScreenApprovalGuard.matches(approvedScreen, current)) return@withOverlayHidden changedScreen(request)
            if (!consumeObservation(request, current)) return@withOverlayHidden observationRejected(request)
            val size = deviceController.screenSize()
            if (!coordinatesInDisplay(x, y, size) || !coordinatesInDisplay(x2, y2, size))
                return@withOverlayHidden ToolResponse(request.requestId, false, error = "Координаты жеста за пределами полного экрана")
            currentCoroutineContext().ensureActive()
            val success = deviceController.swipe(x, y, x2, y2, durationMs)
            ToolResponse(request.requestId, success, result = if (success) "Жест выполнен. Получите новое дерево экрана." else null,
                error = if (!success) "Android отклонил или прервал жест. Остановитесь и сообщите об ошибке по-русски." else null)
        }
    }

    private suspend fun handleText(request: ToolRequest, approvedScreen: ApprovedScreen?): ToolResponse {
        requireAccessibility(request)?.let { return it }
        val text = request.params?.get("text")?.jsonPrimitive?.contentOrNull
            ?: return ToolResponse(request.requestId, false, error = "Не указан текст для ввода")
        val path = request.params?.get("node_id")?.jsonPrimitive?.contentOrNull
        if (request.params?.containsKey("node_id") == true && path == null)
            return ToolResponse(request.requestId, false, error = "Некорректный идентификатор поля ввода")
        if (setOf("x", "y", "x2", "y2").any { request.params?.containsKey(it) == true })
            return ToolResponse(request.requestId, false, error = "Ввод текста выполняется по node_id поля, без координат")
        return withOverlayHidden {
            val current = deviceController.captureApprovalScreen()
            if (!ScreenApprovalGuard.matches(approvedScreen, current)) return@withOverlayHidden changedScreen(request)
            if (!consumeObservation(request, current, path)) return@withOverlayHidden observationRejected(request)
            currentCoroutineContext().ensureActive()
            when (deviceController.inputTextAt(text, current!!.packageName, path)) {
                DeviceController.TextOutcome.VERIFIED -> ToolResponse(request.requestId, true, result = "Текст введён в выбранное поле и проверен. Сообщение ещё не отправлено.")
                DeviceController.TextOutcome.REJECTED -> ToolResponse(request.requestId, false, error = "Выбранное поле недоступно для ввода. Найдите editable supports_set_text в новом дереве. Не нажимайте в других местах наугад.")
                DeviceController.TextOutcome.UNVERIFIED -> ToolResponse(request.requestId, false, error = "Команда ввода выполнена, но содержимое поля подтвердить не удалось. Не повторяйте ввод и не отправляйте сообщение; сообщите об этом пользователю по-русски.")
            }
        }
    }

    private suspend fun handleKeyEvent(request: ToolRequest, approvedScreen: ApprovedScreen?): ToolResponse {
        requireAccessibility(request)?.let { return it }
        val key = request.params?.get("key")?.jsonPrimitive?.contentOrNull
            ?: return ToolResponse(request.requestId, false, error = "key required")
        return withOverlayHidden {
            val success = ScreenApprovalGuard.execute(approvedScreen, deviceController.captureApprovalScreen()) {
                currentCoroutineContext().ensureActive()
                when (key) {
                    "back" -> deviceController.pressBack()
                    "home" -> deviceController.pressHome()
                    "recents" -> deviceController.pressRecents()
                    else -> return@withOverlayHidden ToolResponse(request.requestId, false, error = "Unknown key: $key")
                }
            } ?: return@withOverlayHidden changedScreen(request)
            ToolResponse(
                request.requestId, success,
                result = if (success) "Key pressed: $key" else null,
                error = if (!success) "Key event failed" else null
            )
        }
    }

    private fun changedScreen(request: ToolRequest): ToolResponse {
        observations.clear()
        io.clawdroid.diagnostics.DiagnosticEvents.record("tool", "ui_screen_changed")
        return ToolResponse(request.requestId, false, error = "Экран приложения изменился. Действие отменено; получите новое дерево экрана. Сохранённое разрешение повторно запрашивать не нужно.")
    }

    private fun handleBroadcast(request: ToolRequest): ToolResponse {
        val action = request.params?.get("intent_action")?.jsonPrimitive?.contentOrNull
            ?: return ToolResponse(request.requestId, false, error = "intent_action required")

        val intent = Intent(action)
        applyExtras(intent, request.params)
        context.sendBroadcast(intent)
        return ToolResponse(request.requestId, true, result = "Broadcast sent: $action")
    }

    private fun handleIntent(request: ToolRequest): ToolResponse {
        val action = request.params?.get("intent_action")?.jsonPrimitive?.contentOrNull
            ?: return ToolResponse(request.requestId, false, error = "intent_action required")

        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        request.params?.get("intent_data")?.jsonPrimitive?.contentOrNull?.let {
            intent.data = it.toUri()
        }
        request.params?.get("intent_package")?.jsonPrimitive?.contentOrNull?.let {
            intent.setPackage(it)
        }
        request.params?.get("intent_type")?.jsonPrimitive?.contentOrNull?.let {
            intent.type = it
        }
        applyExtras(intent, request.params)

        return try {
            context.startActivity(intent)
            ToolResponse(request.requestId, true, result = "Intent started: $action")
        } catch (e: Exception) {
            ToolResponse(request.requestId, false, error = "Failed to start intent: ${e.message}")
        }
    }

    private fun applyExtras(intent: Intent, params: JsonObject?) {
        val extras = params?.get("intent_extras") as? JsonObject ?: return
        for ((key, value) in extras) {
            val prim = value as? JsonPrimitive ?: continue
            when {
                prim.isString -> intent.putExtra(key, prim.content)
                prim.content.toBooleanStrictOrNull() != null ->
                    intent.putExtra(key, prim.content.toBooleanStrict())
                prim.longOrNull != null -> intent.putExtra(key, prim.longOrNull!!)
                prim.doubleOrNull != null -> intent.putExtra(key, prim.doubleOrNull!!)
            }
        }
    }

    companion object {
        private const val TAG = "ToolRequestHandler"
    }
}
