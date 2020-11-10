/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.flicker.pip.tv

import android.os.SystemClock
import androidx.test.filters.RequiresDevice
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Test Pip Menu on TV.
 * To run this test: `atest WMShellFlickerTests:TvPipMenuTests`
 */
@RequiresDevice
@RunWith(Parameterized::class)
class TvPipMenuTests(rotationName: String, rotation: Int)
    : TvPipTestBase(rotationName, rotation) {

    @Before
    override fun setUp() {
        super.setUp()
        // Launch the app and go to PiP
        testApp.launchViaIntent()
        testApp.clickEnterPipButton()
    }

    @Test
    fun pipMenu_open() {
        // Pressing the Window key should bring up Pip menu
        uiDevice.pressWindowKey()
        val pipMenu = uiDevice.waitForTvPipMenu()
                ?: fail("Pip notification should have been dismissed")

        assertTrue("Pip menu should be shown fullscreen", pipMenu.isFullscreen(uiDevice))

        testApp.closePipWindow()
    }

    @Test
    fun pipMenu_backButton() {
        // Pressing the Window key should bring up Pip menu
        uiDevice.pressWindowKey()
        assertNotNull("Pip notification should have been dismissed", uiDevice.waitForTvPipMenu())

        // Pressing the Back key should close the Pip menu
        uiDevice.pressBack()
        assertTrue("Pip notification should have closed", uiDevice.waitForTvPipMenuToClose())

        testApp.closePipWindow()
    }

    @Test
    fun pipMenu_closeButton() {
        // Pressing the Window key should bring up Pip menu
        uiDevice.pressWindowKey()
        assertNotNull("Pip notification should have been dismissed", uiDevice.waitForTvPipMenu())

        // PiP menu should contain the Close button
        val closeButton = uiDevice.findTvPipMenuCloseButton()
                ?: fail("\"Close PIP\" button should be shown in Pip menu")

        // Clicking on the Close button should close the app
        closeButton.click()
        assertTrue("\"Close PIP\" button should close the PiP", testApp.waitUntilClosed())
    }

    @Test
    fun pipMenu_fullscreenButton() {
        // Pressing the Window key should bring up Pip menu
        uiDevice.pressWindowKey()
        assertNotNull("Pip notification should have been dismissed", uiDevice.waitForTvPipMenu())

        // PiP menu should contain the Fullscreen button
        val fullscreenButton = uiDevice.findTvPipMenuFullscreenButton()
                ?: fail("\"Full screen\" button should be shown in Pip menu")

        // Clicking on the fullscreen button should return app to the fullscreen mode.
        // Click, wait for 3 seconds, check the app is fullscreen
        fullscreenButton.click()
        SystemClock.sleep(3_000L)
        assertTrue("\"Full screen\" button should open the app fullscreen",
                testApp.ui?.isFullscreen(uiDevice) ?: false)

        // Close the app
        uiDevice.pressBack()
        testApp.waitUntilClosed()
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> = rotationParams
    }
}