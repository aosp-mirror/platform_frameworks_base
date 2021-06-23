/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.verify.domain.models;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A feature specific implementation of a multi-key map, since lookups by both a {@link String}
 * package name and {@link UUID} domain set ID should be supported.
 *
 * @param <ValueType> stored object type
 */
public class DomainVerificationStateMap<ValueType> {

    private static final String TAG = "DomainVerificationStateMap";

    @NonNull
    private final ArrayMap<String, ValueType> mPackageNameMap = new ArrayMap<>();

    @NonNull
    private final ArrayMap<UUID, ValueType> mDomainSetIdMap = new ArrayMap<>();

    public int size() {
        return mPackageNameMap.size();
    }

    @NonNull
    public ValueType valueAt(@IntRange(from = 0) int index) {
        return mPackageNameMap.valueAt(index);
    }

    @Nullable
    public ValueType get(@NonNull String packageName) {
        return mPackageNameMap.get(packageName);
    }

    @Nullable
    public ValueType get(@NonNull UUID domainSetId) {
        return mDomainSetIdMap.get(domainSetId);
    }

    public void put(@NonNull String packageName, @NonNull UUID domainSetId,
            @NonNull ValueType valueType) {
        if (mPackageNameMap.containsKey(packageName)) {
            remove(packageName);
        }

        mPackageNameMap.put(packageName, valueType);
        mDomainSetIdMap.put(domainSetId, valueType);
    }

    @Nullable
    public ValueType remove(@NonNull String packageName) {
        ValueType valueRemoved = mPackageNameMap.remove(packageName);
        if (valueRemoved != null) {
            int index = mDomainSetIdMap.indexOfValue(valueRemoved);
            if (index >= 0) {
                mDomainSetIdMap.removeAt(index);
            }
        }
        return valueRemoved;
    }

    @Nullable
    public ValueType remove(@NonNull UUID domainSetId) {
        ValueType valueRemoved = mDomainSetIdMap.remove(domainSetId);
        if (valueRemoved != null) {
            int index = mPackageNameMap.indexOfValue(valueRemoved);
            if (index >= 0) {
                mPackageNameMap.removeAt(index);
            }
        }
        return valueRemoved;
    }

    @NonNull
    public List<String> getPackageNames() {
        return new ArrayList<>(mPackageNameMap.keySet());
    }

    /**
     * Exposes the backing values collection of the one of the internal maps. Should only be used
     * for test assertions.
     */
    @VisibleForTesting
    public Collection<ValueType> values() {
        return new ArrayList<>(mPackageNameMap.values());
    }

    @Override
    public String toString() {
        return "DomainVerificationStateMap{"
                + "packageNameMap=" + mPackageNameMap
                + ", domainSetIdMap=" + mDomainSetIdMap
                + '}';
    }
}
