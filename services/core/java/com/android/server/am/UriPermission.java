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
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.server.am.ActivityManagerService.GrantUri;
import com.google.android.collect.Sets;

import java.io.PrintWriter;
import java.util.Comparator;

/**
 * Description of a permission granted to an app to access a particular URI.
 *
 * CTS tests for this functionality can be run with "runtest cts-appsecurity".
 *
 * Test cases are at cts/tests/appsecurity-tests/test-apps/UsePermissionDiffCert/
 *      src/com/android/cts/usespermissiondiffcertapp/AccessPermissionWithDiffSigTest.java
 */
final class UriPermission {
    private static final String TAG = "UriPermission";

    public static final int STRENGTH_NONE = 0;
    public static final int STRENGTH_OWNED = 1;
    public static final int STRENGTH_GLOBAL = 2;
    public static final int STRENGTH_PERSISTABLE = 3;

    final int targetUserId;
    final String sourcePkg;
    final String targetPkg;

    /** Cached UID of {@link #targetPkg}; should not be persisted */
    final int targetUid;

    final GrantUri uri;

    /**
     * Allowed modes. All permission enforcement should use this field. Must
     * always be a combination of {@link #ownedModeFlags},
     * {@link #globalModeFlags}, {@link #persistableModeFlags}, and
     * {@link #persistedModeFlags}. Mutations <em>must</em> only be performed by
     * the owning class.
     */
    int modeFlags = 0;

    /** Allowed modes with active owner. */
    int ownedModeFlags = 0;
    /** Allowed modes without explicit owner. */
    int globalModeFlags = 0;
    /** Allowed modes that have been offered for possible persisting. */
    int persistableModeFlags = 0;

    /** Allowed modes that should be persisted across device boots. */
    int persistedModeFlags = 0;

    /**
     * Timestamp when {@link #persistedModeFlags} was first defined in
     * {@link System#currentTimeMillis()} time base.
     */
    long persistedCreateTime = INVALID_TIME;

    private static final long INVALID_TIME = Long.MIN_VALUE;

    private ArraySet<UriPermissionOwner> mReadOwners;
    private ArraySet<UriPermissionOwner> mWriteOwners;

    private String stringName;

    UriPermission(String sourcePkg, String targetPkg, int targetUid, GrantUri uri) {
        this.targetUserId = UserHandle.getUserId(targetUid);
        this.sourcePkg = sourcePkg;
        this.targetPkg = targetPkg;
        this.targetUid = targetUid;
        this.uri = uri;
    }

    private void updateModeFlags() {
        modeFlags = ownedModeFlags | globalModeFlags | persistableModeFlags | persistedModeFlags;
    }

    /**
     * Initialize persisted modes as read from file. This doesn't issue any
     * global or owner grants.
     */
    void initPersistedModes(int modeFlags, long createdTime) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        persistableModeFlags = modeFlags;
        persistedModeFlags = modeFlags;
        persistedCreateTime = createdTime;

