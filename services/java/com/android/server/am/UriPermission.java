/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.util.IndentingPrintWriter;
import com.google.android.collect.Sets;

import java.io.PrintWriter;
import java.util.HashSet;

/**
 * Description of a permission granted to an app to access a particular URI.
 *
 * CTS tests for this functionality can be run with "runtest cts-appsecurity".
 *
 * Test cases are at cts/tests/appsecurity-tests/test-apps/UsePermissionDiffCert/
 *      src/com/android/cts/usespermissiondiffcertapp/AccessPermissionWithDiffSigTest.java
 */
class UriPermission {
    private static final String TAG = "UriPermission";

    final int userHandle;
    final String sourcePkg;
    final String targetPkg;

    /** Cached UID of {@link #targetPkg}; should not be persisted */
    final int targetUid;

    final Uri uri;

    /**
     * Allowed modes. All permission enforcement should use this field. Must
     * always be a superset of {@link #globalModeFlags},
     * {@link #persistedModeFlags}, {@link #mReadOwners}, and
     * {@link #mWriteOwners}. Mutations should only be performed by the owning
     * class.
     */
    int modeFlags = 0;

    /**
     * Allowed modes without explicit owner. Must always be a superset of
     * {@link #persistedModeFlags}. Mutations should only be performed by the
     * owning class.
     */
    int globalModeFlags = 0;

    /**
     * Allowed modes that should be persisted across device boots. These modes
     * have no explicit owners. Mutations should only be performed by the owning
     * class.
     */
    int persistedModeFlags = 0;

    private HashSet<UriPermissionOwner> mReadOwners;
    private HashSet<UriPermissionOwner> mWriteOwners;

    private String stringName;

    UriPermission(String sourcePkg, String targetPkg, int targetUid, Uri uri) {
        this.userHandle = UserHandle.getUserId(targetUid);
        this.sourcePkg = sourcePkg;
        this.targetPkg = targetPkg;
        this.targetUid = targetUid;
        this.uri = uri;
    }

    /**
     * @return If mode changes should trigger persisting.
     */
    boolean grantModes(int modeFlagsToGrant, boolean persist, UriPermissionOwner owner) {
        boolean persistChanged = false;

        modeFlags |= modeFlagsToGrant;

        if (persist) {
            final int before = persistedModeFlags;
            persistedModeFlags |= modeFlagsToGrant;
            persistChanged = persistedModeFlags != before;

            // Treat persisted grants as global (ownerless)
            owner = null;
        }

        if (owner == null) {
            globalModeFlags |= modeFlags;
        } else {
            if ((modeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                addReadOwner(owner);
                owner.addReadPermission(this);
            }
            if ((modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                addWriteOwner(owner);
                owner.addWritePermission(this);
            }
        }

        return persistChanged;
    }
    
    /**
     * @return If mode changes should trigger persisting.
     */
    boolean clearModes(int modeFlagsToClear, boolean persist) {
        final int before = persistedModeFlags;

        if ((modeFlagsToClear & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            if (persist) {
                persistedModeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
            }
            globalModeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
            modeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (mReadOwners != null) {
                for (UriPermissionOwner r : mReadOwners) {
                    r.removeReadPermission(this);
                }
                mReadOwners = null;
            }
        }
        if ((modeFlagsToClear & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            if (persist) {
                persistedModeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            }
            globalModeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            modeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if (mWriteOwners != null) {
                for (UriPermissionOwner r : mWriteOwners) {
                    r.removeWritePermission(this);
                }
                mWriteOwners = null;
            }
        }

        // Mode flags always bubble up
        globalModeFlags |= persistedModeFlags;
        modeFlags |= globalModeFlags;

        return persistedModeFlags != before;
    }

    private void addReadOwner(UriPermissionOwner owner) {
        if (mReadOwners == null) {
            mReadOwners = Sets.newHashSet();
        }
        mReadOwners.add(owner);
    }

    /**
     * Remove given read owner, updating {@Link #modeFlags} as needed.
     */
    void removeReadOwner(UriPermissionOwner owner) {
        if (!mReadOwners.remove(owner)) {
            Log.wtf(TAG, "Unknown read owner " + owner + " in " + this);
        }
        if (mReadOwners.size() == 0) {
            mReadOwners = null;
            if ((globalModeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                modeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
            }
        }
    }

    private void addWriteOwner(UriPermissionOwner owner) {
        if (mWriteOwners == null) {
            mWriteOwners = Sets.newHashSet();
        }
        mWriteOwners.add(owner);
    }

    /**
     * Remove given write owner, updating {@Link #modeFlags} as needed.
     */
    void removeWriteOwner(UriPermissionOwner owner) {
        if (!mWriteOwners.remove(owner)) {
            Log.wtf(TAG, "Unknown write owner " + owner + " in " + this);
        }
        if (mWriteOwners.size() == 0) {
            mWriteOwners = null;
            if ((globalModeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                modeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            }
        }
    }

    @Override
    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("UriPermission{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(uri);
        sb.append('}');
        return stringName = sb.toString();
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("userHandle=" + userHandle);
        pw.print("sourcePkg=" + sourcePkg);
        pw.println("targetPkg=" + targetPkg);

        pw.print(prefix);
        pw.print("modeFlags=0x" + Integer.toHexString(modeFlags));
        pw.print("globalModeFlags=0x" + Integer.toHexString(globalModeFlags));
        pw.println("persistedModeFlags=0x" + Integer.toHexString(persistedModeFlags));

        if (mReadOwners != null) {
            pw.print(prefix);
            pw.println("readOwners:");
            for (UriPermissionOwner owner : mReadOwners) {
                pw.print(prefix);
                pw.println("  * " + owner);
            }
        }
        if (mWriteOwners != null) {
            pw.print(prefix);
            pw.println("writeOwners:");
            for (UriPermissionOwner owner : mReadOwners) {
                pw.print(prefix);
                pw.println("  * " + owner);
            }
        }
    }

    /**
     * Snapshot of {@link UriPermission} with frozen
     * {@link UriPermission#persistedModeFlags} state.
     */
    public static class Snapshot {
        final int userHandle;
        final String sourcePkg;
        final String targetPkg;
        final Uri uri;
        final int persistedModeFlags;

        private Snapshot(UriPermission perm) {
            this.userHandle = perm.userHandle;
            this.sourcePkg = perm.sourcePkg;
            this.targetPkg = perm.targetPkg;
            this.uri = perm.uri;
            this.persistedModeFlags = perm.persistedModeFlags;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }
}
