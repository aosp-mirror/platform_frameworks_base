package com.android.systemui.unfold

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.WallpaperController
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidJUnit4::class)
@SmallTest
class UnfoldTransitionWallpaperControllerTest : SysuiTestCase() {

    @Mock private lateinit var wallpaperController: WallpaperController

    private val progressProvider = FakeUnfoldTransitionProvider()

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    private lateinit var unfoldWallpaperController: UnfoldTransitionWallpaperController

    @Before
    fun setup() {
        unfoldWallpaperController =
            UnfoldTransitionWallpaperController(progressProvider, wallpaperController)
        unfoldWallpaperController.init()
    }

    @Test
    fun onTransitionProgress_zoomsIn() {
        progressProvider.onTransitionProgress(0.8f)

        verify(wallpaperController).setUnfoldTransitionZoom(eq(0.2f, 0.001f))
    }

    @Test
    fun onTransitionFinished_resetsZoom() {
        progressProvider.onTransitionFinished()

        verify(wallpaperController).setUnfoldTransitionZoom(eq(0f, 0.001f))
    }
}
