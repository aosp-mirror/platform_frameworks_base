/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app;

import android.content.pm.IPackageInstallObserver2;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * @hide
 *
 * New-style observer for package installers to use.
 */
public class PackageInstallObserver {
    IPackageInstallObserver2.Stub mObserver = new IPackageInstallObserver2.Stub() {
        @Override
        public void packageInstalled(String pkgName, Bundle extras, int result)
                throws RemoteException {
            PackageInstallObserver.this.packageInstalled(pkgName, extras, result);
        }
    }; 

    /**
     * This method will be called to report the result of the package installation attempt.
     *
     * @param pkgName Name of the package whose installation was attempted
     * @param extras If non-null, this Bundle contains extras providing additional information
     *        about the install result
     * @param result The numeric success or failure code indicating the basic outcome
     */
    public void packageInstalled(String pkgName, Bundle extras, int result) {
    }
}
