/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import android.annotation.NonNull;
import android.content.pm.PackageInfo;
import android.content.pm.ShortcutInfo;
import android.util.Slog;

import com.android.internal.util.Preconditions;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

/**
 * All methods should be guarded by {@code #mShortcutUser.mService.mLock}.
 */
abstract class ShortcutPackageItem {
    private static final String TAG = ShortcutService.TAG;
    private static final String KEY_NAME = "name";

    private final int mPackageUserId;
    private final String mPackageName;

    private final ShortcutPackageInfo mPackageInfo;

    protected ShortcutUser mShortcutUser;

    protected ShortcutPackageItem(@NonNull ShortcutUser shortcutUser,
            int packageUserId, @NonNull String packageName,
            @NonNull ShortcutPackageInfo packageInfo) {
        mShortcutUser = shortcutUser;
        mPackageUserId = packageUserId;
        mPackageName = Preconditions.checkStringNotEmpty(packageName);
        mPackageInfo = Preconditions.checkNotNull(packageInfo);
    }

    /**
     * Change the parent {@link ShortcutUser}.  Need it in the restore code.
     */
    public void replaceUser(ShortcutUser user) {
        mShortcutUser = user;
    }

    public ShortcutUser getUser() {
        return mShortcutUser;
    }

    /**
     * ID of the user who actually has this package running on.  For {@link ShortcutPackage},
     * this is the same thing as {@link #getOwnerUserId}, but if it's a {@link ShortcutLauncher} and
     * {@link #getOwnerUserId} is of work profile, then this ID is of the primary user.
     */
    public int getPackageUserId() {
        return mPackageUserId;
    }

    /**
     * ID of the user who sees the shortcuts from this instance.
     */
    public abstract int getOwnerUserId();

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    public ShortcutPackageInfo getPackageInfo() {
        return mPackageInfo;
    }

    public void refreshPackageSignatureAndSave() {
        if (mPackageInfo.isShadow()) {
            return; // Don't refresh for shadow user.
        }
        final ShortcutService s = mShortcutUser.mService;
        mPackageInfo.refreshSignature(s, this);
        s.scheduleSaveUser(getOwnerUserId());
    }

    public void attemptToRestoreIfNeededAndSave() {
        if (!mPackageInfo.isShadow()) {
            return; // Already installed, nothing to do.
        }
        final ShortcutService s = mShortcutUser.mService;
        if (!s.isPackageInstalled(mPackageName, mPackageUserId)) {
            if (ShortcutService.DEBUG) {
                Slog.d(TAG, String.format("Package still not installed: %s/u%d",
                        mPackageName, mPackageUserId));
            }
            return; // Not installed, no need to restore yet.
        }
        int restoreBlockReason;
        long currentVersionCode = ShortcutInfo.VERSION_CODE_UNKNOWN;

        if (!mPackageInfo.hasSignatures()) {
            s.wtf("Attempted to restore package " + mPackageName + "/u" + mPackageUserId
                    + " but signatures not found in the restore data.");
            restoreBlockReason = ShortcutInfo.DISABLED_REASON_SIGNATURE_MISMATCH;
        } else {
            final PackageInfo pi = s.getPackageInfoWithSignatures(mPackageName, mPackageUserId);
            currentVersionCode = pi.getLongVersionCode();
            restoreBlockReason = mPackageInfo.canRestoreTo(s, pi, canRestoreAnyVersion());
        }

        if (ShortcutService.DEBUG) {
            Slog.d(TAG, String.format("Restoring package: %s/u%d (version=%d) %s for u%d",
                    mPackageName, mPackageUserId, currentVersionCode,
                    ShortcutInfo.getDisabledReasonDebugString(restoreBlockReason),
                    getOwnerUserId()));
        }

        onRestored(restoreBlockReason);

        // Either way, it's no longer a shadow.
        mPackageInfo.setShadow(false);

        s.scheduleSaveUser(mPackageUserId);
    }

    protected abstract boolean canRestoreAnyVersion();

    protected abstract void onRestored(int restoreBlockReason);

    public abstract void saveToXml(@NonNull XmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException;

    public JSONObject dumpCheckin(boolean clear) throws JSONException {
        final JSONObject result = new JSONObject();
        result.put(KEY_NAME, mPackageName);
        return result;
    }

    /**
     * Verify various internal states.
     */
    public void verifyStates() {
    }
}
