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

package android.content.pm.permission;

import android.Manifest;
import android.content.pm.parsing.component.ParsedUsesPermission;

/**
 * Implements compatibility support for permissions, and old applications
 * will be automatically granted it.
 *
 * Compatibility permissions are permissions that are automatically granted to
 * packages that target an SDK prior to when the permission was introduced.
 * Sometimes the platform makes breaking behaviour changes and hides the legacy
 * behaviour behind a permission. In these instances, we ensure applications
 * targeting older platform versions are implicitly granted the correct set of
 * permissions.
 *
 * @hide
 */
public class CompatibilityPermissionInfo extends ParsedUsesPermission {

    public final int sdkVersion;

    /**
     * List of new permissions that have been added since 1.0.
     *
     * @hide
     */
    public static final CompatibilityPermissionInfo[] COMPAT_PERMS =
            new CompatibilityPermissionInfo[]{
                    new CompatibilityPermissionInfo(Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            android.os.Build.VERSION_CODES.DONUT, 0 /*usesPermissionFlags*/),
                    new CompatibilityPermissionInfo(Manifest.permission.READ_PHONE_STATE,
                            android.os.Build.VERSION_CODES.DONUT, 0 /*usesPermissionFlags*/)
            };

    private CompatibilityPermissionInfo(String name, int sdkVersion,
            @UsesPermissionFlags int usesPermissionFlags) {
        super(name, usesPermissionFlags);
        this.sdkVersion = sdkVersion;
    }
}
