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

package android.content.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;

class PackageUserStateInternalDefault extends PackageUserStateDefault implements
        PackageUserStateInternal {

    @Nullable
    @Override
    public ArrayMap<String, SuspendParams> getSuspendParams() {
        return null;
    }

    @Nullable
    @Override
    public ArraySet<String> getDisabledComponentsNoCopy() {
        return null;
    }

    @Nullable
    @Override
    public ArraySet<String> getEnabledComponentsNoCopy() {
        return null;
    }

    @Nullable
    @Override
    public Pair<String, Integer> getOverrideLabelIconForComponent(
            @NonNull ComponentName componentName) {
        return null;
    }
}
