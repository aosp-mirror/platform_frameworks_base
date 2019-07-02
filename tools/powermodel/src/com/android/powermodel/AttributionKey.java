/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel;

import java.util.Set;
import java.util.HashSet;

import com.google.common.collect.ImmutableSet;

public class AttributionKey {
    private final int mUid;
    private final ImmutableSet<String> mPackages;
    private final SpecialApp mSpecialApp;

    public AttributionKey(SpecialApp specialApp) {
        mUid = -1;
        mPackages = ImmutableSet.of();
        mSpecialApp = specialApp;
    }

    public AttributionKey(int uid, Set<String> packages) {
        mUid = uid;
        mPackages = ImmutableSet.copyOf(packages);
        mSpecialApp = null;
    }

    public ImmutableSet<String> getPackages() {
        return mPackages;
    }

    public boolean hasPackage(String pkg) {
        return mPackages.contains(pkg);
    }

    public SpecialApp getSpecialApp() {
        return mSpecialApp;
    }

    public boolean isSpecialApp() {
        return mSpecialApp != null;
    }

    /**
     * Returns the uid for this attribution, or -1 if there isn't one
     * (e.g. if it is a special app).
     */
    public int getUid() {
        return mUid;
    }
    @Override
    public int hashCode() {
        int hash = 7;
        hash = (31 * hash) + (mUid);
        hash = (31 * hash) + (mPackages == null ? 0 : mPackages.hashCode());
        hash = (31 * hash) + (mSpecialApp == null ? 0 : mSpecialApp.hashCode());
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        if (this.getClass() != o.getClass()) {
            return false;
        }
        final AttributionKey that = (AttributionKey)o;
        return (this.mUid == that.mUid)
                && this.mPackages != null && this.mPackages.equals(that.mPackages)
                && this.mSpecialApp != null && this.mSpecialApp.equals(that.mSpecialApp);
    }

    @Override
    public String toString() {
        final StringBuilder str = new StringBuilder("AttributionKey(");
        if (mUid >= 0) {
            str.append(" uid=");
            str.append(mUid);
        }
        if (mPackages.size() > 0) {
            str.append(" packages=[");
            for (String pkg: mPackages) {
                str.append(' ');
                str.append(pkg);
            }
            str.append(" ]");
        }
        if (mSpecialApp != null) {
            str.append(" specialApp=");
            str.append(mSpecialApp.name());
        }
        str.append(" )");
        return str.toString();
    }
}

