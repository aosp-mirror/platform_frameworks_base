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

package com.android.systemui.media.taptotransfer.sender

import android.os.VibrationEffect

/**
 * Represents the different possible transfer states that we could be in and the vibration effects
 * that come with updating transfer states.
 *
 * @property vibrationEffect an optional vibration effect when the transfer status is changed.
 */
enum class TransferStatus(
    val vibrationEffect: VibrationEffect? = null,
) {
    /** The transfer hasn't started yet. */
    NOT_STARTED(
        vibrationEffect =
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 0)
                .compose()
    ),
    /** The transfer is currently ongoing but hasn't completed yet. */
    IN_PROGRESS(
        vibrationEffect =
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 1.0f, 0)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 70)
                .compose(),
    ),
    /** The transfer has completed successfully. */
    SUCCEEDED,
    /** The transfer has completed with a failure. */
    FAILED(vibrationEffect = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK)),
    /** The device is too far away to do a transfer. */
    TOO_FAR,
}
