package io.clawdroid.feature.chat.assistant

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.clawdroid.core.domain.model.VoicePhase
import io.clawdroid.feature.chat.voice.VoiceModeState
import io.clawdroid.feature.chat.voice.CameraCaptureManager
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.cos
import kotlin.math.sin

fun orbStatus(phase: VoicePhase): String = when (phase) {
    VoicePhase.IDLE -> "Готов"
    VoicePhase.LISTENING -> "Слушаю"
    VoicePhase.PAUSED -> "Пауза"
    VoicePhase.SENDING -> "Передаю"
    VoicePhase.THINKING -> "Думаю"
    VoicePhase.SPEAKING -> "Отвечаю"
    VoicePhase.ERROR -> "Нужна помощь"
}

/** Animation reflects actual voice/model state; it does not simulate a reply. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CosmosOrb(
    state: VoiceModeState,
    expanded: Boolean,
    cameraCaptureManager: CameraCaptureManager,
    onExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onInterrupt: () -> Unit,
    onCamera: () -> Unit,
    onScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dragCallback by rememberUpdatedState(onDrag)
    val motion = rememberInfiniteTransition(label = "cosmos")
    val angle by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "orbit")
    val breath by motion.animateFloat(0.9f, 1.04f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val color = when (state.phase) {
        VoicePhase.ERROR -> Color(0xFFFF717C)
        VoicePhase.THINKING, VoicePhase.SENDING -> Color(0xFFC18BFF)
        VoicePhase.SPEAKING -> Color(0xFF75FFC9)
        VoicePhase.PAUSED, VoicePhase.IDLE -> Color(0xFF8298BA)
        else -> Color(0xFF63D9FF)
    }
    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth().then(if (expanded) Modifier.verticalScroll(scroll) else Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(88.dp)
            .semantics { contentDescription = "Джарвис: ${orbStatus(state.phase)}. Нажмите для управления, удерживайте для остановки" }
            .combinedClickable(onClick = onExpand, onLongClick = onStop)
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    dragCallback(amount.x, amount.y)
                }
            }) {
            val center = Offset(size.width / 2, size.height / 2)
            val energetic = state.phase == VoicePhase.SPEAKING || state.phase == VoicePhase.LISTENING
            val pulse = if (energetic) breath + state.amplitudeNormalized.coerceIn(0f, 1f) * 0.08f else breath
            val radius = size.minDimension * 0.32f * pulse
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .35f), Color.Transparent), center, size.minDimension / 2), size.minDimension / 2, center)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFB9E7FF), color.copy(alpha = .8f), Color(0xFF292052), Color(0xFF0B112C)), center - Offset(radius * .32f, radius * .4f), radius * 1.65f), radius, center)
            for (i in 0 until 22) {
                val t = i * 2.39996f + angle * .004f
                val r = radius * (0.2f + (i % 7) / 10f)
                drawCircle(Color.White.copy(alpha = .35f + (i % 4) * .13f), if (i % 5 == 0) 1.6f else .9f,
                    center + Offset(cos(t) * r, sin(t) * r * .8f))
            }
            val rotation = if (state.phase == VoicePhase.THINKING || state.phase == VoicePhase.SENDING) angle else angle * .2f
            rotate(rotation, center) {
                drawArc(color.copy(alpha = .7f), 15f, 285f, false, center - Offset(radius * 1.23f, radius * .55f), Size(radius * 2.46f, radius * 1.1f), style = Stroke(1.8f))
                drawCircle(Color.White, 2.4f, center + Offset(radius * 1.18f, 0f))
            }
            drawCircle(color.copy(alpha = .3f), radius, center, style = Stroke(1f))
            drawCircle(Color.White.copy(alpha = .6f), radius * .09f, center - Offset(radius * .38f, radius * .42f))
        }
        Surface(color = Color(0xE614192D), shape = RoundedCornerShape(16.dp)) {
            Text(orbStatus(state.phase), color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Surface(color = Color(0xF514192D), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Джарвисджон", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
                        if (state.isCameraActive) {
                            val owner = LocalLifecycleOwner.current
                            AndroidView(factory = { context -> PreviewView(context).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                cameraCaptureManager.bind(owner, this)
                            } }, modifier = Modifier.size(64.dp))
                            DisposableEffect(Unit) { onDispose { cameraCaptureManager.unbind() } }
                        }
                    }
                    val text = state.errorMessage ?: state.statusText ?: state.responseText.ifEmpty { state.recognizedText.ifEmpty { "Говорите команду. Шарик можно перемещать пальцем." } }
                    Text(text, color = Color(0xFFC9D6EB), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row {
                        TextButton(onClick = onPause) { Text(if (state.phase == VoicePhase.PAUSED) "Слушать" else "Пауза") }
                        TextButton(onClick = onInterrupt) { Text("Новая команда") }
                    }
                    Row {
                        TextButton(onClick = onCamera) { Text(if (state.isCameraActive) "Камера: выкл." else "Камера") }
                        TextButton(onClick = onScreen) { Text(if (state.isScreenCaptureActive) "Экран: выкл." else "Экран") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB43C51))) { Text("Стоп") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onExpand) { Text("Свернуть") }
                    }
                }
            }
        }
    }
}
