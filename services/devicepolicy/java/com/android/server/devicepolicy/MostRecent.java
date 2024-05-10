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
import android.app.admin.PolicyValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MostRecent<V> extends ResolutionMechanism<V> {

    @Override
    PolicyValue<V> resolve(@NonNull LinkedHashMap<EnforcingAdmin, PolicyValue<V>> adminPolicies) {
        List<Map.Entry<EnforcingAdmin, PolicyValue<V>>> policiesList = new ArrayList<>(
                adminPolicies.entrySet());
        return policiesList.isEmpty() ? null : policiesList.get(policiesList.size() - 1).getValue();
    }

    @Override
    android.app.admin.MostRecent<V> getParcelableResolutionMechanism() {
        return new android.app.admin.MostRecent<V>();
    }

    @Override
    public String toString() {
        return "MostRecent {}";
    }
}
