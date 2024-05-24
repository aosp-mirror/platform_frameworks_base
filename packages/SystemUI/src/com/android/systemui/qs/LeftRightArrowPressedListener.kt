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

package com.android.systemui.qs

import android.view.KeyEvent
import android.view.View
import androidx.core.util.Consumer

/**
 * Listens for left and right arrow keys pressed while focus is on the view.
 *
 * Key press is treated as correct when its full lifecycle happened on the view: first
 * [KeyEvent.ACTION_DOWN] was performed, view didn't lose focus in the meantime and then
 * [KeyEvent.ACTION_UP] was performed with the same [KeyEvent.getKeyCode]
 */
class LeftRightArrowPressedListener private constructor() :
    View.OnKeyListener, View.OnFocusChangeListener {

    private var lastKeyCode: Int? = 0
    private var listener: Consumer<Int>? = null

    fun setArrowKeyPressedListener(arrowPressedListener: Consumer<Int>) {
        listener = arrowPressedListener
    }

    override fun onKey(view: View, keyCode: Int, keyEvent: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // only scroll on ACTION_UP as we don't handle longpressing for now. Still we need
            // to intercept even ACTION_DOWN otherwise keyboard focus will be moved before we
            // have a chance to intercept ACTION_UP.
            if (keyEvent.action == KeyEvent.ACTION_UP && keyCode == lastKeyCode) {
                listener?.accept(keyCode)
                lastKeyCode = null
            } else if (keyEvent.repeatCount == 0) {
                // we only read key events that are NOT coming from long pressing because that also
                // causes reading ACTION_DOWN event (with repeated count > 0) when moving focus with
                // arrow from another sibling view
                lastKeyCode = keyCode
            }
            return true
        }
        return false
    }

    override fun onFocusChange(view: View, hasFocus: Boolean) {
        // resetting lastKeyCode so we get fresh cleared state on focus
        if (hasFocus) {
            lastKeyCode = null
        }
    }

    companion object {
        @JvmStatic
        fun createAndRegisterListenerForView(view: View): LeftRightArrowPressedListener {
            val listener = LeftRightArrowPressedListener()
            view.setOnKeyListener(listener)
            view.onFocusChangeListener = listener
            return listener
        }
    }
}
