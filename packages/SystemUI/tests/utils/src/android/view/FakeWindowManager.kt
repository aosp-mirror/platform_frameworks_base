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

package android.view

import android.content.Context
import android.graphics.Region
import android.view.WindowManager.LayoutParams

class FakeWindowManager(private val context: Context) : WindowManager {

    val addedViews = mutableMapOf<View, LayoutParams>()

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        addedViews[view] = params as LayoutParams
    }

    override fun removeView(view: View) {
        addedViews.remove(view)
    }

    override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        addedViews[view] = params as LayoutParams
    }

    override fun getApplicationLaunchKeyboardShortcuts(deviceId: Int): KeyboardShortcutGroup {
        return KeyboardShortcutGroup("Fake group")
    }

    override fun getCurrentImeTouchRegion(): Region {
        return Region.obtain()
    }

    override fun getDefaultDisplay(): Display {
        return context.display
    }

    override fun removeViewImmediate(view: View) {
        addedViews.remove(view)
    }

    override fun requestAppKeyboardShortcuts(
        receiver: WindowManager.KeyboardShortcutsReceiver,
        deviceId: Int,
    ) {
        receiver.onKeyboardShortcutsReceived(emptyList())
    }
}
