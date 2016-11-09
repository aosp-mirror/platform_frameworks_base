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
package android.service.autofill;

import android.Manifest;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;

/** @hide */
public final class AutoFillServiceInfo {

    private static ServiceInfo getServiceInfoOrThrow(ComponentName comp, int userHandle)
            throws PackageManager.NameNotFoundException {
        try {
            final ServiceInfo si =
                    AppGlobals.getPackageManager().getServiceInfo(comp, 0, userHandle);
            if (si != null) {
                return si;
            }
        } catch (RemoteException e) {
        }
        throw new PackageManager.NameNotFoundException(comp.toString());
    }

    private String mParseError;

    private ServiceInfo mServiceInfo;

    private  AutoFillServiceInfo(ServiceInfo si) {
        if (si == null) {
            mParseError = "Service not available";
            return;
        }
        if (!Manifest.permission.BIND_AUTO_FILL.equals(si.permission)) {
            mParseError = "Service does not require permission "
                    + Manifest.permission.BIND_AUTO_FILL;
            return;
        }

        mServiceInfo = si;
    }

    public AutoFillServiceInfo(ComponentName comp, int userHandle)
            throws PackageManager.NameNotFoundException {
        this(getServiceInfoOrThrow(comp, userHandle));
    }

    public String getParseError() {
        return mParseError;
    }

    public ServiceInfo getServiceInfo() {
        return mServiceInfo;
    }
}
