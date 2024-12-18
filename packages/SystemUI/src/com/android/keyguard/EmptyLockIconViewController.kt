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

package com.android.keyguard

import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.res.R
import dagger.Lazy
import javax.inject.Inject

/**
 * Lock icon view logic now lives in DeviceEntryIconViewBinder and ViewModels. Icon is positioned in
 * [com.android.systemui.keyguard.ui.view.layout.sections.DefaultDeviceEntrySection].
 *
 * This class is to bridge the gap between the logic when the DeviceEntryUdfpsRefactor is enabled
 * and the KeyguardBottomAreaRefactor is NOT enabled. This class can and should be removed when both
 * flags are enabled.
 */
@SysUISingleton
class EmptyLockIconViewController
@Inject
constructor(
    private val keyguardRootView: Lazy<KeyguardRootView>,
) : LockIconViewController {
    private val deviceEntryIconViewId = R.id.device_entry_icon_view
    override fun setLockIconView(lockIconView: LockIconView) {
        // no-op
    }

    override fun getTop(): Float {
        return keyguardRootView.get().getViewById(deviceEntryIconViewId)?.top?.toFloat() ?: 0f
    }

    override fun getBottom(): Float {
        return keyguardRootView.get().getViewById(deviceEntryIconViewId)?.bottom?.toFloat() ?: 0f
    }

    override fun dozeTimeTick() {
        // no-op
    }

    override fun setAlpha(alpha: Float) {
        // no-op
    }

    override fun willHandleTouchWhileDozing(event: MotionEvent): Boolean {
        return false
    }
}
