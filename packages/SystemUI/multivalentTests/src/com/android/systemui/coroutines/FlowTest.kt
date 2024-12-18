package com.android.systemui.coroutines

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FlowTest : SysuiTestCase() {

    @Test
    fun collectLastValue() = runTest {
        val flow = flowOf(0, 1, 2)
        val lastValue by collectLastValue(flow)
        assertThat(lastValue).isEqualTo(2)
    }
}
