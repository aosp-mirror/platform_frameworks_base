package com.android.systemui.communal.data.repository

import com.android.systemui.FeatureFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import javax.inject.Inject

/** Encapsulates the state of communal mode. */
interface CommunalRepository {
    /** Whether communal features are enabled. */
    val isCommunalEnabled: Boolean
}

@SysUISingleton
class CommunalRepositoryImpl
@Inject
constructor(
    private val featureFlags: FeatureFlags,
    private val featureFlagsClassic: FeatureFlagsClassic,
) : CommunalRepository {
    override val isCommunalEnabled: Boolean
        get() =
            featureFlagsClassic.isEnabled(Flags.COMMUNAL_SERVICE_ENABLED) &&
                featureFlags.communalHub()
}
