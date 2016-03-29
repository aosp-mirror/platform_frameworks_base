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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.util.Slog;

import com.android.internal.util.Preconditions;

import libcore.io.Base64;
import libcore.util.HexEncoding;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Package information used by {@link android.content.pm.ShortcutManager} for backup / restore.
 *
 * TODO: The methods about signature hashes are copied from BackupManagerService, which is not
 * visible here.  Unify the code.
 */
class ShortcutPackageInfo implements ShortcutPackageItem {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "package-info";
    private static final String ATTR_USER_ID = "user";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_SHADOW = "shadow";

    private static final String TAG_SIGNATURE = "signature";
    private static final String ATTR_SIGNATURE_HASH = "hash";

    private final String mPackageName;
    private final int mUserId;

    /**
     * When true, this package information was restored from the previous device, and the app hasn't
     * been installed yet.
     */
    private boolean mIsShadow;
    private int mVersionCode;
    private ArrayList<byte[]> mSigHashes;

    private ShortcutPackageInfo(String packageName, int userId,
            int versionCode, ArrayList<byte[]> sigHashes, boolean isShadow) {
        mPackageName = Preconditions.checkNotNull(packageName);
        mUserId = userId;
        mVersionCode = versionCode;
        mIsShadow = isShadow;
        mSigHashes = sigHashes;
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    public int getUserId() {
        return mUserId;
    }

    public boolean isShadow() {
        return mIsShadow;
    }

    public boolean isInstalled() {
        return !mIsShadow;
    }

    public void setShadow(boolean shadow) {
        mIsShadow = shadow;
    }

    public int getVersionCode() {
        return mVersionCode;
    }

    private static byte[] hashSignature(Signature sig) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sig.toByteArray());
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            Slog.w(TAG, "No SHA-256 algorithm found!");
        }
        return null;
    }

    private static ArrayList<byte[]> hashSignatureArray(Signature[] sigs) {
        if (sigs == null) {
            return null;
        }

        ArrayList<byte[]> hashes = new ArrayList<byte[]>(sigs.length);
        for (Signature s : sigs) {
            hashes.add(hashSignature(s));
        }
        return hashes;
    }

    private static boolean signaturesMatch(ArrayList<byte[]> storedSigHashes, PackageInfo target) {
        if (target == null) {
            return false;
        }

        // If the target resides on the system partition, we allow it to restore
        // data from the like-named package in a restore set even if the signatures
        // do not match.  (Unlike general applications, those flashed to the system
        // partition will be signed with the device's platform certificate, so on
        // different phones the same system app will have different signatures.)
        if ((target.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return true;
        }

        // Allow unsigned apps, but not signed on one device and unsigned on the other
        // !!! TODO: is this the right policy?
        Signature[] deviceSigs = target.signatures;
        if ((storedSigHashes == null || storedSigHashes.size() == 0)
                && (deviceSigs == null || deviceSigs.length == 0)) {
            return true;
        }
        if (storedSigHashes == null || deviceSigs == null) {
            return false;
        }

        // !!! TODO: this demands that every stored signature match one
        // that is present on device, and does not demand the converse.
        // Is this this right policy?
        final int nStored = storedSigHashes.size();
        final int nDevice = deviceSigs.length;

        // hash each on-device signature
        ArrayList<byte[]> deviceHashes = new ArrayList<byte[]>(nDevice);
        for (int i = 0; i < nDevice; i++) {
            deviceHashes.add(hashSignature(deviceSigs[i]));
        }

        // now ensure that each stored sig (hash) matches an on-device sig (hash)
        for (int n = 0; n < nStored; n++) {
            boolean match = false;
            final byte[] storedHash = storedSigHashes.get(n);
            for (int i = 0; i < nDevice; i++) {
                if (Arrays.equals(storedHash, deviceHashes.get(i))) {
                    match = true;
                    break;
                }
            }
            // match is false when no on-device sig matched one of the stored ones
            if (!match) {
                return false;
            }
        }

        return true;
    }

    public boolean canRestoreTo(PackageInfo target) {
        if (target.versionCode < mVersionCode) {
            Slog.w(TAG, String.format("Package current version %d < backed up version %d",
                    target.versionCode, mVersionCode));
            return false;
        }
        if (!signaturesMatch(mSigHashes, target)) {
            Slog.w(TAG, "Package signature mismtach");
            return false;
        }
        return true;
    }

    public static ShortcutPackageInfo generateForInstalledPackage(
            ShortcutService s, String packageName, @UserIdInt int userId) {
        final PackageInfo pi = s.getPackageInfoWithSignatures(packageName, userId);
        if (pi.signatures == null || pi.signatures.length == 0) {
            Slog.e(TAG, "Can't get signatures: package=" + packageName);
            return null;
        }
        final ShortcutPackageInfo ret = new ShortcutPackageInfo(packageName, userId, pi.versionCode,
                hashSignatureArray(pi.signatures), /* shadow=*/ false);

        return ret;
    }

    public void refreshAndSave(ShortcutService s, @UserIdInt int userId) {
        final PackageInfo pi = s.getPackageInfoWithSignatures(mPackageName, userId);
        if (pi == null) {
            Slog.w(TAG, "Package not found: " + mPackageName);
            return;
        }
        mVersionCode = pi.versionCode;
        mSigHashes = hashSignatureArray(pi.signatures);

        s.scheduleSaveUser(userId);
    }

    public void saveToXml(XmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException {

        out.startTag(null, TAG_ROOT);

        ShortcutService.writeAttr(out, ATTR_NAME, mPackageName);
        ShortcutService.writeAttr(out, ATTR_USER_ID, mUserId);
        ShortcutService.writeAttr(out, ATTR_VERSION, mVersionCode);
        ShortcutService.writeAttr(out, ATTR_SHADOW, mIsShadow);

        for (int i = 0; i < mSigHashes.size(); i++) {
            out.startTag(null, TAG_SIGNATURE);
            ShortcutService.writeAttr(out, ATTR_SIGNATURE_HASH, Base64.encode(mSigHashes.get(i)));
            out.endTag(null, TAG_SIGNATURE);
        }
        out.endTag(null, TAG_ROOT);
    }

    public static ShortcutPackageInfo loadFromXml(XmlPullParser parser, int ownerUserId)
            throws IOException, XmlPullParserException {

        final String packageName = ShortcutService.parseStringAttribute(parser, ATTR_NAME);
        final int userId = ShortcutService.parseIntAttribute(parser, ATTR_USER_ID, ownerUserId);
        final int versionCode = ShortcutService.parseIntAttribute(parser, ATTR_VERSION);
        final boolean shadow = ShortcutService.parseBooleanAttribute(parser, ATTR_SHADOW);

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
            switch (tag) {
                case TAG_SIGNATURE: {
                    final String hash = ShortcutService.parseStringAttribute(
                            parser, ATTR_SIGNATURE_HASH);
                    hashes.add(Base64.decode(hash.getBytes()));
                    continue;
                }
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }
        return new ShortcutPackageInfo(packageName, userId, versionCode, hashes, shadow);
    }

    public void dump(ShortcutService s, PrintWriter pw, String prefix) {
        pw.println();

        pw.print(prefix);
        pw.print("PackageInfo: ");
        pw.print(mPackageName);
        pw.println();

        pw.print(prefix);
        pw.print("  User: ");
        pw.print(mUserId);
        pw.println();

        pw.print(prefix);
        pw.print("  IsShadow: ");
        pw.print(mIsShadow);
        pw.println();

        pw.print(prefix);
        pw.print("  Version: ");
        pw.print(mVersionCode);
        pw.println();

        for (int i = 0; i < mSigHashes.size(); i++) {
            pw.print(prefix);
            pw.print("    ");
            pw.print("SigHash: ");
            pw.println(HexEncoding.encode(mSigHashes.get(i)));
        }
    }
}
