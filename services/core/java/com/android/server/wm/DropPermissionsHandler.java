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

import android.app.ActivityManagerNative;
import android.content.ClipData;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.view.IDropPermissions;

import java.util.ArrayList;

class DropPermissionsHandler extends IDropPermissions.Stub {

    private final int mSourceUid;
    private final String mTargetPackage;
    private final int mMode;
    private final int mSourceUserId;
    private final int mTargetUserId;

    private final ArrayList<Uri> mUris = new ArrayList<Uri>();

    private IBinder mPermissionOwner = null;

    DropPermissionsHandler(ClipData clipData, int sourceUid, String targetPackage, int mode,
            int sourceUserId, int targetUserId) {
        mSourceUid = sourceUid;
        mTargetPackage = targetPackage;
        mMode = mode;
        mSourceUserId = sourceUserId;
        mTargetUserId = targetUserId;

        clipData.collectUris(mUris);
    }

    @Override
    public void take() throws RemoteException {
        if (mPermissionOwner != null) {
            return;
        }

        mPermissionOwner = ActivityManagerNative.getDefault().newUriPermissionOwner("drop");

        long origId = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < mUris.size(); i++) {
                ActivityManagerNative.getDefault().grantUriPermissionFromOwner(
                        mPermissionOwner, mSourceUid, mTargetPackage, mUris.get(i), mMode,
                        mSourceUserId, mTargetUserId);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void release() throws RemoteException {
        if (mPermissionOwner == null) {
            return;
        }

        for (int i = 0; i < mUris.size(); ++i) {
            ActivityManagerNative.getDefault().revokeUriPermissionFromOwner(
                    mPermissionOwner, mUris.get(i), mMode, mSourceUserId);
        }

        mPermissionOwner = null;
    }
}
