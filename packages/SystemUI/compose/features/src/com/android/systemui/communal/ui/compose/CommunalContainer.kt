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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.android.compose.animation.scene.FixedSizeEdgeDetector
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.transitions
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import kotlinx.coroutines.flow.transform

object Communal {
    object Elements {
        val Content = ElementKey("CommunalContent")
    }
}

val sceneTransitions = transitions {
    from(TransitionSceneKey.Blank, to = TransitionSceneKey.Communal) {
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
fun CommunalContainer(
    modifier: Modifier = Modifier,
    viewModel: CommunalViewModel,
) {
    val currentScene: SceneKey by
        viewModel.currentScene
            .transform<CommunalSceneKey, SceneKey> { value -> value.toTransitionSceneKey() }
            .collectAsState(TransitionSceneKey.Blank)

    // Failsafe to hide the whole SceneTransitionLayout in case of bugginess.
    var showSceneTransitionLayout by remember { mutableStateOf(true) }
    if (!showSceneTransitionLayout) {
        return
    }

    SceneTransitionLayout(
        modifier = modifier.fillMaxSize(),
        currentScene = currentScene,
        onChangeScene = { sceneKey -> viewModel.onSceneChanged(sceneKey.toCommunalSceneKey()) },
        transitions = sceneTransitions,
        edgeDetector = FixedSizeEdgeDetector(ContainerDimensions.EdgeSwipeSize)
    ) {
        scene(
            TransitionSceneKey.Blank,
            userActions =
                mapOf(
                    Swipe(SwipeDirection.Left, fromEdge = Edge.Right) to TransitionSceneKey.Communal
                )
        ) {
            BlankScene { showSceneTransitionLayout = false }
        }

        scene(
            TransitionSceneKey.Communal,
            userActions =
                mapOf(
                    Swipe(SwipeDirection.Right, fromEdge = Edge.Left) to TransitionSceneKey.Blank
                ),
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
                .width(ContainerDimensions.EdgeSwipeSize)
                .align(Alignment.CenterEnd)
                .background(Color(0x55e9f2eb)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

// TODO(b/293899074): Remove these conversions once Compose can be used throughout SysUI.
object TransitionSceneKey {
    val Blank = CommunalSceneKey.Blank.toTransitionSceneKey()
    val Communal = CommunalSceneKey.Communal.toTransitionSceneKey()
}

fun CommunalSceneKey.toTransitionSceneKey(): SceneKey {
    return SceneKey(name = toString(), identity = this)
}

fun SceneKey.toCommunalSceneKey(): CommunalSceneKey {
    return this.identity as CommunalSceneKey
}

object ContainerDimensions {
    val EdgeSwipeSize = 40.dp
}
