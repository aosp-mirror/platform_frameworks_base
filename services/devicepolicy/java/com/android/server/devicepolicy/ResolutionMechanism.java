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

import android.annotation.Nullable;
import android.app.admin.PolicyValue;

import java.util.LinkedHashMap;
import java.util.List;

abstract class ResolutionMechanism<V> {
    /**
     * The most generic resolution logic where we know both the policy value and the admin who
     * sets it.
     */
    @Nullable
    abstract PolicyValue<V> resolve(LinkedHashMap<EnforcingAdmin, PolicyValue<V>> adminPolicies);

    /**
     * A special resolution logic that does not care about admins who set them. Only applicable to
     * a subset of ResolutionMechanism.
     */
    @Nullable
    PolicyValue<V> resolve(List<PolicyValue<V>> adminPolicies) {
        throw new UnsupportedOperationException();
    }

    abstract android.app.admin.ResolutionMechanism<V> getParcelableResolutionMechanism();
}
