/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.backup.transport;

import android.annotation.Nullable;
import android.os.DeadObjectException;
import android.util.Log;
import android.util.Slog;

import com.android.internal.backup.IBackupTransport;

/** Utility methods for transport-related operations. */
public class TransportUtils {
    private static final String TAG = "TransportUtils";

    /**
     * Throws {@link TransportNotAvailableException} if {@param transport} is null. The semantics is
     * similar to a {@link DeadObjectException} coming from a dead transport binder.
     */
    public static IBackupTransport checkTransport(@Nullable IBackupTransport transport)
            throws TransportNotAvailableException {
        if (transport == null) {
            log(Log.ERROR, TAG, "Transport not available");
            throw new TransportNotAvailableException();
        }
        return transport;
    }

    static void log(int priority, String tag, String message) {
        log(priority, tag, null, message);
    }

    static void log(int priority, String tag, @Nullable String caller, String message) {
        log(priority, tag, "", caller, message);
    }

    static void log(
            int priority, String tag, String prefix, @Nullable String caller, String message) {
        if (Log.isLoggable(tag, priority)) {
            if (caller != null) {
                prefix += "[" + caller + "] ";
            }
            Slog.println(priority, tag, prefix + message);
        }
    }

    private TransportUtils() {}
}
