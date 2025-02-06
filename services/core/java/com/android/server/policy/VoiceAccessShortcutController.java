/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.server.policy;

import android.content.ComponentName;
import android.content.Context;
import android.util.Slog;

import com.android.internal.accessibility.util.AccessibilityUtils;

import androidx.annotation.VisibleForTesting;

import java.util.Set;

/** This class controls voice access shortcut related operations such as toggling, querying. */
class VoiceAccessShortcutController {
    private static final String TAG = VoiceAccessShortcutController.class.getSimpleName();
    private static final String VOICE_ACCESS_LABEL = "Voice Access";

    private final Context mContext;

    VoiceAccessShortcutController(Context context) {
        mContext = context;
    }

    /**
     * A function that toggles voice access service.
     *
     * @return whether voice access is enabled after being toggled.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    boolean toggleVoiceAccess(int userId) {
        final Set<ComponentName> enabledServices =
                AccessibilityUtils.getEnabledServicesFromSettings(mContext, userId);
        ComponentName componentName =
                AccessibilityUtils.getInstalledAccessibilityServiceComponentNameByLabel(
                        mContext, VOICE_ACCESS_LABEL);
        if (componentName == null) {
            Slog.e(TAG, "Toggle Voice Access failed due to componentName being null");
            return false;
        }

        boolean newState = !enabledServices.contains(componentName);
        AccessibilityUtils.setAccessibilityServiceState(mContext, componentName, newState, userId);

        return newState;
    }
}
