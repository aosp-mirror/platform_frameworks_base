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
import android.content.ComponentName
import android.provider.Settings
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.Assert.assertNotNull

class AssistantAppHelper @JvmOverloads constructor(
    val instr: Instrumentation,
    val component: ComponentName = ActivityOptions.ASSISTANT_SERVICE_COMPONENT_NAME,
) {
    protected val uiDevice: UiDevice = UiDevice.getInstance(instr)
    protected val defaultAssistant: String? = Settings.Secure.getString(
        instr.targetContext.contentResolver,
        Settings.Secure.ASSISTANT)
    protected val defaultVoiceInteractionService: String? = Settings.Secure.getString(
        instr.targetContext.contentResolver,
        Settings.Secure.VOICE_INTERACTION_SERVICE)

    fun setDefaultAssistant() {
        Settings.Secure.putString(
            instr.targetContext.contentResolver,
            Settings.Secure.VOICE_INTERACTION_SERVICE,
            component.flattenToString())
        Settings.Secure.putString(
            instr.targetContext.contentResolver,
            Settings.Secure.ASSISTANT,
            component.flattenToString())
    }

    fun resetDefaultAssistant() {
        Settings.Secure.putString(
            instr.targetContext.contentResolver,
            Settings.Secure.VOICE_INTERACTION_SERVICE,
            defaultVoiceInteractionService)
        Settings.Secure.putString(
            instr.targetContext.contentResolver,
            Settings.Secure.ASSISTANT,
            defaultAssistant)
    }

    /**
     * Open Assistance UI.
     *
     * @param longpress open the UI by long pressing power button.
     *  Otherwise open the UI through vioceinteraction shell command directly.
     */
    @JvmOverloads
    fun openUI(longpress: Boolean = false) {
        if (longpress) {
            uiDevice.executeShellCommand("input keyevent --longpress KEYCODE_POWER")
        } else {
            uiDevice.executeShellCommand("cmd voiceinteraction show")
        }
        val ui = uiDevice.wait(
            Until.findObject(By.res(ActivityOptions.FLICKER_APP_PACKAGE, "vis_frame")),
            FIND_TIMEOUT)
        assertNotNull("Can't find Assistant UI after long pressing power button.", ui)
    }
}
