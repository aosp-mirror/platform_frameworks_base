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
 * limitations under the License
 */

package com.android.systemui.controls.ui

import android.content.Intent
import android.content.res.Configuration
import android.service.dreams.IDreamManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.SingleActivityFactory
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.settings.ControlsSettingsDialogManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.statusbar.policy.KeyguardStateController
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ControlsActivityTest : SysuiTestCase() {
    @Mock private lateinit var uiController: ControlsUiController
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var dreamManager: IDreamManager
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var controlsSettingsDialogManager: ControlsSettingsDialogManager
    @Mock private lateinit var keyguardStateController: KeyguardStateController

    @Rule
    @JvmField
    var activityRule =
        ActivityTestRule(
            /* activityFactory= */ SingleActivityFactory {
                TestableControlsActivity(
                    uiController,
                    broadcastDispatcher,
                    dreamManager,
                    featureFlags,
                    controlsSettingsDialogManager,
                    keyguardStateController,
                )
            },
            /* initialTouchMode= */ false,
            /* launchActivity= */ false,
        )

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        activityRule.launchActivity(Intent())
    }

    @Test
    fun testOrientationChangeForwardsToUiController() {
        val currentConfig = activityRule.activity.resources.configuration
        val newConfig = Configuration(currentConfig)
        newConfig.orientation = switchOrientation(currentConfig.orientation)
        activityRule.runOnUiThread { activityRule.activity.onConfigurationChanged(newConfig) }

        verify(uiController).onSizeChange()
    }

    @Test
    fun testScreenChangeForwardsToUiController() {
        val currentConfig = activityRule.activity.resources.configuration
        val newConfig = Configuration(currentConfig)
        swapHeightWidth(newConfig)
        activityRule.runOnUiThread { activityRule.activity.onConfigurationChanged(newConfig) }

        verify(uiController).onSizeChange()
    }

    @Test
    fun testChangeSmallestScreenSizeForwardsToUiController() {
        val currentConfig = activityRule.activity.resources.configuration
        val newConfig = Configuration(currentConfig)
        newConfig.smallestScreenWidthDp *= 2
        newConfig.screenWidthDp *= 2
        activityRule.runOnUiThread { activityRule.activity.onConfigurationChanged(newConfig) }

        verify(uiController).onSizeChange()
    }

    @Test
    fun testConfigurationChangeSupportsInPlaceChange() {
        val config = Configuration(activityRule.activity.resources.configuration)

        config.orientation = switchOrientation(config.orientation)
        activityRule.runOnUiThread { activityRule.activity.onConfigurationChanged(config) }
        config.orientation = switchOrientation(config.orientation)
        activityRule.runOnUiThread { activityRule.activity.onConfigurationChanged(config) }

        verify(uiController, times(2)).onSizeChange()
    }

    private fun switchOrientation(orientation: Int): Int {
        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_PORTRAIT
        } else {
            Configuration.ORIENTATION_LANDSCAPE
        }
    }

    private fun swapHeightWidth(configuration: Configuration) {
        val oldHeight = configuration.screenHeightDp
        val oldWidth = configuration.screenWidthDp
        configuration.screenHeightDp = oldWidth
        configuration.screenWidthDp = oldHeight
    }
}
