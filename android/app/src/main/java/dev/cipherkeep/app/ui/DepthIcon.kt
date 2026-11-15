package dev.cipherkeep.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Icon set drawn in Compose so no font glyphs or image assets are required. */
enum class CkIcon {
    VAULT,
    SECURITY,
    SETTINGS,
    MONITOR,
    ADD,
    HEART,
    RADAR,
    IMPORT,
    ALIAS,
    CLOCK,
    TRAVEL,
    FAMILY,
    INFO,
    LOCK,
    BIOMETRIC,
    SYNC,
    BACKUP,
    UPDATE,
    KEY,
}

/**
 * A layered "2.5D" icon: a soft shadow, a vertical gradient plate, a top
 * highlight and a light symbol. Set [plate] to false to draw only the symbol.
 */
@Composable
fun DepthIcon(
    icon: CkIcon,
    size: Dp = 30.dp,
    modifier: Modifier = Modifier,
    plate: Boolean = true,
    tint: Color? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        if (plate) {
            val radius = CornerRadius(s * 0.30f, s * 0.30f)
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.16f),
                topLeft = Offset(s * 0.06f, s * 0.11f),
                size = Size(s * 0.90f, s * 0.90f),
                cornerRadius = radius,
            )
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(tertiary, primary)),
                topLeft = Offset(s * 0.05f, s * 0.05f),
                size = Size(s * 0.90f, s * 0.90f),
                cornerRadius = radius,
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f)),
                ),
                topLeft = Offset(s * 0.05f, s * 0.05f),
                size = Size(s * 0.90f, s * 0.34f),
                cornerRadius = radius,
            )
        }
        drawSymbol(icon, s, tint ?: onPrimary)
    }
}

