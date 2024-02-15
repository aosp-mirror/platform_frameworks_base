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

package com.android.server.display;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContextWrapper;
import android.content.pm.PackageManagerInternal;
import android.provider.DeviceConfigInterface;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.pm.pkg.PackageStateInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SmallAreaDetectionControllerTest {

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock
    private PackageManagerInternal mMockPackageManagerInternal;
    @Mock
    private PackageStateInternal mMockPkgStateA;
    @Mock
    private PackageStateInternal mMockPkgStateB;


    private SmallAreaDetectionController mSmallAreaDetectionController;

    private static final String PKG_A = "com.a.b.c";
    private static final String PKG_B = "com.d.e.f";
    private static final String PKG_NOT_INSTALLED = "com.not.installed";
    private static final float THRESHOLD_A = 0.05f;
    private static final float THRESHOLD_B = 0.07f;
    private static final int APP_ID_A = 11111;
    private static final int APP_ID_B = 22222;

    @Before
    public void setup() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mMockPackageManagerInternal);

        when(mMockPackageManagerInternal.getPackageStateInternal(PKG_A)).thenReturn(mMockPkgStateA);
        when(mMockPackageManagerInternal.getPackageStateInternal(PKG_B)).thenReturn(mMockPkgStateB);
        when(mMockPkgStateA.getAppId()).thenReturn(APP_ID_A);
        when(mMockPkgStateB.getAppId()).thenReturn(APP_ID_B);

        mSmallAreaDetectionController = spy(new SmallAreaDetectionController(
                new ContextWrapper(ApplicationProvider.getApplicationContext()),
                DeviceConfigInterface.REAL));
        doNothing().when(mSmallAreaDetectionController).updateSmallAreaDetection(any(), any());
    }

    @Test
    public void testUpdateAllowlist_validProperty() {
        final String property = PKG_A + ":" + THRESHOLD_A + "," + PKG_B + ":" + THRESHOLD_B;
        mSmallAreaDetectionController.updateAllowlist(property);

        final int[] resultAppIdArray = {APP_ID_A, APP_ID_B};
        final float[] resultThresholdArray = {THRESHOLD_A, THRESHOLD_B};
        verify(mSmallAreaDetectionController).updateSmallAreaDetection(eq(resultAppIdArray),
                eq(resultThresholdArray));
    }

    @Test
    public void testUpdateAllowlist_includeInvalidRow() {
        final String property = PKG_A + "," + PKG_B + ":" + THRESHOLD_B;
        mSmallAreaDetectionController.updateAllowlist(property);

        final int[] resultAppIdArray = {APP_ID_B};
        final float[] resultThresholdArray = {THRESHOLD_B};
        verify(mSmallAreaDetectionController).updateSmallAreaDetection(eq(resultAppIdArray),
                eq(resultThresholdArray));
    }

    @Test
    public void testUpdateAllowlist_includeNotInstalledPkg() {
        final String property =
                PKG_A + ":" + THRESHOLD_A + "," + PKG_NOT_INSTALLED + ":" + THRESHOLD_B;
        mSmallAreaDetectionController.updateAllowlist(property);

        final int[] resultAppIdArray = {APP_ID_A};
        final float[] resultThresholdArray = {THRESHOLD_A};
        verify(mSmallAreaDetectionController).updateSmallAreaDetection(eq(resultAppIdArray),
                eq(resultThresholdArray));
    }

    @Test
    public void testUpdateAllowlist_invalidProperty() {
        final String property = PKG_A;
        mSmallAreaDetectionController.updateAllowlist(property);

        verify(mSmallAreaDetectionController, never()).updateSmallAreaDetection(any(), any());
    }
}
