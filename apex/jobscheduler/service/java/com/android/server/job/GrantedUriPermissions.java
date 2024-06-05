/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.job;

import android.app.UriGrantsManager;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.server.LocalServices;
import com.android.server.uri.UriGrantsManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;

public final class GrantedUriPermissions {
    private final int mGrantFlags;
    private final int mSourceUserId;
    private final String mTag;
    private final IBinder mPermissionOwner;
    private final UriGrantsManagerInternal mUriGrantsManagerInternal;
    private final ArrayList<Uri> mUris = new ArrayList<>();

    private GrantedUriPermissions(int grantFlags, int uid, String tag)
            throws RemoteException {
        mGrantFlags = grantFlags;
        mSourceUserId = UserHandle.getUserId(uid);
        mTag = tag;
        mUriGrantsManagerInternal = LocalServices.getService(UriGrantsManagerInternal.class);
        mPermissionOwner = mUriGrantsManagerInternal.newUriPermissionOwner("job: " + tag);
    }

    public void revoke() {
        for (int i = mUris.size()-1; i >= 0; i--) {
            mUriGrantsManagerInternal.revokeUriPermissionFromOwner(
                    mPermissionOwner, mUris.get(i), mGrantFlags, mSourceUserId);
        }
        mUris.clear();
    }

    public static boolean checkGrantFlags(int grantFlags) {
        return (grantFlags & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                |Intent.FLAG_GRANT_READ_URI_PERMISSION)) != 0;
    }

    public static GrantedUriPermissions createFromIntent(Intent intent,
            int sourceUid, String targetPackage, int targetUserId, String tag) {
        int grantFlags = intent.getFlags();
        if (!checkGrantFlags(grantFlags)) {
            return null;
        }

        GrantedUriPermissions perms = null;

        Uri data = intent.getData();
        if (data != null) {
            perms = grantUri(data, sourceUid, targetPackage, targetUserId, grantFlags, tag,
                    perms);
        }

        ClipData clip = intent.getClipData();
        if (clip != null) {
            perms = grantClip(clip, sourceUid, targetPackage, targetUserId, grantFlags, tag,
                    perms);
        }

        return perms;
    }

    public static GrantedUriPermissions createFromClip(ClipData clip,
            int sourceUid, String targetPackage, int targetUserId, int grantFlags, String tag) {
        if (!checkGrantFlags(grantFlags)) {
            return null;
        }
        GrantedUriPermissions perms = null;
        if (clip != null) {
            perms = grantClip(clip, sourceUid, targetPackage, targetUserId, grantFlags,
                    tag, perms);
        }
        return perms;
    }

    private static GrantedUriPermissions grantClip(ClipData clip,
            int sourceUid, String targetPackage, int targetUserId, int grantFlags, String tag,
            GrantedUriPermissions curPerms) {
        final int N = clip.getItemCount();
        for (int i = 0; i < N; i++) {
            curPerms = grantItem(clip.getItemAt(i), sourceUid, targetPackage, targetUserId,
                    grantFlags, tag, curPerms);
        }
        return curPerms;
    }

    private static GrantedUriPermissions grantUri(Uri uri,
            int sourceUid, String targetPackage, int targetUserId, int grantFlags, String tag,
            GrantedUriPermissions curPerms) {
        try {
            int sourceUserId = ContentProvider.getUserIdFromUri(uri,
                    UserHandle.getUserId(sourceUid));
            uri = ContentProvider.getUriWithoutUserId(uri);
            if (curPerms == null) {
                curPerms = new GrantedUriPermissions(grantFlags, sourceUid, tag);
            }
            UriGrantsManager.getService().grantUriPermissionFromOwner(curPerms.mPermissionOwner,
                    sourceUid, targetPackage, uri, grantFlags, sourceUserId, targetUserId);
            curPerms.mUris.add(uri);
        } catch (RemoteException e) {
            Slog.e("JobScheduler", "AM dead");
        }
        return curPerms;
    }

    private static GrantedUriPermissions grantItem(ClipData.Item item,
            int sourceUid, String targetPackage, int targetUserId, int grantFlags, String tag,
            GrantedUriPermissions curPerms) {
        if (item.getUri() != null) {
            curPerms = grantUri(item.getUri(), sourceUid, targetPackage, targetUserId,
                    grantFlags, tag, curPerms);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            curPerms = grantUri(intent.getData(), sourceUid, targetPackage, targetUserId,
                    grantFlags, tag, curPerms);
        }
        return curPerms;
    }

    // Dumpsys infrastructure
    public void dump(PrintWriter pw) {
        pw.print("mGrantFlags=0x"); pw.print(Integer.toHexString(mGrantFlags));
        pw.print(" mSourceUserId="); pw.println(mSourceUserId);
        pw.print("mTag="); pw.println(mTag);
        pw.print("mPermissionOwner="); pw.println(mPermissionOwner);
        for (int i = 0; i < mUris.size(); i++) {
            pw.print("#"); pw.print(i); pw.print(": ");
            pw.println(mUris.get(i));
        }
    }

    public void dump(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        proto.write(GrantedUriPermissionsDumpProto.FLAGS, mGrantFlags);
        proto.write(GrantedUriPermissionsDumpProto.SOURCE_USER_ID, mSourceUserId);
        proto.write(GrantedUriPermissionsDumpProto.TAG, mTag);
        proto.write(GrantedUriPermissionsDumpProto.PERMISSION_OWNER, mPermissionOwner.toString());
        for (int i = 0; i < mUris.size(); i++) {
            Uri u = mUris.get(i);
            if (u != null) {
                proto.write(GrantedUriPermissionsDumpProto.URIS, u.toString());
            }
        }

        proto.end(token);
    }
}
