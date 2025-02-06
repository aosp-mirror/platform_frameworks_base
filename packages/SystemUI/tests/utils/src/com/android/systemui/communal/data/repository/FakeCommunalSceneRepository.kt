package com.android.systemui.communal.data.repository

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.communal.shared.model.CommunalScenes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Fake implementation of [CommunalSceneRepository]. */
class FakeCommunalSceneRepository(
    private val applicationScope: CoroutineScope,
    override val currentScene: MutableStateFlow<SceneKey> = MutableStateFlow(CommunalScenes.Default),
) : CommunalSceneRepository {

    override fun changeScene(toScene: SceneKey, transitionKey: TransitionKey?) =
        snapToScene(toScene)

    override fun snapToScene(toScene: SceneKey) {
        applicationScope.launch {
            currentScene.value = toScene
            _transitionState.value = flowOf(ObservableTransitionState.Idle(toScene))
        }
    }

    override suspend fun showHubFromPowerButton() {
        snapToScene(CommunalScenes.Communal)
    }

    private val defaultTransitionState = ObservableTransitionState.Idle(CommunalScenes.Default)
    private val _transitionState = MutableStateFlow<Flow<ObservableTransitionState>?>(null)
    override val transitionState: StateFlow<ObservableTransitionState> =
        _transitionState
            .flatMapLatest { innerFlowOrNull -> innerFlowOrNull ?: flowOf(defaultTransitionState) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = defaultTransitionState,
            )

    override fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        _transitionState.value = transitionState
    }
}
