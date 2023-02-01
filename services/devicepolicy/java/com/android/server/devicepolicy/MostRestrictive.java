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

package com.android.server.devicepolicy;

import android.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MostRestrictive<V> extends ResolutionMechanism<V> {

    private List<V> mMostToLeastRestrictive;

    MostRestrictive(@NonNull List<V> mostToLeastRestrictive) {
        mMostToLeastRestrictive = mostToLeastRestrictive;
    }

    @Override
    V resolve(@NonNull LinkedHashMap<EnforcingAdmin, V> adminPolicies) {
        if (adminPolicies.isEmpty()) {
            return null;
        }
        for (V value : mMostToLeastRestrictive) {
            if (adminPolicies.containsValue(value)) {
                return value;
            }
        }
        // Return first set policy if none can be found in known values
        Map.Entry<EnforcingAdmin, V> policy = adminPolicies.entrySet().stream().findFirst().get();
        return policy.getValue();
    }

    @Override
    public String toString() {
        return "MostRestrictive { mMostToLeastRestrictive= " + mMostToLeastRestrictive + " }";
    }
}
