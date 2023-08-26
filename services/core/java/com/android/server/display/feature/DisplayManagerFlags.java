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

package com.android.server.display.feature;

import android.util.Slog;

import com.android.server.display.feature.flags.Flags;

/**
 * Utility class to read the flags used in the display manager server.
 */
public class DisplayManagerFlags {
    private static final boolean DEBUG = false;
    private static final String TAG = "DisplayManagerFlags";
    private static final boolean DEFAULT_IS_CONNECTED_DISPLAY_MANAGEMENT_ENABLED = false;
    private boolean mIsConnectedDisplayManagementEnabled = false;
    private boolean mIsConnectedDisplayManagementEnabledSet = false;

    // TODO(b/297159910): Simplify using READ-ONLY flags when available.
    /** Returns whether connected display management is enabled or not. */
    public boolean isConnectedDisplayManagementEnabled() {
        if (mIsConnectedDisplayManagementEnabledSet) {
            if (DEBUG) {
                Slog.d(TAG, "isConnectedDisplayManagementEnabled. Recall = "
                        + mIsConnectedDisplayManagementEnabled);
            }
            return mIsConnectedDisplayManagementEnabled;
        }
        try {
            mIsConnectedDisplayManagementEnabled = Flags.enableConnectedDisplayManagement();
            if (DEBUG) {
                Slog.d(TAG, "isConnectedDisplayManagementEnabled. Flag value = "
                        + mIsConnectedDisplayManagementEnabled);
            }
        } catch (Throwable ex) {
            if (DEBUG) {
                Slog.i(TAG, "isConnectedDisplayManagementEnabled not available: set to "
                        + DEFAULT_IS_CONNECTED_DISPLAY_MANAGEMENT_ENABLED, ex);
            }
            mIsConnectedDisplayManagementEnabled = DEFAULT_IS_CONNECTED_DISPLAY_MANAGEMENT_ENABLED;
        }
        mIsConnectedDisplayManagementEnabledSet = true;
        return mIsConnectedDisplayManagementEnabled;
    }
}
