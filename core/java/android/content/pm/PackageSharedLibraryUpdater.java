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

    static @NonNull
            <T> ArrayList<T> prefix(@Nullable ArrayList<T> cur, T val) {
        if (cur == null) {
            cur = new ArrayList<>();
        }
        cur.add(0, val);
        return cur;
    }

    static boolean isLibraryPresent(ArrayList<String> usesLibraries,
            ArrayList<String> usesOptionalLibraries, String apacheHttpLegacy) {
        return ArrayUtils.contains(usesLibraries, apacheHttpLegacy)
                || ArrayUtils.contains(usesOptionalLibraries, apacheHttpLegacy);
    }
}
