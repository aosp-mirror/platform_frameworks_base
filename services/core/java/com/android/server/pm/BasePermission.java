/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.content.pm.PermissionInfo;
import android.os.UserHandle;

final class BasePermission {
    final static int TYPE_NORMAL = 0;

    final static int TYPE_BUILTIN = 1;

    final static int TYPE_DYNAMIC = 2;

    final String name;

    String sourcePackage;

    PackageSettingBase packageSetting;

    final int type;

    int protectionLevel;

    PackageParser.Permission perm;

    PermissionInfo pendingInfo;

    /** UID that owns the definition of this permission */
    int uid;

    /** Additional GIDs given to apps granted this permission */
    private int[] gids;

    /**
     * Flag indicating that {@link #gids} should be adjusted based on the
     * {@link UserHandle} the granted app is running as.
     */
    private boolean perUser;

    BasePermission(String _name, String _sourcePackage, int _type) {
        name = _name;
        sourcePackage = _sourcePackage;
        type = _type;
        // Default to most conservative protection level.
        protectionLevel = PermissionInfo.PROTECTION_SIGNATURE;
    }

    @Override
    public String toString() {
        return "BasePermission{" + Integer.toHexString(System.identityHashCode(this)) + " " + name
                + "}";
    }

    public void setGids(int[] gids, boolean perUser) {
        this.gids = gids;
        this.perUser = perUser;
    }

    public int[] computeGids(int userId) {
        if (perUser) {
            final int[] userGids = new int[gids.length];
            for (int i = 0; i < gids.length; i++) {
                userGids[i] = UserHandle.getUid(userId, gids[i]);
            }
            return userGids;
        } else {
            return gids;
        }
    }

    public boolean isRuntime() {
        return (protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_DANGEROUS;
    }
}
