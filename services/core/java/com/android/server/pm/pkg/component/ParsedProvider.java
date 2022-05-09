/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.pkg.component;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PathPermission;
import android.os.PatternMatcher;

import java.util.List;

/** @hide **/
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ParsedProvider extends ParsedMainComponent {

    @Nullable
    String getAuthority();

    int getInitOrder();

    boolean isMultiProcess();

    @NonNull
    List<PathPermission> getPathPermissions();

    @Nullable
    String getReadPermission();

    @NonNull
    List<PatternMatcher> getUriPermissionPatterns();

    @Nullable
    String getWritePermission();

    boolean isForceUriPermissions();

    boolean isGrantUriPermissions();

    boolean isSyncable();
}
