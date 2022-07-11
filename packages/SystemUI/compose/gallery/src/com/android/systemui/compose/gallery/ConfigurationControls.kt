package com.android.systemui.compose.gallery

import android.graphics.Point
import android.os.UserHandle
import android.view.Display
import android.view.WindowManagerGlobal
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatTextdirectionLToR
import androidx.compose.material.icons.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

enum class FontScale(val scale: Float) {
    Small(0.85f),
    Normal(1f),
    Big(1.15f),
    Bigger(1.30f),
}

/** A configuration panel that allows to toggle the theme, font scale and layout direction. */
@Composable
fun ConfigurationControls(
    isDarkTheme: Boolean,
    fontScale: FontScale,
    layoutDirection: LayoutDirection,
    onChangeTheme: () -> Unit,
    onChangeLayoutDirection: () -> Unit,
    onChangeFontScale: () -> Unit,
) {
    // The display we are emulating, if any.
    var emulatedDisplayName by rememberSaveable { mutableStateOf<String?>(null) }
    val emulatedDisplay =
        emulatedDisplayName?.let { name -> EmulatedDisplays.firstOrNull { it.name == name } }

    LaunchedEffect(emulatedDisplay) {
        val wm = WindowManagerGlobal.getWindowManagerService()

        val defaultDisplayId = Display.DEFAULT_DISPLAY
        if (emulatedDisplay == null) {
            wm.clearForcedDisplayDensityForUser(defaultDisplayId, UserHandle.myUserId())
            wm.clearForcedDisplaySize(defaultDisplayId)
        } else {
            val density = emulatedDisplay.densityDpi

            // Emulate the display and make sure that we use the maximum available space possible.
            val initialSize = Point()
            wm.getInitialDisplaySize(defaultDisplayId, initialSize)
            val width = emulatedDisplay.width
            val height = emulatedDisplay.height
            val minOfSize = min(width, height)
            val maxOfSize = max(width, height)
            if (initialSize.x < initialSize.y) {
                wm.setForcedDisplaySize(defaultDisplayId, minOfSize, maxOfSize)
            } else {
                wm.setForcedDisplaySize(defaultDisplayId, maxOfSize, minOfSize)
            }
            wm.setForcedDisplayDensityForUser(defaultDisplayId, density, UserHandle.myUserId())
        }
    }

    // TODO(b/231131244): Fork FlowRow from Accompanist and use that instead to make sure that users
    // don't miss any available configuration.
    LazyRow {
        // Dark/light theme.
        item {
            TextButton(onChangeTheme) {
                val text: String
                val icon: ImageVector
                if (isDarkTheme) {
                    icon = Icons.Default.BrightnessHigh
                    text = "Dark"
                } else {
                    icon = Icons.Default.BrightnessLow
                    text = "Light"
                }

                Icon(icon, null)
                Spacer(Modifier.width(8.dp))
                Text(text)
            }
        }

        // Font scale.
        item {
            TextButton(onChangeFontScale) {
                Icon(Icons.Default.FormatSize, null)
                Spacer(Modifier.width(8.dp))

                Text(fontScale.name)
            }
        }

        // Layout direction.
        item {
            TextButton(onChangeLayoutDirection) {
                when (layoutDirection) {
                    LayoutDirection.Ltr -> {
                        Icon(Icons.Default.FormatTextdirectionLToR, null)
                        Spacer(Modifier.width(8.dp))
                        Text("LTR")
                    }
                    LayoutDirection.Rtl -> {
                        Icon(Icons.Default.FormatTextdirectionRToL, null)
                        Spacer(Modifier.width(8.dp))
                        Text("RTL")
                    }
                }
            }
        }

        // Display emulation.
        EmulatedDisplays.forEach { display ->
            item {
                DisplayButton(
                    display,
                    emulatedDisplay == display,
                    { emulatedDisplayName = it?.name },
                )
            }
        }
    }
}

@Composable
private fun DisplayButton(
    display: EmulatedDisplay,
    selected: Boolean,
    onChangeEmulatedDisplay: (EmulatedDisplay?) -> Unit,
) {
    val onClick = {
        if (selected) {
            onChangeEmulatedDisplay(null)
        } else {
            onChangeEmulatedDisplay(display)
        }
    }

    val content: @Composable RowScope.() -> Unit = {
        Icon(display.icon, null)
        Spacer(Modifier.width(8.dp))
        Text(display.name)
    }

    if (selected) {
        Button(onClick, contentPadding = ButtonDefaults.TextButtonContentPadding, content = content)
    } else {
        TextButton(onClick, content = content)
    }
}

/** The displays that can be emulated from this Gallery app. */
private val EmulatedDisplays =
    listOf(
        EmulatedDisplay(
            "Phone",
            Icons.Default.Smartphone,
            width = 1440,
            height = 3120,
            densityDpi = 560,
        ),
        EmulatedDisplay(
            "Tablet",
            Icons.Default.Tablet,
            width = 2560,
            height = 1600,
            densityDpi = 320,
        ),
    )

private data class EmulatedDisplay(
    val name: String,
    val icon: ImageVector,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)
