/*
 * Copyright 2020 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

/**
 * @hide
 */
public class MediaRouter2Utils {

    static final String TAG = "MR2Utils";
    static final String SEPARATOR = ":";

    @NonNull
    public static String toUniqueId(@NonNull String providerId, @NonNull String id) {
        if (TextUtils.isEmpty(providerId)) {
            Log.w(TAG, "toUniqueId: providerId shouldn't be empty");
            return null;
        }
        if (TextUtils.isEmpty(id)) {
            Log.w(TAG, "toUniqueId: id shouldn't be null");
            return null;
        }

        return providerId + SEPARATOR + id;
    }

    /**
     * Gets provider ID from unique ID.
     * If the corresponding provider ID could not be generated, it will return null.
     */
    @Nullable
    public static String getProviderId(@NonNull String uniqueId) {
        if (TextUtils.isEmpty(uniqueId)) {
            Log.w(TAG, "getProviderId: uniqueId shouldn't be empty");
            return null;
        }

        int firstIndexOfSeparator = uniqueId.indexOf(SEPARATOR);
        if (firstIndexOfSeparator == -1) {
            return null;
        }

        String providerId = uniqueId.substring(0, firstIndexOfSeparator);
        if (TextUtils.isEmpty(providerId)) {
            return null;
        }

        return providerId;
    }

    /**
     * Gets the original ID (i.e. non-unique route/session ID) from unique ID.
     * If the corresponding ID could not be generated, it will return null.
     */
    @Nullable
    public static String getOriginalId(@NonNull String uniqueId) {
        if (TextUtils.isEmpty(uniqueId)) {
            Log.w(TAG, "getOriginalId: uniqueId shouldn't be empty");
            return null;
        }

        int firstIndexOfSeparator = uniqueId.indexOf(SEPARATOR);
        if (firstIndexOfSeparator == -1 || firstIndexOfSeparator + 1 >= uniqueId.length()) {
            return null;
        }

        String providerId = uniqueId.substring(firstIndexOfSeparator + 1);
        if (TextUtils.isEmpty(providerId)) {
            return null;
        }

        return providerId;
    }
}
