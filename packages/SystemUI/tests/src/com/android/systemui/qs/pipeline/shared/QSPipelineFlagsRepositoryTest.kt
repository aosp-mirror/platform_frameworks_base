package com.android.systemui.qs.pipeline.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSPipelineFlagsRepositoryTest : SysuiTestCase() {

    private val underTest = QSPipelineFlagsRepository()

    @Test
    fun pipelineFlagDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_QS_NEW_PIPELINE)

        assertThat(underTest.pipelineEnabled).isFalse()
    }

    @Test
    fun pipelineFlagEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_QS_NEW_PIPELINE)

        assertThat(underTest.pipelineEnabled).isTrue()
    }

    @Test
    fun tilesFlagDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_QS_NEW_TILES)

        assertThat(underTest.tilesEnabled).isFalse()
    }

    @Test
    fun tilesFlagEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_QS_NEW_TILES)

        assertThat(underTest.tilesEnabled).isTrue()
    }
}
