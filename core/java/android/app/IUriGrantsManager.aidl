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

package android.app;

import android.content.pm.ParceledListSlice;
import android.net.Uri;
import android.os.IBinder;

/**
 * Interface for managing an app's permission to access a particular URI.
 * {@hide}
 */
interface IUriGrantsManager {
    void takePersistableUriPermission(in Uri uri, int modeFlags, String toPackage, int userId);
    void releasePersistableUriPermission(in Uri uri, int modeFlags, String toPackage, int userId);
    void grantUriPermissionFromOwner(in IBinder owner, int fromUid, in String targetPkg,
            in Uri uri, int mode, int sourceUserId, int targetUserId);
    /**
     * Gets the URI permissions granted to an arbitrary package (or all packages if null)
     * NOTE: this is different from getUriPermissions(), which returns the URIs the package
     * granted to another packages (instead of those granted to it).
     */
    ParceledListSlice getGrantedUriPermissions(in String packageName, int userId);
    /** Clears the URI permissions granted to an arbitrary package. */
    void clearGrantedUriPermissions(in String packageName, int userId);
    ParceledListSlice getUriPermissions(in String packageName, boolean incoming,
            boolean persistedOnly);

    int checkGrantUriPermission_ignoreNonSystem(
            int sourceUid, String targetPkg, in Uri uri, int modeFlags, int userId);
}
