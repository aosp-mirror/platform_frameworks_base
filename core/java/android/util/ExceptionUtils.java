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

package android.util;

import android.annotation.NonNull;
import android.os.ParcelableException;

import com.android.internal.util.Preconditions;

import java.io.IOException;

/**
 * Utility methods for proxying richer exceptions across Binder calls.
 *
 * @hide
 */
public class ExceptionUtils {
    public static RuntimeException wrap(IOException e) {
        throw new ParcelableException(e);
    }

    public static void maybeUnwrapIOException(RuntimeException e) throws IOException {
        if (e instanceof ParcelableException) {
            ((ParcelableException) e).maybeRethrow(IOException.class);
        }
    }

    public static String getCompleteMessage(String msg, Throwable t) {
        final StringBuilder builder = new StringBuilder();
        if (msg != null) {
            builder.append(msg).append(": ");
        }
        builder.append(t.getMessage());
        while ((t = t.getCause()) != null) {
            builder.append(": ").append(t.getMessage());
        }
        return builder.toString();
    }

    public static String getCompleteMessage(Throwable t) {
        return getCompleteMessage(null, t);
    }

    public static RuntimeException propagate(@NonNull Throwable t) {
        Preconditions.checkNotNull(t);
        if (t instanceof Error) throw (Error)t;
        if (t instanceof RuntimeException) throw (RuntimeException)t;
        throw new RuntimeException(t);
    }
}
