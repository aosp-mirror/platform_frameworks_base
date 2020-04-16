/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricAuthenticator;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@Presubmit
@SmallTest
public class BiometricServiceBaseTest {
    private static class TestableBiometricServiceBase extends BiometricServiceBase {
        TestableBiometricServiceBase(Context context) {
            super(context);
        }

        @Override
        protected String getTag() {
            return null;
        }

        @Override
        protected DaemonWrapper getDaemonWrapper() {
            return null;
        }

        @Override
        protected BiometricUtils getBiometricUtils() {
            return null;
        }

        @Override
        protected Constants getConstants() {
            return null;
        }

        @Override
        protected boolean hasReachedEnrollmentLimit(int userId) {
            return false;
        }

        @Override
        protected void updateActiveGroup(int userId, String clientPackage) {
        }

        @Override
        protected String getLockoutResetIntent() {
            return null;
        }

        @Override
        protected String getLockoutBroadcastPermission() {
            return null;
        }

        @Override
        protected long getHalDeviceId() {
            return 0;
        }

        @Override
        protected boolean hasEnrolledBiometrics(int userId) {
            return false;
        }

        @Override
        protected String getManageBiometricPermission() {
            return null;
        }

        @Override
        protected void checkUseBiometricPermission() {
        }

        @Override
        protected boolean checkAppOps(int uid, String opPackageName) {
            return false;
        }

        @Override
        protected List<? extends BiometricAuthenticator.Identifier> getEnrolledTemplates(
                int userId) {
            return null;
        }

        @Override
        protected int statsModality() {
            return 0;
        }

        @Override
        protected int getLockoutMode() {
            return 0;
        }
    }

    private static final int CLIENT_COOKIE = 0xc00c1e;

    private BiometricServiceBase mBiometricServiceBase;

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private BiometricAuthenticator.Identifier mIdentifier;
    @Mock
    private ClientMonitor mClient;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getString(anyInt())).thenReturn("");
        when(mClient.getCookie()).thenReturn(CLIENT_COOKIE);

        mBiometricServiceBase = new TestableBiometricServiceBase(mContext);
    }

    @Test
    public void testHandleEnumerate_doesNotCrash_withNullClient() {
        mBiometricServiceBase.handleEnumerate(mIdentifier, 0 /* remaining */);
    }

    @Test
    public void testStartClient_sendsErrorAndRemovesClient_onNonzeroErrorCode() {
        when(mClient.start()).thenReturn(1);

        mBiometricServiceBase.startClient(mClient, false /* initiatedByClient */);

        verify(mClient).onError(anyLong(), anyInt(), anyInt());
        verify(mClient).destroy();
    }
}
