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

import static org.junit.Assert.assertEquals;

import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test support for building {@link PackageParser.Package} instances.
 */
class PackageBuilder {

    private int mTargetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

    private ArrayList<String> mRequiredLibraries;

    private ArrayList<String> mOptionalLibraries;

    public static PackageBuilder builder() {
        return new PackageBuilder();
    }

    public PackageParser.Package build() {
        PackageParser.Package pkg = new PackageParser.Package("org.package.name");
        pkg.applicationInfo.targetSdkVersion = mTargetSdkVersion;
        pkg.usesLibraries = mRequiredLibraries;
        pkg.usesOptionalLibraries = mOptionalLibraries;
        return pkg;
    }

    PackageBuilder targetSdkVersion(int version) {
        this.mTargetSdkVersion = version;
        return this;
    }

    PackageBuilder requiredLibraries(String... names) {
        this.mRequiredLibraries = arrayListOrNull(names);
        return this;
    }

    PackageBuilder requiredLibraries(List<String> names) {
        this.mRequiredLibraries = arrayListOrNull(names.toArray(new String[names.size()]));
        return this;
    }

    PackageBuilder optionalLibraries(String... names) {
        this.mOptionalLibraries = arrayListOrNull(names);
        return this;
    }

    /**
     * Check that this matches the supplied {@link PackageParser.Package}.
     *
     * @param pkg the instance to compare with this.
     */
    public void check(PackageParser.Package pkg) {
        assertEquals("targetSdkVersion should not be changed",
                mTargetSdkVersion,
                pkg.applicationInfo.targetSdkVersion);
        assertEquals("usesLibraries not updated correctly",
                mRequiredLibraries,
                pkg.usesLibraries);
        assertEquals("usesOptionalLibraries not updated correctly",
                mOptionalLibraries,
                pkg.usesOptionalLibraries);
    }

    private static ArrayList<String> arrayListOrNull(String... strings) {
        if (strings == null || strings.length == 0) {
            return null;
        }
        ArrayList<String> list = new ArrayList<>();
        Collections.addAll(list, strings);
        return list;
    }

}
