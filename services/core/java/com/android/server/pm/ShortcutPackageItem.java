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
import android.util.Slog;

import com.android.internal.util.Preconditions;

import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

abstract class ShortcutPackageItem {
    private static final String TAG = ShortcutService.TAG;

    private final int mPackageUserId;
    private final String mPackageName;

    private final ShortcutPackageInfo mPackageInfo;

    protected final ShortcutUser mShortcutUser;

    protected ShortcutPackageItem(@NonNull ShortcutUser shortcutUser,
            int packageUserId, @NonNull String packageName,
            @NonNull ShortcutPackageInfo packageInfo) {
        mShortcutUser = shortcutUser;
        mPackageUserId = packageUserId;
        mPackageName = Preconditions.checkStringNotEmpty(packageName);
        mPackageInfo = Preconditions.checkNotNull(packageInfo);
    }

    /**
     * ID of the user who actually has this package running on.  For {@link ShortcutPackage},
     * this is the same thing as {@link #getOwnerUserId}, but if it's a {@link ShortcutLauncher} and
     * {@link #getOwnerUserId} is of a work profile, then this ID could be the user who owns the
     * profile.
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

    public void refreshPackageInfoAndSave(ShortcutService s) {
        if (mPackageInfo.isShadow()) {
            return; // Don't refresh for shadow user.
        }
        mPackageInfo.refresh(s, this);
        s.scheduleSaveUser(getOwnerUserId());
    }

    public void attemptToRestoreIfNeededAndSave(ShortcutService s) {
        if (!mPackageInfo.isShadow()) {
            return; // Already installed, nothing to do.
        }
        if (!s.isPackageInstalled(mPackageName, mPackageUserId)) {
            if (ShortcutService.DEBUG) {
                Slog.d(TAG, String.format("Package still not installed: %s user=%d",
                        mPackageName, mPackageUserId));
            }
            return; // Not installed, no need to restore yet.
        }
        if (!mPackageInfo.hasSignatures()) {
            s.wtf("Attempted to restore package " + mPackageName + ", user=" + mPackageUserId
                    + " but signatures not found in the restore data.");
            onRestoreBlocked(s);
            return;
        }

        final PackageInfo pi = s.getPackageInfoWithSignatures(mPackageName, mPackageUserId);
        if (!mPackageInfo.canRestoreTo(s, pi)) {
            // Package is now installed, but can't restore.  Let the subclass do the cleanup.
            onRestoreBlocked(s);
            return;
        }
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, String.format("Restored package: %s/%d on user %d", mPackageName,
                    mPackageUserId, getOwnerUserId()));
        }

        onRestored(s);

        // Now the package is not shadow.
        mPackageInfo.setShadow(false);

        s.scheduleSaveUser(mPackageUserId);
    }

    /**
     * Called when the new package can't be restored because it has a lower version number
     * or different signatures.
     */
    protected abstract void onRestoreBlocked(ShortcutService s);

    /**
     * Called when the new package is successfully restored.
     */
    protected abstract void onRestored(ShortcutService s);

    public abstract void saveToXml(@NonNull XmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException;
}
