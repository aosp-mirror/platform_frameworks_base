package com.android.systemui.communal.ui.compose

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.FixedSizeEdgeDetector
import com.android.compose.animation.scene.LowestZIndexScenePicker
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.observableTransitionState
import com.android.compose.animation.scene.transitions
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalTransitionKeys
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.util.CommunalColors
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.ui.composable.SceneTransitionLayoutDataSource
import com.android.systemui.statusbar.phone.SystemUIDialogFactory

object Communal {
    object Elements {
        val Scrim = ElementKey("Scrim", scenePicker = LowestZIndexScenePicker)
        val Content = ElementKey("CommunalContent")
    }
}

val sceneTransitions = transitions {
    to(CommunalScenes.Communal, key = CommunalTransitionKeys.SimpleFade) {
        spec = tween(durationMillis = 250)
        fade(Communal.Elements.Scrim)
        fade(Communal.Elements.Content)
    }
    to(CommunalScenes.Communal) {
        spec = tween(durationMillis = 1000)
        translate(Communal.Elements.Content, Edge.Right)
        timestampRange(startMillis = 167, endMillis = 334) {
            fade(Communal.Elements.Scrim)
            fade(Communal.Elements.Content)
        }
    }
    to(CommunalScenes.Blank) {
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
    dataSourceDelegator: SceneDataSourceDelegator,
    dialogFactory: SystemUIDialogFactory,
    colors: CommunalColors,
) {
    val coroutineScope = rememberCoroutineScope()
    val currentSceneKey: SceneKey by viewModel.currentScene.collectAsState(CommunalScenes.Blank)
    val state: MutableSceneTransitionLayoutState = remember {
        MutableSceneTransitionLayoutState(
            initialScene = currentSceneKey,
            canChangeScene = { _ -> viewModel.canChangeScene() },
            transitions = sceneTransitions,
            enableInterruptions = false,
        )
    }

    DisposableEffect(state) {
        val dataSource = SceneTransitionLayoutDataSource(state, coroutineScope)
        dataSourceDelegator.setDelegate(dataSource)
        onDispose { dataSourceDelegator.setDelegate(null) }
    }

    // This effect exposes the SceneTransitionLayout's observable transition state to the rest of
    // the system, and unsets it when the view is disposed to avoid a memory leak.
    DisposableEffect(viewModel, state) {
        viewModel.setTransitionState(state.observableTransitionState())
        onDispose { viewModel.setTransitionState(null) }
    }

    SceneTransitionLayout(
        state = state,
        modifier = modifier.fillMaxSize(),
        swipeSourceDetector =
            FixedSizeEdgeDetector(
                dimensionResource(id = R.dimen.communal_gesture_initiation_width)
            ),
    ) {
        scene(
            CommunalScenes.Blank,
            userActions =
                mapOf(
                    Swipe(SwipeDirection.Left, fromSource = Edge.Right) to CommunalScenes.Communal
                )
        ) {
            // This scene shows nothing only allowing for transitions to the communal scene.
            Box(modifier = Modifier.fillMaxSize())
        }

        scene(
            CommunalScenes.Communal,
            userActions =
                mapOf(Swipe(SwipeDirection.Right, fromSource = Edge.Left) to CommunalScenes.Blank)
        ) {
            CommunalScene(viewModel, colors, dialogFactory, modifier = modifier)
        }
    }
}

/** Scene containing the glanceable hub UI. */
@Composable
private fun SceneScope.CommunalScene(
    viewModel: CommunalViewModel,
    colors: CommunalColors,
    dialogFactory: SystemUIDialogFactory,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by colors.backgroundColor.collectAsState()

    Box(
        modifier =
            Modifier.element(Communal.Elements.Scrim)
                .fillMaxSize()
                .background(Color(backgroundColor.toArgb())),
    )
    Box(modifier.element(Communal.Elements.Content)) {
        CommunalHub(viewModel = viewModel, dialogFactory = dialogFactory)
    }
}
