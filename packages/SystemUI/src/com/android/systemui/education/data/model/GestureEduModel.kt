/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.data.model

import com.android.systemui.contextualeducation.GestureType
import java.time.Instant

/**
 * Model to store education data related to each gesture (e.g. Back, Home, All Apps, Overview). Each
 * gesture stores its own model separately.
 */
data class GestureEduModel(
    val gestureType: GestureType,
    val signalCount: Int = 0,
    val educationShownCount: Int = 0,
    val lastShortcutTriggeredTime: Instant? = null,
    val usageSessionStartTime: Instant? = null,
    val lastEducationTime: Instant? = null,
    val userId: Int
)
