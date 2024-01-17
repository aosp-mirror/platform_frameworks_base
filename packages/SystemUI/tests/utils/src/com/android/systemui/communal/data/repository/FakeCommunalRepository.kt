package com.android.systemui.communal.data.repository

import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.dagger.qualifiers.Background
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope

/** Fake implementation of [CommunalRepository]. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeCommunalRepository(
    @Background applicationScope: CoroutineScope = TestScope(),
    override var isCommunalEnabled: Boolean = false,
    override val desiredScene: MutableStateFlow<CommunalSceneKey> =
        MutableStateFlow(CommunalSceneKey.DEFAULT),
) : CommunalRepository {
    override fun setDesiredScene(desiredScene: CommunalSceneKey) {
        this.desiredScene.value = desiredScene
    }

    private val defaultTransitionState =
        ObservableCommunalTransitionState.Idle(CommunalSceneKey.DEFAULT)
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

    fun setIsCommunalEnabled(value: Boolean) {
        isCommunalEnabled = value
    }

    private val _isCommunalHubShowing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isCommunalHubShowing: Flow<Boolean> = _isCommunalHubShowing

    fun setIsCommunalHubShowing(isCommunalHubShowing: Boolean) {
        _isCommunalHubShowing.value = isCommunalHubShowing
    }
}
