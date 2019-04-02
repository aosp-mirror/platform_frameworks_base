/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.provider.DeviceConfig;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;


/**
 * Collection of utilities for the network stack.
 */
public class NetworkStackUtils {

    /**
     * @return True if the array is null or 0-length.
     */
    public static <T> boolean isEmpty(T[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Close a socket, ignoring any exception while closing.
     */
    public static void closeSocketQuietly(FileDescriptor fd) {
        try {
            SocketUtils.closeSocket(fd);
        } catch (IOException ignored) {
        }
    }

    /**
     * Returns an int array from the given Integer list.
     */
    public static int[] convertToIntArray(@NonNull List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /**
     * Returns a long array from the given long list.
     */
    public static long[] convertToLongArray(@NonNull List<Long> list) {
        long[] array = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /**
     * @return True if there exists at least one element in the sparse array for which
     * condition {@code predicate}
     */
    public static <T> boolean any(SparseArray<T> array, Predicate<T> predicate) {
        for (int i = 0; i < array.size(); ++i) {
            if (predicate.test(array.valueAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Look up the value of a property for a particular namespace from {@link DeviceConfig}.
     * @param namespace The namespace containing the property to look up.
     * @param name The name of the property to look up.
     * @param defaultValue The value to return if the property does not exist or has no non-null
     *                     value.
     * @return the corresponding value, or defaultValue if none exists.
     */
    @Nullable
    public static String getDeviceConfigProperty(@NonNull String namespace, @NonNull String name,
            @Nullable String defaultValue) {
        String value = DeviceConfig.getProperty(namespace, name);
        return value != null ? value : defaultValue;
    }
}
