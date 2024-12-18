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

package com.android.server.wm.flicker.ime

import android.platform.test.annotations.Presubmit
import android.platform.test.rule.UnlockScreenRule
import android.tools.Rotation
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.traces.component.ComponentNameMatcher
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeAppHelper
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window closing on lock and opening on screen unlock.
 * To run this test: `atest FlickerTestsIme2:ShowImeOnUnlockScreenTest`
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ShowImeOnUnlockScreenTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    private val testApp = ImeAppHelper(instrumentation)
    private val imeOrSnapshot = ComponentNameMatcher.IME.or(ComponentNameMatcher.IME_SNAPSHOT)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.expectedRotationCheckEnabled = false
            testApp.launchViaIntent(wmHelper)
            testApp.openIME(wmHelper)
        }
        transitions {
            device.sleep()
            wmHelper.StateSyncBuilder().withKeyguardShowing().waitForAndVerify()
            UnlockScreenRule.unlockScreen(device)
            wmHelper.StateSyncBuilder().withImeShown().waitForAndVerify()
        }
        teardown { testApp.exit(wmHelper) }
    }

    @Presubmit
    @Test
    fun imeAndAppAnimateTogetherWhenLockingAndUnlocking() {
        flicker.assertLayers {
            this.isVisible(testApp)
                .isVisible(imeOrSnapshot)
                .then()
                .isInvisible(testApp)
                .isInvisible(imeOrSnapshot)
                .then()
                .isVisible(testApp)
                .isVisible(imeOrSnapshot)
        }
    }

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display turns off during transition")
    override fun navBarWindowIsAlwaysVisible() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display turns off during transition")
    override fun statusBarWindowIsAlwaysVisible() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display turns off during transition")
    override fun taskBarWindowIsAlwaysVisible() {}

    @FlakyTest(bugId = 338178020)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
    }
}
