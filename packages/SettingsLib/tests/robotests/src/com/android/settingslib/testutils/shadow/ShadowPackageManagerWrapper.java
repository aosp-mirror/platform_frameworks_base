/*
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

package com.android.settingslib.testutils.shadow;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.ArrayMap;

import com.android.settingslib.wrapper.PackageManagerWrapper;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shadow for {@link PackageManagerWrapper} to allow stubbing hidden methods.
 */
@Implements(PackageManagerWrapper.class)
public class ShadowPackageManagerWrapper {
    private static final Map<Intent, List<ResolveInfo>> intentServices = new ArrayMap<>();

    @Implementation
    public List<ResolveInfo> queryIntentServicesAsUser(Intent intent, int i, int user) {
        List<ResolveInfo> list = intentServices.get(intent);
        return list != null ? list : Collections.emptyList();
    }

    public static void addResolveInfoForIntent(Intent intent, ResolveInfo info) {
        List<ResolveInfo> infoList = intentServices.computeIfAbsent(intent, k -> new ArrayList<>());
        infoList.add(info);
    }

    public static void reset() {
        intentServices.clear();
    }
}