private fun DrawScope.drawSymbol(icon: CkIcon, s: Float, fg: Color) {
    val stroke = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = s * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(s * x, s * y)
    when (icon) {
        CkIcon.VAULT -> {
            drawRoundRect(
                color = fg,
                topLeft = p(0.24f, 0.30f),
                size = Size(s * 0.52f, s * 0.44f),
                cornerRadius = CornerRadius(s * 0.08f, s * 0.08f),
                style = stroke,
            )
            drawCircle(fg, radius = s * 0.075f, center = p(0.50f, 0.47f))
            drawLine(fg, p(0.50f, 0.52f), p(0.50f, 0.62f), stroke.width, StrokeCap.Round)
            drawLine(fg, p(0.24f, 0.42f), p(0.32f, 0.42f), thin.width, StrokeCap.Round)
        }
        CkIcon.SECURITY, CkIcon.LOCK -> {
            val shield = Path().apply {
                moveTo(s * 0.50f, s * 0.20f)
                lineTo(s * 0.78f, s * 0.30f)
                lineTo(s * 0.75f, s * 0.56f)
                quadraticBezierTo(s * 0.73f, s * 0.70f, s * 0.50f, s * 0.80f)
                quadraticBezierTo(s * 0.27f, s * 0.70f, s * 0.25f, s * 0.56f)
                lineTo(s * 0.22f, s * 0.30f)
                close()
            }
            drawPath(shield, fg, style = stroke)
            if (icon == CkIcon.SECURITY) {
                drawLine(fg, p(0.39f, 0.50f), p(0.47f, 0.59f), stroke.width, StrokeCap.Round)
                drawLine(fg, p(0.47f, 0.59f), p(0.62f, 0.40f), stroke.width, StrokeCap.Round)
            } else {
                drawCircle(fg, radius = s * 0.05f, center = p(0.50f, 0.47f))
                drawLine(fg, p(0.50f, 0.47f), p(0.50f, 0.60f), thin.width, StrokeCap.Round)
            }
        }
        CkIcon.SETTINGS -> {
            drawCircle(fg, radius = s * 0.16f, center = p(0.50f, 0.50f), style = stroke)
            drawCircle(fg, radius = s * 0.055f, center = p(0.50f, 0.50f))
            for (index in 0 until 8) {
                val angle = Math.toRadians(index * 45.0)
                val inner = p(
                    (0.50 + 0.22 * cos(angle)).toFloat(),
                    (0.50 + 0.22 * sin(angle)).toFloat(),
                )
                val outer = p(
                    (0.50 + 0.32 * cos(angle)).toFloat(),
                    (0.50 + 0.32 * sin(angle)).toFloat(),
                )
                drawLine(fg, inner, outer, s * 0.075f, StrokeCap.Round)
            }
        }
        CkIcon.MONITOR -> {
            drawRoundRect(
                color = fg,
                topLeft = p(0.22f, 0.28f),
                size = Size(s * 0.56f, s * 0.42f),
                cornerRadius = CornerRadius(s * 0.07f, s * 0.07f),
                style = stroke,
            )
            val pulse = Path().apply {
                moveTo(s * 0.27f, s * 0.50f)
                lineTo(s * 0.38f, s * 0.50f)
                lineTo(s * 0.44f, s * 0.38f)
                lineTo(s * 0.52f, s * 0.62f)
                lineTo(s * 0.58f, s * 0.50f)
                lineTo(s * 0.73f, s * 0.50f)
            }
            drawPath(pulse, fg, style = Stroke(width = s * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        CkIcon.ADD -> {
            drawLine(fg, p(0.50f, 0.28f), p(0.50f, 0.72f), s * 0.10f, StrokeCap.Round)
            drawLine(fg, p(0.28f, 0.50f), p(0.72f, 0.50f), s * 0.10f, StrokeCap.Round)
        }
        CkIcon.HEART -> {
            val heart = Path().apply {
                moveTo(s * 0.50f, s * 0.74f)
                cubicTo(s * 0.16f, s * 0.52f, s * 0.22f, s * 0.26f, s * 0.38f, s * 0.28f)
                cubicTo(s * 0.46f, s * 0.29f, s * 0.50f, s * 0.36f, s * 0.50f, s * 0.38f)
                cubicTo(s * 0.50f, s * 0.36f, s * 0.54f, s * 0.29f, s * 0.62f, s * 0.28f)
                cubicTo(s * 0.78f, s * 0.26f, s * 0.84f, s * 0.52f, s * 0.50f, s * 0.74f)
                close()
            }
            drawPath(heart, fg, style = stroke)
        }
        CkIcon.RADAR -> {
            drawCircle(fg, radius = s * 0.28f, center = p(0.50f, 0.50f), style = thin)
            drawCircle(fg, radius = s * 0.17f, center = p(0.50f, 0.50f), style = thin)
            drawLine(fg, p(0.50f, 0.50f), p(0.72f, 0.34f), thin.width, StrokeCap.Round)
            drawCircle(fg, radius = s * 0.045f, center = p(0.62f, 0.42f))
        }
        CkIcon.IMPORT -> {
            drawLine(fg, p(0.50f, 0.24f), p(0.50f, 0.56f), stroke.width, StrokeCap.Round)
            drawLine(fg, p(0.38f, 0.45f), p(0.50f, 0.57f), stroke.width, StrokeCap.Round)
            drawLine(fg, p(0.62f, 0.45f), p(0.50f, 0.57f), stroke.width, StrokeCap.Round)
            drawLine(fg, p(0.28f, 0.66f), p(0.28f, 0.74f), stroke.width, StrokeCap.Round)
            drawLine(fg, p(0.28f, 0.74f), p(0.72f, 0.74f), stroke.width, StrokeCap.Round)
            drawLine(fg, p(0.72f, 0.74f), p(0.72f, 0.66f), stroke.width, StrokeCap.Round)
        }
        CkIcon.ALIAS -> {
            drawRoundRect(
                color = fg,
                topLeft = p(0.22f, 0.30f),
                size = Size(s * 0.56f, s * 0.40f),
                cornerRadius = CornerRadius(s * 0.06f, s * 0.06f),
                style = stroke,
            )
            drawLine(fg, p(0.24f, 0.33f), p(0.50f, 0.53f), thin.width, StrokeCap.Round)
            drawLine(fg, p(0.76f, 0.33f), p(0.50f, 0.53f), thin.width, StrokeCap.Round)
        }
        CkIcon.CLOCK -> {
            drawCircle(fg, radius = s * 0.26f, center = p(0.50f, 0.50f), style = stroke)
            drawLine(fg, p(0.50f, 0.50f), p(0.50f, 0.34f), thin.width, StrokeCap.Round)
            drawLine(fg, p(0.50f, 0.50f), p(0.62f, 0.56f), thin.width, StrokeCap.Round)
        }
        CkIcon.TRAVEL -> {
            val plane = Path().apply {
                moveTo(s * 0.24f, s * 0.56f)
                lineTo(s * 0.76f, s * 0.32f)
                lineTo(s * 0.60f, s * 0.58f)
                lineTo(s * 0.66f, s * 0.74f)
                lineTo(s * 0.52f, s * 0.62f)
                lineTo(s * 0.40f, s * 0.70f)
                close()
            }
            drawPath(plane, fg, style = thin)
        }
        CkIcon.FAMILY -> {
            drawCircle(fg, radius = s * 0.09f, center = p(0.38f, 0.38f), style = thin)
            drawCircle(fg, radius = s * 0.09f, center = p(0.62f, 0.38f), style = thin)
            drawArc(
                color = fg,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = p(0.22f, 0.46f),
                size = Size(s * 0.32f, s * 0.34f),
                style = thin,
            )
            drawArc(
                color = fg,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = p(0.46f, 0.46f),
                size = Size(s * 0.32f, s * 0.34f),
                style = thin,
            )
        }
        CkIcon.INFO -> {
            drawCircle(fg, radius = s * 0.26f, center = p(0.50f, 0.50f), style = stroke)
            drawCircle(fg, radius = s * 0.04f, center = p(0.50f, 0.36f))
            drawLine(fg, p(0.50f, 0.46f), p(0.50f, 0.64f), thin.width, StrokeCap.Round)
        }
        CkIcon.BIOMETRIC -> {
            for (index in 0..2) {
                drawArc(
                    color = fg,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = p(0.24f + index * 0.06f, 0.26f + index * 0.05f),
                    size = Size(s * (0.52f - index * 0.12f), s * (0.50f - index * 0.10f)),
                    style = thin,
                )
            }
            drawLine(fg, p(0.50f, 0.62f), p(0.50f, 0.72f), thin.width, StrokeCap.Round)
        }
        CkIcon.SYNC -> {
            drawArc(
                color = fg,
                startAngle = 160f,
                sweepAngle = 200f,
                useCenter = false,
                topLeft = p(0.24f, 0.28f),
                size = Size(s * 0.52f, s * 0.46f),
                style = stroke,
            )
            drawArc(
                color = fg,
                startAngle = -20f,
                sweepAngle = 200f,
                useCenter = false,
                topLeft = p(0.24f, 0.28f),
                size = Size(s * 0.52f, s * 0.46f),
                style = stroke,
            )
            drawLine(fg, p(0.72f, 0.28f), p(0.80f, 0.36f), thin.width, StrokeCap.Round)
            drawLine(fg, p(0.72f, 0.28f), p(0.66f, 0.36f), thin.width, StrokeCap.Round)
        }
        CkIcon.BACKUP -> {
            drawRoundRect(
                color = fg,
                topLeft = p(0.24f, 0.34f),
                size = Size(s * 0.52f, s * 0.40f),
                cornerRadius = CornerRadius(s * 0.06f, s * 0.06f),
                style = stroke,
            )
            drawLine(fg, p(0.24f, 0.46f), p(0.76f, 0.46f), thin.width, StrokeCap.Round)
            drawLine(fg, p(0.42f, 0.58f), p(0.58f, 0.58f), thin.width, StrokeCap.Round)
        }
        CkIcon.UPDATE -> {
            drawArc(
                color = fg,
                startAngle = 40f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = p(0.24f, 0.24f),
                size = Size(s * 0.52f, s * 0.52f),
                style = stroke,
            )
            drawLine(fg, p(0.72f, 0.24f), p(0.78f, 0.34f), thin.width, StrokeCap.Round)
            drawLine(fg, p(0.72f, 0.24f), p(0.64f, 0.32f), thin.width, StrokeCap.Round)
        }
        CkIcon.KEY -> {
            drawCircle(fg, radius = s * 0.12f, center = p(0.38f, 0.50f), style = stroke)
            drawLine(fg, p(0.50f, 0.50f), p(0.76f, 0.50f), stroke.width, StrokeCap.Round)
            drawLine(fg, p(0.66f, 0.50f), p(0.66f, 0.62f), thin.width, StrokeCap.Round)
            drawLine(fg, p(0.74f, 0.50f), p(0.74f, 0.60f), thin.width, StrokeCap.Round)
        }
    }
}
