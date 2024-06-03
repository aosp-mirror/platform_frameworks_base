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

package com.android.systemui.statusbar.ui

import com.android.systemui.statusbar.policy.FakeConfigurationController

class FakeSystemBarUtilsProxy(
    val fakeConfigurationController: FakeConfigurationController,
    statusBarHeight: Int,
    keyguardStatusBarHeight: Int,
) : SystemBarUtilsProxy {
    var fakeStatusBarHeight: Int = statusBarHeight
        get() = field
        set(value) {
            if (field != value) {
                field = value
                fakeConfigurationController.notifyConfigurationChanged()
            }
        }

    var fakeKeyguardStatusBarHeight = keyguardStatusBarHeight
        get() = field
        set(value) {
            if (field != value) {
                field = value
                fakeConfigurationController.notifyConfigurationChanged()
            }
        }

    override fun getStatusBarHeight(): Int = fakeStatusBarHeight
    override fun getStatusBarHeaderHeightKeyguard(): Int = fakeKeyguardStatusBarHeight
}
