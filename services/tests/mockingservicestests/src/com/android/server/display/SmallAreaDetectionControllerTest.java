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

import static android.os.Process.INVALID_UID;

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
import com.android.server.pm.UserManagerInternal;

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
    private UserManagerInternal mMockUserManagerInternal;

    private SmallAreaDetectionController mSmallAreaDetectionController;

    private static final String PKG_A = "com.a.b.c";
    private static final String PKG_B = "com.d.e.f";
    private static final String PKG_NOT_INSTALLED = "com.not.installed";
    private static final float THRESHOLD_A = 0.05f;
    private static final float THRESHOLD_B = 0.07f;
    private static final int USER_1 = 110;
    private static final int USER_2 = 111;
    private static final int UID_A_1 = 11011111;
    private static final int UID_A_2 = 11111111;
    private static final int UID_B_1 = 11022222;
    private static final int UID_B_2 = 11122222;

    @Before
    public void setup() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mMockPackageManagerInternal);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mMockUserManagerInternal);

        when(mMockUserManagerInternal.getUserIds()).thenReturn(new int[]{USER_1, USER_2});
        when(mMockPackageManagerInternal.getPackageUid(PKG_A, 0, USER_1)).thenReturn(UID_A_1);
        when(mMockPackageManagerInternal.getPackageUid(PKG_A, 0, USER_2)).thenReturn(UID_A_2);
        when(mMockPackageManagerInternal.getPackageUid(PKG_B, 0, USER_1)).thenReturn(UID_B_1);
        when(mMockPackageManagerInternal.getPackageUid(PKG_B, 0, USER_2)).thenReturn(UID_B_2);
        when(mMockPackageManagerInternal.getPackageUid(PKG_NOT_INSTALLED, 0, USER_1)).thenReturn(
                INVALID_UID);
        when(mMockPackageManagerInternal.getPackageUid(PKG_NOT_INSTALLED, 0, USER_2)).thenReturn(
                INVALID_UID);

        mSmallAreaDetectionController = spy(new SmallAreaDetectionController(
                new ContextWrapper(ApplicationProvider.getApplicationContext()),
                DeviceConfigInterface.REAL));
        doNothing().when(mSmallAreaDetectionController).updateSmallAreaDetection(any(), any());
    }

    @Test
    public void testUpdateAllowlist_validProperty() {
        final String property = PKG_A + ":" + THRESHOLD_A + "," + PKG_B + ":" + THRESHOLD_B;
        mSmallAreaDetectionController.updateAllowlist(property);

        final int[] resultUidArray = {UID_A_1, UID_B_1, UID_A_2, UID_B_2};
        final float[] resultThresholdArray = {THRESHOLD_A, THRESHOLD_B, THRESHOLD_A, THRESHOLD_B};
        verify(mSmallAreaDetectionController).updateSmallAreaDetection(eq(resultUidArray),
                eq(resultThresholdArray));
    }

    @Test
    public void testUpdateAllowlist_includeInvalidRow() {
        final String property = PKG_A + "," + PKG_B + ":" + THRESHOLD_B;
        mSmallAreaDetectionController.updateAllowlist(property);

        final int[] resultUidArray = {UID_B_1, UID_B_2};
        final float[] resultThresholdArray = {THRESHOLD_B, THRESHOLD_B};
        verify(mSmallAreaDetectionController).updateSmallAreaDetection(eq(resultUidArray),
                eq(resultThresholdArray));
    }

    @Test
    public void testUpdateAllowlist_includeNotInstalledPkg() {
        final String property =
                PKG_A + ":" + THRESHOLD_A + "," + PKG_NOT_INSTALLED + ":" + THRESHOLD_B;
        mSmallAreaDetectionController.updateAllowlist(property);

        final int[] resultUidArray = {UID_A_1, UID_A_2};
        final float[] resultThresholdArray = {THRESHOLD_A, THRESHOLD_A};
        verify(mSmallAreaDetectionController).updateSmallAreaDetection(eq(resultUidArray),
                eq(resultThresholdArray));
    }

    @Test
    public void testUpdateAllowlist_invalidProperty() {
        final String property = PKG_A;
        mSmallAreaDetectionController.updateAllowlist(property);

        verify(mSmallAreaDetectionController, never()).updateSmallAreaDetection(any(), any());
    }
}
