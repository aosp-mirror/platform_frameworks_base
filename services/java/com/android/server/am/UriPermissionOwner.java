/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.am;

import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;

import java.util.HashSet;
import java.util.Iterator;

class UriPermissionOwner {
    final ActivityManagerService service;
    final Object owner;

    Binder externalToken;

    HashSet<UriPermission> readUriPermissions; // special access to reading uris.
    HashSet<UriPermission> writeUriPermissions; // special access to writing uris.

    class ExternalToken extends Binder {
        UriPermissionOwner getOwner() {
            return UriPermissionOwner.this;
        }
    }

    UriPermissionOwner(ActivityManagerService _service, Object _owner) {
        service = _service;
        owner = _owner;
    }

    Binder getExternalTokenLocked() {
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

    void removeUriPermissionsLocked() {
        removeUriPermissionsLocked(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    void removeUriPermissionsLocked(int mode) {
        if ((mode&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                && readUriPermissions != null) {
            for (UriPermission perm : readUriPermissions) {
                perm.readOwners.remove(this);
                if (perm.readOwners.size() == 0 && (perm.globalModeFlags
                        &Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                    perm.modeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    service.removeUriPermissionIfNeededLocked(perm);
                }
            }
            readUriPermissions = null;
        }
        if ((mode&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                && writeUriPermissions != null) {
            for (UriPermission perm : writeUriPermissions) {
                perm.writeOwners.remove(this);
                if (perm.writeOwners.size() == 0 && (perm.globalModeFlags
                        &Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                    perm.modeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    service.removeUriPermissionIfNeededLocked(perm);
                }
            }
            writeUriPermissions = null;
        }
    }

    void removeUriPermissionLocked(Uri uri, int mode) {
        if ((mode&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                && readUriPermissions != null) {
            Iterator<UriPermission> it = readUriPermissions.iterator();
            while (it.hasNext()) {
                UriPermission perm = it.next();
                if (uri.equals(perm.uri)) {
                    perm.readOwners.remove(this);
                    if (perm.readOwners.size() == 0 && (perm.globalModeFlags
                            &Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                        perm.modeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        service.removeUriPermissionIfNeededLocked(perm);
                    }
                    it.remove();
                }
            }
            if (readUriPermissions.size() == 0) {
                readUriPermissions = null;
            }
        }
        if ((mode&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                && writeUriPermissions != null) {
            Iterator<UriPermission> it = writeUriPermissions.iterator();
            while (it.hasNext()) {
                UriPermission perm = it.next();
                if (uri.equals(perm.uri)) {
                    perm.writeOwners.remove(this);
                    if (perm.writeOwners.size() == 0 && (perm.globalModeFlags
                            &Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                        perm.modeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                        service.removeUriPermissionIfNeededLocked(perm);
                    }
                    it.remove();
                }
            }
            if (writeUriPermissions.size() == 0) {
                writeUriPermissions = null;
            }
        }
    }

    public void addReadPermission(UriPermission perm) {
        if (readUriPermissions == null) {
            readUriPermissions = new HashSet<UriPermission>();
        }
        readUriPermissions.add(perm);
    }

    public void addWritePermission(UriPermission perm) {
        if (writeUriPermissions == null) {
            writeUriPermissions = new HashSet<UriPermission>();
        }
        writeUriPermissions.add(perm);
    }

    public void removeReadPermission(UriPermission perm) {
        readUriPermissions.remove(perm);
        if (readUriPermissions.size() == 0) {
            readUriPermissions = null;
        }
    }

    public void removeWritePermission(UriPermission perm) {
        writeUriPermissions.remove(perm);
        if (writeUriPermissions.size() == 0) {
            writeUriPermissions = null;
        }
    }

    @Override
    public String toString() {
        return owner.toString();
    }
}
