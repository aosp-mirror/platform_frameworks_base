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
import android.processor.immutability.Immutable;

import java.util.List;

/**
 * @hide
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
@Immutable
public interface SharedLibrary {

    /**
     * @see SharedLibraryInfo#getPath()
     */
    @Nullable
    String getPath();

    /**
     * @see SharedLibraryInfo#getPackageName()
     */
    @Nullable
    String getPackageName();

    /**
     * @see SharedLibraryInfo#getName()
     */
    @Nullable
    String getName();

    /**
     * @see SharedLibraryInfo#getAllCodePaths()
     */
    @NonNull
    List<String> getAllCodePaths();

    /**
     * @see SharedLibraryInfo#getLongVersion()
     */
    long getVersion();

    /**
     * @see SharedLibraryInfo#getType()
     */
    int getType();

    /**
     * @see SharedLibraryInfo#isNative()
     */
    boolean isNative();

    /**
     * @see SharedLibraryInfo#getDeclaringPackage()
     */
    @Immutable.Policy(exceptions = {Immutable.Policy.Exception.FINAL_CLASSES_WITH_FINAL_FIELDS})
    @NonNull
    VersionedPackage getDeclaringPackage();

    /**
     * @see SharedLibraryInfo#getDependentPackages()
     */
    @Immutable.Policy(exceptions = {Immutable.Policy.Exception.FINAL_CLASSES_WITH_FINAL_FIELDS})
    @NonNull
    List<VersionedPackage> getDependentPackages();

    /**
     * @see SharedLibraryInfo#getDependencies()
     */
    @NonNull
    List<SharedLibrary> getDependencies();
}
