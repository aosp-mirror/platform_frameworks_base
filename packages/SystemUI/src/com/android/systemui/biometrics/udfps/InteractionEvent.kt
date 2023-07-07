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

package com.android.systemui.biometrics.udfps

import android.view.MotionEvent

/** Interaction event between a finger and the under-display fingerprint sensor (UDFPS). */
enum class InteractionEvent {
    /**
     * A finger entered the sensor area. This can originate from either [MotionEvent.ACTION_DOWN] or
     * [MotionEvent.ACTION_MOVE].
     */
    DOWN,

    /**
     * A finger left the sensor area. This can originate from either [MotionEvent.ACTION_UP] or
     * [MotionEvent.ACTION_MOVE].
     */
    UP,

    /**
     * The touch reporting has stopped. This corresponds to [MotionEvent.ACTION_CANCEL]. This should
     * not be confused with [UP]. If there was a finger on the sensor, it may or may not still be on
     * the sensor.
     */
    CANCEL,

    /**
     * The interaction hasn't changed since the previous event. The can originate from any of
     * [MotionEvent.ACTION_DOWN], [MotionEvent.ACTION_MOVE], or [MotionEvent.ACTION_UP] if one of
     * these is true:
     * - There was previously a finger on the sensor, and that finger is still on the sensor.
     * - There was previously no finger on the sensor, and there still isn't.
     */
    UNCHANGED,
}
