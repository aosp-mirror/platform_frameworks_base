/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.qs.external;

import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import javax.inject.Inject;

// Adapter that wraps calls to PackageManager or IPackageManager for {@link TileLifecycleManager}.
// TODO: This is very much an intermediate step to allow for PackageManager mocking and should be
// abstracted into something more useful for other tests in systemui.
public class PackageManagerAdapter {
    private static final String TAG = "PackageManagerAdapter";

    private PackageManager mPackageManager;
    private IPackageManager mIPackageManager;

    // Uses the PackageManager for the provided context.
    // When necessary, uses the IPackagemanger in AppGlobals.
    @Inject
    public PackageManagerAdapter(Context context) {
        mPackageManager = context.getPackageManager();
        mIPackageManager = AppGlobals.getPackageManager();
    }

    @Nullable
    public ServiceInfo getServiceInfo(ComponentName className, int flags, int userId)
            throws RemoteException {
        return mIPackageManager.getServiceInfo(className, flags, userId);
    }

    public ServiceInfo getServiceInfo(ComponentName component, int flags)
            throws PackageManager.NameNotFoundException {
        return mPackageManager.getServiceInfo(component, flags);
    }

    public PackageInfo getPackageInfoAsUser(String packageName, int flags, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException {
        return mPackageManager.getPackageInfoAsUser(packageName, flags, userId);
    }
}
