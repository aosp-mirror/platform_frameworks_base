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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.pkg.FrameworkPackageUserState;
import android.util.Pair;

import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedArraySet;

/**
 * Internal variant of {@link PackageUserState} that includes data not exposed as API. This is
 * still read-only and should be used inside system server code when possible over the
 * implementation.
 */
public interface PackageUserStateInternal extends PackageUserState, FrameworkPackageUserState {

    PackageUserStateInternal DEFAULT = new PackageUserStateDefault();

    // TODO: Make non-null with emptyMap()
    @Nullable
    WatchedArrayMap<String, SuspendParams> getSuspendParams();

    @Nullable
    WatchedArraySet<String> getDisabledComponentsNoCopy();

    @Nullable
    WatchedArraySet<String> getEnabledComponentsNoCopy();

    @Nullable
    Pair<String, Integer> getOverrideLabelIconForComponent(@NonNull ComponentName componentName);
}
