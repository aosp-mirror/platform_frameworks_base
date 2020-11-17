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

import android.graphics.Rect
import androidx.test.filters.RequiresDevice
import androidx.test.uiautomator.UiObject2
import com.android.wm.shell.flicker.SYSTEM_UI_PACKAGE_NAME
import com.android.wm.shell.flicker.wait
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test Pip Menu on TV.
 * To run this test: `atest WMShellFlickerTests:TvPipMenuTests`
 */
@RequiresDevice
class TvPipMenuTests : TvPipTestBase() {

    private val systemUiResources =
            packageManager.getResourcesForApplication(SYSTEM_UI_PACKAGE_NAME)
    private val pipBoundsWhileInMenu: Rect = systemUiResources.run {
        val bounds = getString(getIdentifier("pip_menu_bounds", "string", SYSTEM_UI_PACKAGE_NAME))
        Rect.unflattenFromString(bounds) ?: error("Could not retrieve PiP menu bounds")
    }
    private val playButtonDescription = systemUiResources.run {
        getString(getIdentifier("pip_play", "string", SYSTEM_UI_PACKAGE_NAME))
    }
    private val pauseButtonDescription = systemUiResources.run {
        getString(getIdentifier("pip_pause", "string", SYSTEM_UI_PACKAGE_NAME))
    }

    @Before
    override fun setUp() {
        super.setUp()
        // Launch the app and go to PiP
        testApp.launchViaIntent()
    }

    @Test
    fun pipMenu_correctPosition() {
        val pipMenu = enterPip_openMenu_assertShown()

        // Make sure it's fullscreen
        assertTrue("Pip menu should be shown fullscreen", pipMenu.isFullscreen(uiDevice))

        // Make sure the PiP task is positioned where it should be.
        val activityBounds: Rect = testApp.ui?.visibleBounds
                ?: error("Could not retrieve PiP Activity bounds")
        assertTrue("Pip Activity is positioned correctly while Pip menu is shown",
                pipBoundsWhileInMenu == activityBounds)

        testApp.closePipWindow()
    }

    @Test
    fun pipMenu_backButton() {
        enterPip_openMenu_assertShown()

        // Pressing the Back key should close the Pip menu
        uiDevice.pressBack()
        assertTrue("Pip menu should have closed", uiDevice.waitForTvPipMenuToClose())

        testApp.closePipWindow()
    }

    @Test
    fun pipMenu_homeButton() {
        enterPip_openMenu_assertShown()

        // Pressing the Home key should close the Pip menu
        uiDevice.pressHome()
        assertTrue("Pip menu should have closed", uiDevice.waitForTvPipMenuToClose())

        testApp.closePipWindow()
    }

    @Test
    fun pipMenu_closeButton() {
        enterPip_openMenu_assertShown()

        // PiP menu should contain the Close button
        val closeButton = uiDevice.findTvPipMenuCloseButton()
                ?: fail("\"Close PIP\" button should be shown in Pip menu")

        // Clicking on the Close button should close the app
        closeButton.click()
        assertTrue("\"Close PIP\" button should close the PiP", testApp.waitUntilClosed())
    }

    @Test
    fun pipMenu_fullscreenButton() {
        enterPip_openMenu_assertShown()

        // PiP menu should contain the Fullscreen button
        val fullscreenButton = uiDevice.findTvPipMenuFullscreenButton()
                ?: fail("\"Full screen\" button should be shown in Pip menu")

        // Clicking on the fullscreen button should return app to the fullscreen mode.
        // Click, wait for the app to go fullscreen
        fullscreenButton.click()
        assertTrue("\"Full screen\" button should open the app fullscreen",
                wait { testApp.ui?.isFullscreen(uiDevice) ?: false })

        // Close the app
        uiDevice.pressBack()
        testApp.waitUntilClosed()
    }

    @Test
    fun pipMenu_mediaPlayPauseButtons() {
        // Start media session before entering PiP
        testApp.clickStartMediaSessionButton()

        enterPip_openMenu_assertShown()

        // PiP menu should contain the Pause button
        val pauseButton = uiDevice.findTvPipMenuElementWithDescription(pauseButtonDescription)
                ?: fail("\"Pause\" button should be shown in Pip menu if there is an active " +
                        "playing media session.")

        // When we pause media, the button should change from Pause to Play
        pauseButton.click()

        // PiP menu should contain the Play button now
        uiDevice.waitForTvPipMenuElementWithDescription(playButtonDescription)
                ?: fail("\"Play\" button should be shown in Pip menu if there is an active " +
                        "paused media session.")

        testApp.closePipWindow()
    }

    private fun enterPip_openMenu_assertShown(): UiObject2 {
        testApp.clickEnterPipButton()
        // Pressing the Window key should bring up Pip menu
        uiDevice.pressWindowKey()
        return uiDevice.waitForTvPipMenu() ?: fail("Pip menu should have been shown")
    }
}