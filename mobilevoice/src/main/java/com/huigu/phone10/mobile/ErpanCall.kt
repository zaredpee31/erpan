package com.huigu.phone10.mobile

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

/**
 * 家里版的通话页：打开耳畔先到这里，像打电话。原来的首页挪成「设置」，从右上角进。
 * 只读 VoiceState、只发原有的开始 / TOGGLE / 停止，通话逻辑一点不碰。
 */

/** 放在进程里，页面重建（比如转屏）计时不归零。 */
private var callStartedAt = 0L

internal enum class CallMood { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, MUTED, TROUBLE, REVIEW }

internal fun VoiceState.callMood(): CallMood = when {
    changing -> CallMood.CONNECTING
    !running -> CallMood.IDLE
    pendingDraft != null -> CallMood.REVIEW
    message.contains("失败") || message.contains("不可用") || message.contains("中断") -> CallMood.TROUBLE
    message.contains("播放") -> CallMood.SPEAKING
    message.contains("识别") || (message.contains("等待") && message.contains("回复")) -> CallMood.THINKING
    !micEnabled -> CallMood.MUTED
    else -> CallMood.LISTENING
}

@Composable internal fun ErpanCall(settings: MobileSettings, state: VoiceState, avatar: Drawable?, busy: Boolean,
    notice: String, onStart: () -> Unit, onEnd: () -> Unit, onMute: () -> Unit, onSettings: () -> Unit) {
    val configured = configurationIssues(settings).isEmpty()
    val mood = state.callMood()
    val name = settings.chatTitle.ifBlank { "阿辞" }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) {
        if (!state.running) { callStartedAt = 0L; return@LaunchedEffect }
        if (callStartedAt == 0L) callStartedAt = System.currentTimeMillis()
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val label = when (mood) {
        CallMood.IDLE -> if (configured) "点一下，打给他" else "先去设置里把连接填好"
        CallMood.CONNECTING -> "正在接通…"
        CallMood.LISTENING -> "在听你说"
        CallMood.THINKING -> "在想…"
        CallMood.SPEAKING -> "$name 在说"
        CallMood.MUTED -> "你这边静音了"
        CallMood.TROUBLE -> "信号不太好"
        CallMood.REVIEW -> "说的话等你确认"
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ErpanColors.Paper, ErpanColors.Blush)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("耳畔", fontFamily = FontFamily.Serif, fontSize = 18.sp, color = ErpanColors.Muted)
                if (!state.running) TextButton(onClick = onSettings, enabled = !busy) {
                    Text("设置", fontSize = 14.sp, color = ErpanColors.Muted)
                }
            }
            Spacer(Modifier.weight(0.7f))
            AvatarHalo(avatar, mood, name)
            Spacer(Modifier.height(26.dp))
            Text(name, fontFamily = FontFamily.Serif, fontSize = 34.sp, color = ErpanColors.Ink)
            Spacer(Modifier.height(10.dp))
            Text(label, fontSize = 15.sp, color = if (mood == CallMood.SPEAKING) ErpanColors.Rose else ErpanColors.Muted)
            if (state.running && callStartedAt > 0L) {
                val sec = ((now - callStartedAt) / 1000).coerceAtLeast(0)
                Spacer(Modifier.height(6.dp))
                Text("%02d:%02d".format(sec / 60, sec % 60), fontSize = 13.sp, letterSpacing = 2.sp, color = ErpanColors.Muted)
            }
            if (mood == CallMood.TROUBLE) {
                Spacer(Modifier.height(10.dp))
                Text(state.message, fontSize = 12.sp, lineHeight = 18.sp, color = ErpanColors.Muted, textAlign = TextAlign.Center)
            }
            if (!state.running && notice.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(notice, fontSize = 13.sp, lineHeight = 19.sp, color = ErpanColors.Rose, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            if (state.running) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(if (state.micEnabled) "静音" else "取消静音", Color.White, ErpanColors.Ink,
                        enabled = !state.changing, onClick = onMute) { MicIcon(!state.micEnabled, ErpanColors.Ink) }
                    RoundAction("挂断", HANG_UP, Color.White, enabled = true, onClick = onEnd) {
                        LineIcon(ErpanIcon.END, Color.White, Modifier.size(30.dp))
                    }
                }
            } else {
                RoundAction("打电话", ErpanColors.Rose, Color.White, enabled = !busy && !state.changing, onClick = onStart) {
                    LineIcon(ErpanIcon.PHONE, Color.White, Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private val HANG_UP = Color(0xFFE0566B)

@Composable private fun AvatarHalo(avatar: Drawable?, mood: CallMood, name: String) {
    val live = mood == CallMood.SPEAKING || mood == CallMood.LISTENING || mood == CallMood.THINKING || mood == CallMood.CONNECTING
    val ring = if (mood == CallMood.SPEAKING) ErpanColors.Rose else ErpanColors.Line
    val pulse = rememberInfiniteTransition(label = "halo")
    val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "p")
    val picture = remember(avatar) {
        avatar?.let { d ->
            runCatching {
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 512
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 512
                val k = minOf(1f, 1024f / maxOf(w, h))
                d.toBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1)).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        if (live) Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.34f
            for (i in 0..1) {
                val q = (p + i * 0.5f) % 1f
                drawCircle(ring.copy(alpha = (1f - q) * 0.6f), radius = base + q * size.minDimension * 0.15f,
                    style = Stroke(width = 2.dp.toPx()))
            }
        }
        Box(Modifier.size(176.dp).clip(CircleShape).background(ErpanColors.Blush).border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center) {
            if (picture != null) Image(picture, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(name.take(1), fontFamily = FontFamily.Serif, fontSize = 64.sp, color = ErpanColors.Rose)
        }
    }
}

@Composable private fun RoundAction(label: String, fill: Color, text: Color, enabled: Boolean, onClick: () -> Unit,
    icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(74.dp).clip(CircleShape).background(if (enabled) fill else fill.copy(alpha = 0.45f))
            .border(0.8.dp, if (fill == Color.White) ErpanColors.Line else Color.Transparent, CircleShape)
            .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = 13.sp, color = if (fill == Color.White) text else ErpanColors.Muted)
    }
}

@Composable private fun MicIcon(off: Boolean, tint: Color) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.width
        val line = w * 0.07f
        val s = Stroke(width = line, cap = StrokeCap.Round)
        drawRoundRect(tint, topLeft = Offset(w * 0.36f, w * 0.08f), size = Size(w * 0.28f, w * 0.46f),
            cornerRadius = CornerRadius(w * 0.14f), style = s)
        drawArc(tint, 0f, 180f, false, topLeft = Offset(w * 0.24f, w * 0.26f), size = Size(w * 0.52f, w * 0.46f), style = s)
        drawLine(tint, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.88f), line, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.36f, w * 0.88f), Offset(w * 0.64f, w * 0.88f), line, StrokeCap.Round)
        if (off) drawLine(tint, Offset(w * 0.16f, w * 0.12f), Offset(w * 0.84f, w * 0.88f), line, StrokeCap.Round)
    }
}
