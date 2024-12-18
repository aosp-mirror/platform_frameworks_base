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

package com.android.systemui.biometrics.ui.binder

import com.android.systemui.keyguard.ui.view.KeyguardRootView

/** ViewBinder for device entry unlock tracker implemented in vendor */
interface DeviceEntryUnlockTrackerViewBinder {
    /**
     *  Allows vendor binds vendor's view model to the specified view.
     *  @param view the view to be bound
     */
    fun bind(view: KeyguardRootView) {}
}