        updateModeFlags();
    }

    void grantModes(int modeFlags, UriPermissionOwner owner) {
        final boolean persistable = (modeFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if (persistable) {
            persistableModeFlags |= modeFlags;
        }

        if (owner == null) {
            globalModeFlags |= modeFlags;
        } else {
            if ((modeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                addReadOwner(owner);
            }
            if ((modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                addWriteOwner(owner);
            }
        }

        updateModeFlags();
    }

    /**
     * @return if mode changes should trigger persisting.
     */
    boolean takePersistableModes(int modeFlags) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if ((modeFlags & persistableModeFlags) != modeFlags) {
            Slog.w(TAG, "Requested flags 0x"
                    + Integer.toHexString(modeFlags) + ", but only 0x"
                    + Integer.toHexString(persistableModeFlags) + " are allowed");
            return false;
        }

        final int before = persistedModeFlags;
        persistedModeFlags |= (persistableModeFlags & modeFlags);

        if (persistedModeFlags != 0) {
            persistedCreateTime = System.currentTimeMillis();
        }

        updateModeFlags();
        return persistedModeFlags != before;
    }

    boolean releasePersistableModes(int modeFlags) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        final int before = persistedModeFlags;

        persistableModeFlags &= ~modeFlags;
        persistedModeFlags &= ~modeFlags;

        if (persistedModeFlags == 0) {
            persistedCreateTime = INVALID_TIME;
        }

        updateModeFlags();
        return persistedModeFlags != before;
    }

    /**
     * @return if mode changes should trigger persisting.
     */
    boolean revokeModes(int modeFlags, boolean includingOwners) {
        final boolean persistable = (modeFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        final int before = persistedModeFlags;

        if ((modeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            if (persistable) {
                persistableModeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
                persistedModeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
            }
            globalModeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (mReadOwners != null && includingOwners) {
                ownedModeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
                for (UriPermissionOwner r : mReadOwners) {
                    r.removeReadPermission(this);
                }
                mReadOwners = null;
            }
        }
        if ((modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            if (persistable) {
                persistableModeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                persistedModeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            }
            globalModeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if (mWriteOwners != null && includingOwners) {
                ownedModeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                for (UriPermissionOwner r : mWriteOwners) {
                    r.removeWritePermission(this);
                }
                mWriteOwners = null;
            }
        }

        if (persistedModeFlags == 0) {
            persistedCreateTime = INVALID_TIME;
        }

        updateModeFlags();
        return persistedModeFlags != before;
    }

    /**
     * Return strength of this permission grant for the given flags.
     */
    public int getStrength(int modeFlags) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((persistableModeFlags & modeFlags) == modeFlags) {
            return STRENGTH_PERSISTABLE;
        } else if ((globalModeFlags & modeFlags) == modeFlags) {
            return STRENGTH_GLOBAL;
        } else if ((ownedModeFlags & modeFlags) == modeFlags) {
            return STRENGTH_OWNED;
        } else {
            return STRENGTH_NONE;
        }
    }

    private void addReadOwner(UriPermissionOwner owner) {
        if (mReadOwners == null) {
            mReadOwners = Sets.newArraySet();
            ownedModeFlags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            updateModeFlags();
        }
        if (mReadOwners.add(owner)) {
            owner.addReadPermission(this);
        }
    }

    /**
     * Remove given read owner, updating {@Link #modeFlags} as needed.
     */
    void removeReadOwner(UriPermissionOwner owner) {
        if (!mReadOwners.remove(owner)) {
            Slog.wtf(TAG, "Unknown read owner " + owner + " in " + this);
        }
        if (mReadOwners.size() == 0) {
            mReadOwners = null;
            ownedModeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
            updateModeFlags();
        }
    }

    private void addWriteOwner(UriPermissionOwner owner) {
        if (mWriteOwners == null) {
            mWriteOwners = Sets.newArraySet();
            ownedModeFlags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            updateModeFlags();
        }
        if (mWriteOwners.add(owner)) {
            owner.addWritePermission(this);
        }
    }

    /**
     * Remove given write owner, updating {@Link #modeFlags} as needed.
     */
    void removeWriteOwner(UriPermissionOwner owner) {
        if (!mWriteOwners.remove(owner)) {
            Slog.wtf(TAG, "Unknown write owner " + owner + " in " + this);
        }
        if (mWriteOwners.size() == 0) {
            mWriteOwners = null;
            ownedModeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            updateModeFlags();
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
        pw.print("targetUserId=" + targetUserId);
        pw.print(" sourcePkg=" + sourcePkg);
        pw.println(" targetPkg=" + targetPkg);

        pw.print(prefix);
        pw.print("mode=0x" + Integer.toHexString(modeFlags));
        pw.print(" owned=0x" + Integer.toHexString(ownedModeFlags));
        pw.print(" global=0x" + Integer.toHexString(globalModeFlags));
        pw.print(" persistable=0x" + Integer.toHexString(persistableModeFlags));
        pw.print(" persisted=0x" + Integer.toHexString(persistedModeFlags));
        if (persistedCreateTime != INVALID_TIME) {
            pw.print(" persistedCreate=" + persistedCreateTime);
        }
        pw.println();

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

    public static class PersistedTimeComparator implements Comparator<UriPermission> {
        @Override
        public int compare(UriPermission lhs, UriPermission rhs) {
            return Long.compare(lhs.persistedCreateTime, rhs.persistedCreateTime);
        }
    }

    /**
     * Snapshot of {@link UriPermission} with frozen
     * {@link UriPermission#persistedModeFlags} state.
     */
    public static class Snapshot {
        final int targetUserId;
        final String sourcePkg;
        final String targetPkg;
        final GrantUri uri;
        final int persistedModeFlags;
        final long persistedCreateTime;

        private Snapshot(UriPermission perm) {
            this.targetUserId = perm.targetUserId;
            this.sourcePkg = perm.sourcePkg;
            this.targetPkg = perm.targetPkg;
            this.uri = perm.uri;
            this.persistedModeFlags = perm.persistedModeFlags;
            this.persistedCreateTime = perm.persistedCreateTime;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public android.content.UriPermission buildPersistedPublicApiObject() {
        return new android.content.UriPermission(uri.uri, persistedModeFlags, persistedCreateTime);
    }
}
