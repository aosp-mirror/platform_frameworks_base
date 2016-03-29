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
import android.content.pm.Signature;
import android.util.Slog;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BackupUtils {
    private static final String TAG = "BackupUtils";

    private static final boolean DEBUG = false; // STOPSHIP if true

    public static boolean signaturesMatch(ArrayList<byte[]> storedSigHashes, PackageInfo target) {
        if (target == null) {
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

        // Allow unsigned apps, but not signed on one device and unsigned on the other
        // !!! TODO: is this the right policy?
        Signature[] deviceSigs = target.signatures;
        if (DEBUG) Slog.v(TAG, "signaturesMatch(): stored=" + storedSigHashes
                + " device=" + deviceSigs);
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
