package com.android.systemui.shared.regionsampling

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.util.concurrent.Executor
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidJUnit4::class)
@SmallTest
class RegionSamplerTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Mock private lateinit var sampledView: View
    @Mock private lateinit var mainExecutor: Executor
    @Mock private lateinit var bgExecutor: Executor
    @Mock private lateinit var pw: PrintWriter
    @Mock private lateinit var wallpaperManager: WallpaperManager
    @Mock private lateinit var updateForegroundColor: UpdateColorCallback

    private lateinit var regionSampler: RegionSampler // lockscreen
    private lateinit var homescreenRegionSampler: RegionSampler

    @Captor
    private lateinit var colorsChangedListener:
        ArgumentCaptor<WallpaperManager.LocalWallpaperColorConsumer>

    @Captor private lateinit var layoutChangedListener: ArgumentCaptor<View.OnLayoutChangeListener>

    @Before
    fun setUp() {
        whenever(sampledView.isAttachedToWindow).thenReturn(true)
        whenever(sampledView.width).thenReturn(100)
        whenever(sampledView.height).thenReturn(100)
        whenever(sampledView.isLaidOut).thenReturn(true)
        whenever(sampledView.locationOnScreen).thenReturn(intArrayOf(0, 0))

        regionSampler =
            RegionSampler(
                sampledView,
                mainExecutor,
                bgExecutor,
                regionSamplingEnabled = true,
                isLockscreen = true,
                wallpaperManager,
                updateForegroundColor
            )
        regionSampler.displaySize.set(1080, 2050)

        // TODO(b/265969235): test sampling on home screen via WallpaperManager.FLAG_SYSTEM
        homescreenRegionSampler =
            RegionSampler(
                sampledView,
                mainExecutor,
                bgExecutor,
                regionSamplingEnabled = true,
                isLockscreen = false,
                wallpaperManager,
                updateForegroundColor
            )
    }

    @Test
    fun testCalculatedBounds_inRange() {
        // test calculations return region within [0,1]
        sampledView.setLeftTopRightBottom(100, 100, 200, 200)
        var fractionalBounds =
            regionSampler.calculateScreenLocation(sampledView)?.let {
                regionSampler.convertBounds(it)
            }

        assertTrue(fractionalBounds?.left!! >= 0.0f)
        assertTrue(fractionalBounds.right <= 1.0f)
        assertTrue(fractionalBounds.top >= 0.0f)
        assertTrue(fractionalBounds.bottom <= 1.0f)
    }

    @Test
    fun testEmptyView_returnsEarly() {
        sampledView.setLeftTopRightBottom(0, 0, 0, 0)
        whenever(sampledView.width).thenReturn(0)
        whenever(sampledView.height).thenReturn(0)
        regionSampler.startRegionSampler()
        // returns early so should never call this function
        verify(wallpaperManager, never())
            .addOnColorsChangedListener(
                any(WallpaperManager.LocalWallpaperColorConsumer::class.java),
                any(),
                any()
            )
    }

    @Test
    fun testLayoutChange_notifiesListener() {
        regionSampler.startRegionSampler()
        // don't count addOnColorsChangedListener() call made in startRegionSampler()
        clearInvocations(wallpaperManager)

        verify(sampledView).addOnLayoutChangeListener(capture(layoutChangedListener))
        layoutChangedListener.value.onLayoutChange(
            sampledView,
            300,
            300,
            400,
            400,
            100,
            100,
            200,
            200
        )
        verify(sampledView).removeOnLayoutChangeListener(layoutChangedListener.value)
        verify(wallpaperManager)
            .removeOnColorsChangedListener(
                any(WallpaperManager.LocalWallpaperColorConsumer::class.java)
            )
        verify(wallpaperManager)
            .addOnColorsChangedListener(
                any(WallpaperManager.LocalWallpaperColorConsumer::class.java),
                any(),
                any()
            )
    }

    @Test
    fun testColorsChanged_triggersCallback() {
        regionSampler.startRegionSampler()
        verify(wallpaperManager)
            .addOnColorsChangedListener(
                capture(colorsChangedListener),
                any(),
                eq(WallpaperManager.FLAG_LOCK)
            )
        setWhiteWallpaper()
        verify(updateForegroundColor).invoke()
    }

    @Test
    fun testRegionDarkness() {
        regionSampler.startRegionSampler()
        verify(wallpaperManager)
            .addOnColorsChangedListener(
                capture(colorsChangedListener),
                any(),
                eq(WallpaperManager.FLAG_LOCK)
            )

        // should detect dark region
        setBlackWallpaper()
        assertThat(regionSampler.currentRegionDarkness()).isEqualTo(RegionDarkness.DARK)

        // should detect light region
        setWhiteWallpaper()
        assertThat(regionSampler.currentRegionDarkness()).isEqualTo(RegionDarkness.LIGHT)
    }

    @Test
    fun testForegroundColor() {
        regionSampler.setForegroundColors(Color.WHITE, Color.BLACK)
        regionSampler.startRegionSampler()
        verify(wallpaperManager)
            .addOnColorsChangedListener(
                capture(colorsChangedListener),
                any(),
                eq(WallpaperManager.FLAG_LOCK)
            )

        // dark background, light text
        setBlackWallpaper()
        assertThat(regionSampler.currentForegroundColor()).isEqualTo(Color.WHITE)

        // light background, dark text
        setWhiteWallpaper()
        assertThat(regionSampler.currentForegroundColor()).isEqualTo(Color.BLACK)
    }

    private fun setBlackWallpaper() {
        val wallpaperColors =
            WallpaperColors(Color.valueOf(Color.BLACK), Color.valueOf(Color.BLACK), null)
        colorsChangedListener.value.onColorsChanged(
            RectF(100.0f, 100.0f, 200.0f, 200.0f),
            wallpaperColors
        )
    }
    private fun setWhiteWallpaper() {
        val wallpaperColors =
            WallpaperColors(
                Color.valueOf(Color.WHITE),
                Color.valueOf(Color.WHITE),
                null,
                WallpaperColors.HINT_SUPPORTS_DARK_TEXT
            )
        colorsChangedListener.value.onColorsChanged(
            RectF(100.0f, 100.0f, 200.0f, 200.0f),
            wallpaperColors
        )
    }

    @Test
    fun testDump() {
        regionSampler.dump(pw)
        homescreenRegionSampler.dump(pw)
    }
}
