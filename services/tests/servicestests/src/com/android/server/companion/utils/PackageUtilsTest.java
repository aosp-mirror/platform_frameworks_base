/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.companion.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.pm.pkg.AndroidPackage;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidTestingRunner.class)
public class PackageUtilsTest {
    private static final String[] ALLOWED_PACKAGE_NAMES = new String[]{
            "allowed_app",
    };
    private static final Signature[] ALLOWED_PACKAGE_SIGNATURES = new Signature[]{
            new Signature("001122"),
    };
    private static final String[] DISALLOWED_PACKAGE_NAMES = new String[]{
            "disallowed_app",
    };
    private static final Signature[] DISALLOWED_PACKAGE_SIGNATURES = new Signature[]{
            new Signature("778899"),
    };

    @Test
    public void isAllowlisted_true() {
        Context context = spy(
                new ContextWrapper(
                        InstrumentationRegistry.getInstrumentation().getTargetContext()));
        final Resources res = spy(context.getResources());
        doReturn(ALLOWED_PACKAGE_NAMES).when(res).getStringArray(
                com.android.internal.R.array.config_companionDevicePackages);
        doReturn(android.util.PackageUtils.computeSignaturesSha256Digests(
                ALLOWED_PACKAGE_SIGNATURES)).when(res).getStringArray(
                com.android.internal.R.array.config_companionDeviceCerts);
        doReturn(res).when(context).getResources();
        PackageManagerInternal pm = mock(PackageManagerInternal.class);
        AndroidPackage ap = mock(AndroidPackage.class);
        SigningDetails sd = new SigningDetails(
                ALLOWED_PACKAGE_SIGNATURES,
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                null,
                null);
        doReturn(ap).when(pm).getPackage(ALLOWED_PACKAGE_NAMES[0]);
        doReturn(sd).when(ap).getSigningDetails();
        assertTrue(PackageUtils.isPackageAllowlisted(context, pm, ALLOWED_PACKAGE_NAMES[0]));
    }

    @Test
    public void isAllowlisted_package_disallowed() {
        Context context = spy(
                new ContextWrapper(
                        InstrumentationRegistry.getInstrumentation().getTargetContext()));
        final Resources res = spy(context.getResources());
        doReturn(ALLOWED_PACKAGE_NAMES).when(res).getStringArray(
                com.android.internal.R.array.config_companionDevicePackages);
        doReturn(android.util.PackageUtils.computeSignaturesSha256Digests(
                ALLOWED_PACKAGE_SIGNATURES)).when(res).getStringArray(
                com.android.internal.R.array.config_companionDeviceCerts);
        doReturn(res).when(context).getResources();
        PackageManagerInternal pm = mock(PackageManagerInternal.class);
        AndroidPackage ap = mock(AndroidPackage.class);
        SigningDetails sd = new SigningDetails(
                ALLOWED_PACKAGE_SIGNATURES, // Giving the package a wrong signature
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                null,
                null);
        doReturn(ap).when(pm).getPackage(DISALLOWED_PACKAGE_NAMES[0]);
        doReturn(sd).when(ap).getSigningDetails();
        assertFalse(PackageUtils.isPackageAllowlisted(context, pm, DISALLOWED_PACKAGE_NAMES[0]));
    }

    @Test
    public void isAllowlisted_signature_mismatch() {
        Context context = spy(
                new ContextWrapper(
                        InstrumentationRegistry.getInstrumentation().getTargetContext()));
        final Resources res = spy(context.getResources());
        doReturn(ALLOWED_PACKAGE_NAMES).when(res).getStringArray(
                com.android.internal.R.array.config_companionDevicePackages);
        doReturn(android.util.PackageUtils.computeSignaturesSha256Digests(
                ALLOWED_PACKAGE_SIGNATURES)).when(res).getStringArray(
                com.android.internal.R.array.config_companionDeviceCerts);
        doReturn(res).when(context).getResources();
        PackageManagerInternal pm = mock(PackageManagerInternal.class);
        AndroidPackage ap = mock(AndroidPackage.class);
        SigningDetails sd = new SigningDetails(
                DISALLOWED_PACKAGE_SIGNATURES, // Giving the package a wrong signature
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                null,
                null);
        doReturn(ap).when(pm).getPackage(ALLOWED_PACKAGE_NAMES[0]);
        doReturn(sd).when(ap).getSigningDetails();
        assertFalse(PackageUtils.isPackageAllowlisted(context, pm, ALLOWED_PACKAGE_NAMES[0]));
    }
}
