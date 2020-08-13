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
 * limitations under the License
 */

package com.android.server.uri;

import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.UserHandle;

import java.io.PrintWriter;

/**
 * Uri Grants local system service interface.
 * @hide Only for use within system server
 */
public interface UriGrantsManagerInternal {
    void onSystemReady();
    void removeUriPermissionIfNeeded(UriPermission perm);

    void revokeUriPermission(String targetPackage, int callingUid,
            GrantUri grantUri, final int modeFlags);

    boolean checkUriPermission(GrantUri grantUri, int uid, final int modeFlags);
    int checkGrantUriPermission(
            int callingUid, String targetPkg, Uri uri, int modeFlags, int userId);

    /**
     * Calculate the set of permission grants that would be needed to extend
     * access for the given {@link Intent} to the given target package.
     *
     * @throws SecurityException if the caller doesn't have permission to the
     *             {@link Intent} data, or if the underlying provider doesn't
     *             allow permissions to be granted.
     */
    NeededUriGrants checkGrantUriPermissionFromIntent(Intent intent, int callingUid,
            String targetPkg, int targetUserId);

    /**
     * Extend a previously calculated set of permissions grants to the given
     * owner. All security checks will have already been performed as part of
     * calculating {@link NeededUriGrants}.
     */
    void grantUriPermissionUncheckedFromIntent(
            NeededUriGrants needed, UriPermissionOwner owner);

    IBinder newUriPermissionOwner(String name);

    /**
     * Remove any {@link UriPermission} granted <em>from</em> or <em>to</em> the
     * given package.
     *
     * @param packageName Package name to match, or {@code null} to apply to all
     *            packages.
     * @param userHandle User to match, or {@link UserHandle#USER_ALL} to apply
     *            to all users.
     * @param persistable If persistable grants should be removed.
     * @param targetOnly When {@code true}, only remove permissions where the app is the target,
     * not source.
     */
    void removeUriPermissionsForPackage(
            String packageName, int userHandle, boolean persistable, boolean targetOnly);
    /**
     * Remove any {@link UriPermission} associated with the owner whose values match the given
     * filtering parameters.
     *
     * @param token An opaque owner token as returned by {@link #newUriPermissionOwner(String)}.
     * @param uri This uri must NOT contain an embedded userId. {@code null} to apply to all Uris.
     * @param mode The modes (as a bitmask) to revoke.
     * @param userId The userId in which the uri is to be resolved.
     */
    void revokeUriPermissionFromOwner(IBinder token, Uri uri, int mode, int userId);

    /**
     * Remove any {@link UriPermission} associated with the owner whose values match the given
     * filtering parameters.
     *
     * @param token An opaque owner token as returned by {@link #newUriPermissionOwner(String)}.
     * @param uri This uri must NOT contain an embedded userId. {@code null} to apply to all Uris.
     * @param mode The modes (as a bitmask) to revoke.
     * @param userId The userId in which the uri is to be resolved.
     * @param targetPkg Calling package name to match, or {@code null} to apply to all packages.
     * @param targetUserId Calling user to match, or {@link UserHandle#USER_ALL} to apply to all
     *                     users.
     */
    void revokeUriPermissionFromOwner(IBinder token, Uri uri, int mode, int userId,
            String targetPkg, int targetUserId);

    boolean checkAuthorityGrants(
            int callingUid, ProviderInfo cpi, int userId, boolean checkUser);
    void dump(PrintWriter pw, boolean dumpAll, String dumpPackage);
}
