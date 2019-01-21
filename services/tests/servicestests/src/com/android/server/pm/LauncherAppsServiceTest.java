/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;

import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class LauncherAppsServiceTest {

    private static final Signature SIGNATURE_1 = new Signature(new byte[]{0x00, 0x01, 0x02, 0x03});
    private static final Signature SIGNATURE_2 = new Signature(new byte[]{0x04, 0x05, 0x06, 0x07});
    private static final Signature SIGNATURE_3 = new Signature(new byte[]{0x08, 0x09, 0x10, 0x11});

    @Test
    public void testComputePackageCertDigest() {
        String digest = LauncherAppsService.LauncherAppsImpl.computePackageCertDigest(SIGNATURE_1);
        assertEquals("A02A05B025B928C039CF1AE7E8EE04E7C190C0DB", digest);
    }

    @Test
    public void testGetLatestSignaturesWithSingleCert() {
        SigningInfo signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[]{SIGNATURE_1},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        Signature[] signatures = LauncherAppsService.LauncherAppsImpl.getLatestSignatures(
                signingInfo);
        assertEquals(1, signatures.length);
        assertEquals(SIGNATURE_1, signatures[0]);
    }

    @Test
    public void testGetLatestSignaturesWithMultiCert() {
        SigningInfo signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[]{SIGNATURE_1, SIGNATURE_2},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        Signature[] signatures = LauncherAppsService.LauncherAppsImpl.getLatestSignatures(
                signingInfo);
        assertEquals(2, signatures.length);
        assertEquals(SIGNATURE_1, signatures[0]);
        assertEquals(SIGNATURE_2, signatures[1]);
    }

    @Test
    public void testGetLatestSignaturesWithCertHistory() {
        SigningInfo signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[]{SIGNATURE_1},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        new Signature[]{SIGNATURE_2, SIGNATURE_3}));
        Signature[] signatures = LauncherAppsService.LauncherAppsImpl.getLatestSignatures(
                signingInfo);
        assertEquals(1, signatures.length);
        assertEquals(SIGNATURE_2, signatures[0]);
    }

}
