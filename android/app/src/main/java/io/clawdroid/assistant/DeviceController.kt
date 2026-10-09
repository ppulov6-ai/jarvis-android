package io.clawdroid.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import java.security.MessageDigest
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume

class DeviceController {

    @Volatile
    private var service: AccessibilityService? = null

    val isAvailable: Boolean get() = service != null

    fun setService(s: AccessibilityService) {
        service = s
    }

    fun clearService() {
        service = null
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val svc = service ?: return false
        if (!coordinatesInDisplay(x, y, screenSize())) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, gesture)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val svc = service ?: return false
        if (!coordinatesInDisplay(x1, y1, screenSize()) || !coordinatesInDisplay(x2, y2, screenSize()) || durationMs !in 50..5000) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, gesture)
    }

    fun pressBack(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    }

    fun pressHome(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    }

    fun pressRecents(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
    }

    fun getRootNode(): AccessibilityNodeInfo? {
        return service?.rootInActiveWindow
    }

    /** A bounded, complete fingerprint of the external foreground screen. */
    fun captureApprovalScreen(): ApprovedScreen? {
        val svc = service ?: return null
        val root = svc.rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString() ?: return null
        if (packageName == svc.packageName) return null
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        var complete = true
        fun add(value: String) {
            if (value.length > 4096) { complete = false; return }
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++count > 300 || depth > 15) { complete = false; return }
            if (!node.isVisibleToUser) return
            if (node.isPassword) { complete = false; return }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            add(node.packageName?.toString().orEmpty())
            add(node.className?.toString().orEmpty())
            add(node.viewIdResourceName.orEmpty())
            add(node.text?.toString().orEmpty())
            add(node.contentDescription?.toString().orEmpty())
            add(bounds.toString())
            add("${node.isEnabled}:${node.isClickable}:${node.isEditable}:${node.isFocused}:${node.isSelected}:${node.isChecked}:${node.childCount}")
            for (index in 0 until node.childCount) {
                if (!complete) return
                val child = node.getChild(index) ?: run { complete = false; return }
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
        if (!complete || count == 0) return null
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        return ApprovedScreen(packageName, fingerprint, count)
    }

    fun screenSize(): Pair<Int, Int>? = service?.resources?.displayMetrics?.let { it.widthPixels to it.heightPixels }

    fun resolveNode(path: String, expectedPackage: String): AccessibilityNodeInfo? {
        if (!Regex("0(?:\\.[0-9]{1,4}){0,49}").matches(path)) return null
        var node = getRootNode() ?: return null
        if (node.packageName?.toString() != expectedPackage) return null
        for (part in path.split('.').drop(1)) node = node.getChild(part.toInt()) ?: return null
        return node.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword && it.packageName?.toString() == expectedPackage }
    }

    fun clickNode(node: AccessibilityNodeInfo, expectedPackage: String): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return false
            if (!candidate.isVisibleToUser || !candidate.isEnabled || candidate.isPassword || candidate.packageName?.toString() != expectedPackage) return false
            if (candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (candidate.isEditable && candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_FOCUS }) {
                return candidate.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            current = candidate.parent
        }
        return false
    }

    enum class TextOutcome { VERIFIED, REJECTED, UNVERIFIED }
    suspend fun inputTextAt(text: String, expectedPackage: String, path: String?): TextOutcome {
        val svc = service ?: return TextOutcome.REJECTED
        val root = svc.rootInActiveWindow ?: return TextOutcome.REJECTED
        val node = if (path != null) resolveNode(path, expectedPackage) else findInputField(root, svc.packageName, expectedPackage)
        return writeText(node, text, expectedPackage)
    }
    internal suspend fun writeText(node: AccessibilityNodeInfo?, text: String, expectedPackage: String): TextOutcome {
        if (node == null || !node.isVisibleToUser || !node.isEnabled || !node.isEditable || node.isPassword ||
            node.packageName?.toString() != expectedPackage || node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return TextOutcome.REJECTED
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        currentCoroutineContext().ensureActive()
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return TextOutcome.REJECTED
        repeat(10) {
            currentCoroutineContext().ensureActive()
            if (node.refresh() && node.text?.toString() == text) return TextOutcome.VERIFIED
            delay(100)
        }
        // The action may have succeeded: do not type a second time blindly.
        return TextOutcome.UNVERIFIED
    }

    internal fun findInputField(root: AccessibilityNodeInfo, ownPackage: String, expectedPackage: String?): AccessibilityNodeInfo? {
        val packageName = root.packageName?.toString() ?: return null
        if (packageName == ownPackage || (expectedPackage != null && packageName != expectedPackage)) return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!focused.isVisibleToUser || !focused.isEnabled || !focused.isEditable || focused.isPassword) return null
        if (focused.packageName?.toString() != packageName) return null
        if (focused.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return null
        return focused
    }

    internal suspend fun dispatchGesture(
        svc: AccessibilityService,
        gesture: GestureDescription
    ): Boolean = withTimeoutOrNull(8_000) { suspendCancellableCoroutine<Boolean> { cont ->
        val accepted = svc.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )
        if (!accepted && cont.isActive) cont.resume(false)
    } } ?: false
}
