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

package com.android.wm.shell.common.split

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.wm.shell.ShellTestCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@RunWith(AndroidJUnit4::class)
class SplitScreenUtilsTests : ShellTestCase() {

    @Test
    fun getShortcutComponent_nullShortcuts() {
        val launcherApps = mock(LauncherApps::class.java).also {
            `when`(it.getShortcuts(any(), any())).thenReturn(null)
        }
        assertEquals(null, SplitScreenUtils.getShortcutComponent(TEST_PACKAGE,
                TEST_SHORTCUT_ID, UserHandle.CURRENT, launcherApps))
    }

    @Test
    fun getShortcutComponent_noShortcuts() {
        val launcherApps = mock(LauncherApps::class.java).also {
            `when`(it.getShortcuts(any(), any())).thenReturn(ArrayList<ShortcutInfo>())
        }
        assertEquals(null, SplitScreenUtils.getShortcutComponent(TEST_PACKAGE,
                TEST_SHORTCUT_ID, UserHandle.CURRENT, launcherApps))
    }

    @Test
    fun getShortcutComponent_validShortcut() {
        val component = ComponentName(TEST_PACKAGE, TEST_ACTIVITY)
        val shortcutInfo = ShortcutInfo.Builder(context, "id").setActivity(component).build()
        val launcherApps = mock(LauncherApps::class.java).also {
            `when`(it.getShortcuts(any(), any())).thenReturn(arrayListOf(shortcutInfo))
        }
        assertEquals(component, SplitScreenUtils.getShortcutComponent(TEST_PACKAGE,
                TEST_SHORTCUT_ID, UserHandle.CURRENT, launcherApps))
    }

    companion object {
        val TEST_PACKAGE = "com.android.wm.shell.common.split"
        val TEST_ACTIVITY = "TestActivity";
        val TEST_SHORTCUT_ID = "test_shortcut_1"
    }
}