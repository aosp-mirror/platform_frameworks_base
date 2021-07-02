/*
 * Copyright (C) 2014 The Android Open Source Project
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

import java.util.List;

/**
 * Runtime bridge between the Backup Manager Service and the App Widget Service,
 * since those two modules are intentionally decoupled for modularity.
 *
 * @hide
 */
public class AppWidgetBackupBridge {
    private static WidgetBackupProvider sAppWidgetService;

    public static void register(WidgetBackupProvider instance) {
        sAppWidgetService = instance;
    }

    public static List<String> getWidgetParticipants(int userId) {
        return (sAppWidgetService != null)
                ? sAppWidgetService.getWidgetParticipants(userId)
                : null;
    }

    /** Returns a byte array of widget data for the specified package or {@code null}. */
    @Nullable
    public static byte[] getWidgetState(String packageName, int userId) {
        return (sAppWidgetService != null)
                ? sAppWidgetService.getWidgetState(packageName, userId)
                : null;
    }

    public static void systemRestoreStarting(int userId) {
        if (sAppWidgetService != null) {
            sAppWidgetService.systemRestoreStarting(userId);
        }
    }

    public static void restoreWidgetState(String packageName, byte[] restoredState, int userId) {
        if (sAppWidgetService != null) {
            sAppWidgetService.restoreWidgetState(packageName, restoredState, userId);
        }
    }

    public static void systemRestoreFinished(int userId) {
        if (sAppWidgetService != null) {
            sAppWidgetService.systemRestoreFinished(userId);
        }
    }
}
