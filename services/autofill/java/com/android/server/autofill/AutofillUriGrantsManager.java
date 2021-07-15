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

import static java.lang.Integer.toHexString;

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
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;

/**
 * Grants URI permissions for content-based autofill suggestions.
 *
 * <p>URI permissions granted by this class are tied to the activity being filled. When the
 * activity finishes, its URI grants are automatically revoked.
 *
 * <p>To dump all active URI permissions, use {@code adb shell dumpsys activity permissions}.
 */
final class AutofillUriGrantsManager {
    private static final String TAG = AutofillUriGrantsManager.class.getSimpleName();

    private final int mSourceUid;
    @UserIdInt
    private final int mSourceUserId;
    @NonNull
    private final ActivityTaskManagerInternal mActivityTaskMgrInternal;
    @NonNull
    private final IUriGrantsManager mUgm;

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
        mActivityTaskMgrInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mUgm = UriGrantsManager.getService();
    }

    public void grantUriPermissions(@NonNull ComponentName targetActivity,
            @NonNull IBinder targetActivityToken, @UserIdInt int targetUserId,
            @NonNull ClipData clip) {
        final String targetPkg = targetActivity.getPackageName();
        final IBinder permissionOwner =
                mActivityTaskMgrInternal.getUriPermissionOwnerForActivity(targetActivityToken);
        if (permissionOwner == null) {
            Slog.w(TAG, "Can't grant URI permissions, because the target activity token is invalid:"
                    + " clip=" + clip
                    + ", targetActivity=" + targetActivity + ", targetUserId=" + targetUserId
                    + ", targetActivityToken=" + toHexString(targetActivityToken.hashCode()));
            return;
        }
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            Uri uri = item.getUri();
            if (uri == null || !SCHEME_CONTENT.equals(uri.getScheme())) {
                continue;
            }
            grantUriPermissions(uri, targetPkg, targetUserId, permissionOwner);
        }
    }

    private void grantUriPermissions(@NonNull Uri uri, @NonNull String targetPkg,
            @UserIdInt int targetUserId, @NonNull IBinder permissionOwner) {
        final int sourceUserId = ContentProvider.getUserIdFromUri(uri, mSourceUserId);
        if (sVerbose) {
            Slog.v(TAG, "Granting URI permissions: uri=" + uri
                    + ", sourceUid=" + mSourceUid + ", sourceUserId=" + sourceUserId
                    + ", targetPkg=" + targetPkg + ", targetUserId=" + targetUserId
                    + ", permissionOwner=" + toHexString(permissionOwner.hashCode()));
        }
        final Uri uriWithoutUserId = ContentProvider.getUriWithoutUserId(uri);
        final long ident = Binder.clearCallingIdentity();
        try {
            mUgm.grantUriPermissionFromOwner(
                    permissionOwner,
                    mSourceUid,
                    targetPkg,
                    uriWithoutUserId,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    sourceUserId,
                    targetUserId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Granting URI permissions failed: uri=" + uri
                    + ", sourceUid=" + mSourceUid + ", sourceUserId=" + sourceUserId
                    + ", targetPkg=" + targetPkg + ", targetUserId=" + targetUserId
                    + ", permissionOwner=" + toHexString(permissionOwner.hashCode()), e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
