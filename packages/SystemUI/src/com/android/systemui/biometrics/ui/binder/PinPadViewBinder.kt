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
 *
 */

package com.android.systemui.biometrics.ui.binder

import android.view.KeyEvent
import android.widget.ImeAwareEditText
import com.android.internal.widget.LockscreenCredential
import com.android.systemui.res.R
import com.android.systemui.biometrics.ui.CredentialPasswordView
import com.android.systemui.biometrics.ui.IPinPad
import com.android.systemui.biometrics.ui.PinPadClickListener

/** Binder for IPinPad */
object PinPadViewBinder {
    /** Implements a PinPadClickListener inside a pin pad */
    @JvmStatic
    fun bind(view: IPinPad, credentialPasswordView: CredentialPasswordView) {
        val passwordField: ImeAwareEditText =
            credentialPasswordView.requireViewById(R.id.lockPassword)
        view.setPinPadClickListener(
            object : PinPadClickListener {

                override fun onDigitKeyClick(digit: String?) {
                    passwordField.append(digit)
                }

                override fun onBackspaceClick() {
                    val pin = LockscreenCredential.createPinOrNone(passwordField.text)
                    if (pin.size() > 0) {
                        passwordField.text.delete(
                            passwordField.selectionEnd - 1,
                            passwordField.selectionEnd
                        )
                    }
                    pin.zeroize()
                }

                override fun onEnterKeyClick() {
                    passwordField.dispatchKeyEvent(
                        KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
                    )
                    passwordField.dispatchKeyEvent(
                        KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
                    )
                }
            }
        )
    }
}
