/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.media

import android.app.smartspace.SmartspaceAction
import android.content.Intent
import com.android.internal.logging.InstanceId

/** State of a Smartspace media recommendations view. */
data class SmartspaceMediaData(
    /**
     * Unique id of a Smartspace media target.
     */
    val targetId: String,
    /**
     * Indicates if the status is active.
     */
    val isActive: Boolean,
    /**
     * Indicates if all the required data field is valid.
     */
    val isValid: Boolean,
    /**
     * Package name of the media recommendations' provider-app.
     */
    val packageName: String,
    /**
     * Action to perform when the card is tapped. Also contains the target's extra info.
     */
    val cardAction: SmartspaceAction?,
    /**
     * List of media recommendations.
     */
    val recommendations: List<SmartspaceAction>,
    /**
     * Intent for the user's initiated dismissal.
     */
    val dismissIntent: Intent?,
    /**
     * View's background color.
     */
    val backgroundColor: Int,
    /**
     * The timestamp in milliseconds that headphone is connected.
     */
    val headphoneConnectionTimeMillis: Long,
    /**
     * Instance ID for [MediaUiEventLogger]
     */
    val instanceId: InstanceId
)
