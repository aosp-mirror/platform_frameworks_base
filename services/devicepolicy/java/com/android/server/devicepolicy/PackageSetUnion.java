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
import android.app.admin.PackageSetPolicyValue;
import android.app.admin.StringSetUnion;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

final class PackageSetUnion extends ResolutionMechanism<Set<String>> {

    @Override
    PolicyValue<Set<String>> resolve(
            @NonNull LinkedHashMap<EnforcingAdmin, PolicyValue<Set<String>>> adminPolicies) {
        Objects.requireNonNull(adminPolicies);
        if (adminPolicies.isEmpty()) {
            return null;
        }
        Set<String> unionOfPolicies = new HashSet<>();
        for (PolicyValue<Set<String>> policy : adminPolicies.values()) {
            unionOfPolicies.addAll(policy.getValue());
        }
        return new PackageSetPolicyValue(unionOfPolicies);
    }

    @Override
    StringSetUnion getParcelableResolutionMechanism() {
        return new StringSetUnion();
    }


    @Override
    public String toString() {
        return "PackageSetUnion {}";
    }
}
