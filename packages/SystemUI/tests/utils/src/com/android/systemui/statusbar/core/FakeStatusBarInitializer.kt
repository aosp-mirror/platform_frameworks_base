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

package com.android.systemui.statusbar.core

import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewUpdatedListener
import com.android.systemui.statusbar.phone.PhoneStatusBarTransitions
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController
import org.mockito.kotlin.mock

class FakeStatusBarInitializer : StatusBarInitializer {

    val statusBarViewController = mock<PhoneStatusBarViewController>()
    val statusBarTransitions = mock<PhoneStatusBarTransitions>()

    var startedByCoreStartable: Boolean = false
        private set

    var initializedByCentralSurfaces: Boolean = false
        private set

    override var statusBarViewUpdatedListener: OnStatusBarViewUpdatedListener? = null
        set(value) {
            field = value
            value?.onStatusBarViewUpdated(statusBarViewController, statusBarTransitions)
        }

    override fun initializeStatusBar() {
        initializedByCentralSurfaces = true
    }

    override fun start() {
        startedByCoreStartable = true
    }
}
