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

import android.content.Intent;
import android.content.pm.IPackageDeleteObserver2;

/** {@hide} */
public class PackageDeleteObserver {
    private final IPackageDeleteObserver2.Stub mBinder = new IPackageDeleteObserver2.Stub() {
        @Override
        public void onUserActionRequired(Intent intent) {
            PackageDeleteObserver.this.onUserActionRequired(intent);
        }

        @Override
        public void onPackageDeleted(String basePackageName, int returnCode, String msg) {
            PackageDeleteObserver.this.onPackageDeleted(basePackageName, returnCode, msg);
        }
    };

    /** {@hide} */
    public IPackageDeleteObserver2 getBinder() {
        return mBinder;
    }

    public void onUserActionRequired(Intent intent) {
    }

    public void onPackageDeleted(String basePackageName, int returnCode, String msg) {
    }
}
