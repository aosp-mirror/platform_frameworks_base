/*
** Copyright 2015, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.server.wm;

import static java.lang.Integer.toHexString;

import android.app.UriGrantsManager;
import android.content.ClipData;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.view.IDragAndDropPermissions;
import com.android.server.LocalServices;
import com.android.server.uri.UriGrantsManagerInternal;

import java.util.ArrayList;

class DragAndDropPermissionsHandler extends IDragAndDropPermissions.Stub {

    private static final String TAG = "DragAndDrop";
    private static final boolean DEBUG = false;

    private final WindowManagerGlobalLock mGlobalLock;
    private final int mSourceUid;
    private final String mTargetPackage;
    private final int mMode;
    private final int mSourceUserId;
    private final int mTargetUserId;

    private final ArrayList<Uri> mUris = new ArrayList<Uri>();

    private IBinder mActivityToken = null;
    private IBinder mPermissionOwnerToken = null;

    DragAndDropPermissionsHandler(WindowManagerGlobalLock lock, ClipData clipData, int sourceUid,
            String targetPackage, int mode, int sourceUserId, int targetUserId) {
        mGlobalLock = lock;
        mSourceUid = sourceUid;
        mTargetPackage = targetPackage;
        mMode = mode;
        mSourceUserId = sourceUserId;
        mTargetUserId = targetUserId;

        clipData.collectUris(mUris);
    }

    @Override
    public void take(IBinder activityToken) throws RemoteException {
        if (mActivityToken != null || mPermissionOwnerToken != null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, this + ": taking permissions bound to activity: "
                    + toHexString(activityToken.hashCode()));
        }
        mActivityToken = activityToken;

        // Will throw if Activity is not found.
        IBinder permissionOwner = getUriPermissionOwnerForActivity(mActivityToken);

        doTake(permissionOwner);
    }

    private void doTake(IBinder permissionOwner) throws RemoteException {
        final long origId = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < mUris.size(); i++) {
                UriGrantsManager.getService().grantUriPermissionFromOwner(
                        permissionOwner, mSourceUid, mTargetPackage, mUris.get(i), mMode,
                        mSourceUserId, mTargetUserId);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void takeTransient() throws RemoteException {
        if (mActivityToken != null || mPermissionOwnerToken != null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, this + ": taking transient permissions");
        }
        mPermissionOwnerToken = LocalServices.getService(UriGrantsManagerInternal.class)
                .newUriPermissionOwner("drop");

        doTake(mPermissionOwnerToken);
    }

    @Override
    public void release() throws RemoteException {
        if (mActivityToken == null && mPermissionOwnerToken == null) {
            return;
        }

        IBinder permissionOwner = null;
        if (mActivityToken != null) {
            try {
                permissionOwner = getUriPermissionOwnerForActivity(mActivityToken);
            } catch (Exception e) {
                // Activity is destroyed, permissions already revoked.
                return;
            } finally {
                mActivityToken = null;
            }
            if (DEBUG) {
                Log.d(TAG, this + ": releasing activity-bound permissions");
            }
        } else {
            permissionOwner = mPermissionOwnerToken;
            mPermissionOwnerToken = null;
            if (DEBUG) {
                Log.d(TAG, this + ": releasing transient permissions");
            }
        }

        UriGrantsManagerInternal ugm = LocalServices.getService(UriGrantsManagerInternal.class);
        for (int i = 0; i < mUris.size(); ++i) {
            ugm.revokeUriPermissionFromOwner(permissionOwner, mUris.get(i), mMode, mSourceUserId);
        }
    }

    private IBinder getUriPermissionOwnerForActivity(IBinder activityToken) {
        ActivityTaskManagerService.enforceNotIsolatedCaller("getUriPermissionOwnerForActivity");
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInRootTaskLocked(activityToken);
            if (r == null) {
                throw new IllegalArgumentException("Activity does not exist; token="
                        + activityToken);
            }
            return r.getUriPermissionsLocked().getExternalToken();
        }
    }

    /**
     * If permissions are not tied to an activity, release whenever there are no more references
     * to this object (if not already released).
     */
    @Override
    protected void finalize() throws Throwable {
        if (DEBUG) {
            Log.d(TAG, this + ": running finalizer");
        }
        if (mActivityToken != null || mPermissionOwnerToken == null) {
            return;
        }
        release();
    }
}
