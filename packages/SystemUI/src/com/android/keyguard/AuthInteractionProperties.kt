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

import android.os.VibrationAttributes
import com.google.android.msdl.domain.InteractionProperties

/**
 * This class represents the set of [InteractionProperties] that only hold [VibrationAttributes] for
 * the case of user authentication.
 */
data class AuthInteractionProperties(
    override val vibrationAttributes: VibrationAttributes =
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
) : InteractionProperties
