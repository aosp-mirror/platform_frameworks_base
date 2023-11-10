package com.android.systemui.communal.data.repository

import com.android.systemui.communal.shared.model.CommunalSceneKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Fake implementation of [CommunalRepository]. */
class FakeCommunalRepository(
    override var isCommunalEnabled: Boolean = false,
    override val desiredScene: MutableStateFlow<CommunalSceneKey> =
        MutableStateFlow(CommunalSceneKey.Blank)
) : CommunalRepository {
    override fun setDesiredScene(desiredScene: CommunalSceneKey) {
        this.desiredScene.value = desiredScene
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
