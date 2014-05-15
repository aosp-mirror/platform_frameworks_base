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

/** {@hide} */
public class PackageInstallObserver {
    private final IPackageInstallObserver2.Stub mBinder = new IPackageInstallObserver2.Stub() {
        @Override
        public void packageInstalled(String basePackageName, Bundle extras, int returnCode) {
            PackageInstallObserver.this.packageInstalled(basePackageName, extras, returnCode);
        }
    };

    /** {@hide} */
    public IPackageInstallObserver2.Stub getBinder() {
        return mBinder;
    }

    /**
     * This method will be called to report the result of the package
     * installation attempt.
     *
     * @param basePackageName Name of the package whose installation was
     *            attempted
     * @param extras If non-null, this Bundle contains extras providing
     *            additional information about an install failure. See
     *            {@link android.content.pm.PackageManager} for documentation
     *            about which extras apply to various failures; in particular
     *            the strings named EXTRA_FAILURE_*.
     * @param returnCode The numeric success or failure code indicating the
     *            basic outcome
     * @hide
     */
    public void packageInstalled(String basePackageName, Bundle extras, int returnCode) {
    }
}
