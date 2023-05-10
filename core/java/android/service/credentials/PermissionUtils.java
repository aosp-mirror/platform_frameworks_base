/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.credentials;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Utils for checking permissions, or any other permission related function
 *
 * @hide
 */
public class PermissionUtils {
    //TODO(274838409): Move all CredentialManagerService permission checks here

    /** Checks whether the given package name hold the given permission **/
    public static boolean hasPermission(Context context, String packageName, String permission) {
        return context.getPackageManager().checkPermission(permission, packageName)
                == PackageManager.PERMISSION_GRANTED;
    }
}

