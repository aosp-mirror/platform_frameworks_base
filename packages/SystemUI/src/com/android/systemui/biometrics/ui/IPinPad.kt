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

package com.android.systemui.biometrics.ui

/**
 * Interface for PinPad in auth_credential_pin_view. This is needed when a custom pin pad is
 * preferred to the IME To use a PinPad, one needs to implement IPinPad interface and provide it in
 * auth_credential_pin_view and specify the id as [pin_pad]
 */
interface IPinPad {
    fun setPinPadClickListener(pinPadClickListener: PinPadClickListener)
}

/** The call back interface for onClick event in the view. */
interface PinPadClickListener {
    /**
     * One of the digit key has been clicked.
     *
     * @param digit A String representing a digit between 0 and 9.
     */
    fun onDigitKeyClick(digit: String?)

    /** The backspace key has been clicked. */
    fun onBackspaceClick()

    /** The enter key has been clicked. */
    fun onEnterKeyClick()
}
