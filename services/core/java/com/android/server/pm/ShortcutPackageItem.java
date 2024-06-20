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
import android.annotation.Nullable;
import android.content.pm.PackageInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.os.FileUtils;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.modules.utils.TypedXmlSerializer;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * All methods should be either guarded by {@code #mPackageItemLock}.
 */
abstract class ShortcutPackageItem {
    private static final String TAG = ShortcutService.TAG;
    private static final String KEY_NAME = "name";

    private final int mPackageUserId;
    private final String mPackageName;

    private final ShortcutPackageInfo mPackageInfo;

    protected ShortcutUser mShortcutUser;

    @GuardedBy("mPackageItemLock")
    protected final ShortcutBitmapSaver mShortcutBitmapSaver;

    protected final Object mPackageItemLock = new Object();

    protected ShortcutPackageItem(@NonNull ShortcutUser shortcutUser,
            int packageUserId, @NonNull String packageName,
            @NonNull ShortcutPackageInfo packageInfo) {
        mShortcutUser = shortcutUser;
        mPackageUserId = packageUserId;
        mPackageName = Preconditions.checkStringNotEmpty(packageName);
        mPackageInfo = Objects.requireNonNull(packageInfo);
        mShortcutBitmapSaver = new ShortcutBitmapSaver(shortcutUser.mService);
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
        scheduleSave();
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

        scheduleSave();
    }

    protected abstract boolean canRestoreAnyVersion();

    protected abstract void onRestored(int restoreBlockReason);

    public abstract void saveToXml(@NonNull TypedXmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException;

    @GuardedBy("mPackageItemLock")
    public void saveToFileLocked(File path, boolean forBackup) {
        try (ResilientAtomicFile file = getResilientFile(path)) {
            FileOutputStream os = null;
            try {
                os = file.startWrite();

                // Write to XML
                final TypedXmlSerializer itemOut;
                if (forBackup) {
                    itemOut = Xml.newFastSerializer();
                    itemOut.setOutput(os, StandardCharsets.UTF_8.name());
                } else {
                    itemOut = Xml.resolveSerializer(os);
                }
                itemOut.startDocument(null, true);

                saveToXml(itemOut, forBackup);

                itemOut.endDocument();

                os.flush();
                file.finishWrite(os);
            } catch (XmlPullParserException | IOException e) {
                Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
                file.failWrite(os);
            }
        }
    }

    @GuardedBy("mPackageItemLock")
    void scheduleSaveToAppSearchLocked() {

    }

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

    public void scheduleSave() {
        mShortcutUser.mService.injectPostToHandlerDebounced(
                mSaveShortcutPackageRunner, mSaveShortcutPackageRunner);
    }

    private final Runnable mSaveShortcutPackageRunner = this::saveShortcutPackageItem;

    void saveShortcutPackageItem() {
        // Wait for bitmap saves to conclude before proceeding to saving shortcuts.
        waitForBitmapSaves();
        // Save each ShortcutPackageItem in a separate Xml file.
        final File path = getShortcutPackageItemFile();
        if (ShortcutService.DEBUG || ShortcutService.DEBUG_REBOOT) {
            Slog.d(TAG, "Saving package item " + getPackageName() + " to " + path);
        }
        synchronized (mPackageItemLock) {
            path.getParentFile().mkdirs();
            // TODO: Since we are persisting shortcuts into AppSearch, we should read from/write to
            //  AppSearch as opposed to maintaining a separate XML file.
            saveToFileLocked(path, false /*forBackup*/);
            scheduleSaveToAppSearchLocked();
        }
    }

    public boolean waitForBitmapSaves() {
        synchronized (mPackageItemLock) {
            return mShortcutBitmapSaver.waitForAllSavesLocked();
        }
    }

    public void saveBitmap(ShortcutInfo shortcut,
            int maxDimension, Bitmap.CompressFormat format, int quality) {
        synchronized (mPackageItemLock) {
            mShortcutBitmapSaver.saveBitmapLocked(shortcut, maxDimension, format, quality);
        }
    }

    /**
     * Wait for all pending saves to finish, and then return the given shortcut's bitmap path.
     */
    @Nullable
    public String getBitmapPathMayWait(ShortcutInfo shortcut) {
        synchronized (mPackageItemLock) {
            return mShortcutBitmapSaver.getBitmapPathMayWaitLocked(shortcut);
        }
    }

    public void removeIcon(ShortcutInfo shortcut) {
        synchronized (mPackageItemLock) {
            mShortcutBitmapSaver.removeIcon(shortcut);
        }
    }

    void removeShortcutPackageItem() {
        synchronized (mPackageItemLock) {
            getResilientFile(getShortcutPackageItemFile()).delete();
        }
    }

    protected abstract File getShortcutPackageItemFile();

    protected static ResilientAtomicFile getResilientFile(File file) {
        String path = file.getPath();
        File temporaryBackup = new File(path + ".backup");
        File reserveCopy = new File(path + ".reservecopy");
        int fileMode = FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH;
        return new ResilientAtomicFile(file, temporaryBackup, reserveCopy, fileMode,
                "shortcut package item", null);
    }
}
