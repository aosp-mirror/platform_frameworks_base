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

import android.util.ArraySet;

/**
 * Settings data for a particular shared user ID we know about.
 */
final class SharedUserSetting extends GrantedPermissions {
    final String name;

    int userId;

    // flags that are associated with this uid, regardless of any package flags
    int uidFlags;

    final ArraySet<PackageSetting> packages = new ArraySet<PackageSetting>();

    final PackageSignatures signatures = new PackageSignatures();

    SharedUserSetting(String _name, int _pkgFlags) {
        super(_pkgFlags);
        uidFlags =  _pkgFlags;
        name = _name;
    }

    @Override
    public String toString() {
        return "SharedUserSetting{" + Integer.toHexString(System.identityHashCode(this)) + " "
                + name + "/" + userId + "}";
    }

    void removePackage(PackageSetting packageSetting) {
        if (packages.remove(packageSetting)) {
            // recalculate the pkgFlags for this shared user if needed
            if ((this.pkgFlags & packageSetting.pkgFlags) != 0) {
                int aggregatedFlags = uidFlags;
                for (PackageSetting ps : packages) {
                    aggregatedFlags |= ps.pkgFlags;
                }
                setFlags(aggregatedFlags);
            }
        }
    }

    void addPackage(PackageSetting packageSetting) {
        if (packages.add(packageSetting)) {
            setFlags(this.pkgFlags | packageSetting.pkgFlags);
        }
    }
}
