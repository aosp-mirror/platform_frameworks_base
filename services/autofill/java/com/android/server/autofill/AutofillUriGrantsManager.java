/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.autofill;

import static android.content.ContentResolver.SCHEME_CONTENT;

import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.IUriGrantsManager;
import android.app.UriGrantsManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.uri.UriGrantsManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Grants and revokes URI permissions for content-based autofill suggestions.
 *
 * <p>Note that the system cannot just hand out grants directly; it must always do so on behalf of
 * an owner (see {@link com.android.server.uri.UriGrantsManagerService}). For autofill, the owner
 * is the autofill service provider that creates a given autofill suggestion containing a content
 * URI. Therefore, this manager class must be instantiated with the service uid of the provider for
 * which it will manage URI grants.
 *
 * <p>To dump the state of this class, use {@code adb shell dumpsys autofill}.
 *
 * <p>To dump all active URI permissions, use {@code adb shell dumpsys activity permissions}.
 */
final class AutofillUriGrantsManager {
    private static final String TAG = AutofillUriGrantsManager.class.getSimpleName();

    private final int mSourceUid;
    @UserIdInt
    private final int mSourceUserId;
    @NonNull
    private final IBinder mPermissionOwner;
    @NonNull
    private final UriGrantsManagerInternal mUgmInternal;
    @NonNull
    private final IUriGrantsManager mUgm;

    // We use a local lock here for simplicity, since the synchronized code does not depend on
    // any other resources (the "hold and wait" condition required for deadlock is not present).
    // If this changes in the future, instead of using a local lock this should be updated to
    // use the shared lock from AutofillManagerServiceImpl.
    @NonNull
    private final Object mLock;

    // Tracks the URIs that have been granted to each package. For each URI, the map stores the
    // activities that triggered the grant. This allows revoking permissions only once all
    // activities that triggered the grant are finished.
    @NonNull
    @GuardedBy("mLock")
    private final ArrayMap<String, List<Pair<Uri, String>>> mActiveGrantsByPackage;

    /**
     * Creates a new instance of the manager.
     *
     * @param serviceUid The uid of the autofill service provider for which this manager is being
     * created. URI grants will be requested on behalf of this uid (ie, this uid will be passed as
     * the {@code fromUid} to {@link IUriGrantsManager#grantUriPermissionFromOwner}).
     */
    AutofillUriGrantsManager(int serviceUid) {
        mSourceUid = serviceUid;
        mSourceUserId = UserHandle.getUserId(mSourceUid);
        mUgmInternal = LocalServices.getService(UriGrantsManagerInternal.class);
        mPermissionOwner = mUgmInternal.newUriPermissionOwner("autofill-" + serviceUid);
        mUgm = UriGrantsManager.getService();
        mLock = new Object();
        mActiveGrantsByPackage = new ArrayMap<>(0);
    }

