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
package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;

/**
 * Base for classes that update a {@link PackageParser.Package}'s shared libraries.
 *
 * @hide
 */
@VisibleForTesting
public abstract class PackageSharedLibraryUpdater {

    /**
     * Update the package's shared libraries.
     *
     * @param pkg the package to update.
     */
    public abstract void updatePackage(PackageParser.Package pkg);

    static void removeLibrary(PackageParser.Package pkg, String libraryName) {
        pkg.usesLibraries = ArrayUtils.remove(pkg.usesLibraries, libraryName);
        pkg.usesOptionalLibraries =
                ArrayUtils.remove(pkg.usesOptionalLibraries, libraryName);
    }

    static @NonNull
            <T> ArrayList<T> prefix(@Nullable ArrayList<T> cur, T val) {
        if (cur == null) {
            cur = new ArrayList<>();
        }
        cur.add(0, val);
        return cur;
    }

    private static boolean isLibraryPresent(ArrayList<String> usesLibraries,
            ArrayList<String> usesOptionalLibraries, String apacheHttpLegacy) {
        return ArrayUtils.contains(usesLibraries, apacheHttpLegacy)
                || ArrayUtils.contains(usesOptionalLibraries, apacheHttpLegacy);
    }

    /**
     * Add an implicit dependency.
     *
     * <p>If the package has an existing dependency on {@code existingLibrary} then prefix it with
     * the {@code implicitDependency} if it is not already in the list of libraries.
     *
     * @param pkg the {@link PackageParser.Package} to update.
     * @param existingLibrary the existing library.
     * @param implicitDependency the implicit dependency to add
     */
    void prefixImplicitDependency(PackageParser.Package pkg, String existingLibrary,
            String implicitDependency) {
        ArrayList<String> usesLibraries = pkg.usesLibraries;
        ArrayList<String> usesOptionalLibraries = pkg.usesOptionalLibraries;

        if (!isLibraryPresent(usesLibraries, usesOptionalLibraries, implicitDependency)) {
            if (ArrayUtils.contains(usesLibraries, existingLibrary)) {
                prefix(usesLibraries, implicitDependency);
            } else if (ArrayUtils.contains(usesOptionalLibraries, existingLibrary)) {
                prefix(usesOptionalLibraries, implicitDependency);
            }

            pkg.usesLibraries = usesLibraries;
            pkg.usesOptionalLibraries = usesOptionalLibraries;
        }
    }

    void prefixRequiredLibrary(PackageParser.Package pkg, String libraryName) {
        ArrayList<String> usesLibraries = pkg.usesLibraries;
        ArrayList<String> usesOptionalLibraries = pkg.usesOptionalLibraries;

        boolean alreadyPresent = isLibraryPresent(
                usesLibraries, usesOptionalLibraries, libraryName);
        if (!alreadyPresent) {
            usesLibraries = prefix(usesLibraries, libraryName);

            pkg.usesLibraries = usesLibraries;
        }
    }
}
