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
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.util.Log;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class LockTaskPolicy {
    static final int DEFAULT_LOCK_TASK_FLAG = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
    private Set<String> mPackages = new HashSet<>();
    private int mFlags = DEFAULT_LOCK_TASK_FLAG;

    @NonNull
    Set<String> getPackages() {
        return mPackages;
    }

    int getFlags() {
        return mFlags;
    }

    LockTaskPolicy(Set<String> packages) {
        Objects.requireNonNull(packages);
        mPackages.addAll(packages);
    }

    private LockTaskPolicy(Set<String> packages, int flags) {
        Objects.requireNonNull(packages);
        mPackages = new HashSet<>(packages);
        mFlags = flags;
    }

    void setPackages(@NonNull Set<String> packages) {
        Objects.requireNonNull(packages);
        mPackages = new HashSet<>(packages);
    }

    void setFlags(int flags) {
        mFlags = flags;
    }

    @Override
    public LockTaskPolicy clone() {
        LockTaskPolicy policy = new LockTaskPolicy(mPackages);
        policy.setFlags(mFlags);
        return policy;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LockTaskPolicy other = (LockTaskPolicy) o;
        return Objects.equals(mPackages, other.mPackages)
                && mFlags == other.mFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackages, mFlags);
    }

    @Override
    public String toString() {
        return "mPackages= " + String.join(", ", mPackages) + "; mFlags= " + mFlags;
    }

    static final class LockTaskPolicySerializer extends PolicySerializer<LockTaskPolicy> {

        private static final String ATTR_PACKAGES = ":packages";
        private static final String ATTR_PACKAGES_SEPARATOR = ";";
        private static final String ATTR_FLAGS = ":flags";

        @Override
        void saveToXml(TypedXmlSerializer serializer, String attributeNamePrefix,
                @NonNull LockTaskPolicy value) throws IOException {
            Objects.requireNonNull(value);
            if (value.mPackages == null || value.mPackages.isEmpty()) {
                throw new IllegalArgumentException("Error saving LockTaskPolicy to file, lock task "
                        + "packages must be present");
            }
            serializer.attribute(
                    /* namespace= */ null,
                    attributeNamePrefix + ATTR_PACKAGES,
                    String.join(ATTR_PACKAGES_SEPARATOR, value.mPackages));
            serializer.attributeInt(
                    /* namespace= */ null,
                    attributeNamePrefix + ATTR_FLAGS,
                    value.mFlags);
        }

        @Override
        LockTaskPolicy readFromXml(TypedXmlPullParser parser, String attributeNamePrefix) {
            String packagesStr = parser.getAttributeValue(
                    /* namespace= */ null,
                    attributeNamePrefix + ATTR_PACKAGES);
            if (packagesStr == null) {
                Log.e(DevicePolicyEngine.TAG, "Error parsing LockTask policy value.");
                return null;
            }
            Set<String> packages = Set.of(packagesStr.split(ATTR_PACKAGES_SEPARATOR));
            try {
                int flags = parser.getAttributeInt(
                        /* namespace= */ null,
                        attributeNamePrefix + ATTR_FLAGS);
                return new LockTaskPolicy(packages, flags);
            } catch (XmlPullParserException e) {
                Log.e(DevicePolicyEngine.TAG, "Error parsing LockTask policy value", e);
                return null;
            }
        }
    }
}
