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

import java.util.HashSet;

class GrantedPermissions {
    int pkgFlags;

    HashSet<String> grantedPermissions = new HashSet<String>();

    int[] gids;

    GrantedPermissions(int pkgFlags) {
        setFlags(pkgFlags);
    }

    @SuppressWarnings("unchecked")
    GrantedPermissions(GrantedPermissions base) {
        pkgFlags = base.pkgFlags;
        grantedPermissions = (HashSet<String>) base.grantedPermissions.clone();

        if (base.gids != null) {
            gids = base.gids.clone();
        }
    }

    void setFlags(int pkgFlags) {
        this.pkgFlags = pkgFlags
                & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_PRIVILEGED
                        | ApplicationInfo.FLAG_FORWARD_LOCK
                        | ApplicationInfo.FLAG_EXTERNAL_STORAGE);
    }
}
