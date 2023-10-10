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
import android.annotation.SystemApi;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.processor.immutability.Immutable;

import java.util.List;

/**
 * Information for a shared library dependency, which is resolved to a real package on the device.
 *
 * There are four types of shared libraries:
 * <table>
 *     <tr><td>Built-in</td> <td>Non-updatable part of OS</td></tr>
 *     <tr><td>Dynamic</td> <td>Updatable backwards-compatible dynamically linked</td></tr>
 *     <tr><td>Static</td> <td>Non-backwards-compatible emulating static linking</td></tr>
 *     <tr><td>SDK</td> <td>Updatable backwards-incompatible dynamically loaded</td></tr>
 * </table>
 *
 * This class is a clone of {@link SharedLibraryInfo} but as an interface with more guaranteed
 * immutability.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
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
    @SharedLibraryInfo.Type
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
