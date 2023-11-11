package com.android.systemui.qs.pipeline.shared

import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSPipelineFlagsRepositoryTest : SysuiTestCase() {

    @Rule @JvmField val setFlagsRule = SetFlagsRule()

    private val fakeFeatureFlagsClassic = FakeFeatureFlagsClassic()

    private val underTest = QSPipelineFlagsRepository(fakeFeatureFlagsClassic)

    @Test
    fun pipelineFlagDisabled() {
        setFlagsRule.disableFlags(Flags.FLAG_QS_NEW_PIPELINE)

        assertThat(underTest.pipelineEnabled).isFalse()
    }

    @Test
    fun pipelineFlagEnabled() {
        setFlagsRule.enableFlags(Flags.FLAG_QS_NEW_PIPELINE)

        assertThat(underTest.pipelineEnabled).isTrue()
    }
}
