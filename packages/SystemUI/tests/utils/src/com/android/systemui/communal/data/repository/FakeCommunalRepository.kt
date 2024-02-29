package com.android.systemui.communal.data.repository

import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
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
    override val desiredScene: MutableStateFlow<CommunalSceneKey> =
        MutableStateFlow(CommunalScenes.Default),
) : CommunalRepository {
    override fun setDesiredScene(desiredScene: CommunalSceneKey) {
        this.desiredScene.value = desiredScene
    }

    private val defaultTransitionState =
        ObservableCommunalTransitionState.Idle(CommunalScenes.Default)
    private val _transitionState = MutableStateFlow<Flow<ObservableCommunalTransitionState>?>(null)
    override val transitionState: StateFlow<ObservableCommunalTransitionState> =
        _transitionState
            .flatMapLatest { innerFlowOrNull -> innerFlowOrNull ?: flowOf(defaultTransitionState) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = defaultTransitionState,
            )

    override fun setTransitionState(transitionState: Flow<ObservableCommunalTransitionState>?) {
        _transitionState.value = transitionState
    }
}
