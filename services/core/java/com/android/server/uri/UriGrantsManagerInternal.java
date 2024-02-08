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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ActivityInfo.RequiredContentUriPermission;
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

    /**
     * Check if the uid has permission to the URI in grantUri.
     *
     * @param isFullAccessForContentUri If true, the URI has to be a content URI
     *                                  and the method will consider full access.
     *                                  Otherwise, the method will only consider
     *                                  URI grants.
     */
    boolean checkUriPermission(GrantUri grantUri, int uid, int modeFlags,
            boolean isFullAccessForContentUri);

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
     * Same as {@link #checkGrantUriPermissionFromIntent(Intent, int, String, int)}, but with an
     * extra parameter {@code requireContentUriPermissionFromCaller}, which is the value from {@link
     * android.R.attr#requireContentUriPermissionFromCaller} attribute.
     */
    NeededUriGrants checkGrantUriPermissionFromIntent(Intent intent, int callingUid,
            String targetPkg, int targetUserId,
            @RequiredContentUriPermission int requireContentUriPermissionFromCaller);

    /**
     * Extend a previously calculated set of permissions grants to the given
     * owner. All security checks will have already been performed as part of
     * calculating {@link NeededUriGrants}.
     */
    void grantUriPermissionUncheckedFromIntent(
            NeededUriGrants needed, UriPermissionOwner owner);

    /**
     * Creates a new stateful object to track uri permission grants. This is needed to maintain
     * state when managing grants via {@link UriGrantsManagerService#grantUriPermissionFromOwner},
     * {@link #revokeUriPermissionFromOwner}, etc.
     *
     * @param name A name for the object. This is only used for logcat/dumpsys logging, so there
     *             are no uniqueness or other requirements, but it is recommended to make the
     *             name sufficiently readable so that the relevant code area can be determined
     *             easily when this name shows up in a bug report.
     * @return An opaque owner token for tracking uri permission grants.
     * @see UriPermissionOwner
     * @see UriGrantsManagerService
     */
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
     * Like {@link #revokeUriPermissionFromOwner(IBinder, Uri, int, int, String, int)} but applies
     * to all target packages and all target users.
     */
    void revokeUriPermissionFromOwner(@NonNull IBinder token, @Nullable Uri uri, int mode,
            int userId);

    /**
     * Remove any {@link UriPermission} associated with the owner whose values match the given
     * filtering parameters.
     *
     * @param token An opaque owner token as returned by {@link #newUriPermissionOwner(String)}.
     * @param uri The content uri for which the permission grant should be revoked. This uri
     *            must NOT contain an embedded userId; use
     *            {@link android.content.ContentProvider#getUriWithoutUserId(Uri)} if needed.
     *            This param may be {@code null} to revoke grants for all uris tracked by the
     *            provided owner token.
     * @param mode The modes (as a bitmask) to revoke. See
     *             {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}, etc.
     * @param userId The userId in which the given uri is to be resolved. If the {@code uri}
     *               param is {@code null}, this param is ignored since permissions for all
     *               uris will be revoked.
     * @param targetPkg Target package name to match (app that received the grant), or
     *                  {@code null} to apply to all packages.
     * @param targetUserId Target user to match (userId of the app that received the grant), or
     *                     {@link UserHandle#USER_ALL} to apply to all users.
     */
    void revokeUriPermissionFromOwner(@NonNull IBinder token, @Nullable Uri uri, int mode,
            int userId, @Nullable String targetPkg, int targetUserId);

    boolean checkAuthorityGrants(
            int callingUid, ProviderInfo cpi, int userId, boolean checkUser);

    void dump(PrintWriter pw, boolean dumpAll, String dumpPackage);
}
