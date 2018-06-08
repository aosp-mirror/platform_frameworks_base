/**
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
 * limitations under the License.
 */

package com.android.server.broadcastradio.hal1;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;

import java.util.Map;
import java.util.Set;

class Convert {
    private static final String TAG = "BroadcastRadioService.Convert";

    /**
     * Converts string map to an array that's easily accessible by native code.
     *
     * Calling this java method once is more efficient than converting map object on the native
     * side, which requires several separate java calls for each element.
     *
     * @param map map to convert.
     * @returns array (sized the same as map) of two-element string arrays
     *          (first element is the key, second is value).
     */
    static @NonNull String[][] stringMapToNative(@Nullable Map<String, String> map) {
        if (map == null) {
            Slog.v(TAG, "map is null, returning zero-elements array");
            return new String[0][0];
        }

        Set<Map.Entry<String, String>> entries = map.entrySet();
        int len = entries.size();
        String[][] arr = new String[len][2];

        int i = 0;
        for (Map.Entry<String, String> entry : entries) {
            arr[i][0] = entry.getKey();
            arr[i][1] = entry.getValue();
            i++;
        }

        Slog.v(TAG, "converted " + i + " element(s)");
        return arr;
    }
}
