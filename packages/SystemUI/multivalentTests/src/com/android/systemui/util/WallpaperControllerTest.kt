/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.util

import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.os.IBinder
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.view.ViewRootImpl
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.eq
import com.android.systemui.wallpapers.data.repository.FakeWallpaperRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidJUnit4::class)
@RunWithLooper
@SmallTest
class WallpaperControllerTest : SysuiTestCase() {

    @Mock private lateinit var wallpaperManager: WallpaperManager
    @Mock private lateinit var root: View
    @Mock private lateinit var viewRootImpl: ViewRootImpl
    @Mock private lateinit var windowToken: IBinder
    private val wallpaperRepository = FakeWallpaperRepository()

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    private lateinit var wallaperController: WallpaperController

    @Before
    fun setup() {
        `when`(root.viewRootImpl).thenReturn(viewRootImpl)
        `when`(root.windowToken).thenReturn(windowToken)
        `when`(root.isAttachedToWindow).thenReturn(true)

        wallaperController = WallpaperController(wallpaperManager, wallpaperRepository)

        wallaperController.rootView = root
    }

    @Test
    fun setNotificationShadeZoom_updatesWallpaperManagerZoom() {
        wallaperController.setNotificationShadeZoom(0.5f)

        verify(wallpaperManager).setWallpaperZoomOut(any(), eq(0.5f))
    }

    @Test
    fun setUnfoldTransitionZoom_updatesWallpaperManagerZoom() {
        wallaperController.setUnfoldTransitionZoom(0.5f)

        verify(wallpaperManager).setWallpaperZoomOut(any(), eq(0.5f))
    }

    @Test
    fun setUnfoldTransitionZoom_defaultUnfoldTransitionIsDisabled_doesNotUpdateWallpaperZoom() {
        wallpaperRepository.wallpaperInfo.value = createWallpaperInfo(useDefaultTransition = false)

        wallaperController.setUnfoldTransitionZoom(0.5f)

        verify(wallpaperManager, never()).setWallpaperZoomOut(any(), anyFloat())
    }

    @Test
    fun setUnfoldTransitionZoomAndNotificationShadeZoom_updatesWithMaximumZoom() {
        wallaperController.setUnfoldTransitionZoom(0.7f)
        clearInvocations(wallpaperManager)

        wallaperController.setNotificationShadeZoom(0.5f)

        verify(wallpaperManager).setWallpaperZoomOut(any(), eq(0.7f))
    }

    @Test
    fun setNotificationShadeZoomAndThenUnfoldTransition_updatesWithMaximumZoom() {
        wallaperController.setNotificationShadeZoom(0.7f)
        clearInvocations(wallpaperManager)

        wallaperController.setUnfoldTransitionZoom(0.5f)

        verify(wallpaperManager).setWallpaperZoomOut(any(), eq(0.7f))
    }

    @Test
    fun setNotificationZoom_invalidWindow_doesNotSetZoom() {
        `when`(root.isAttachedToWindow).thenReturn(false)

        verify(wallpaperManager, times(0)).setWallpaperZoomOut(any(), anyFloat())
    }

    @Test
    fun setNotificationZoom_exceptionWhenUpdatingZoom_doesNotFail() {
        doThrow(IllegalArgumentException("test exception"))
            .`when`(wallpaperManager)
            .setWallpaperZoomOut(any(), anyFloat())

        wallaperController.setNotificationShadeZoom(0.5f)

        verify(wallpaperManager).setWallpaperZoomOut(any(), anyFloat())
    }

    private fun createWallpaperInfo(useDefaultTransition: Boolean = true): WallpaperInfo {
        val info = mock(WallpaperInfo::class.java)
        whenever(info.shouldUseDefaultUnfoldTransition()).thenReturn(useDefaultTransition)
        return info
    }
}
