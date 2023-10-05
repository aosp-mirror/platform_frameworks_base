/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.IWindowManager
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argThat
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ScreenshotDetectionControllerTest {

    @Mock lateinit var windowManager: IWindowManager

    @Mock lateinit var packageManager: PackageManager

    lateinit var controller: ScreenshotDetectionController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        controller = ScreenshotDetectionController(windowManager, packageManager)
    }

    @Test
    fun testMaybeNotifyOfScreenshot_ignoresOverview() {
        val data = ScreenshotData.forTesting()
        data.source = WindowManager.ScreenshotSource.SCREENSHOT_OVERVIEW

        val list = controller.maybeNotifyOfScreenshot(data)

        assertTrue(list.isEmpty())
        verify(windowManager, never()).notifyScreenshotListeners(any())
    }

    @Test
    fun testMaybeNotifyOfScreenshot_emptySet() {
        val data = ScreenshotData.forTesting()
        data.source = WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD

        whenever(windowManager.notifyScreenshotListeners(eq(Display.DEFAULT_DISPLAY)))
            .thenReturn(listOf())

        val list = controller.maybeNotifyOfScreenshot(data)

        assertTrue(list.isEmpty())
    }

    @Test
    fun testMaybeNotifyOfScreenshot_oneApp() {
        val data = ScreenshotData.forTesting()
        data.source = WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD

        val component = ComponentName("package1", "class1")
        val appName = "app name"
        val activityInfo = mock(ActivityInfo::class.java)

        whenever(
                packageManager.getActivityInfo(
                    eq(component),
                    any(PackageManager.ComponentInfoFlags::class.java)
                )
            )
            .thenReturn(activityInfo)
        whenever(activityInfo.loadLabel(eq(packageManager))).thenReturn(appName)

        whenever(windowManager.notifyScreenshotListeners(eq(Display.DEFAULT_DISPLAY)))
            .thenReturn(listOf(component))

        val list = controller.maybeNotifyOfScreenshot(data)

        assertEquals(1, list.size)
        assertEquals(appName, list[0])
    }

    @Test
    fun testMaybeNotifyOfScreenshot_multipleApps() {
        val data = ScreenshotData.forTesting()
        data.source = WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD

        val component1 = ComponentName("package1", "class1")
        val component2 = ComponentName("package2", "class2")
        val component3 = ComponentName("package3", "class3")
        val appName1 = "app name 1"
        val appName2 = "app name 2"
        val appName3 = "app name 3"

        val activityInfo1 = mock(ActivityInfo::class.java)
        val activityInfo2 = mock(ActivityInfo::class.java)
        val activityInfo3 = mock(ActivityInfo::class.java)

        whenever(
                packageManager.getActivityInfo(
                    eq(component1),
                    any(PackageManager.ComponentInfoFlags::class.java)
                )
            )
            .thenReturn(activityInfo1)
        whenever(
                packageManager.getActivityInfo(
                    eq(component2),
                    any(PackageManager.ComponentInfoFlags::class.java)
                )
            )
            .thenReturn(activityInfo2)
        whenever(
                packageManager.getActivityInfo(
                    eq(component3),
                    any(PackageManager.ComponentInfoFlags::class.java)
                )
            )
            .thenReturn(activityInfo3)

        whenever(activityInfo1.loadLabel(eq(packageManager))).thenReturn(appName1)
        whenever(activityInfo2.loadLabel(eq(packageManager))).thenReturn(appName2)
        whenever(activityInfo3.loadLabel(eq(packageManager))).thenReturn(appName3)

        whenever(windowManager.notifyScreenshotListeners(eq(Display.DEFAULT_DISPLAY)))
            .thenReturn(listOf(component1, component2, component3))

        val list = controller.maybeNotifyOfScreenshot(data)

        assertEquals(3, list.size)
        assertEquals(appName1, list[0])
        assertEquals(appName2, list[1])
        assertEquals(appName3, list[2])
    }

    private fun includesFlagBits(@PackageManager.ComponentInfoFlagsBits mask: Int) =
        ComponentInfoFlagMatcher(mask, mask)
    private fun excludesFlagBits(@PackageManager.ComponentInfoFlagsBits mask: Int) =
        ComponentInfoFlagMatcher(mask, 0)

    private class ComponentInfoFlagMatcher(
        @PackageManager.ComponentInfoFlagsBits val mask: Int, val value: Int
    ): ArgumentMatcher<PackageManager.ComponentInfoFlags> {
        override fun matches(flags: PackageManager.ComponentInfoFlags?): Boolean {
            return flags != null && (mask.toLong() and flags.value) == value.toLong()
        }

        override fun toString(): String{
            return "mask 0x%08x == 0x%08x".format(mask, value)
        }
    }

    @Test
    fun testMaybeNotifyOfScreenshot_disabledApp() {
        val data = ScreenshotData.forTesting()
        data.source = WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD

        val component = ComponentName("package1", "class1")
        val appName = "app name"
        val activityInfo = mock(ActivityInfo::class.java)

        whenever(
            packageManager.getActivityInfo(
                eq(component),
                argThat(includesFlagBits(MATCH_DISABLED_COMPONENTS))
            )
        ).thenReturn(activityInfo);

        whenever(
            packageManager.getActivityInfo(
                eq(component),
                argThat(excludesFlagBits(MATCH_DISABLED_COMPONENTS))
            )
        ).thenThrow(PackageManager.NameNotFoundException::class.java);

        whenever(windowManager.notifyScreenshotListeners(eq(Display.DEFAULT_DISPLAY)))
            .thenReturn(listOf(component))

        whenever(activityInfo.loadLabel(eq(packageManager))).thenReturn(appName)

        val list = controller.maybeNotifyOfScreenshot(data)

        assertEquals(1, list.size)
        assertEquals(appName, list[0])
    }

}
