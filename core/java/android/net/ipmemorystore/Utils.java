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

package android.net.ipmemorystore;

import android.annotation.NonNull;

/** {@hide} */
public class Utils {
    /** Pretty print */
    public static String blobToString(final Blob blob) {
        final StringBuilder sb = new StringBuilder("Blob : [");
        if (blob.data.length <= 24) {
            appendByteArray(sb, blob.data, 0, blob.data.length);
        } else {
            appendByteArray(sb, blob.data, 0, 16);
            sb.append("...");
            appendByteArray(sb, blob.data, blob.data.length - 8, blob.data.length);
        }
        sb.append("]");
        return sb.toString();
    }

    // Adds the hex representation of the array between the specified indices (inclusive, exclusive)
    private static void appendByteArray(@NonNull final StringBuilder sb, @NonNull final byte[] ar,
            final int from, final int to) {
        for (int i = from; i < to; ++i) {
            sb.append(String.format("%02X", ar[i]));
        }
    }
}
