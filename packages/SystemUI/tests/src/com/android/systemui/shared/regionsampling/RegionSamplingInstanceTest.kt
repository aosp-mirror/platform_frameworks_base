package com.android.systemui.shared.regionsampling

import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.navigationbar.RegionSamplingHelper
import java.io.PrintWriter
import java.util.concurrent.Executor
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class RegionSamplingInstanceTest : SysuiTestCase() {

    @JvmField @Rule
    val mockito = MockitoJUnit.rule()

    @Mock private lateinit var sampledView: View
    @Mock private lateinit var mainExecutor: Executor
    @Mock private lateinit var bgExecutor: Executor
    @Mock private lateinit var regionSampler: RegionSamplingHelper
    @Mock private lateinit var updateFun: RegionSamplingInstance.UpdateColorCallback
    @Mock private lateinit var pw: PrintWriter
    @Mock private lateinit var callback: RegionSamplingHelper.SamplingCallback

    private lateinit var regionSamplingInstance: RegionSamplingInstance

    @Before
    fun setUp() {
        whenever(sampledView.isAttachedToWindow).thenReturn(true)
        whenever(regionSampler.callback).thenReturn(this@RegionSamplingInstanceTest.callback)

        regionSamplingInstance = object : RegionSamplingInstance(
                sampledView,
                mainExecutor,
                bgExecutor,
                true,
                updateFun
        ) {
            override fun createRegionSamplingHelper(
                    sampledView: View,
                    callback: RegionSamplingHelper.SamplingCallback,
                    mainExecutor: Executor?,
                    bgExecutor: Executor?
            ): RegionSamplingHelper {
                return this@RegionSamplingInstanceTest.regionSampler
            }
        }
    }

    @Test
    fun testStartRegionSampler() {
        regionSamplingInstance.startRegionSampler()

        verify(regionSampler).start(Rect(0, 0, 0, 0))
    }

    @Test
    fun testStopRegionSampler() {
        regionSamplingInstance.stopRegionSampler()

        verify(regionSampler).stop()
    }

    @Test
    fun testDump() {
        regionSamplingInstance.dump(pw)

        verify(regionSampler).dump(pw)
    }

    @Test
    fun testUpdateColorCallback() {
        regionSampler.callback.onRegionDarknessChanged(false)
        verify(regionSampler.callback).onRegionDarknessChanged(false)
        clearInvocations(regionSampler.callback)
        regionSampler.callback.onRegionDarknessChanged(true)
        verify(regionSampler.callback).onRegionDarknessChanged(true)
    }

    @Test
    fun testFlagFalse() {
        regionSamplingInstance = object : RegionSamplingInstance(
                sampledView,
                mainExecutor,
                bgExecutor,
                false,
                updateFun
        ) {
            override fun createRegionSamplingHelper(
                    sampledView: View,
                    callback: RegionSamplingHelper.SamplingCallback,
                    mainExecutor: Executor?,
                    bgExecutor: Executor?
            ): RegionSamplingHelper {
                return this@RegionSamplingInstanceTest.regionSampler
            }
        }

        Assert.assertEquals(regionSamplingInstance.regionSampler, null)
    }
}
