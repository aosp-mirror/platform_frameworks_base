/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server;

import android.annotation.Nullable;
import android.app.BroadcastOptions;
import android.os.Bundle;

/**
 * Some utility methods for system server.
 * @hide
 */
public class PendingIntentUtils {
    /**
     * Creates a Bundle that can be used to restrict the background PendingIntents.
     * @param bundle when provided, will merge the extra options to restrict background
     *              PendingIntent into the existing bundle.
     * @return the created Bundle.
     */
    public static Bundle createDontSendToRestrictedAppsBundle(@Nullable Bundle bundle) {
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setDontSendToRestrictedApps(true);
        if (bundle == null) {
            return options.toBundle();
        }
        bundle.putAll(options.toBundle());
        return bundle;
    }

    // Disable the constructor.
    private PendingIntentUtils() {}
}
