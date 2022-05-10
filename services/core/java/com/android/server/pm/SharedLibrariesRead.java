/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.SharedLibraryInfo;
import android.util.proto.ProtoOutputStream;

import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedLongSparseArray;

import java.io.PrintWriter;

/**
 * An interface implemented by {@link SharedLibrariesImpl} for {@link Computer} to get current
 * shared libraries on the device.
 */
public interface SharedLibrariesRead {

    /**
     * Returns all shared libraries on the device.
     *
     * @return A map of library name to a list of {@link SharedLibraryInfo}s with
     * different versions.
     */
    @NonNull
    WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>> getAll();

    /**
     * Given the library name, returns a list of shared libraries on all versions.
     *
     * @param libName The library name.
     * @return A list of shared library info.
     */
    @Nullable
    WatchedLongSparseArray<SharedLibraryInfo> getSharedLibraryInfos(@NonNull String libName);

    /**
     * Returns the shared library with given library name and version number.
     *
     * @param libName The library name.
     * @param version The library version number.
     * @return The shared library info.
     */
    @Nullable
    SharedLibraryInfo getSharedLibraryInfo(@NonNull String libName, long version);

    /**
     * Given the declaring package name, returns a list of static shared libraries on all versions.
     *
     * @param declaringPackageName The declaring name of the package.
     * @return A list of shared library info.
     */
    @Nullable
    WatchedLongSparseArray<SharedLibraryInfo> getStaticLibraryInfos(
            @NonNull String declaringPackageName);

    /**
     * Dump all shared libraries.
     *
     * @param pw A PrintWriter to dump to.
     * @param dumpState Including options and states for writing.
     */
    void dump(@NonNull PrintWriter pw, @NonNull DumpState dumpState);

    /**
     * Dump all shared libraries to given proto output stream.
     * @param proto A proto output stream to dump to.
     */
    void dumpProto(@NonNull ProtoOutputStream proto);
}
