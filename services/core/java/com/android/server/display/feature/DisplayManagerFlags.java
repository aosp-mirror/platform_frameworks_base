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

import android.os.Build;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.display.feature.flags.Flags;

import java.util.function.Supplier;

/**
 * Utility class to read the flags used in the display manager server.
 */
public class DisplayManagerFlags {
    private static final boolean DEBUG = false;
    private static final String TAG = "DisplayManagerFlags";
    private boolean mIsConnectedDisplayManagementEnabled = false;
    private boolean mIsConnectedDisplayManagementEnabledSet = false;

    private boolean flagOrSystemProperty(Supplier<Boolean> flagFunction, String flagName) {
        // TODO(b/299462337) Remove when the infrastructure is ready.
        if ((Build.IS_ENG || Build.IS_USERDEBUG)
                    && SystemProperties.getBoolean("persist.sys." + flagName, false)) {
            return true;
        }
        try {
            return flagFunction.get();
        } catch (Throwable ex) {
            if (DEBUG) {
                Slog.i(TAG, "Flags not ready yet. Return false for " + flagName, ex);
            }
            return false;
        }
    }

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
        mIsConnectedDisplayManagementEnabled =
                flagOrSystemProperty(Flags::enableConnectedDisplayManagement,
                        Flags.FLAG_ENABLE_CONNECTED_DISPLAY_MANAGEMENT);
        if (DEBUG) {
            Slog.d(TAG, "isConnectedDisplayManagementEnabled. Flag value = "
                                + mIsConnectedDisplayManagementEnabled);
        }
        mIsConnectedDisplayManagementEnabledSet = true;
        return mIsConnectedDisplayManagementEnabled;
    }
}
