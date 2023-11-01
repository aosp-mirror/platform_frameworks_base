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
package com.android.systemui.biometrics

import android.hardware.biometrics.common.AuthenticateReason
import kotlinx.coroutines.flow.Flow

/**
 * Provides the status of the interactive to auth feature.
 *
 * This controls whether fingerprint authentication can be used to unlock the device any time versus
 * only when the device is interactive. This is controlled by the user through a settings toggle.
 */
interface FingerprintInteractiveToAuthProvider {
    /**
     * Whether the setting is enabled for the current user. This is the opposite of the "Touch to
     * Unlock" settings toggle.
     */
    val enabledForCurrentUser: Flow<Boolean>

    /**
     * @param userId the user Id.
     * @return Vendor extension if needed for authentication.
     */
    fun getVendorExtension(userId: Int): AuthenticateReason.Vendor?
}
