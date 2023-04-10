package com.android.systemui.shared.regionsampling

import android.app.WallpaperManager
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import java.io.PrintWriter
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class RegionSamplerTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Mock private lateinit var sampledView: View
    @Mock private lateinit var mainExecutor: Executor
    @Mock private lateinit var bgExecutor: Executor
    @Mock private lateinit var pw: PrintWriter
    @Mock private lateinit var wallpaperManager: WallpaperManager

    private lateinit var mRegionSampler: RegionSampler
    private var updateFun: UpdateColorCallback = {}

    @Before
    fun setUp() {
        whenever(sampledView.isAttachedToWindow).thenReturn(true)

        mRegionSampler =
            RegionSampler(sampledView, mainExecutor, bgExecutor, true, updateFun, wallpaperManager)
    }

    @Test
    fun testStartRegionSampler() {
        mRegionSampler.startRegionSampler()
    }

    @Test
    fun testDump() {
        mRegionSampler.dump(pw)
    }
}
