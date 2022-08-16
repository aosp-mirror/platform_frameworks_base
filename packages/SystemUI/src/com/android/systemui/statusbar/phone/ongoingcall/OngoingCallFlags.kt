/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone.ongoingcall

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject

@SysUISingleton
class OngoingCallFlags @Inject constructor(private val featureFlags: FeatureFlags) {

    fun isStatusBarChipEnabled(): Boolean =
            featureFlags.isEnabled(Flags.ONGOING_CALL_STATUS_BAR_CHIP)

    fun isInImmersiveEnabled(): Boolean = isStatusBarChipEnabled()
            && featureFlags.isEnabled(Flags.ONGOING_CALL_IN_IMMERSIVE)

    fun isInImmersiveChipTapEnabled(): Boolean = isInImmersiveEnabled()
            && featureFlags.isEnabled(Flags.ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP)
}