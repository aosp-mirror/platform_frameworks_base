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

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.UriGrantsManager;
import android.content.ClipData;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.view.IDragAndDropPermissions;
import com.android.server.LocalServices;
import com.android.server.uri.UriGrantsManagerInternal;

import java.util.ArrayList;

class DragAndDropPermissionsHandler extends IDragAndDropPermissions.Stub
        implements IBinder.DeathRecipient {

    private final int mSourceUid;
    private final String mTargetPackage;
    private final int mMode;
    private final int mSourceUserId;
    private final int mTargetUserId;

    private final ArrayList<Uri> mUris = new ArrayList<Uri>();

    private IBinder mActivityToken = null;
    private IBinder mPermissionOwnerToken = null;
    private IBinder mTransientToken = null;

    DragAndDropPermissionsHandler(ClipData clipData, int sourceUid, String targetPackage, int mode,
                                  int sourceUserId, int targetUserId) {
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
        mActivityToken = activityToken;

        // Will throw if Activity is not found.
        IBinder permissionOwner = ActivityTaskManager.getService().
                getUriPermissionOwnerForActivity(mActivityToken);

        doTake(permissionOwner);
    }

    private void doTake(IBinder permissionOwner) throws RemoteException {
        long origId = Binder.clearCallingIdentity();
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
    public void takeTransient(IBinder transientToken) throws RemoteException {
        if (mActivityToken != null || mPermissionOwnerToken != null) {
            return;
        }
        mPermissionOwnerToken = LocalServices.getService(UriGrantsManagerInternal.class)
                .newUriPermissionOwner("drop");
        mTransientToken = transientToken;
        mTransientToken.linkToDeath(this, 0);

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
                permissionOwner = ActivityTaskManager.getService().
                        getUriPermissionOwnerForActivity(mActivityToken);
            } catch (Exception e) {
                // Activity is destroyed, permissions already revoked.
                return;
            } finally {
                mActivityToken = null;
            }
        } else {
            permissionOwner = mPermissionOwnerToken;
            mPermissionOwnerToken = null;
            mTransientToken.unlinkToDeath(this, 0);
            mTransientToken = null;
        }

        UriGrantsManagerInternal ugm = LocalServices.getService(UriGrantsManagerInternal.class);
        for (int i = 0; i < mUris.size(); ++i) {
            ugm.revokeUriPermissionFromOwner(permissionOwner, mUris.get(i), mMode, mSourceUserId);
        }
    }

    @Override
    public void binderDied() {
        try {
            release();
        } catch (RemoteException e) {
            // Cannot happen, local call.
        }
    }
}
