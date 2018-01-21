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
    public static IBackupTransport checkTransportNotNull(@Nullable IBackupTransport transport)
            throws TransportNotAvailableException {
        if (transport == null) {
            log(Log.ERROR, TAG, "Transport not available");
            throw new TransportNotAvailableException();
        }
        return transport;
    }

    static void log(int priority, String tag, String message) {
        if (Log.isLoggable(tag, priority)) {
            Slog.println(priority, tag, message);
        }
    }

    static String formatMessage(@Nullable String prefix, @Nullable String caller, String message) {
        StringBuilder string = new StringBuilder();
        if (prefix != null) {
            string.append(prefix).append(" ");
        }
        if (caller != null) {
            string.append("[").append(caller).append("] ");
        }
        return string.append(message).toString();
    }

    private TransportUtils() {}
}
