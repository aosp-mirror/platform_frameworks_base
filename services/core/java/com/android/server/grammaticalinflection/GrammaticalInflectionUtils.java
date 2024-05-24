/**
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.grammaticalinflection;

import static android.Manifest.permission.READ_SYSTEM_GRAMMATICAL_GENDER;

import android.annotation.NonNull;
import android.content.AttributionSource;
import android.permission.PermissionManager;
import android.util.Log;

/**
 * Utility methods for system grammatical gender.
 */
public class GrammaticalInflectionUtils {

    private static final String TAG = "GrammaticalInflectionUtils";

    public static boolean checkSystemGrammaticalGenderPermission(
            @NonNull PermissionManager permissionManager,
            @NonNull AttributionSource attributionSource) {
        int permissionCheckResult = permissionManager.checkPermissionForDataDelivery(
                READ_SYSTEM_GRAMMATICAL_GENDER,
                attributionSource, /* message= */ null);
        if (permissionCheckResult != PermissionManager.PERMISSION_GRANTED) {
            Log.v(TAG, "AttributionSource: " + attributionSource
                    + " does not have READ_SYSTEM_GRAMMATICAL_GENDER permission.");
            return false;
        }
        return true;
    }
}
