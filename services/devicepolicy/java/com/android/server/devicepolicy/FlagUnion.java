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
import android.app.admin.IntegerPolicyValue;
import android.app.admin.PolicyValue;

import java.util.LinkedHashMap;
import java.util.Objects;

final class FlagUnion extends ResolutionMechanism<Integer> {

    @Override
    IntegerPolicyValue resolve(
            @NonNull LinkedHashMap<EnforcingAdmin, PolicyValue<Integer>> adminPolicies) {
        Objects.requireNonNull(adminPolicies);
        if (adminPolicies.isEmpty()) {
            return null;
        }

        Integer unionOfPolicies = 0;
        for (PolicyValue<Integer> policy : adminPolicies.values()) {
            unionOfPolicies |= policy.getValue();
        }
        return new IntegerPolicyValue(unionOfPolicies);
    }

    @Override
    android.app.admin.FlagUnion getParcelableResolutionMechanism() {
        return android.app.admin.FlagUnion.FLAG_UNION;
    }

    @Override
    public String toString() {
        return "IntegerUnion {}";
    }
}
