/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content.pm.verify.domain;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.Parcel;
import android.util.ArraySet;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * @hide
 */
public class DomainVerificationUtils {

    private static final int STRINGS_TARGET_BYTE_SIZE = IBinder.getSuggestedMaxIpcSizeBytes() / 2;

    /**
     * Write a map containing web hosts to the given parcel, using {@link Parcel#writeBlob(byte[])}
     * if the limit exceeds {@link IBinder#getSuggestedMaxIpcSizeBytes()} / 2. This assumes that the
     * written map is the only data structure in the caller that varies based on the host data set.
     * Other data that will be written to the parcel after this method will not be considered in the
     * calculation.
     */
    public static void writeHostMap(@NonNull Parcel dest, @NonNull Map<String, ?> map) {
        boolean targetSizeExceeded = false;
        int totalSize = dest.dataSize();
        for (String host : map.keySet()) {
            totalSize += estimatedByteSizeOf(host);
            if (totalSize > STRINGS_TARGET_BYTE_SIZE) {
                targetSizeExceeded = true;
                break;
            }
        }

        dest.writeBoolean(targetSizeExceeded);

        if (!targetSizeExceeded) {
            dest.writeMap(map);
            return;
        }

        Parcel data = Parcel.obtain();
        try {
            data.writeMap(map);
            dest.writeBlob(data.marshall());
        } finally {
            data.recycle();
        }
    }

    /**
     * Retrieve a map previously written by {@link #writeHostMap(Parcel, Map)}.
     */
    @NonNull
    @SuppressWarnings("rawtypes")
    public static <T extends Map> T readHostMap(@NonNull Parcel in, @NonNull T map,
            @NonNull ClassLoader classLoader) {
        boolean targetSizeExceeded = in.readBoolean();

        if (!targetSizeExceeded) {
            in.readMap(map, classLoader);
            return map;
        }

        Parcel data = Parcel.obtain();
        try {
            byte[] blob = in.readBlob();
            data.unmarshall(blob, 0, blob.length);
            data.setDataPosition(0);
            data.readMap(map, classLoader);
        } finally {
            data.recycle();
        }

        return map;
    }

    /**
     * {@link ArraySet} variant of {@link #writeHostMap(Parcel, Map)}.
     */
    public static void writeHostSet(@NonNull Parcel dest, @NonNull Set<String> set) {
        boolean targetSizeExceeded = false;
        int totalSize = dest.dataSize();
        for (String host : set) {
            totalSize += estimatedByteSizeOf(host);
            if (totalSize > STRINGS_TARGET_BYTE_SIZE) {
                targetSizeExceeded = true;
                break;
            }
        }

        dest.writeBoolean(targetSizeExceeded);

        if (!targetSizeExceeded) {
            writeSet(dest, set);
            return;
        }

        Parcel data = Parcel.obtain();
        try {
            writeSet(data, set);
            dest.writeBlob(data.marshall());
        } finally {
            data.recycle();
        }
    }

    /**
     * {@link ArraySet} variant of {@link #readHostMap(Parcel, Map, ClassLoader)}.
     */
    @NonNull
    public static Set<String> readHostSet(@NonNull Parcel in) {
        boolean targetSizeExceeded = in.readBoolean();

        if (!targetSizeExceeded) {
            return readSet(in);
        }

        Parcel data = Parcel.obtain();
        try {
            byte[] blob = in.readBlob();
            data.unmarshall(blob, 0, blob.length);
            data.setDataPosition(0);
            return readSet(data);
        } finally {
            data.recycle();
        }
    }

    private static void writeSet(@NonNull Parcel dest, @Nullable Set<String> set) {
        if (set == null) {
            dest.writeInt(-1);
            return;
        }
        dest.writeInt(set.size());
        for (String string : set) {
            dest.writeString(string);
        }
    }

    @NonNull
    private static Set<String> readSet(@NonNull Parcel in) {
        int size = in.readInt();
        if (size == -1) {
            return Collections.emptySet();
        }

        ArraySet<String> set = new ArraySet<>(size);
        for (int count = 0; count < size; count++) {
            set.add(in.readString());
        }
        return set;
    }

    /**
     * Ballpark the size of domains to avoid unnecessary allocation of ashmem when sending domains
     * across the client-server API.
     */
    public static int estimatedByteSizeOf(String string) {
        return string.length() * 2 + 12;
    }
}
