package com.android.systemui.communal.ui.compose

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.FixedSizeEdgeDetector
import com.android.compose.animation.scene.LowestZIndexScenePicker
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.observableTransitionState
import com.android.compose.animation.scene.transitions
import com.android.compose.animation.scene.updateSceneTransitionLayoutState
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.communal.ui.compose.extensions.allowGestures
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.res.R
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

object Communal {
    object Elements {
        val Scrim = ElementKey("Scrim", scenePicker = LowestZIndexScenePicker)
        val Content = ElementKey("CommunalContent")
    }
}

val sceneTransitions = transitions {
    to(TransitionSceneKey.Communal) {
        spec = tween(durationMillis = 1000)
        translate(Communal.Elements.Content, Edge.Right)
        timestampRange(startMillis = 167, endMillis = 334) {
            fade(Communal.Elements.Scrim)
            fade(Communal.Elements.Content)
        }
    }
    to(TransitionSceneKey.Blank) {
        spec = tween(durationMillis = 1000)
        translate(Communal.Elements.Content, Edge.Right)
        timestampRange(endMillis = 167) { fade(Communal.Elements.Content) }
        timestampRange(startMillis = 167, endMillis = 334) { fade(Communal.Elements.Scrim) }
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
            .transform { value -> emit(value.toTransitionSceneKey()) }
            .collectAsState(TransitionSceneKey.Blank)
    val sceneTransitionLayoutState =
        updateSceneTransitionLayoutState(
            currentScene,
            onChangeScene = { viewModel.onSceneChanged(it.toCommunalSceneKey()) },
            transitions = sceneTransitions,
        )
    val touchesAllowed by viewModel.touchesAllowed.collectAsState(initial = false)

    // This effect exposes the SceneTransitionLayout's observable transition state to the rest of
    // the system, and unsets it when the view is disposed to avoid a memory leak.
    DisposableEffect(viewModel, sceneTransitionLayoutState) {
        viewModel.setTransitionState(
            sceneTransitionLayoutState.observableTransitionState().map { it.toModel() }
        )
        onDispose { viewModel.setTransitionState(null) }
    }

    SceneTransitionLayout(
        state = sceneTransitionLayoutState,
        modifier = modifier.fillMaxSize().allowGestures(allowed = touchesAllowed),
        swipeSourceDetector =
            FixedSizeEdgeDetector(
                dimensionResource(id = R.dimen.communal_gesture_initiation_width)
            ),
    ) {
        scene(
            TransitionSceneKey.Blank,
            userActions =
                mapOf(
                    Swipe(SwipeDirection.Left, fromSource = Edge.Right) to
                        TransitionSceneKey.Communal
                )
        ) {
            // This scene shows nothing only allowing for transitions to the communal scene.
            Box(modifier = Modifier.fillMaxSize())
        }

        scene(
            TransitionSceneKey.Communal,
            userActions =
                mapOf(
                    Swipe(SwipeDirection.Right, fromSource = Edge.Left) to TransitionSceneKey.Blank
                ),
        ) {
            CommunalScene(viewModel, modifier = modifier)
        }
    }
}

/** Scene containing the glanceable hub UI. */
@Composable
private fun SceneScope.CommunalScene(
    viewModel: BaseCommunalViewModel,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            Modifier.element(Communal.Elements.Scrim)
                .fillMaxSize()
                .background(LocalAndroidColorScheme.current.outlineVariant),
    )
    Box(modifier.element(Communal.Elements.Content)) { CommunalHub(viewModel = viewModel) }
}

// TODO(b/315490861): Remove these conversions once Compose can be used throughout SysUI.
object TransitionSceneKey {
    val Blank = CommunalScenes.Blank.toTransitionSceneKey()
    val Communal = CommunalScenes.Communal.toTransitionSceneKey()
}

// TODO(b/315490861): Remove these conversions once Compose can be used throughout SysUI.
fun SceneKey.toCommunalSceneKey(): CommunalSceneKey {
    return this.identity as CommunalSceneKey
}

// TODO(b/315490861): Remove these conversions once Compose can be used throughout SysUI.
fun CommunalSceneKey.toTransitionSceneKey(): SceneKey {
    return SceneKey(debugName = toString(), identity = this)
}

/**
 * Converts between the [SceneTransitionLayout] state class and our forked data class that can be
 * used throughout SysUI.
 */
// TODO(b/315490861): Remove these conversions once Compose can be used throughout SysUI.
fun ObservableTransitionState.toModel(): ObservableCommunalTransitionState {
    return when (this) {
        is ObservableTransitionState.Idle ->
            ObservableCommunalTransitionState.Idle(scene.toCommunalSceneKey())
        is ObservableTransitionState.Transition ->
            ObservableCommunalTransitionState.Transition(
                fromScene = fromScene.toCommunalSceneKey(),
                toScene = toScene.toCommunalSceneKey(),
                progress = progress,
                isInitiatedByUserInput = isInitiatedByUserInput,
                isUserInputOngoing = isUserInputOngoing,
            )
    }
}
