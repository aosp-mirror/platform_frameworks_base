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

package com.android.server.backup;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class BackupUtils {
    private static final String TAG = "BackupUtils";

    private static final boolean DEBUG = false;

    public static boolean signaturesMatch(ArrayList<byte[]> storedSigHashes, PackageInfo target,
            PackageManagerInternal pmi) {
        if (target == null || target.packageName == null) {
            return false;
        }
        // If the target resides on the system partition, we allow it to restore
        // data from the like-named package in a restore set even if the signatures
        // do not match.  (Unlike general applications, those flashed to the system
        // partition will be signed with the device's platform certificate, so on
        // different phones the same system app will have different signatures.)
        if ((target.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            if (DEBUG) Slog.v(TAG, "System app " + target.packageName + " - skipping sig check");
            return true;
        }

        // Don't allow unsigned apps on either end
        if (ArrayUtils.isEmpty(storedSigHashes)) {
            return false;
        }

        SigningInfo signingInfo = target.signingInfo;
        if (signingInfo == null) {
            Slog.w(TAG, "signingInfo is empty, app was either unsigned or the flag" +
                    " PackageManager#GET_SIGNING_CERTIFICATES was not specified");
            return false;
        }

        if (DEBUG) {
            Slog.v(TAG, "signaturesMatch(): stored="
                    + storedSigHashes.stream().map(Arrays::toString).collect(Collectors.toList())
                    + " device=" + Arrays.toString(signingInfo.getApkContentsSigners()));
        }

        final int nStored = storedSigHashes.size();
        if (nStored == 1) {
            // if the app is only signed with one sig, it's possible it has rotated its key
            // the checks with signing history are delegated to PackageManager
            // TODO(b/73988180): address the case that app has declared restoreAnyVersion and is
            // restoring from higher version to lower after having rotated the key (i.e. higher
            // version has different sig than lower version that we want to restore to)
            return pmi.isDataRestoreSafe(storedSigHashes.get(0), target.packageName);
        } else {
            // the app couldn't have rotated keys, since it was signed with multiple sigs - do
            // a check to see if we find a match for all stored sigs
            // since app hasn't rotated key, we only need to check with current signers
            ArrayList<byte[]> deviceHashes =
                    hashSignatureArray(signingInfo.getApkContentsSigners());
            int nDevice = deviceHashes.size();
            // ensure that each stored sig matches an on-device sig
            for (int i = 0; i < nStored; i++) {
                boolean match = false;
                for (int j = 0; j < nDevice; j++) {
                    if (Arrays.equals(storedSigHashes.get(i), deviceHashes.get(j))) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    return false;
                }
            }
            // we have found a match for all stored sigs
            return true;
        }
    }

    public static byte[] hashSignature(byte[] signature) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(signature);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            Slog.w(TAG, "No SHA-256 algorithm found!");
        }
        return null;
    }

    public static byte[] hashSignature(Signature signature) {
        return hashSignature(signature.toByteArray());
    }

    public static ArrayList<byte[]> hashSignatureArray(Signature[] sigs) {
        if (sigs == null) {
            return null;
        }

        ArrayList<byte[]> hashes = new ArrayList<>(sigs.length);
        for (Signature s : sigs) {
            hashes.add(hashSignature(s));
        }
        return hashes;
    }

    public static ArrayList<byte[]> hashSignatureArray(List<byte[]> sigs) {
        if (sigs == null) {
            return null;
        }

        ArrayList<byte[]> hashes = new ArrayList<>(sigs.size());
        for (byte[] s : sigs) {
            hashes.add(hashSignature(s));
        }
        return hashes;
    }
}
