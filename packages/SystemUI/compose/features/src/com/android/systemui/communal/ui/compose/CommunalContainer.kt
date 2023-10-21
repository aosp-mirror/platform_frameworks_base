package com.android.systemui.communal.ui.compose

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.transitions
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel

object Scenes {
    val Blank = SceneKey(name = "blank")
    val Communal = SceneKey(name = "communal")
}

object Communal {
    object Elements {
        val Content = ElementKey("CommunalContent")
    }
}

val sceneTransitions = transitions {
    from(Scenes.Blank, to = Scenes.Communal) {
        spec = tween(durationMillis = 500)

        translate(Communal.Elements.Content, Edge.Right)
        fade(Communal.Elements.Content)
    }
}

/**
 * View containing a [SceneTransitionLayout] that shows the communal UI and handles transitions.
 *
 * This is a temporary container to allow the communal UI to use [SceneTransitionLayout] for gesture
 * handling and transitions before the full Flexiglass layout is ready.
 */
@Composable
fun CommunalContainer(modifier: Modifier = Modifier, viewModel: CommunalViewModel) {
    val (currentScene, setCurrentScene) = remember { mutableStateOf(Scenes.Blank) }

    // Failsafe to hide the whole SceneTransitionLayout in case of bugginess.
    var showSceneTransitionLayout by remember { mutableStateOf(true) }
    if (!showSceneTransitionLayout) {
        return
    }

    SceneTransitionLayout(
        modifier = modifier.fillMaxSize(),
        currentScene = currentScene,
        onChangeScene = setCurrentScene,
        transitions = sceneTransitions,
    ) {
        scene(Scenes.Blank, userActions = mapOf(Swipe.Left to Scenes.Communal)) {
            BlankScene { showSceneTransitionLayout = false }
        }

        scene(
            Scenes.Communal,
            userActions = mapOf(Swipe.Right to Scenes.Blank),
        ) {
            CommunalScene(viewModel, modifier = modifier)
        }
    }
}

/**
 * Blank scene that shows over keyguard/dream. This scene will eventually show nothing at all and is
 * only used to allow for transitions to the communal scene.
 */
@Composable
private fun BlankScene(
    modifier: Modifier = Modifier,
    hideSceneTransitionLayout: () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight()
                .width(100.dp)
                .align(Alignment.CenterEnd)
                .background(Color(0x55e9f2eb)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Default scene")

            IconButton(onClick = hideSceneTransitionLayout) {
                Icon(Icons.Filled.Close, contentDescription = "Close button")
            }
        }
    }
}

/** Scene containing the glanceable hub UI. */
@Composable
private fun SceneScope.CommunalScene(
    viewModel: CommunalViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier.element(Communal.Elements.Content)) { CommunalHub(viewModel = viewModel) }
}
