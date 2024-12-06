/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.pinner;

import android.annotation.Nullable;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Slog;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;

/* package */ final class PinnerUtils {
    private static final String TAG = "PinnerUtils";

    public static long clamp(long min, long value, long max) {
        return Math.max(min, Math.min(value, max));
    }

    public static void safeMunmap(long address, long mapSize) {
        try {
            Os.munmap(address, mapSize);
        } catch (ErrnoException ex) {
            Slog.w(TAG, "ignoring error in unmap", ex);
        }
    }

    /**
     * Close FD, swallowing irrelevant errors.
     */
    public static void safeClose(@Nullable FileDescriptor fd) {
        if (fd != null && fd.valid()) {
            try {
                Os.close(fd);
            } catch (ErrnoException ex) {
                // Swallow the exception: non-EBADF errors in close(2)
                // indicate deferred paging write errors, which we
                // don't care about here. The underlying file
                // descriptor is always closed.
                if (ex.errno == OsConstants.EBADF) {
                    throw new AssertionError(ex);
                }
            }
        }
    }

    /**
     * Close closeable thing, swallowing errors.
     */
    public static void safeClose(@Nullable Closeable thing) {
        if (thing != null) {
            try {
                thing.close();
            } catch (IOException ex) {
                Slog.w(TAG, "ignoring error closing resource: " + thing, ex);
            }
        }
    }
}
