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

package com.android.systemui.statusbar.notification

import android.content.Context
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject

class NotifPipelineFlags @Inject constructor(
    val context: Context,
    val featureFlags: FeatureFlags
) {
    fun isDevLoggingEnabled(): Boolean =
        featureFlags.isEnabled(Flags.NOTIFICATION_PIPELINE_DEVELOPER_LOGGING)

    fun isSmartspaceDedupingEnabled(): Boolean = featureFlags.isEnabled(Flags.SMARTSPACE)

    fun fullScreenIntentRequiresKeyguard(): Boolean =
        featureFlags.isEnabled(Flags.FSI_REQUIRES_KEYGUARD)

    fun fsiOnDNDUpdate(): Boolean = featureFlags.isEnabled(Flags.FSI_ON_DND_UPDATE)

    val shouldFilterUnseenNotifsOnKeyguard: Boolean by lazy {
        featureFlags.isEnabled(Flags.FILTER_UNSEEN_NOTIFS_ON_KEYGUARD)
    }

    val isNoHunForOldWhenEnabled: Boolean by lazy {
        featureFlags.isEnabled(Flags.NO_HUN_FOR_OLD_WHEN)
    }
}
