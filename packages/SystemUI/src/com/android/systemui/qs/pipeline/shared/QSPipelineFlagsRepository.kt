package com.android.systemui.qs.pipeline.shared

import com.android.systemui.Flags as AconfigFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.flags.RefactorFlagUtils
import javax.inject.Inject

/** Encapsulate the different QS pipeline flags and their dependencies */
@SysUISingleton
class QSPipelineFlagsRepository
@Inject
constructor(
    private val featureFlags: FeatureFlagsClassic,
) {
    val pipelineEnabled: Boolean
        get() = AconfigFlags.qsNewPipeline()

    /** @see Flags.QS_PIPELINE_NEW_TILES */
    val pipelineTilesEnabled: Boolean
        get() = featureFlags.isEnabled(Flags.QS_PIPELINE_NEW_TILES)

    companion object Utils {
        fun assertInLegacyMode() =
            RefactorFlagUtils.assertInLegacyMode(
                AconfigFlags.qsNewPipeline(),
                AconfigFlags.FLAG_QS_NEW_PIPELINE
            )
    }
}
