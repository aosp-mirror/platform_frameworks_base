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

package com.android.systemui.biometrics

/**
 * Interface for controlling the on finger down & on finger up events.
 */
interface AlternateUdfpsTouchProvider {

    /**
     * This operation is used to notify the Fingerprint HAL that
     * a fingerprint has been detected on the device's screen.
     *
     * See fingerprint/ISession#onPointerDown for more details.
     */
    fun onPointerDown(pointerId: Long, x: Int, y: Int, minor: Float, major: Float)

    /**
     * onPointerUp:
     *
     * This operation can be invoked when the HAL is performing any one of: ISession#authenticate,
     * ISession#enroll, ISession#detectInteraction. This operation is used to indicate
     * that a fingerprint that was previously down, is now up.
     *
     * See fingerprint/ISession#onPointerUp for more details.
     */
    fun onPointerUp(pointerId: Long)
}
