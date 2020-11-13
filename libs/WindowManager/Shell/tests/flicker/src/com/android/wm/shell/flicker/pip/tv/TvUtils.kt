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

import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.android.wm.shell.flicker.SYSTEM_UI_PACKAGE_NAME

/** Id of the root view in the com.android.wm.shell.pip.tv.PipMenuActivity */
private const val TV_PIP_MENU_ROOT_ID = "tv_pip_menu"
private const val TV_PIP_MENU_CLOSE_BUTTON_ID = "close_button"
private const val TV_PIP_MENU_FULLSCREEN_BUTTON_ID = "full_button"

private const val WAIT_TIME_MS = 3_000L

private val tvPipMenuSelector = By.res(SYSTEM_UI_PACKAGE_NAME, TV_PIP_MENU_ROOT_ID)

fun UiDevice.pressWindowKey() = pressKeyCode(KeyEvent.KEYCODE_WINDOW)

fun UiDevice.waitForTvPipMenu(): UiObject2? =
        wait(Until.findObject(tvPipMenuSelector), WAIT_TIME_MS)

fun UiDevice.waitForTvPipMenuToClose(): Boolean = wait(Until.gone(tvPipMenuSelector), WAIT_TIME_MS)

fun UiDevice.findTvPipMenuCloseButton(): UiObject2? = findObject(
        By.res(SYSTEM_UI_PACKAGE_NAME, TV_PIP_MENU_CLOSE_BUTTON_ID))

fun UiDevice.findTvPipMenuFullscreenButton(): UiObject2? = findObject(
        By.res(SYSTEM_UI_PACKAGE_NAME, TV_PIP_MENU_FULLSCREEN_BUTTON_ID))

fun UiDevice.findTvPipMenuElementWithDescription(desc: String): UiObject2? {
    val buttonSelector = By.desc(desc)
    val menuWithButtonSelector = By.copy(tvPipMenuSelector).hasDescendant(buttonSelector)
    return findObject(menuWithButtonSelector)?.findObject(buttonSelector)
}

fun UiDevice.waitForTvPipMenuElementWithDescription(desc: String): UiObject2? {
    val buttonSelector = By.desc(desc)
    val menuWithButtonSelector = By.copy(tvPipMenuSelector).hasDescendant(buttonSelector)
    return wait(Until.findObject(menuWithButtonSelector), WAIT_TIME_MS)
            ?.findObject(buttonSelector)
}

fun UiObject2.isFullscreen(uiDevice: UiDevice): Boolean = visibleBounds.run {
    height() == uiDevice.displayHeight && width() == uiDevice.displayWidth
}