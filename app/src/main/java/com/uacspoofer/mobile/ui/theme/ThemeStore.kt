package com.uacspoofer.mobile.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val id: String) {
    DARK("dark"),
    LIGHT("light"),
    SYSTEM("system");

    fun toggled(): ThemeMode = when (this) {
        DARK -> LIGHT
        LIGHT -> DARK
        SYSTEM -> LIGHT
    }

    companion object {
        fun fromStored(raw: String?): ThemeMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: DARK
    }
}

val SunIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Theme.Sun",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 7f)
            curveTo(9.24f, 7f, 7f, 9.24f, 7f, 12f)
            curveTo(7f, 14.76f, 9.24f, 17f, 12f, 17f)
            curveTo(14.76f, 17f, 17f, 14.76f, 17f, 12f)
            curveTo(17f, 9.24f, 14.76f, 7f, 12f, 7f)
            close()
            moveTo(12f, 2f)
            lineTo(12f, 5f)
            moveTo(12f, 19f)
            lineTo(12f, 22f)
            moveTo(2f, 12f)
            lineTo(5f, 12f)
            moveTo(19f, 12f)
            lineTo(22f, 12f)
            moveTo(4.93f, 4.93f)
            lineTo(7.05f, 7.05f)
            moveTo(16.95f, 16.95f)
            lineTo(19.07f, 19.07f)
            moveTo(4.93f, 19.07f)
            lineTo(7.05f, 16.95f)
            moveTo(16.95f, 7.05f)
            lineTo(19.07f, 4.93f)
        }
    }.build()
}

val MoonIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Theme.Moon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 3f)
            curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
            curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
            curveTo(15.9f, 21f, 19.22f, 18.52f, 20.48f, 15.01f)
            curveTo(14.8f, 15.65f, 9.85f, 10.7f, 10.49f, 5.02f)
            curveTo(11.0f, 4.25f, 11.45f, 3.56f, 12f, 3f)
            close()
        }
    }.build()
}

class ThemeStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableTheme = MutableStateFlow(ThemeMode.fromStored(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<ThemeMode> = mutableTheme.asStateFlow()

    fun snapshot(): ThemeMode = mutableTheme.value

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.id).apply()
        mutableTheme.value = mode
    }

    fun toggleTheme(): ThemeMode {
        val next = mutableTheme.value.toggled()
        setTheme(next)
        return next
    }

    companion object {
        private const val PREFS = "app_theme_prefs_v1"
        private const val KEY_THEME = "theme_mode"

        @Volatile private var instance: ThemeStore? = null

        fun get(context: Context): ThemeStore = instance ?: synchronized(this) {
            instance ?: ThemeStore(context.applicationContext).also { instance = it }
        }
    }
}
