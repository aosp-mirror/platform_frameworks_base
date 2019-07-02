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

import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

import android.os.Binder;
import android.os.IBinder;
import android.util.ArraySet;
import android.util.proto.ProtoOutputStream;

import com.android.server.am.UriPermissionOwnerProto;
import com.google.android.collect.Sets;

import java.io.PrintWriter;
import java.util.Iterator;

public class UriPermissionOwner {
    private final UriGrantsManagerInternal mService;
    private final Object mOwner;

    private Binder externalToken;

    private ArraySet<UriPermission> mReadPerms;
    private ArraySet<UriPermission> mWritePerms;

    class ExternalToken extends Binder {
        UriPermissionOwner getOwner() {
            return UriPermissionOwner.this;
        }
    }

    public UriPermissionOwner(UriGrantsManagerInternal service, Object owner) {
        mService = service;
        mOwner = owner;
    }

    public Binder getExternalToken() {
        if (externalToken == null) {
            externalToken = new ExternalToken();
        }
        return externalToken;
    }

    static UriPermissionOwner fromExternalToken(IBinder token) {
        if (token instanceof ExternalToken) {
            return ((ExternalToken)token).getOwner();
        }
        return null;
    }

    public void removeUriPermissions() {
        removeUriPermissions(FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    void removeUriPermissions(int mode) {
        removeUriPermission(null, mode);
    }

    void removeUriPermission(GrantUri grantUri, int mode) {
        if ((mode & FLAG_GRANT_READ_URI_PERMISSION) != 0 && mReadPerms != null) {
            Iterator<UriPermission> it = mReadPerms.iterator();
            while (it.hasNext()) {
                UriPermission perm = it.next();
                if (grantUri == null || grantUri.equals(perm.uri)) {
                    perm.removeReadOwner(this);
                    mService.removeUriPermissionIfNeeded(perm);
                    it.remove();
                }
            }
            if (mReadPerms.isEmpty()) {
                mReadPerms = null;
            }
        }
        if ((mode & FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                && mWritePerms != null) {
            Iterator<UriPermission> it = mWritePerms.iterator();
            while (it.hasNext()) {
                UriPermission perm = it.next();
                if (grantUri == null || grantUri.equals(perm.uri)) {
                    perm.removeWriteOwner(this);
                    mService.removeUriPermissionIfNeeded(perm);
                    it.remove();
                }
            }
            if (mWritePerms.isEmpty()) {
                mWritePerms = null;
            }
        }
    }

    public void addReadPermission(UriPermission perm) {
        if (mReadPerms == null) {
            mReadPerms = Sets.newArraySet();
        }
        mReadPerms.add(perm);
    }

    public void addWritePermission(UriPermission perm) {
        if (mWritePerms == null) {
            mWritePerms = Sets.newArraySet();
        }
        mWritePerms.add(perm);
    }

    public void removeReadPermission(UriPermission perm) {
        mReadPerms.remove(perm);
        if (mReadPerms.isEmpty()) {
            mReadPerms = null;
        }
    }

    public void removeWritePermission(UriPermission perm) {
        mWritePerms.remove(perm);
        if (mWritePerms.isEmpty()) {
            mWritePerms = null;
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        if (mReadPerms != null) {
            pw.print(prefix); pw.print("readUriPermissions="); pw.println(mReadPerms);
        }
        if (mWritePerms != null) {
            pw.print(prefix); pw.print("writeUriPermissions="); pw.println(mWritePerms);
        }
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(UriPermissionOwnerProto.OWNER, mOwner.toString());
        if (mReadPerms != null) {
            synchronized (mReadPerms) {
                for (UriPermission p : mReadPerms) {
                    p.uri.writeToProto(proto, UriPermissionOwnerProto.READ_PERMS);
                }
            }
        }
        if (mWritePerms != null) {
            synchronized (mWritePerms) {
                for (UriPermission p : mWritePerms) {
                    p.uri.writeToProto(proto, UriPermissionOwnerProto.WRITE_PERMS);
                }
            }
        }
        proto.end(token);
    }

    @Override
    public String toString() {
        return mOwner.toString();
    }
}
