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

package com.android.server.pm;

import static com.android.server.pm.PackageManagerService.TAG;

import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.ArrayList;

final class PackageInstalledInfo {
    String mName;
    int mUid;
    // The set of users that originally had this package installed.
    int[] mOrigUsers;
    // The set of users that now have this package installed.
    int[] mNewUsers;
    AndroidPackage mPkg;
    int mReturnCode;
    String mReturnMsg;
    String mInstallerPackageName;
    PackageRemovedInfo mRemovedInfo;
    // The set of packages consuming this shared library or null if no consumers exist.
    ArrayList<AndroidPackage> mLibraryConsumers;
    PackageFreezer mFreezer;

    // In some error cases we want to convey more info back to the observer
    String mOrigPackage;
    String mOrigPermission;

    PackageInstalledInfo(int currentStatus) {
        mReturnCode = currentStatus;
        mUid = -1;
        mPkg = null;
        mRemovedInfo = null;
    }

    public void setError(int code, String msg) {
        setReturnCode(code);
        setReturnMessage(msg);
        Slog.w(TAG, msg);
    }

    public void setError(String msg, PackageManagerException e) {
        mReturnCode = e.error;
        setReturnMessage(ExceptionUtils.getCompleteMessage(msg, e));
        Slog.w(TAG, msg, e);
    }

    public void setReturnCode(int returnCode) {
        mReturnCode = returnCode;
    }

    private void setReturnMessage(String returnMsg) {
        mReturnMsg = returnMsg;
    }
}
