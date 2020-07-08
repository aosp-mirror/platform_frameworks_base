/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.settingslib;

import static android.os.UserManager.DISALLOW_CONFIG_TETHERING;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.UserHandle;

public class TetherUtil {
    public static boolean isTetherAvailable(Context context) {
        final ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        final boolean tetherConfigDisallowed = RestrictedLockUtilsInternal
                .checkIfRestrictionEnforced(context, DISALLOW_CONFIG_TETHERING,
                        UserHandle.myUserId()) != null;
        final boolean hasBaseUserRestriction = RestrictedLockUtilsInternal.hasBaseUserRestriction(
                context, DISALLOW_CONFIG_TETHERING, UserHandle.myUserId());
        return (cm.isTetheringSupported() || tetherConfigDisallowed) && !hasBaseUserRestriction;
    }
}
