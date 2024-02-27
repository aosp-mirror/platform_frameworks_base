/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.helpers.FIND_TIMEOUT
import android.tools.traces.component.ComponentNameMatcher
import android.tools.traces.parsers.toFlickerComponent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions

class MailAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.Mail.LABEL,
    component: ComponentNameMatcher = ActivityOptions.Mail.COMPONENT.toFlickerComponent()
) : StandardAppHelper(instr, launcherName, component) {

    fun openMail(rowIdx: Int) {
        val rowSel =
            By.res(packageName, "mail_row_item_text").textEndsWith(String.format("%04d", rowIdx))
        var row: UiObject2? = null
        for (i in 1..1000) {
            row = uiDevice.wait(Until.findObject(rowSel), SHORT_WAIT_TIME_MS)
            if (row != null) break
            scrollDown()
        }
        require(row != null) { "" }
        row.click()
        uiDevice.wait(Until.gone(By.res(packageName, MAIL_LIST_RES_ID)), FIND_TIMEOUT)
    }

    fun scrollDown() {
        val listView = waitForMailList()
        listView.scroll(Direction.DOWN, 1.0f)
    }

    fun waitForMailList(): UiObject2 {
        val sel = By.res(packageName, MAIL_LIST_RES_ID).scrollable(true)
        val ret = uiDevice.wait(Until.findObject(sel), FIND_TIMEOUT)
        requireNotNull(ret) { "Unable to find $MAIL_LIST_RES_ID object" }
        return ret
    }

    companion object {
        private const val SHORT_WAIT_TIME_MS = 5000L
        private const val MAIL_LIST_RES_ID = "mail_recycle_view"
    }
}
