/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.compatui

import android.content.ComponentName
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.desktopmode.DesktopTestHelpers.createFreeformTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for {@link AppCompatUtils}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:AppCompatUtilsTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class AppCompatUtilsTest : ShellTestCase() {
    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_onlyTransparentActivitiesInStack() {
        assertTrue(isTopActivityExemptFromDesktopWindowing(mContext,
            createFreeformTask(/* displayId */ 0)
                    .apply {
                        isActivityStackTransparent = true
                        isTopActivityNoDisplay = false
                        numActivities = 1
                    }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_noActivitiesInStack() {
        assertFalse(isTopActivityExemptFromDesktopWindowing(mContext,
            createFreeformTask(/* displayId */ 0)
                .apply {
                    isActivityStackTransparent = true
                    isTopActivityNoDisplay = false
                    numActivities = 0
                }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_nonTransparentActivitiesInStack() {
        assertFalse(isTopActivityExemptFromDesktopWindowing(mContext,
            createFreeformTask(/* displayId */ 0)
                .apply {
                    isActivityStackTransparent = false
                    isTopActivityNoDisplay = false
                    numActivities = 1
                }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_transparentActivityStack_notDisplayed() {
        assertFalse(isTopActivityExemptFromDesktopWindowing(mContext,
            createFreeformTask(/* displayId */ 0)
                .apply {
                    isActivityStackTransparent = true
                    isTopActivityNoDisplay = true
                    numActivities = 1
                }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_systemUiTask() {
        val systemUIPackageName = context.resources.getString(R.string.config_systemUi)
        val baseComponent = ComponentName(systemUIPackageName, /* class */ "")
        assertTrue(isTopActivityExemptFromDesktopWindowing(mContext,
            createFreeformTask(/* displayId */ 0)
                    .apply {
                        baseActivity = baseComponent
                        isTopActivityNoDisplay = false
                    }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_systemUiTask_notDisplayed() {
        val systemUIPackageName = context.resources.getString(R.string.config_systemUi)
        val baseComponent = ComponentName(systemUIPackageName, /* class */ "")
        assertFalse(isTopActivityExemptFromDesktopWindowing(mContext,
            createFreeformTask(/* displayId */ 0)
                .apply {
                    baseActivity = baseComponent
                    isTopActivityNoDisplay = true
                }))
    }
}
