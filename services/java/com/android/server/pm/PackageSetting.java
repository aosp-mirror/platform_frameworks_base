/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.pm.PackageParser;

import java.io.File;

/**
 * Settings data for a particular package we know about.
 */
final class PackageSetting extends PackageSettingBase {
    int userId;
    PackageParser.Package pkg;
    SharedUserSetting sharedUser;

    PackageSetting(String name, String realName, File codePath, File resourcePath,
            String nativeLibraryPathString, int pVersionCode, int pkgFlags) {
        super(name, realName, codePath, resourcePath, nativeLibraryPathString, pVersionCode,
                pkgFlags);
    }

    /**
     * New instance of PackageSetting replicating the original settings.
     * Note that it keeps the same PackageParser.Package instance.
     */
    PackageSetting(PackageSetting orig) {
        super(orig);

        userId = orig.userId;
        pkg = orig.pkg;
        sharedUser = orig.sharedUser;
    }

    @Override
    public String toString() {
        return "PackageSetting{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + name + "/" + userId + "}";
    }
}