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

package android.companion.utils;

import android.os.Binder;
import android.os.Build;
import android.provider.DeviceConfig;

/**
 * Util class for feature flags
 *
 * @hide
 */
public final class FeatureUtils {

    private static final String NAMESPACE_COMPANION = "companion";

    private static final String PROPERTY_PERM_SYNC_ENABLED = "perm_sync_enabled";

    public static boolean isPermSyncEnabled() {
        // Permissions sync is always enabled in debuggable mode.
        if (Build.isDebuggable()) {
            return true;
        }

        // Clear app identity to read the device config for feature flag.
        final long identity = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getBoolean(NAMESPACE_COMPANION,
                    PROPERTY_PERM_SYNC_ENABLED, false);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private FeatureUtils() {
    }
}
