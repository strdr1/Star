package com.amsales.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmAccent2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Поле из 5-конечных звёзд, медленно плывущих вверх и вращающихся
 * вокруг своей оси. Цвет каждой — микс между accent (фиолетовый)
 * и accent2 (розово-красный).
 */
private data class Star(
    var x: Float,
    var y: Float,
    val size: Float,
    val baseAlpha: Float,
    val phase: Float,
    val speed: Float,
    val colorMix: Float,
    var rot: Float,
    val rotSpeed: Float,
)

@Composable
fun StarField(
    modifier: Modifier = Modifier,
    particleColor: Color = AmAccent,
    particleColor2: Color = AmAccent2,
    particleCount: Int = 60,
) {
    // Накопитель времени для лёгкого мерцания.
    val tick = remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(33)  // ~30 FPS — экономия батареи
            tick.value = tick.value + 1
        }
    }

    // Лениво создаём массив частиц при первой отрисовке (с размерами Canvas).
    val parts = remember { ArrayList<Star>() }

    Canvas(modifier = modifier) {
        if (parts.isEmpty() && size.width > 0f && size.height > 0f) {
            val rnd = Random(42)
            repeat(particleCount) {
                val depth = rnd.nextFloat()
                parts.add(
                    Star(
                        x = rnd.nextFloat() * size.width,
                        y = rnd.nextFloat() * size.height,
                        size = 4f + depth * 20f,           // крупнее
                        baseAlpha = 0.08f + depth * 0.34f,
                        phase = rnd.nextFloat() * (PI * 2).toFloat(),
                        speed = 0.09f + depth * 0.50f,
                        colorMix = rnd.nextFloat(),
                        rot = rnd.nextFloat() * (PI * 2).toFloat(),
                        rotSpeed = (rnd.nextFloat() - 0.5f) * 0.022f,
                    )
                )
            }
        }
        val t = tick.value * 0.04f
        for (p in parts) {
            p.y -= p.speed
            p.rot += p.rotSpeed
            if (p.y < -p.size * 2) {
                p.y = size.height + p.size * 2
                p.x = Random.nextFloat() * size.width
            }
            val flicker = 0.7f + 0.3f * sin(t * 1.2f + p.phase)
            val a = (p.baseAlpha * flicker).coerceIn(0f, 1f)
            val r = particleColor.red * (1 - p.colorMix) + particleColor2.red * p.colorMix
            val g = particleColor.green * (1 - p.colorMix) + particleColor2.green * p.colorMix
            val b = particleColor.blue * (1 - p.colorMix) + particleColor2.blue * p.colorMix
            val col = Color(r, g, b, a)
            drawStar(p.x, p.y, p.size, p.size * 0.42f, p.rot, col)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStar(
    cx: Float, cy: Float, outerR: Float, innerR: Float, rot: Float, color: Color,
) {
    val path = Path()
    for (i in 0 until 10) {
        val a = rot - (PI / 2).toFloat() + i * (PI / 5).toFloat()
        val r = if (i % 2 == 0) outerR else innerR
        val x = cx + cos(a) * r
        val y = cy + sin(a) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
