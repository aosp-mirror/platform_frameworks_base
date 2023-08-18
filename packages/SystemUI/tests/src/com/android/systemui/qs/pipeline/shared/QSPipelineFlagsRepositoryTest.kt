package com.android.systemui.qs.pipeline.shared

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.junit.runners.Parameterized.Parameters

@SmallTest
@RunWith(Parameterized::class)
class QSPipelineFlagsRepositoryTest : SysuiTestCase() {
    companion object {
        @Parameters(
            name =
                """
WHEN: qs_pipeline_new_host = {0}, qs_pipeline_auto_add = {1}
THEN: pipelineNewHost = {2}, pipelineAutoAdd = {3}
                """
        )
        @JvmStatic
        fun data(): List<Array<Boolean>> =
            (0 until 4).map { combination ->
                val qs_pipeline_new_host = combination and 0b10 != 0
                val qs_pipeline_auto_add = combination and 0b01 != 0
                arrayOf(
                    qs_pipeline_new_host,
                    qs_pipeline_auto_add,
                    /* pipelineNewHost = */ qs_pipeline_new_host,
                    /* pipelineAutoAdd = */ qs_pipeline_new_host && qs_pipeline_auto_add,
                )
            }
    }

    @JvmField @Parameter(0) var qsPipelineNewHostFlag: Boolean = false
    @JvmField @Parameter(1) var qsPipelineAutoAddFlag: Boolean = false
    @JvmField @Parameter(2) var pipelineNewHostExpected: Boolean = false
    @JvmField @Parameter(3) var pipelineAutoAddExpected: Boolean = false

    private val featureFlags = FakeFeatureFlags()
    private val pipelineFlags = QSPipelineFlagsRepository(featureFlags)

    @Before
    fun setUp() {
        featureFlags.apply {
            set(Flags.QS_PIPELINE_NEW_HOST, qsPipelineNewHostFlag)
            set(Flags.QS_PIPELINE_AUTO_ADD, qsPipelineAutoAddFlag)
        }
    }

    @Test
    fun flagLogic() {
        assertThat(pipelineFlags.pipelineHostEnabled).isEqualTo(pipelineNewHostExpected)
        assertThat(pipelineFlags.pipelineAutoAddEnabled).isEqualTo(pipelineAutoAddExpected)
    }
}
