/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.testutils.shadow;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.AttributionSource;
import android.content.Context;
import android.content.PermissionChecker;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.HashMap;
import java.util.Map;
/** Shadow class of {@link PermissionChecker}. */
@Implements(PermissionChecker.class)
public class ShadowPermissionChecker {
    private static final Map<String, Map<String, Integer>> RESULTS = new HashMap<>();
    /** Set the result of permission check for a specific permission. */
    public static void setResult(String packageName, String permission, int result) {
        if (!RESULTS.containsKey(packageName)) {
            RESULTS.put(packageName, new HashMap<>());
        }
        RESULTS.get(packageName).put(permission, result);
    }
    /** Check the permission of calling package. */
    @Implementation
    public static int checkCallingPermissionForDataDelivery(
            Context context,
            String permission,
            String packageName,
            String attributionTag,
            String message) {
        return RESULTS.containsKey(packageName) && RESULTS.get(packageName).containsKey(permission)
                ? RESULTS.get(packageName).get(permission)
                : PermissionChecker.checkCallingPermissionForDataDelivery(
                        context, permission, packageName, attributionTag, message);
    }
    /** Check general permission. */
    @Implementation
    public static int checkPermissionForDataDelivery(
            Context context,
            String permission,
            int pid,
            int uid,
            String packageName,
            String attributionTag,
            String message) {
        return RESULTS.containsKey(packageName) && RESULTS.get(packageName).containsKey(permission)
                ? RESULTS.get(packageName).get(permission)
                : PermissionChecker.checkPermissionForDataDelivery(
                        context, permission, pid, uid, packageName, attributionTag, message);
    }
    /** Check general permission. */
    @Implementation
    public static int checkPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName) {
        return checkPermissionForPreflight(context, permission, new AttributionSource(
                uid, packageName, null /*attributionTag*/));
    }
    /** Check general permission. */
    @Implementation
    public static int checkPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, @NonNull AttributionSource attributionSource) {
        final String packageName = attributionSource.getPackageName();
        return RESULTS.containsKey(packageName) && RESULTS.get(packageName).containsKey(permission)
                ? RESULTS.get(packageName).get(permission)
                : PermissionChecker.checkPermissionForPreflight(
                        context, permission, attributionSource);
    }
}
