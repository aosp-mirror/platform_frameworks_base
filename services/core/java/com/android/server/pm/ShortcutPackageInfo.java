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
import android.annotation.UserIdInt;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ShortcutInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.backup.BackupUtils;

import libcore.util.HexEncoding;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Base64;

/**
 * Package information used by {@link android.content.pm.ShortcutManager} for backup / restore.
 *
 * All methods should be guarded by {@code ShortcutService.mLock}.
 */
class ShortcutPackageInfo {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "package-info";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_LAST_UPDATE_TIME = "last_udpate_time";
    private static final String ATTR_BACKUP_SOURCE_VERSION = "bk_src_version";
    private static final String ATTR_BACKUP_ALLOWED = "allow-backup";
    private static final String ATTR_BACKUP_ALLOWED_INITIALIZED = "allow-backup-initialized";
    private static final String ATTR_BACKUP_SOURCE_BACKUP_ALLOWED = "bk_src_backup-allowed";
    private static final String ATTR_SHADOW = "shadow";

    private static final String TAG_SIGNATURE = "signature";
    private static final String ATTR_SIGNATURE_HASH = "hash";

    /**
     * When true, this package information was restored from the previous device, and the app hasn't
     * been installed yet.
     */
    private boolean mIsShadow;
    private long mVersionCode = ShortcutInfo.VERSION_CODE_UNKNOWN;
    private long mBackupSourceVersionCode = ShortcutInfo.VERSION_CODE_UNKNOWN;
    private long mLastUpdateTime;
    private ArrayList<byte[]> mSigHashes;

    // mBackupAllowed didn't used to be parsisted, so we don't restore it from a file.
    // mBackupAllowed will always start with false, and will have been updated before making a
    // backup next time, which works file.
    // We just don't want to print an uninitialzied mBackupAlldowed value on dumpsys, so
    // we use this boolean to control dumpsys.
    private boolean mBackupAllowedInitialized;
    private boolean mBackupAllowed;
    private boolean mBackupSourceBackupAllowed;

    private ShortcutPackageInfo(long versionCode, long lastUpdateTime,
            ArrayList<byte[]> sigHashes, boolean isShadow) {
        mVersionCode = versionCode;
        mLastUpdateTime = lastUpdateTime;
        mIsShadow = isShadow;
        mSigHashes = sigHashes;
        mBackupAllowed = false; // By default, we assume false.
        mBackupSourceBackupAllowed = false;
    }

    public static ShortcutPackageInfo newEmpty() {
        return new ShortcutPackageInfo(ShortcutInfo.VERSION_CODE_UNKNOWN, /* last update time =*/ 0,
                new ArrayList<>(0), /* isShadow */ false);
    }

    public boolean isShadow() {
        return mIsShadow;
    }

    public void setShadow(boolean shadow) {
        mIsShadow = shadow;
    }

    public long getVersionCode() {
        return mVersionCode;
    }

    public long getBackupSourceVersionCode() {
        return mBackupSourceVersionCode;
    }

    @VisibleForTesting
    public boolean isBackupSourceBackupAllowed() {
        return mBackupSourceBackupAllowed;
    }

    public long getLastUpdateTime() {
        return mLastUpdateTime;
    }

    public boolean isBackupAllowed() {
        return mBackupAllowed;
    }

    /**
     * Set {@link #mVersionCode}, {@link #mLastUpdateTime} and {@link #mBackupAllowed}
     * from a {@link PackageInfo}.
     */
    public void updateFromPackageInfo(@NonNull PackageInfo pi) {
        if (pi != null) {
            mVersionCode = pi.getLongVersionCode();
            mLastUpdateTime = pi.lastUpdateTime;
            mBackupAllowed = ShortcutService.shouldBackupApp(pi);
            mBackupAllowedInitialized = true;
        }
    }

    public boolean hasSignatures() {
        return mSigHashes.size() > 0;
    }

