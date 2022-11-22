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

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

final class LockTaskPolicy {
    private Set<String> mPackages;
    private int mFlags;

    LockTaskPolicy(@Nullable Set<String> packages, int flags) {
        mPackages = packages;
        mFlags = flags;
    }

    @Nullable
    Set<String> getPackages() {
        return mPackages;
    }

    int getFlags() {
        return mFlags;
    }

    void setPackages(Set<String> packages) {
        mPackages = packages;
    }

    void setFlags(int flags) {
        mFlags = flags;
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

    static final class LockTaskPolicySerializer extends PolicySerializer<LockTaskPolicy> {

        private static final String ATTR_PACKAGES = ":packages";
        private static final String ATTR_PACKAGES_SEPARATOR = ";";
        private static final String ATTR_FLAGS = ":flags";

        @Override
        void saveToXml(
                TypedXmlSerializer serializer, String attributeNamePrefix, LockTaskPolicy value)
                throws IOException {
            if (value.mPackages != null) {
                serializer.attribute(
                        /* namespace= */ null,
                        attributeNamePrefix + ATTR_PACKAGES,
                        String.join(ATTR_PACKAGES_SEPARATOR, value.mPackages));
            }
            serializer.attributeInt(
                    /* namespace= */ null,
                    attributeNamePrefix + ATTR_FLAGS,
                    value.mFlags);
        }

        @Override
        LockTaskPolicy readFromXml(TypedXmlPullParser parser, String attributeNamePrefix)
                throws XmlPullParserException {
            String packagesStr = parser.getAttributeValue(
                    /* namespace= */ null,
                    attributeNamePrefix + ATTR_PACKAGES);
            Set<String> packages = packagesStr == null
                    ? null
                    : Set.of(packagesStr.split(ATTR_PACKAGES_SEPARATOR));
            int flags = parser.getAttributeInt(
                    /* namespace= */ null,
                    attributeNamePrefix + ATTR_FLAGS);
            return new LockTaskPolicy(packages, flags);
        }
    }
}
