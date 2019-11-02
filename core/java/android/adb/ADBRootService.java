/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.adb;

import android.adbroot.IADBRootService;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * {@hide}
 */
@SystemApi
public class ADBRootService {
    private static final String TAG = "ADBRootService";

    private static final String ADB_ROOT_SERVICE = "adbroot_service";

    private IADBRootService mADBRootService;
    private Context mContext;

    /**
     * Creates a new instance.
     */
    public ADBRootService(Context context) {
        mADBRootService = IADBRootService.Stub.asInterface(
                ServiceManager.getService(ADB_ROOT_SERVICE));
        mContext = context;
    }

    /**
     * @hide
     */
    public void setEnabled(boolean enable) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ADBROOT, "adbroot");
        try {
            mADBRootService.setEnabled(enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean getEnabled() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ADBROOT, "adbroot");
        try {
            return mADBRootService.getEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
