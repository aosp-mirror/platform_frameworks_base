package com.android.systemui.communal.data.repository

import com.android.systemui.Flags.communalHub
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Encapsulates the state of communal mode. */
interface CommunalRepository {
    /** Whether communal features are enabled. */
    val isCommunalEnabled: Boolean

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through
     * [setDesiredScene].
     */
    val desiredScene: StateFlow<CommunalSceneKey>

    /** Updates the requested scene. */
    fun setDesiredScene(desiredScene: CommunalSceneKey)
}

@SysUISingleton
class CommunalRepositoryImpl
@Inject
constructor(
    private val featureFlagsClassic: FeatureFlagsClassic,
) : CommunalRepository {
    override val isCommunalEnabled: Boolean
        get() = featureFlagsClassic.isEnabled(Flags.COMMUNAL_SERVICE_ENABLED) && communalHub()

    private val _desiredScene: MutableStateFlow<CommunalSceneKey> =
        MutableStateFlow(CommunalSceneKey.Blank)
    override val desiredScene: StateFlow<CommunalSceneKey> = _desiredScene.asStateFlow()

    override fun setDesiredScene(desiredScene: CommunalSceneKey) {
        _desiredScene.value = desiredScene
    }
}
