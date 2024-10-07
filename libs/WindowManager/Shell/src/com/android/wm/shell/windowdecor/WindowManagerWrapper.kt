/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.windowdecor

import android.view.View
import android.view.WindowManager

/**
 * A wrapper for [WindowManager] to make view manipulation operations related to window
 * decors more testable.
 */
class WindowManagerWrapper (
    private val windowManager: WindowManager
){

    fun addView(v: View, lp: WindowManager.LayoutParams) {
        windowManager.addView(v, lp)
    }

    fun removeViewImmediate(v: View) {
        windowManager.removeViewImmediate(v)
    }

    fun updateViewLayout(v: View, lp: WindowManager.LayoutParams) {
        windowManager.updateViewLayout(v, lp)
    }
}