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

package com.android.systemui.media.controls.shared.model

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Process
import com.android.internal.logging.InstanceId

data class MediaRecommendationsModel(
    val key: String,
    val uid: Int = Process.INVALID_UID,
    val packageName: String,
    val instanceId: InstanceId? = null,
    val appName: CharSequence? = null,
    val dismissIntent: Intent? = null,
    /** Whether the model contains enough number of valid recommendations. */
    val areRecommendationsValid: Boolean = false,
    val mediaRecs: List<MediaRecModel>,
)

/** Represents smartspace media recommendation action */
data class MediaRecModel(
    val intent: Intent? = null,
    val title: CharSequence? = null,
    val subtitle: CharSequence? = null,
    val icon: Icon? = null,
    val extras: Bundle? = null,
)
