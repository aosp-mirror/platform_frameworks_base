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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.DeadObjectException;
import android.util.Log;
import android.util.Slog;

import com.android.internal.backup.IBackupTransport;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
            log(Priority.ERROR, TAG, "Transport not available");
            throw new TransportNotAvailableException();
        }
        return transport;
    }

    static void log(@Priority int priority, String tag, String message) {
        if (priority == Priority.WTF) {
            Slog.wtf(tag, message);
        } else if (Log.isLoggable(tag, priority)) {
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

    /**
     * Create our own constants so we can log WTF using the same APIs. Except for {@link
     * Priority#WTF} all the others have the same value, so can be used directly
     */
    @IntDef({Priority.VERBOSE, Priority.DEBUG, Priority.INFO, Priority.WARN, Priority.WTF})
    @Retention(RetentionPolicy.SOURCE)
    @interface Priority {
        int VERBOSE = Log.VERBOSE;
        int DEBUG = Log.DEBUG;
        int INFO = Log.INFO;
        int WARN = Log.WARN;
        int ERROR = Log.ERROR;
        int WTF = -1;
    }

    private TransportUtils() {}
}