    //@DisabledReason
    public int canRestoreTo(ShortcutService s, PackageInfo currentPackage, boolean anyVersionOkay) {
        PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        if (!BackupUtils.signaturesMatch(mSigHashes, currentPackage, pmi)) {
            Slog.w(TAG, "Can't restore: Package signature mismatch");
            return ShortcutInfo.DISABLED_REASON_SIGNATURE_MISMATCH;
        }
        if (!ShortcutService.shouldBackupApp(currentPackage) || !mBackupSourceBackupAllowed) {
            // "allowBackup" was true when backed up, but now false.
            Slog.w(TAG, "Can't restore: package didn't or doesn't allow backup");
            return ShortcutInfo.DISABLED_REASON_BACKUP_NOT_SUPPORTED;
        }
        if (!anyVersionOkay && (currentPackage.getLongVersionCode() < mBackupSourceVersionCode)) {
            Slog.w(TAG, String.format(
                    "Can't restore: package current version %d < backed up version %d",
                    currentPackage.getLongVersionCode(), mBackupSourceVersionCode));
            return ShortcutInfo.DISABLED_REASON_VERSION_LOWER;
        }
        return ShortcutInfo.DISABLED_REASON_NOT_DISABLED;
    }

    @VisibleForTesting
    public static ShortcutPackageInfo generateForInstalledPackageForTest(
            ShortcutService s, String packageName, @UserIdInt int packageUserId) {
        final PackageInfo pi = s.getPackageInfoWithSignatures(packageName, packageUserId);
        // retrieve the newest sigs
        SigningInfo signingInfo = pi.signingInfo;
        if (signingInfo == null) {
            Slog.e(TAG, "Can't get signatures: package=" + packageName);
            return null;
        }
        // TODO (b/73988180) use entire signing history in case of rollbacks
        Signature[] signatures = signingInfo.getApkContentsSigners();
        final ShortcutPackageInfo ret = new ShortcutPackageInfo(pi.getLongVersionCode(),
                pi.lastUpdateTime, BackupUtils.hashSignatureArray(signatures), /* shadow=*/ false);

        ret.mBackupSourceBackupAllowed = s.shouldBackupApp(pi);
        ret.mBackupSourceVersionCode = pi.getLongVersionCode();
        return ret;
    }

    public void refreshSignature(ShortcutService s, ShortcutPackageItem pkg) {
        if (mIsShadow) {
            s.wtf("Attempted to refresh package info for shadow package " + pkg.getPackageName()
                    + ", user=" + pkg.getOwnerUserId());
            return;
        }
        // Note use mUserId here, rather than userId.
        final PackageInfo pi = s.getPackageInfoWithSignatures(
                pkg.getPackageName(), pkg.getPackageUserId());
        if (pi == null) {
            Slog.w(TAG, "Package not found: " + pkg.getPackageName());
            return;
        }
        // retrieve the newest sigs
        SigningInfo signingInfo = pi.signingInfo;
        if (signingInfo == null) {
            Slog.w(TAG, "Not refreshing signature for " + pkg.getPackageName()
                    + " since it appears to have no signing info.");
            return;
        }
        // TODO (b/73988180) use entire signing history in case of rollbacks
        Signature[] signatures = signingInfo.getApkContentsSigners();
        mSigHashes = BackupUtils.hashSignatureArray(signatures);
    }

    public void saveToXml(ShortcutService s, XmlSerializer out, boolean forBackup)
            throws IOException {
        if (forBackup && !mBackupAllowedInitialized) {
            s.wtf("Backup happened before mBackupAllowed is initialized.");
        }

        out.startTag(null, TAG_ROOT);

        ShortcutService.writeAttr(out, ATTR_VERSION, mVersionCode);
        ShortcutService.writeAttr(out, ATTR_LAST_UPDATE_TIME, mLastUpdateTime);
        ShortcutService.writeAttr(out, ATTR_SHADOW, mIsShadow);
        ShortcutService.writeAttr(out, ATTR_BACKUP_ALLOWED, mBackupAllowed);

        // We don't need to save this field (we don't even read it back), but it'll show up
        // in the dumpsys in the backup / restore payload.
        ShortcutService.writeAttr(out, ATTR_BACKUP_ALLOWED_INITIALIZED, mBackupAllowedInitialized);

        ShortcutService.writeAttr(out, ATTR_BACKUP_SOURCE_VERSION, mBackupSourceVersionCode);
        ShortcutService.writeAttr(out,
                ATTR_BACKUP_SOURCE_BACKUP_ALLOWED, mBackupSourceBackupAllowed);


        for (int i = 0; i < mSigHashes.size(); i++) {
            out.startTag(null, TAG_SIGNATURE);
            final String encoded = Base64.getEncoder().encodeToString(mSigHashes.get(i));
            ShortcutService.writeAttr(out, ATTR_SIGNATURE_HASH, encoded);
            out.endTag(null, TAG_SIGNATURE);
        }
        out.endTag(null, TAG_ROOT);
    }

