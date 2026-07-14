package com.soma.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.fonts.SystemFonts
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Grey = Color(0xFF888888)
private val GreyOnPaper = Color(0xFF555555)

/** Paka's screen palette: native dark, with light mode hidden in Developer. */
object SomaPalette {
    var lightMode by mutableStateOf(false)
    val background: Color get() = if (lightMode) White else Black
    val foreground: Color get() = if (lightMode) Black else White
    val dim: Color get() = if (lightMode) GreyOnPaper else Grey
}

val Paper: Color get() = SomaPalette.background
val Ink: Color get() = SomaPalette.foreground
val DimInk: Color get() = SomaPalette.dim

private val LocalSomaFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun SomaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val family = remember(context) { lightPhoneFontFamily(context) }
    val scheme = if (SomaPalette.lightMode) {
        lightColorScheme(
            primary = Ink,
            onPrimary = Paper,
            secondary = Ink,
            onSecondary = Paper,
            background = Paper,
            onBackground = Ink,
            surface = Paper,
            onSurface = Ink,
            outline = DimInk,
        )
    } else {
        darkColorScheme(
            primary = Ink,
            onPrimary = Paper,
            secondary = Ink,
            onSecondary = Paper,
            background = Paper,
            onBackground = Ink,
            surface = Paper,
            onSurface = Ink,
            outline = DimInk,
        )
    }
    CompositionLocalProvider(
        LocalSomaFontFamily provides family,
        LocalTextStyle provides LocalTextStyle.current.withSomaFont(family),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography.copy(
                bodyLarge = MaterialTheme.typography.bodyLarge.withSomaFont(family),
                bodyMedium = MaterialTheme.typography.bodyMedium.withSomaFont(family),
                bodySmall = MaterialTheme.typography.bodySmall.withSomaFont(family),
                titleLarge = MaterialTheme.typography.titleLarge.withSomaFont(family),
                titleMedium = MaterialTheme.typography.titleMedium.withSomaFont(family),
                labelLarge = MaterialTheme.typography.labelLarge.withSomaFont(family),
            ),
            content = content,
        )
    }
}

@Composable
fun TextStyle.withSomaFont(fontFamily: FontFamily? = null): TextStyle {
    val family = fontFamily ?: LocalSomaFontFamily.current
    return if (family == null) this else copy(fontFamily = family)
}

private fun lightPhoneFontFamily(context: Context): FontFamily {
    val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) systemAkkuratFonts() else null
    return system ?: systemAkkuratFiles() ?: bundledAkkuratFonts(context) ?: FontFamily.Default
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun systemAkkuratFonts(): FontFamily? {
    val fonts = SystemFonts.getAvailableFonts()
        .filter { it.file?.name?.startsWith("Akkurat", ignoreCase = true) == true }
        .mapNotNull { font ->
            val file = font.file ?: return@mapNotNull null
            Font(
                file = file,
                weight = FontWeight(font.style.weight),
                style = if (font.style.slant != 0) FontStyle.Italic else FontStyle.Normal,
            )
        }
    return if (fonts.isEmpty()) null else FontFamily(fonts)
}

private fun systemAkkuratFiles(): FontFamily? {
    val fonts = listOf(
        "AkkuratLLTT-Light.ttf" to FontWeight.Light,
        "AkkuratLLTT-Regular.ttf" to FontWeight.Normal,
        "AkkuratLLTT-Bold.ttf" to FontWeight.Bold,
    ).mapNotNull { (name, weight) ->
        File("/system/fonts", name).takeIf(File::canRead)?.let { Font(it, weight) }
    }
    return if (fonts.isEmpty()) null else FontFamily(fonts)
}

private fun bundledAkkuratFonts(context: Context): FontFamily? {
    val resources = context.resources
    val packageName = context.packageName
    val fonts = buildList {
        listOf(
            "akkuratll_light" to FontWeight.Light,
            "akkuratll_regular" to FontWeight.Normal,
            "akkuratll_medium" to FontWeight.Medium,
            "akkuratll_bold" to FontWeight.Bold,
        ).forEach { (name, weight) ->
            resources.getIdentifier(name, "font", packageName).takeIf { it != 0 }?.let { add(Font(it, weight)) }
        }
    }
    return if (fonts.isEmpty()) null else FontFamily(fonts)
}

fun performSomaHaptic(
    context: Context,
    feedback: HapticFeedback,
    type: HapticFeedbackType = HapticFeedbackType.LongPress,
) {
    if (SomaPrefs.vibration(context)) feedback.performHapticFeedback(type)
}

@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
fun tapModifier(
    onClick: () -> Unit,
    label: String? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val feedback = LocalHapticFeedback.current
    return Modifier
        .then(if (label == null) Modifier else Modifier.semantics { contentDescription = label })
        .clickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onClick = {
                performSomaHaptic(context, feedback)
                onClick()
            },
        )
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
fun tapLongModifier(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    label: String? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val feedback = LocalHapticFeedback.current
    return Modifier
        .then(if (label == null) Modifier else Modifier.semantics { contentDescription = label })
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onClick = {
                performSomaHaptic(context, feedback)
                onClick()
            },
            onLongClick = {
                performSomaHaptic(context, feedback, HapticFeedbackType.LongPress)
                onLongClick()
            },
        )
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
}

@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
fun longPressModifier(onLongClick: () -> Unit, label: String? = null): Modifier {
    val current = rememberUpdatedState(onLongClick)
    val context = LocalContext.current
    val feedback = LocalHapticFeedback.current
    return Modifier
        .semantics(mergeDescendants = true) {
            if (label != null) contentDescription = label
            role = Role.Button
            onClick(label = label) {
                performSomaHaptic(context, feedback, HapticFeedbackType.LongPress)
                current.value()
                true
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                performSomaHaptic(context, feedback, HapticFeedbackType.LongPress)
                current.value()
            })
        }
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
}

@Composable
fun BackArrow(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    enabled: Boolean = true,
) {
    Canvas(modifier.size(48.dp).then(if (enabled) tapModifier(onBack, "back") else Modifier)) {
        val side = size.minDimension
        val color = if (enabled) Ink else DimInk
        drawLine(color, Offset(side * 0.59f, side * 0.31f), Offset(side * 0.41f, side * 0.5f), side * 0.055f, StrokeCap.Round)
        drawLine(color, Offset(side * 0.41f, side * 0.5f), Offset(side * 0.59f, side * 0.69f), side * 0.055f, StrokeCap.Round)
    }
}
