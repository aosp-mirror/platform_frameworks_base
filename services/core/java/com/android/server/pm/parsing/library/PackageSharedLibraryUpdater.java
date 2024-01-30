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
package com.android.server.pm.parsing.library;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for classes that update a {@link ParsedPackage}'s shared libraries.
 *
 * @hide
 */
@VisibleForTesting
public abstract class PackageSharedLibraryUpdater {

    /**
     * Update the package's shared libraries.
     *
     * @param parsedPackage the package to update.
     */
    public abstract void updatePackage(ParsedPackage parsedPackage, boolean isSystemApp,
            boolean isUpdatedSystemApp);

    static void removeLibrary(ParsedPackage parsedPackage, String libraryName) {
        parsedPackage.removeUsesLibrary(libraryName)
                .removeUsesOptionalLibrary(libraryName);
    }

    static @NonNull
            <T> ArrayList<T> prefix(@Nullable ArrayList<T> cur, T val) {
        if (cur == null) {
            cur = new ArrayList<>();
        }
        cur.add(0, val);
        return cur;
    }

    private static boolean isLibraryPresent(List<String> usesLibraries,
            List<String> usesOptionalLibraries, String apacheHttpLegacy) {
        return ArrayUtils.contains(usesLibraries, apacheHttpLegacy)
                || ArrayUtils.contains(usesOptionalLibraries, apacheHttpLegacy);
    }

    /**
     * Add an implicit dependency.
     *
     * <p>If the package has an existing dependency on {@code existingLibrary} then prefix it with
     * the {@code implicitDependency} if it is not already in the list of libraries.
     *
     * @param parsedPackage the {@link ParsedPackage} to update.
     * @param existingLibrary the existing library.
     * @param implicitDependency the implicit dependency to add
     */
    void prefixImplicitDependency(ParsedPackage parsedPackage, String existingLibrary,
            String implicitDependency) {
        List<String> usesLibraries = parsedPackage.getUsesLibraries();
        List<String> usesOptionalLibraries = parsedPackage.getUsesOptionalLibraries();

        if (!isLibraryPresent(usesLibraries, usesOptionalLibraries, implicitDependency)) {
            if (ArrayUtils.contains(usesLibraries, existingLibrary)) {
                parsedPackage.addUsesLibrary(0, implicitDependency);
            } else if (ArrayUtils.contains(usesOptionalLibraries, existingLibrary)) {
                parsedPackage.addUsesOptionalLibrary(0, implicitDependency);
            }
        }
    }

    void prefixRequiredLibrary(ParsedPackage parsedPackage, String libraryName) {
        List<String> usesLibraries = parsedPackage.getUsesLibraries();
        List<String> usesOptionalLibraries = parsedPackage.getUsesOptionalLibraries();

        boolean alreadyPresent = isLibraryPresent(
                usesLibraries, usesOptionalLibraries, libraryName);
        if (!alreadyPresent) {
            parsedPackage.addUsesLibrary(0, libraryName);
        }
    }
}
