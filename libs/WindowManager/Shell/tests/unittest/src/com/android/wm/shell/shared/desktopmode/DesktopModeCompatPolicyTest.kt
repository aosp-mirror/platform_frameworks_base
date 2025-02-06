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

package com.android.wm.shell.shared.desktopmode

import android.app.TaskInfo
import android.compat.testing.PlatformCompatChangeRule
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.desktopmode.DesktopTestHelpers.createFreeformTask
import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [@link DesktopModeCompatPolicy].
 *
 * Build/Install/Run: atest WMShellUnitTests:DesktopModeCompatPolicyTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class DesktopModeCompatPolicyTest : ShellTestCase() {
    @get:Rule val compatRule = PlatformCompatChangeRule()
    private lateinit var desktopModeCompatPolicy: DesktopModeCompatPolicy

    @Before
    fun setUp() {
        desktopModeCompatPolicy = DesktopModeCompatPolicy(mContext)
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_onlyTransparentActivitiesInStack() {
        assertTrue(desktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
            createFreeformTask(/* displayId */ 0)
                    .apply {
                        isActivityStackTransparent = true
                        isTopActivityNoDisplay = false
                        numActivities = 1
                    }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_noActivitiesInStack() {
        assertFalse(desktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
            createFreeformTask(/* displayId */ 0)
                .apply {
                    isActivityStackTransparent = true
                    isTopActivityNoDisplay = false
                    numActivities = 0
                }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_nonTransparentActivitiesInStack() {
        assertFalse(desktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
            createFreeformTask(/* displayId */ 0)
                .apply {
                    isActivityStackTransparent = false
                    isTopActivityNoDisplay = false
                    numActivities = 1
                }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_transparentActivityStack_notDisplayed() {
        assertFalse(desktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
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
        assertTrue(desktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
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
        assertFalse(desktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
            createFreeformTask(/* displayId */ 0)
                .apply {
                    baseActivity = baseComponent
                    isTopActivityNoDisplay = true
                }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_defaultHomePackage() {
        val packageManager: PackageManager = mock()
        val homeActivities = ComponentName("defaultHomePackage", /* class */ "")
        whenever(packageManager.getHomeActivities(any())).thenReturn(homeActivities)
        mContext.setMockPackageManager(packageManager)
        assertTrue(desktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
            createFreeformTask(/* displayId */ 0)
                .apply {
                    baseActivity = homeActivities
                    isTopActivityNoDisplay = false
                }))
    }

    @Test
    fun testIsTopActivityExemptFromDesktopWindowing_defaultHomePackage_notDisplayed() {
        val packageManager: PackageManager = mock()
        val homeActivities = ComponentName("defaultHomePackage", /* class */ "")
        whenever(packageManager.getHomeActivities(any())).thenReturn(homeActivities)
        mContext.setMockPackageManager(packageManager)
        assertFalse(desktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
            createFreeformTask(/* displayId */ 0)
                .apply {
                    baseActivity = homeActivities
                    isTopActivityNoDisplay = true
                }))
    }

    @Test
    @EnableFlags(Flags.FLAG_EXCLUDE_CAPTION_FROM_APP_BOUNDS)
    @DisableCompatChanges(ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED)
    fun testShouldExcludeCaptionFromAppBounds_resizeable_false() {
        assertFalse(desktopModeCompatPolicy.shouldExcludeCaptionFromAppBounds(
            setUpFreeformTask().apply { isResizeable = true })
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_EXCLUDE_CAPTION_FROM_APP_BOUNDS)
    @DisableCompatChanges(ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED)
    fun testShouldExcludeCaptionFromAppBounds_nonResizeable_true() {
        assertTrue(desktopModeCompatPolicy.shouldExcludeCaptionFromAppBounds(
            setUpFreeformTask().apply { isResizeable = false })
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_EXCLUDE_CAPTION_FROM_APP_BOUNDS)
    @EnableCompatChanges(ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED)
    fun testShouldExcludeCaptionFromAppBounds_nonResizeable_sdk35_false() {
        assertFalse(desktopModeCompatPolicy.shouldExcludeCaptionFromAppBounds(
            setUpFreeformTask().apply { isResizeable = false })
        )
    }

    fun setUpFreeformTask(): TaskInfo =
        createFreeformTask().apply {
            val componentName =
                ComponentName.createRelative(
                    mContext,
                    DesktopModeCompatPolicyTest::class.java.simpleName
                )
            baseActivity = componentName
            topActivityInfo = ActivityInfo().apply {
                applicationInfo = ApplicationInfo().apply {
                    packageName = componentName.packageName
                    uid = Process.myUid()
                }
            }
        }
}
