package com.armin7270.snispoof.ui

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.armin7270.snispoof.R
import com.armin7270.snispoof.ui.theme.SpoofColors

@Composable
internal fun AnimatedDottedWave(
    accent: Color,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clipToBounds()
            .background(
                Brush.verticalGradient(
                    0f to SpoofColors.BackgroundBottom.copy(alpha = 0.10f),
                    0.22f to accent.copy(alpha = 0.025f),
                    1f to SpoofColors.BackgroundBottom.copy(alpha = 0.58f),
                ),
            ),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PlatformAnimatedWave(
                accent = accent,
                motionEnabled = motionEnabled,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            StaticWave(modifier = Modifier.fillMaxSize(), tint = accent)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun PlatformAnimatedWave(
    accent: Color,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animatedWave: Drawable? = remember(context) { decodeAnimatedWave(context) }
    val accentArgb = accent.toArgb()

    DisposableEffect(animatedWave, motionEnabled) {
        animatedWave?.let { setRunning(it, motionEnabled) }
        onDispose { animatedWave?.let { stop(it) } }
    }

    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(animatedWave)
            }
        },
        update = { imageView ->
            imageView.setColorFilter(accentArgb, android.graphics.PorterDuff.Mode.SRC_IN)
            animatedWave?.let { setRunning(it, motionEnabled) }
        },
        modifier = modifier,
    )
}

@Composable
private fun StaticWave(modifier: Modifier = Modifier, tint: Color) {
    val context = LocalContext.current
    val bitmap = remember(context) {
        runCatching {
            android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.uac_digital_wave)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = modifier,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeAnimatedWave(context: android.content.Context): Drawable? = runCatching {
    val source = android.graphics.ImageDecoder.createSource(context.resources, R.drawable.uac_digital_wave)
    android.graphics.ImageDecoder.decodeDrawable(source)
}.getOrNull()

@RequiresApi(Build.VERSION_CODES.P)
private fun setRunning(drawable: Drawable, running: Boolean) {
    val animated = drawable as? AnimatedImageDrawable ?: return
    animated.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
    if (running) animated.start() else animated.stop()
}

@RequiresApi(Build.VERSION_CODES.P)
private fun stop(drawable: Drawable) {
    (drawable as? AnimatedImageDrawable)?.stop()
}
