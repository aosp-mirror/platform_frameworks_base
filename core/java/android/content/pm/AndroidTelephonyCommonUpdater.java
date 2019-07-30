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

import static android.content.pm.SharedLibraryNames.ANDROID_TELEPHONY_COMMON;


import com.android.internal.compat.IPlatformCompat;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.pm.PackageParser.Package;

import android.os.Build.VERSION_CODES;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Updates a package to ensure that
 * <ul>
 * <li> if apps have target SDK < R, then telephony-common library is included by default to
 * their class path. Even without <uses-library>.</li>
 * <li> if apps with target SDK level >= R && have special permission (or Phone UID):
 * apply <uses-library> on telephony-common should work.</li>
 * <li> Otherwise not allow to use the lib.
 * See {@link PackageSharedLibraryUpdater#removeLibrary(Package, String)}.</li>
 * </ul>
 *
 * @hide
 */
@VisibleForTesting
public class AndroidTelephonyCommonUpdater extends PackageSharedLibraryUpdater {

    private static final String TAG = AndroidTelephonyCommonUpdater.class.getSimpleName();
    /**
     * Restrict telephony-common lib for apps having target SDK >= R
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = VERSION_CODES.Q)
    static final long RESTRICT_TELEPHONY_COMMON_CHANGE_ID = 139318877L;

    private static boolean apkTargetsApiLevelLessThanROrCurrent(Package pkg) {
        boolean shouldRestrict = false;
        try {
            IBinder b = ServiceManager.getService("platform_compat");
            IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(b);
            shouldRestrict = platformCompat.isChangeEnabled(RESTRICT_TELEPHONY_COMMON_CHANGE_ID,
                pkg.applicationInfo);
        } catch (RemoteException ex) {
            Log.e(TAG, ex.getMessage());
        }
        // TODO(b/139318877): remove version check for CUR_DEVELOPEMENT after clean up work.
        return !shouldRestrict
            || pkg.applicationInfo.targetSdkVersion == VERSION_CODES.CUR_DEVELOPMENT;
    }

    @Override
    public void updatePackage(Package pkg) {
        // for apps with targetSDKVersion < R include the library for backward compatibility.
        if (apkTargetsApiLevelLessThanROrCurrent(pkg)) {
            prefixRequiredLibrary(pkg, ANDROID_TELEPHONY_COMMON);
        } else if (pkg.mSharedUserId == null || !pkg.mSharedUserId.equals("android.uid.phone")) {
            // if apps target >= R
            removeLibrary(pkg, ANDROID_TELEPHONY_COMMON);
        }
    }
}
