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

package com.android.server.wm.flicker.ime

import android.platform.test.annotations.Presubmit
import android.tools.Rotation
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.traces.component.ComponentNameMatcher
import android.tools.traces.parsers.toFlickerComponent
import android.view.WindowInsets.Type.ime
import android.view.WindowInsets.Type.navigationBars
import android.view.WindowInsets.Type.statusBars
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeShownOnAppStartHelper
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.flicker.testapp.ActivityOptions.Ime.Default.ACTION_START_DIALOG_THEMED_ACTIVITY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME snapshot mechanism won't apply when transitioning from non-IME focused dialog activity.
 * To run this test: `atest FlickerTestsIme:ShowImeWhileDismissingThemedPopupDialogTest`
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ShowImeWhileDismissingThemedPopupDialogTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    private val testApp = ImeShownOnAppStartHelper(instrumentation, flicker.scenario.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            testApp.launchViaIntent(wmHelper)
            testApp.waitIMEShown(wmHelper)
            broadcastActionTrigger.doAction(ACTION_START_DIALOG_THEMED_ACTIVITY)
            wmHelper
                .StateSyncBuilder()
                .withFullScreenApp(
                    ActivityOptions.DialogThemedActivity.COMPONENT.toFlickerComponent()
                )
                .waitForAndVerify()
            // Verify IME insets isn't visible on dialog since it's non-IME focusable window
            assertFalse(testApp.getInsetsVisibleFromDialog(ime()))
            assertTrue(testApp.getInsetsVisibleFromDialog(statusBars()))
            assertTrue(testApp.getInsetsVisibleFromDialog(navigationBars()))
        }
        teardown { testApp.exit(wmHelper) }
        transitions { testApp.dismissDialog(wmHelper) }
    }

    /** Checks that [ComponentNameMatcher.IME] layer becomes visible during the transition */
    @Presubmit @Test fun imeWindowIsAlwaysVisible() = flicker.imeWindowIsAlwaysVisible()

    /** Checks that [ComponentNameMatcher.IME] layer is visible at the end of the transition */
    @Presubmit
    @Test
    fun imeLayerExistsEnd() {
        flicker.assertLayersEnd { this.isVisible(ComponentNameMatcher.IME) }
    }

    /** Checks that [ComponentNameMatcher.IME_SNAPSHOT] layer is invisible always. */
    @Presubmit
    @Test
    fun imeSnapshotNotVisible() {
        flicker.assertLayers { this.isInvisible(ComponentNameMatcher.IME_SNAPSHOT) }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
    }
}
