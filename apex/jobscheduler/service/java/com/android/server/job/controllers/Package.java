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

package com.android.server.job.controllers;

import java.util.Objects;

/** Wrapper class to represent a userId-pkgName combo. */
final class Package {
    public final String packageName;
    public final int userId;

    Package(int userId, String packageName) {
        this.userId = userId;
        this.packageName = packageName;
    }

    @Override
    public String toString() {
        return packageToString(userId, packageName);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Package)) {
            return false;
        }
        Package other = (Package) obj;
        return userId == other.userId && Objects.equals(packageName, other.packageName);
    }

    @Override
    public int hashCode() {
        return packageName.hashCode() + userId;
    }

    /**
     * Standardize the output of userId-packageName combo.
     */
    static String packageToString(int userId, String packageName) {
        return "<" + userId + ">" + packageName;
    }
}
