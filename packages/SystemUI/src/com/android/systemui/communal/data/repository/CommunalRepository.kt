package com.android.systemui.communal.data.repository

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
    featureFlags: FeatureFlagsClassic,
) : CommunalRepository {
    override val isCommunalEnabled = featureFlags.isEnabled(Flags.COMMUNAL_HUB)
}
