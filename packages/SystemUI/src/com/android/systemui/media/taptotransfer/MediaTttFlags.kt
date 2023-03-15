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

package com.android.systemui.media.taptotransfer

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject

/** Flags related to media tap-to-transfer. */
@SysUISingleton
class MediaTttFlags @Inject constructor(private val featureFlags: FeatureFlags) {
    /** */
    fun isMediaTttEnabled(): Boolean = featureFlags.isEnabled(Flags.MEDIA_TAP_TO_TRANSFER)

    /** Check whether the flag for the receiver success state is enabled. */
    fun isMediaTttReceiverSuccessRippleEnabled(): Boolean =
        featureFlags.isEnabled(Flags.MEDIA_TTT_RECEIVER_SUCCESS_RIPPLE)
}
