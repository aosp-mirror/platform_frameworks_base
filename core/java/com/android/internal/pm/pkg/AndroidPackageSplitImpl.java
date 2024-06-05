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

package com.android.internal.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;

import com.android.server.pm.pkg.AndroidPackageSplit;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** @hide */
public class AndroidPackageSplitImpl implements AndroidPackageSplit {

    @Nullable
    private final String mName;
    @NonNull
    private final String mPath;
    private final int mRevisionCode;
    private final int mFlags;
    @Nullable
    private final String mClassLoaderName;

    @NonNull
    private List<AndroidPackageSplit> mDependencies = Collections.emptyList();

    public AndroidPackageSplitImpl(@Nullable String name, @NonNull String path, int revisionCode,
            int flags, @Nullable String classLoaderName) {
        mName = name;
        mPath = path;
        mRevisionCode = revisionCode;
        mFlags = flags;
        mClassLoaderName = classLoaderName;
    }

    public void fillDependencies(@NonNull List<AndroidPackageSplit> splits) {
        if (!mDependencies.isEmpty()) {
            throw new IllegalStateException("Cannot fill split dependencies more than once");
        }
        mDependencies = splits;
    }

    @Nullable
    @Override
    public String getName() {
        return mName;
    }

    @NonNull
    @Override
    public String getPath() {
        return mPath;
    }

    @Override
    public int getRevisionCode() {
        return mRevisionCode;
    }

    @Override
    public boolean isHasCode() {
        return (mFlags & ApplicationInfo.FLAG_HAS_CODE) != 0;
    }

    @Nullable
    @Override
    public String getClassLoaderName() {
        return mClassLoaderName;
    }

    @NonNull
    @Override
    public List<AndroidPackageSplit> getDependencies() {
        return mDependencies;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AndroidPackageSplitImpl)) return false;
        AndroidPackageSplitImpl that = (AndroidPackageSplitImpl) o;
        var fieldsEqual = mRevisionCode == that.mRevisionCode && mFlags == that.mFlags
                && Objects.equals(mName, that.mName) && Objects.equals(mPath, that.mPath)
                && Objects.equals(mClassLoaderName, that.mClassLoaderName);

        if (!fieldsEqual) return false;
        if (mDependencies.size() != that.mDependencies.size()) return false;

        // Should be impossible, but to avoid circular dependencies,
        // only search 1 level deep using split name
        for (int index = 0; index < mDependencies.size(); index++) {
            if (!Objects.equals(mDependencies.get(index).getName(),
                    that.mDependencies.get(index).getName())) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        // Should be impossible, but to avoid circular dependencies,
        // only search 1 level deep using split name
        var dependenciesHash = Objects.hash(mName, mPath, mRevisionCode, mFlags, mClassLoaderName);
        for (int index = 0; index < mDependencies.size(); index++) {
            var name = mDependencies.get(index).getName();
            dependenciesHash = 31 * dependenciesHash + (name == null ? 0 : name.hashCode());
        }
        return dependenciesHash;
    }
}
