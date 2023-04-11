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

package com.android.server.appop;

import android.annotation.NonNull;

import java.util.Map;

/**
 * In-process api for app-ops migration.
 *
 * @hide
 */
public interface AppOpMigrationHelper {

    /**
     * @return a map of app ID to app-op modes (op name -> mode) for a given user.
     */
    @NonNull
    Map<Integer, Map<String, Integer>> getLegacyAppIdAppOpModes(int userId);

    /**
     * @return a map of package name to app-op modes (op name -> mode) for a given user.
     */
    @NonNull
    Map<String, Map<String, Integer>> getLegacyPackageAppOpModes(int userId);

    /**
     * @return AppOps file version, the version is same for all the user.
     */
    int getLegacyAppOpVersion();

    /**
     * @return Whether app-op state exists or not.
     */
    boolean hasLegacyAppOpState();
}