    public void loadFromXml(XmlPullParser parser, boolean fromBackup)
            throws IOException, XmlPullParserException {
        // Don't use the version code from the backup file.
        final long versionCode = ShortcutService.parseLongAttribute(parser, ATTR_VERSION,
                ShortcutInfo.VERSION_CODE_UNKNOWN);

        final long lastUpdateTime = ShortcutService.parseLongAttribute(
                parser, ATTR_LAST_UPDATE_TIME);

        // When restoring from backup, it's always shadow.
        final boolean shadow =
                fromBackup || ShortcutService.parseBooleanAttribute(parser, ATTR_SHADOW);

        // We didn't used to save these attributes, and all backed up shortcuts were from
        // apps that support backups, so the default values take this fact into consideration.
        final long backupSourceVersion = ShortcutService.parseLongAttribute(parser,
                ATTR_BACKUP_SOURCE_VERSION, ShortcutInfo.VERSION_CODE_UNKNOWN);

        // Note the only time these "true" default value is used is when restoring from an old
        // build that didn't save ATTR_BACKUP_ALLOWED, and that means all the data included in
        // a backup file were from apps that support backup, so we can just use "true" as the
        // default.
        final boolean backupAllowed = ShortcutService.parseBooleanAttribute(
                parser, ATTR_BACKUP_ALLOWED, true);
        final boolean backupSourceBackupAllowed = ShortcutService.parseBooleanAttribute(
                parser, ATTR_BACKUP_SOURCE_BACKUP_ALLOWED, true);

        final ArrayList<byte[]> hashes = new ArrayList<>();

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();

            if (depth == outerDepth + 1) {
                switch (tag) {
                    case TAG_SIGNATURE: {
                        final String hash = ShortcutService.parseStringAttribute(
                                parser, ATTR_SIGNATURE_HASH);
                        // Throws IllegalArgumentException if hash is invalid base64 data
                        final byte[] decoded = Base64.getDecoder().decode(hash);
                        hashes.add(decoded);
                        continue;
                    }
                }
            }
            ShortcutService.warnForInvalidTag(depth, tag);
        }

        // Successfully loaded; replace the fields.
        if (fromBackup) {
            mVersionCode = ShortcutInfo.VERSION_CODE_UNKNOWN;
            mBackupSourceVersionCode = versionCode;
            mBackupSourceBackupAllowed = backupAllowed;
        } else {
            mVersionCode = versionCode;
            mBackupSourceVersionCode = backupSourceVersion;
            mBackupSourceBackupAllowed = backupSourceBackupAllowed;
        }
        mLastUpdateTime = lastUpdateTime;
        mIsShadow = shadow;
        mSigHashes = hashes;

        // Note we don't restore it from the file because it didn't used to be saved.
        // We always start by assuming backup is disabled for the current package,
        // and this field will have been updated before we actually create a backup, at the same
        // time when we update the version code.
        // Until then, the value of mBackupAllowed shouldn't matter, but we don't want to print
        // a false flag on dumpsys, so set mBackupAllowedInitialized to false.
        mBackupAllowed = false;
        mBackupAllowedInitialized = false;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println();

        pw.print(prefix);
        pw.println("PackageInfo:");

        pw.print(prefix);
        pw.print("  IsShadow: ");
        pw.print(mIsShadow);
        pw.print(mIsShadow ? " (not installed)" : " (installed)");
        pw.println();

        pw.print(prefix);
        pw.print("  Version: ");
        pw.print(mVersionCode);
        pw.println();

        if (mBackupAllowedInitialized) {
            pw.print(prefix);
            pw.print("  Backup Allowed: ");
            pw.print(mBackupAllowed);
            pw.println();
        }

        if (mBackupSourceVersionCode != ShortcutInfo.VERSION_CODE_UNKNOWN) {
            pw.print(prefix);
            pw.print("  Backup source version: ");
            pw.print(mBackupSourceVersionCode);
            pw.println();

            pw.print(prefix);
            pw.print("  Backup source backup allowed: ");
            pw.print(mBackupSourceBackupAllowed);
            pw.println();
        }

        pw.print(prefix);
        pw.print("  Last package update time: ");
        pw.print(mLastUpdateTime);
        pw.println();

        for (int i = 0; i < mSigHashes.size(); i++) {
            pw.print(prefix);
            pw.print("    ");
            pw.print("SigHash: ");
            pw.println(HexEncoding.encode(mSigHashes.get(i)));
        }
    }
}
