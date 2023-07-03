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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** @hide */
public class SharedLibraryWrapper implements SharedLibrary {

    private final SharedLibraryInfo mInfo;

    @Nullable
    private List<SharedLibrary> cachedDependenciesList;

    public SharedLibraryWrapper(@NonNull SharedLibraryInfo info) {
        mInfo = info;
    }

    @NonNull
    public SharedLibraryInfo getInfo() {
        return mInfo;
    }

    @Override
    public String getPath() {
        return mInfo.getPath();
    }

    @Override
    public String getPackageName() {
        return mInfo.getPackageName();
    }

    @Override
    public String getName() {
        return mInfo.getName();
    }

    @Override
    public List<String> getAllCodePaths() {
        return Collections.unmodifiableList(mInfo.getAllCodePaths());
    }

    @Override
    public long getVersion() {
        return mInfo.getLongVersion();
    }

    @Override
    public int getType() {
        return mInfo.getType();
    }

    @Override
    public boolean isNative() {
        return mInfo.isNative();
    }

    @NonNull
    @Override
    public VersionedPackage getDeclaringPackage() {
        return mInfo.getDeclaringPackage();
    }

    @NonNull
    @Override
    public List<VersionedPackage> getDependentPackages() {
        return Collections.unmodifiableList(mInfo.getDependentPackages());
    }

    @NonNull
    @Override
    public List<SharedLibrary> getDependencies() {
        if (cachedDependenciesList == null) {
            var dependencies = mInfo.getDependencies();
            if (dependencies == null) {
                cachedDependenciesList = Collections.emptyList();
            } else {
                var list = new ArrayList<SharedLibrary>();
                for (int index = 0; index < dependencies.size(); index++) {
                    list.add(new SharedLibraryWrapper(dependencies.get(index)));
                }
                cachedDependenciesList = Collections.unmodifiableList(list);
            }
        }
        return cachedDependenciesList;
    }
}
