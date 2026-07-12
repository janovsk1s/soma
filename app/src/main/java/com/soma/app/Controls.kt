package com.soma.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun LineInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    password: Boolean = false,
    onDone: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .drawBehind {
                drawLine(DimInk, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 1.dp.toPx())
            }
            .padding(horizontal = 2.dp, vertical = 10.dp),
        textStyle = TextStyle(color = Ink, fontSize = if (singleLine) 20.sp else 22.sp, fontWeight = FontWeight.Normal),
        cursorBrush = SolidColor(Ink),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = DimInk, fontSize = 18.sp, fontWeight = FontWeight.Light)
                inner()
            }
        },
    )
}

@Composable
fun SettingsItem(
    label: String,
    trailing: String? = null,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().fillMaxHeight().then(
            onClick?.let { tapModifier(it, label) } ?: Modifier,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutoFitText(
            label,
            color = if (onClick == null) DimInk else Ink,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, color = DimInk, fontSize = 18.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
fun GearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(modifier.size(48.dp).then(tapModifier(onClick, "settings"))) { drawGear() }
}

@Composable
fun PlusButton(onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(modifier.size(48.dp).then(tapLongModifier(onClick, onLongClick, "add"))) { drawPlus() }
}

@Composable
fun RecordButton(recording: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(modifier.size(48.dp).then(tapModifier(onClick, if (recording) "stop" else "record"))) {
        if (recording) drawStop() else drawMicrophone()
    }
}

@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(modifier.size(48.dp).then(tapModifier(onClick, if (playing) "stop playback" else "play"))) {
        if (playing) drawStop() else drawPlay()
    }
}

private fun DrawScope.drawPlus() {
    val side = size.minDimension
    val half = side * 0.29f
    val width = side * 0.065f
    val center = side / 2f
    drawLine(Ink, Offset(center - half, center), Offset(center + half, center), width, StrokeCap.Square)
    drawLine(Ink, Offset(center, center - half), Offset(center, center + half), width, StrokeCap.Square)
}

private fun DrawScope.drawGear() {
    val s = size.minDimension
    val toothWidth = (s * GEAR_TOOTH_WIDTH).roundToInt().toFloat()
    val toothHeight = (s * GEAR_TOOTH_HEIGHT).roundToInt().toFloat()
    val center = pixelAlignedCenter(s / 2f, toothWidth)
    val c = Offset(center, center)
    val body = (s * GEAR_BODY_RADIUS).roundToInt().toFloat()
    val hole = (s * GEAR_HOLE_RADIUS).roundToInt().toFloat()
    val outerRadius = (s * GEAR_OUTER_RADIUS).roundToInt().toFloat()
    for (tooth in 0 until GEAR_TOOTH_COUNT) {
        rotate(GEAR_TOOTH_STEP_DEGREES * tooth, c) {
            drawRect(
                color = Ink,
                topLeft = Offset(
                    pixelAlignedCenter(c.x, toothWidth) - toothWidth / 2f,
                    c.y - outerRadius,
                ),
                size = Size(toothWidth, toothHeight),
            )
        }
    }
    drawCircle(Ink, body, c)
    drawCircle(Paper, hole, c)
}

private fun pixelAlignedStroke(rawWidth: Float): Float =
    rawWidth.roundToInt().coerceAtLeast(MIN_STROKE_PX).toFloat()

private fun pixelAlignedCenter(rawCenter: Float, rawWidth: Float): Float {
    val stroke = pixelAlignedStroke(rawWidth).toInt()
    val rounded = rawCenter.roundToInt().toFloat()
    return if (stroke % ODD_STROKE_MODULUS == 0) rounded else rounded + HALF_PIXEL
}

private const val MIN_STROKE_PX = 1
private const val ODD_STROKE_MODULUS = 2
private const val HALF_PIXEL = 0.5f
private const val GEAR_TOOTH_COUNT = 8
private const val GEAR_TOOTH_STEP_DEGREES = 45f
private const val GEAR_BODY_RADIUS = 0.205f
private const val GEAR_HOLE_RADIUS = 0.085f
private const val GEAR_TOOTH_WIDTH = 0.10f
private const val GEAR_TOOTH_HEIGHT = 0.12f
private const val GEAR_OUTER_RADIUS = 0.27f

private fun DrawScope.drawMicrophone() {
    val side = size.minDimension
    val center = side / 2f
    drawRoundRect(
        Ink,
        topLeft = Offset(side * 0.39f, side * 0.22f),
        size = Size(side * 0.22f, side * 0.40f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.11f),
    )
    drawArc(
        Ink,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(side * 0.30f, side * 0.34f),
        size = Size(side * 0.40f, side * 0.38f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(side * 0.045f, cap = StrokeCap.Round),
    )
    drawLine(Ink, Offset(center, side * 0.72f), Offset(center, side * 0.81f), side * 0.045f)
    drawLine(Ink, Offset(side * 0.39f, side * 0.81f), Offset(side * 0.61f, side * 0.81f), side * 0.045f)
}

private fun DrawScope.drawPlay() {
    val side = size.minDimension
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(side * 0.38f, side * 0.28f)
        lineTo(side * 0.72f, side * 0.50f)
        lineTo(side * 0.38f, side * 0.72f)
        close()
    }
    drawPath(path, Ink)
}

private fun DrawScope.drawStop() {
    val side = size.minDimension
    drawRect(Ink, Offset(side * 0.34f, side * 0.34f), Size(side * 0.32f, side * 0.32f))
}