    public void grantUriPermissions(@NonNull ComponentName targetActivity,
            @UserIdInt int targetUserId, @NonNull ClipData clip) {
        String targetPkg = targetActivity.getPackageName();
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            Uri uri = item.getUri();
            if (uri == null || !SCHEME_CONTENT.equals(uri.getScheme())) {
                continue;
            }
            if (grantUriPermissions(targetPkg, targetUserId, uri)) {
                addToActiveGrants(uri, targetActivity);
            }
        }
    }

    public void revokeUriPermissions(@NonNull ComponentName targetActivity,
            @UserIdInt int targetUserId) {
        String targetPkg = targetActivity.getPackageName();
        Set<Uri> urisWhoseGrantsShouldBeRevoked = removeFromActiveGrants(targetActivity);
        for (Uri uri : urisWhoseGrantsShouldBeRevoked) {
            revokeUriPermissions(targetPkg, targetUserId, uri);
        }
    }

    private boolean grantUriPermissions(@NonNull String targetPkg, @UserIdInt int targetUserId,
            @NonNull Uri uri) {
        final int sourceUserId = ContentProvider.getUserIdFromUri(uri, mSourceUserId);
        if (sVerbose) {
            Slog.v(TAG, "Granting URI permissions: uri=" + uri
                    + ", sourceUid=" + mSourceUid + ", sourceUserId=" + sourceUserId
                    + ", targetPkg=" + targetPkg + ", targetUserId=" + targetUserId);
        }
        final Uri uriWithoutUserId = ContentProvider.getUriWithoutUserId(uri);
        final long ident = Binder.clearCallingIdentity();
        try {
            mUgm.grantUriPermissionFromOwner(
                    mPermissionOwner,
                    mSourceUid,
                    targetPkg,
                    uriWithoutUserId,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    sourceUserId,
                    targetUserId);
            return true;
        } catch (RemoteException e) {
            Slog.e(TAG, "Granting URI permissions failed: uri=" + uri
                    + ", sourceUid=" + mSourceUid + ", sourceUserId=" + sourceUserId
                    + ", targetPkg=" + targetPkg + ", targetUserId=" + targetUserId, e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void revokeUriPermissions(@NonNull String targetPkg, @UserIdInt int targetUserId,
            @NonNull Uri uri) {
        final int sourceUserId = ContentProvider.getUserIdFromUri(uri, mSourceUserId);
        if (sVerbose) {
            Slog.v(TAG, "Revoking URI permissions: uri=" + uri
                    + ", sourceUid=" + mSourceUid + ", sourceUserId=" + sourceUserId
                    + ", target=" + targetPkg + ", targetUserId=" + targetUserId);
        }
        final Uri uriWithoutUserId = ContentProvider.getUriWithoutUserId(uri);
        final long ident = Binder.clearCallingIdentity();
        try {
            mUgmInternal.revokeUriPermissionFromOwner(
                    mPermissionOwner,
                    uriWithoutUserId,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    sourceUserId,
                    targetPkg,
                    targetUserId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void addToActiveGrants(@NonNull Uri uri, @NonNull ComponentName targetActivity) {
        synchronized (mLock) {
            String packageName = targetActivity.getPackageName();
            List<Pair<Uri, String>> uris = mActiveGrantsByPackage.computeIfAbsent(packageName,
                    k -> new ArrayList<>(1));
            uris.add(Pair.create(uri, targetActivity.getClassName()));
        }
    }

    private Set<Uri> removeFromActiveGrants(@NonNull ComponentName targetActivity) {
        synchronized (mLock) {
            String targetPackageName = targetActivity.getPackageName();
            List<Pair<Uri, String>> uris = mActiveGrantsByPackage.get(targetPackageName);
            if (uris == null || uris.isEmpty()) {
                return Collections.emptySet();
            }

            // Collect all URIs whose grant was triggered by the target activity.
            String targetActivityClassName = targetActivity.getClassName();
            Set<Uri> urisWhoseGrantsShouldBeRevoked = new ArraySet<>(1);
            for (Iterator<Pair<Uri, String>> iter = uris.iterator(); iter.hasNext(); ) {
                Pair<Uri, String> uriAndActivity = iter.next();
                if (uriAndActivity.second.equals(targetActivityClassName)) {
                    urisWhoseGrantsShouldBeRevoked.add(uriAndActivity.first);
                    iter.remove();
                }
            }

            // A URI grant may have been triggered by more than one activity for the same package.
            // We should not revoke a grant if it was triggered by multiple activities and one or
            // more of those activities is still alive. Therefore we do a second pass and prune
            // the set of URIs to be revoked if an additional activity that triggered its grant
            // is still present.
            for (Pair<Uri, String> uriAndActivity : uris) {
                urisWhoseGrantsShouldBeRevoked.remove(uriAndActivity.first);
            }

            // If there are no remaining URIs granted to the package, drop the entry from the map.
            if (uris.isEmpty()) {
                mActiveGrantsByPackage.remove(targetPackageName);
            }
            return urisWhoseGrantsShouldBeRevoked;
        }
    }

    /**
     * Dump the active URI grants.
     */
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        synchronized (mLock) {
            if (mActiveGrantsByPackage.isEmpty()) {
                pw.print(prefix); pw.println("URI grants: none");
                return;
            }
            pw.print(prefix); pw.println("URI grants:");
            final String prefix2 = prefix + "  ";
            final String prefix3 = prefix2 + "  ";
            for (int i = mActiveGrantsByPackage.size() - 1; i >= 0; i--) {
                String packageName = mActiveGrantsByPackage.keyAt(i);
                pw.print(prefix2); pw.println(packageName);
                List<Pair<Uri, String>> uris = mActiveGrantsByPackage.valueAt(i);
                if (uris == null || uris.isEmpty())  {
                    continue;
                }
                for (Pair<Uri, String> uriAndActivity : uris) {
                    pw.print(prefix3);
                    pw.println(uriAndActivity.first + ": " + uriAndActivity.second);
                }
            }
        }
    }
}
