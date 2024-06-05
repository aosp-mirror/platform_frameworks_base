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

package android.companion.multidevices.bluetooth

import android.companion.cts.uicommon.CompanionDeviceManagerUi
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

class BluetoothUi(private val ui: UiDevice) : CompanionDeviceManagerUi(ui) {
    fun clickAllowButton() = click(ALLOW_BUTTON, "Allow button")

    fun confirmPairingRequest(): Boolean {
        if (ui.hasObject(PAIRING_PIN_ENTRY)) {
            // It is prompting for a custom user pin entry
            Log.d(TAG, "Is user entry prompt.")
            ui.findObject(PAIRING_PIN_ENTRY).text = "0000"
            click(OK_BUTTON, "Ok button")
        } else {
            // It just needs user consent
            Log.d(TAG, "Looking for pair button.")
            val button = ui.wait(Until.findObject(PAIR_BUTTON), 1_000)
            if (button != null) {
                Log.d(TAG, "Pair button found.")
                button.click()
                return true
            }
            Log.d(TAG, "Pair button not found.")
        }
        return false
    }

    companion object {
        private const val TAG = "CDM_BluetoothUi"

        private val ALLOW_TEXT_PATTERN = caseInsensitive("allow")
        private val ALLOW_BUTTON = By.text(ALLOW_TEXT_PATTERN).clickable(true)

        private val PAIRING_PIN_ENTRY = By.clazz(".EditText")

        private val OK_TEXT_PATTERN = caseInsensitive("ok")
        private val OK_BUTTON = By.text(OK_TEXT_PATTERN).clickable(true)

        private val PAIR_TEXT_PATTERN = caseInsensitive("pair")
        private val PAIR_BUTTON = By.text(PAIR_TEXT_PATTERN).clickable(true)

        private fun caseInsensitive(text: String): Pattern =
            Pattern.compile(text, Pattern.CASE_INSENSITIVE)
    }
}
