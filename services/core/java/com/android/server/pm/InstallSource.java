/*
 * Copyright 2019 The Android Open Source Project
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

import android.annotation.Nullable;

import com.android.internal.util.IndentingPrintWriter;

/**
 * Immutable class holding information about where the request to install or update an app
 * came from.
 */
final class InstallSource {
    private static final InstallSource EMPTY = new InstallSource(null);

    /**
     * The package that requested the installation, if known.
     */
    @Nullable
    final String initiatingPackageName;

    static InstallSource create(@Nullable String initiatingPackageName) {
        return initiatingPackageName == null
                ? EMPTY : new InstallSource(initiatingPackageName.intern());
    }

    private InstallSource(@Nullable String initiatingPackageName) {
        this.initiatingPackageName = initiatingPackageName;
    }

    void dump(IndentingPrintWriter pw) {
        pw.printPair("installInitiatingPackageName", initiatingPackageName);
    }

    /**
     * Return an InstallSource the same as this one except it does not refer to the specified
     * installer package name.
     */
    InstallSource removeInstallerPackage(String packageName) {
        if (packageName != null && packageName.equals(initiatingPackageName)) {
            return create(null);
        }
        return this;
    }
}
