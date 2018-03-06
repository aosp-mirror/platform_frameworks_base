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
package com.android.server.pm.backup;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser.Package;
import android.content.pm.Signature;
import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.backup.BackupUtils;

import java.util.ArrayList;
import java.util.Arrays;

@SmallTest
public class BackupUtilsTest extends AndroidTestCase {

    private Signature[] genSignatures(String... signatures) {
        final Signature[] sigs = new Signature[signatures.length];
        for (int i = 0; i < signatures.length; i++){
            sigs[i] = new Signature(signatures[i].getBytes());
        }
        return sigs;
    }

    private PackageInfo genPackage(String... signatures) {
        final PackageInfo pi = new PackageInfo();
        pi.packageName = "package";
        pi.applicationInfo = new ApplicationInfo();
        pi.signatures = genSignatures(signatures);

        return pi;
    }

    public void testSignaturesMatch() {
        final ArrayList<byte[]> stored1 = BackupUtils.hashSignatureArray(Arrays.asList(
                "abc".getBytes()));
        final ArrayList<byte[]> stored2 = BackupUtils.hashSignatureArray(Arrays.asList(
                "abc".getBytes(), "def".getBytes()));

        PackageInfo pi;

        // False for null package.
        assertFalse(BackupUtils.signaturesMatch(stored1, null));

        // If it's a system app, signatures don't matter.
        pi = genPackage("xyz");
        pi.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        assertTrue(BackupUtils.signaturesMatch(stored1, pi));

        // Non system apps.
        assertTrue(BackupUtils.signaturesMatch(stored1, genPackage("abc")));

        // Superset is okay.
        assertTrue(BackupUtils.signaturesMatch(stored1, genPackage("abc", "xyz")));
        assertTrue(BackupUtils.signaturesMatch(stored1, genPackage("xyz", "abc")));

        assertFalse(BackupUtils.signaturesMatch(stored1, genPackage("xyz")));
        assertFalse(BackupUtils.signaturesMatch(stored1, genPackage("xyz", "def")));

        assertTrue(BackupUtils.signaturesMatch(stored2, genPackage("def", "abc")));
        assertTrue(BackupUtils.signaturesMatch(stored2, genPackage("x", "def", "abc", "y")));

        // Subset is not okay.
        assertFalse(BackupUtils.signaturesMatch(stored2, genPackage("abc")));
        assertFalse(BackupUtils.signaturesMatch(stored2, genPackage("def")));
    }

    public void testHashSignature() {
        final byte[] sig1 = "abc".getBytes();
        final byte[] sig2 = "def".getBytes();

        final byte[] hash1a = BackupUtils.hashSignature(sig1);
        final byte[] hash1b = BackupUtils.hashSignature(new Signature(sig1));

        final byte[] hash2a = BackupUtils.hashSignature(sig2);
        final byte[] hash2b = BackupUtils.hashSignature(new Signature(sig2));

        assertEquals(32, hash1a.length);
        MoreAsserts.assertEquals(hash1a, hash1b);

        assertEquals(32, hash2a.length);
        MoreAsserts.assertEquals(hash2a, hash2b);

        assertFalse(Arrays.equals(hash1a, hash2a));

        final ArrayList<byte[]> listA = BackupUtils.hashSignatureArray(Arrays.asList(
                "abc".getBytes(), "def".getBytes()));

        final ArrayList<byte[]> listB = BackupUtils.hashSignatureArray(new Signature[]{
                new Signature("abc".getBytes()), new Signature("def".getBytes())});

        assertEquals(2, listA.size());
        assertEquals(2, listB.size());

        MoreAsserts.assertEquals(hash1a, listA.get(0));
        MoreAsserts.assertEquals(hash1a, listB.get(0));

        MoreAsserts.assertEquals(hash2a, listA.get(1));
        MoreAsserts.assertEquals(hash2a, listB.get(1));
    }
}
