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
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
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
    private static final String ATTR_SHADOW = "shadow";

    private static final String TAG_SIGNATURE = "signature";
    private static final String ATTR_SIGNATURE_HASH = "hash";

    private static final int VERSION_UNKNOWN = -1;

    /**
     * When true, this package information was restored from the previous device, and the app hasn't
     * been installed yet.
     */
    private boolean mIsShadow;
    private int mVersionCode = VERSION_UNKNOWN;
    private long mLastUpdateTime;
    private ArrayList<byte[]> mSigHashes;

    private ShortcutPackageInfo(int versionCode, long lastUpdateTime,
            ArrayList<byte[]> sigHashes, boolean isShadow) {
        mVersionCode = versionCode;
        mLastUpdateTime = lastUpdateTime;
        mIsShadow = isShadow;
        mSigHashes = sigHashes;
    }

    public static ShortcutPackageInfo newEmpty() {
        return new ShortcutPackageInfo(VERSION_UNKNOWN, /* last update time =*/ 0,
                new ArrayList<>(0), /* isShadow */ false);
    }

    public boolean isShadow() {
        return mIsShadow;
    }

    public void setShadow(boolean shadow) {
        mIsShadow = shadow;
    }

    public int getVersionCode() {
        return mVersionCode;
    }

    public long getLastUpdateTime() {
        return mLastUpdateTime;
    }

    /** Set {@link #mVersionCode} and {@link #mLastUpdateTime} from a {@link PackageInfo}. */
    public void updateVersionInfo(@NonNull PackageInfo pi) {
        if (pi != null) {
            mVersionCode = pi.versionCode;
            mLastUpdateTime = pi.lastUpdateTime;
        }
    }

    public boolean hasSignatures() {
        return mSigHashes.size() > 0;
    }

    public boolean canRestoreTo(ShortcutService s, PackageInfo target) {
        if (!s.shouldBackupApp(target)) {
            // "allowBackup" was true when backed up, but now false.
            Slog.w(TAG, "Can't restore: package no longer allows backup");
            return false;
        }
        if (target.versionCode < mVersionCode) {
            Slog.w(TAG, String.format(
                    "Can't restore: package current version %d < backed up version %d",
                    target.versionCode, mVersionCode));
            return false;
        }
        if (!BackupUtils.signaturesMatch(mSigHashes, target)) {
            Slog.w(TAG, "Can't restore: Package signature mismatch");
            return false;
        }
        return true;
    }

    @VisibleForTesting
    public static ShortcutPackageInfo generateForInstalledPackageForTest(
            ShortcutService s, String packageName, @UserIdInt int packageUserId) {
        final PackageInfo pi = s.getPackageInfoWithSignatures(packageName, packageUserId);
        if (pi.signatures == null || pi.signatures.length == 0) {
            Slog.e(TAG, "Can't get signatures: package=" + packageName);
            return null;
        }
        final ShortcutPackageInfo ret = new ShortcutPackageInfo(pi.versionCode, pi.lastUpdateTime,
                BackupUtils.hashSignatureArray(pi.signatures), /* shadow=*/ false);

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
        mSigHashes = BackupUtils.hashSignatureArray(pi.signatures);
    }

    public void saveToXml(XmlSerializer out) throws IOException {

        out.startTag(null, TAG_ROOT);

        ShortcutService.writeAttr(out, ATTR_VERSION, mVersionCode);
        ShortcutService.writeAttr(out, ATTR_LAST_UPDATE_TIME, mLastUpdateTime);
        ShortcutService.writeAttr(out, ATTR_SHADOW, mIsShadow);

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

        final int versionCode = ShortcutService.parseIntAttribute(parser, ATTR_VERSION);

        final long lastUpdateTime = ShortcutService.parseLongAttribute(
                parser, ATTR_LAST_UPDATE_TIME);

        // When restoring from backup, it's always shadow.
        final boolean shadow =
                fromBackup || ShortcutService.parseBooleanAttribute(parser, ATTR_SHADOW);

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

        // Successfully loaded; replace the feilds.
        mVersionCode = versionCode;
        mLastUpdateTime = lastUpdateTime;
        mIsShadow = shadow;
        mSigHashes = hashes;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println();

        pw.print(prefix);
        pw.println("PackageInfo:");

        pw.print(prefix);
        pw.print("  IsShadow: ");
        pw.print(mIsShadow);
        pw.println();

        pw.print(prefix);
        pw.print("  Version: ");
        pw.print(mVersionCode);
        pw.println();

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
