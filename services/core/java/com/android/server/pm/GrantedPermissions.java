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

import android.content.pm.ApplicationInfo;
import android.util.ArraySet;

class GrantedPermissions {
    int pkgFlags;
    int pkgPrivateFlags;

    ArraySet<String> grantedPermissions = new ArraySet<String>();

    int[] gids;

    GrantedPermissions(int pkgFlags, int pkgPrivateFlags) {
        setFlags(pkgFlags);
        setPrivateFlags(pkgPrivateFlags);
    }

    @SuppressWarnings("unchecked")
    GrantedPermissions(GrantedPermissions base) {
        pkgFlags = base.pkgFlags;
        grantedPermissions = new ArraySet<>(base.grantedPermissions);

        if (base.gids != null) {
            gids = base.gids.clone();
        }
    }

    void setFlags(int pkgFlags) {
        this.pkgFlags = pkgFlags
                & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_EXTERNAL_STORAGE);
    }

    void setPrivateFlags(int pkgPrivateFlags) {
        this.pkgPrivateFlags = pkgPrivateFlags
                & (ApplicationInfo.PRIVATE_FLAG_PRIVILEGED
                        | ApplicationInfo.PRIVATE_FLAG_FORWARD_LOCK);
    }
}
