package com.android.systemui.compose.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.android.systemui.compose.theme.SystemUITheme

/** The gallery app screens. */
object GalleryAppScreens {
    val Typography = ChildScreen("typography") { TypographyScreen() }
    val MaterialColors = ChildScreen("material_colors") { MaterialColorsScreen() }
    val AndroidColors = ChildScreen("android_colors") { AndroidColorsScreen() }
    val ExampleFeature = ChildScreen("example_feature") { ExampleFeatureScreen() }

    val Home =
        ParentScreen(
            "home",
            mapOf(
                "Typography" to Typography,
                "Material colors" to MaterialColors,
                "Android colors" to AndroidColors,
                "Example feature" to ExampleFeature,
            )
        )
}

/** The main content of the app, that shows [GalleryAppScreens.Home] by default. */
@Composable
private fun MainContent() {
    Box(Modifier.fillMaxSize()) {
        val navController = rememberNavController()
        NavHost(
            navController = navController,
            startDestination = GalleryAppScreens.Home.identifier,
        ) {
            screen(GalleryAppScreens.Home, navController)
        }
    }
}

/**
 * The top-level composable shown when starting the app. This composable always shows a
 * [ConfigurationControls] at the top of the screen, above the [MainContent].
 */
@Composable
fun GalleryApp(
    isDarkTheme: Boolean,
    onChangeTheme: () -> Unit,
) {
    val systemFontScale = LocalDensity.current.fontScale
    var fontScale: FontScale by remember {
        mutableStateOf(
            FontScale.values().firstOrNull { it.scale == systemFontScale } ?: FontScale.Normal
        )
    }
    val context = LocalContext.current
    val density = Density(context.resources.displayMetrics.density, fontScale.scale)
    val onChangeFontScale = {
        fontScale =
            when (fontScale) {
                FontScale.Small -> FontScale.Normal
                FontScale.Normal -> FontScale.Big
                FontScale.Big -> FontScale.Bigger
                FontScale.Bigger -> FontScale.Small
            }
    }

    val systemLayoutDirection = LocalLayoutDirection.current
    var layoutDirection by remember { mutableStateOf(systemLayoutDirection) }
    val onChangeLayoutDirection = {
        layoutDirection =
            when (layoutDirection) {
                LayoutDirection.Ltr -> LayoutDirection.Rtl
                LayoutDirection.Rtl -> LayoutDirection.Ltr
            }
    }

    CompositionLocalProvider(
        LocalDensity provides density,
        LocalLayoutDirection provides layoutDirection,
    ) {
        SystemUITheme(isDarkTheme) {
            Surface(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                    ConfigurationControls(
                        isDarkTheme,
                        fontScale,
                        layoutDirection,
                        onChangeTheme,
                        onChangeLayoutDirection,
                        onChangeFontScale,
                    )

                    Spacer(Modifier.height(4.dp))

                    MainContent()
                }
            }
        }
    }
}
