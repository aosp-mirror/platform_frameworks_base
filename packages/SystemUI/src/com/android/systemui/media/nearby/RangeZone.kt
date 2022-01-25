/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.nearby

import androidx.annotation.IntDef
import kotlin.annotation.AnnotationRetention

@IntDef(
        RangeZone.RANGE_UNKNOWN,
        RangeZone.RANGE_FAR,
        RangeZone.RANGE_LONG,
        RangeZone.RANGE_CLOSE,
        RangeZone.RANGE_WITHIN_REACH
)
@Retention(AnnotationRetention.SOURCE)
/** The various range zones a device can be in, in relation to the current device. */
annotation class RangeZone {
    companion object {
        /** Unknown distance range. */
        const val RANGE_UNKNOWN = 0
        /** Distance is very far away from the peer device. */
        const val RANGE_FAR = 1
        /** Distance is relatively long from the peer device, typically a few meters. */
        const val RANGE_LONG = 2
        /** Distance is close to the peer device, typically with one or two meter. */
        const val RANGE_CLOSE = 3
        /** Distance is very close to the peer device, typically within one meter or less. */
        const val RANGE_WITHIN_REACH = 4
    }
}
