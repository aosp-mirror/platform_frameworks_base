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

package com.android.server.wm.flicker.launch.common

import android.platform.test.annotations.Presubmit
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.navBarLayerPositionAtEnd
import com.android.server.wm.flicker.statusBarLayerPositionAtEnd
import org.junit.Assume
import org.junit.Ignore
import org.junit.Test

/** Base class for app launch tests from lock screen */
abstract class OpenAppFromLockscreenTransition(flicker: LegacyFlickerTest) :
    OpenAppTransition(flicker) {

    /** Defines the transition used to run the test */
    override val transition: FlickerBuilder.() -> Unit = {
        super.transition(this)
        setup {
            device.sleep()
            wmHelper.StateSyncBuilder().withoutTopVisibleAppWindows().waitForAndVerify()
        }
        teardown { testApp.exit(wmHelper) }
        transitions { testApp.launchViaIntent(wmHelper) }
    }

    /** Check that we go from no focus to focus on the [testApp] */
    @Presubmit
    @Test
    open fun focusChanges() {
        flicker.assertEventLog { this.focusChanges("", testApp.packageName) }
    }

    /**
     * Checks that we start of with no top windows and then [testApp] becomes the first and only top
     * window of the transition, with snapshot or splash screen windows optionally showing first.
     */
    @FlakyTest(bugId = 203538234)
    @Test
    open fun appWindowBecomesFirstAndOnlyTopWindow() {
        flicker.assertWm {
            this.hasNoVisibleAppWindow()
                .then()
                .isAppWindowOnTop(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isAppWindowOnTop(ComponentNameMatcher.SPLASH_SCREEN, isOptional = true)
                .then()
                .isAppWindowOnTop(testApp)
        }
    }

    /** Checks that the screen is locked at the start of the transition */
    @Presubmit
    @Test
    fun screenLockedStart() {
        flicker.assertLayersStart { isEmpty() }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 203538234)
    @Test
    override fun appWindowBecomesVisible() = super.appWindowBecomesVisible()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun navBarLayerPositionAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun statusBarLayerPositionAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun taskBarLayerIsVisibleAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun taskBarWindowIsAlwaysVisible() {}

    /** Checks the position of the [ComponentNameMatcher.NAV_BAR] at the end of the transition */
    @Presubmit
    @Test
    open fun navBarLayerPositionAtEnd() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.navBarLayerPositionAtEnd()
    }

    /** Checks the position of the [ComponentNameMatcher.STATUS_BAR] at the end of the transition */
    @Presubmit @Test fun statusBarLayerPositionAtEnd() = flicker.statusBarLayerPositionAtEnd()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun statusBarLayerIsVisibleAtStartAndEnd() {}

    /**
     * Checks that the [ComponentNameMatcher.STATUS_BAR] layer is visible at the end of the trace
     *
     * It is not possible to check at the start because the screen is off
     */
    @Presubmit
    @Test
    fun statusBarLayerIsVisibleAtEnd() {
        flicker.assertLayersEnd { this.isVisible(ComponentNameMatcher.STATUS_BAR) }
    }
}
