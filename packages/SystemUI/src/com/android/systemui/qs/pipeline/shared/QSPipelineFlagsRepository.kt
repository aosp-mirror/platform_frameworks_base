package com.android.systemui.qs.pipeline.shared

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import javax.inject.Inject

/** Encapsulate the different QS pipeline flags and their dependencies */
@SysUISingleton
class QSPipelineFlagsRepository
@Inject
constructor(
    private val featureFlags: FeatureFlagsClassic,
) {

    /** @see Flags.QS_PIPELINE_NEW_HOST */
    val pipelineHostEnabled: Boolean
        get() = featureFlags.isEnabled(Flags.QS_PIPELINE_NEW_HOST)

    /** @see Flags.QS_PIPELINE_AUTO_ADD */
    val pipelineAutoAddEnabled: Boolean
        get() = pipelineHostEnabled && featureFlags.isEnabled(Flags.QS_PIPELINE_AUTO_ADD)

    /** @see Flags.QS_PIPELINE_NEW_TILES */
    val pipelineTilesEnabled: Boolean
        get() = featureFlags.isEnabled(Flags.QS_PIPELINE_NEW_TILES)
}
