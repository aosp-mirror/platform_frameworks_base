package com.android.systemui.communal.data.repository

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.communal.shared.model.CommunalScenes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Fake implementation of [CommunalRepository]. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeCommunalRepository(
    applicationScope: CoroutineScope,
    override val desiredScene: MutableStateFlow<SceneKey> =
        MutableStateFlow(CommunalScenes.Default),
) : CommunalRepository {
    override fun setDesiredScene(desiredScene: SceneKey) {
        this.desiredScene.value = desiredScene
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
