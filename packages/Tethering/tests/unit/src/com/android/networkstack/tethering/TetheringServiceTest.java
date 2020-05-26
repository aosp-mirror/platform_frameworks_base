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

package com.android.networkstack.tethering;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.Manifest.permission.UPDATE_APP_OPS_STATS;
import static android.Manifest.permission.WRITE_SETTINGS;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.UiAutomation;
import android.content.Intent;
import android.net.IIntResultListener;
import android.net.ITetheringConnector;
import android.net.ITetheringEventCallback;
import android.net.TetheringRequestParcel;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.networkstack.tethering.MockTetheringService.MockTetheringConnector;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class TetheringServiceTest {
    private static final String TEST_IFACE_NAME = "test_wlan0";
    private static final String TEST_CALLER_PKG = "com.android.shell";
    @Mock private ITetheringEventCallback mITetheringEventCallback;
    @Rule public ServiceTestRule mServiceTestRule;
    private Tethering mTethering;
    private Intent mMockServiceIntent;
    private ITetheringConnector mTetheringConnector;
    private UiAutomation mUiAutomation;

    private class TestTetheringResult extends IIntResultListener.Stub {
        private int mResult = -1; // Default value that does not match any result code.
        @Override
        public void onResult(final int resultCode) {
            mResult = resultCode;
        }

        public void assertResult(final int expected) {
            assertEquals(expected, mResult);
        }
    }

    private class MyResultReceiver extends ResultReceiver {
        MyResultReceiver(Handler handler) {
            super(handler);
        }
        private int mResult = -1; // Default value that does not match any result code.
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            mResult = resultCode;
        }

        public void assertResult(int expected) {
            assertEquals(expected, mResult);
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mServiceTestRule = new ServiceTestRule();
        mMockServiceIntent = new Intent(
                InstrumentationRegistry.getTargetContext(),
                MockTetheringService.class);
        final MockTetheringConnector mockConnector =
                (MockTetheringConnector) mServiceTestRule.bindService(mMockServiceIntent);
        mTetheringConnector = mockConnector.getTetheringConnector();
        final MockTetheringService service = mockConnector.getService();
        mTethering = service.getTethering();
    }

    @After
    public void tearDown() throws Exception {
        mServiceTestRule.unbindService();
        mUiAutomation.dropShellPermissionIdentity();
    }

    private interface TestTetheringCall {
        void runTetheringCall(TestTetheringResult result) throws Exception;
    }

    private void runAsNoPermission(final TestTetheringCall test) throws Exception {
        runTetheringCall(test, new String[0]);
    }

    private void runAsTetherPrivileged(final TestTetheringCall test) throws Exception {
        runTetheringCall(test, TETHER_PRIVILEGED);
    }

    private void runAsAccessNetworkState(final TestTetheringCall test) throws Exception {
        runTetheringCall(test, ACCESS_NETWORK_STATE);
    }

    private void runAsWriteSettings(final TestTetheringCall test) throws Exception {
        runTetheringCall(test, WRITE_SETTINGS, UPDATE_APP_OPS_STATS);
    }

    private void runTetheringCall(final TestTetheringCall test, String... permissions)
            throws Exception {
        if (permissions.length > 0) mUiAutomation.adoptShellPermissionIdentity(permissions);
        try {
            when(mTethering.isTetheringSupported()).thenReturn(true);
            test.runTetheringCall(new TestTetheringResult());
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    private void verifyNoMoreInteractionsForTethering() {
        verifyNoMoreInteractions(mTethering);
        verifyNoMoreInteractions(mITetheringEventCallback);
        reset(mTethering, mITetheringEventCallback);
    }

    private void runTether(final TestTetheringResult result) throws Exception {
        when(mTethering.tether(TEST_IFACE_NAME)).thenReturn(TETHER_ERROR_NO_ERROR);
        mTetheringConnector.tether(TEST_IFACE_NAME, TEST_CALLER_PKG, result);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).tether(TEST_IFACE_NAME);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testTether() throws Exception {
        runAsNoPermission((result) -> {
            mTetheringConnector.tether(TEST_IFACE_NAME, TEST_CALLER_PKG, result);
            verify(mTethering).isTetherProvisioningRequired();
            result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });

        runAsTetherPrivileged((result) -> {
            runTether(result);
            verifyNoMoreInteractionsForTethering();
        });

        runAsWriteSettings((result) -> {
            runTether(result);
            verify(mTethering).isTetherProvisioningRequired();
            verifyNoMoreInteractionsForTethering();
        });
    }

    private void runUnTether(final TestTetheringResult result) throws Exception {
        when(mTethering.untether(TEST_IFACE_NAME)).thenReturn(TETHER_ERROR_NO_ERROR);
        mTetheringConnector.untether(TEST_IFACE_NAME, TEST_CALLER_PKG, result);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).untether(TEST_IFACE_NAME);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testUntether() throws Exception {
        runAsNoPermission((result) -> {
            mTetheringConnector.untether(TEST_IFACE_NAME, TEST_CALLER_PKG, result);
            verify(mTethering).isTetherProvisioningRequired();
            result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });

        runAsTetherPrivileged((result) -> {
            runUnTether(result);
            verifyNoMoreInteractionsForTethering();
        });

        runAsWriteSettings((result) -> {
            runUnTether(result);
            verify(mTethering).isTetherProvisioningRequired();
            verifyNoMoreInteractionsForTethering();
        });
    }

    private void runSetUsbTethering(final TestTetheringResult result) throws Exception {
        when(mTethering.setUsbTethering(true /* enable */)).thenReturn(TETHER_ERROR_NO_ERROR);
        mTetheringConnector.setUsbTethering(true /* enable */, TEST_CALLER_PKG, result);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).setUsbTethering(true /* enable */);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testSetUsbTethering() throws Exception {
        runAsNoPermission((result) -> {
            mTetheringConnector.setUsbTethering(true /* enable */, TEST_CALLER_PKG, result);
            verify(mTethering).isTetherProvisioningRequired();
            result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });

        runAsTetherPrivileged((result) -> {
            runSetUsbTethering(result);
            verifyNoMoreInteractionsForTethering();
        });

        runAsWriteSettings((result) -> {
            runSetUsbTethering(result);
            verify(mTethering).isTetherProvisioningRequired();
            verifyNoMoreInteractionsForTethering();
        });

    }

    private void runStartTethering(final TestTetheringResult result,
            final TetheringRequestParcel request) throws Exception {
        mTetheringConnector.startTethering(request, TEST_CALLER_PKG, result);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).startTethering(eq(request), eq(result));
    }

    @Test
    public void testStartTethering() throws Exception {
        final TetheringRequestParcel request = new TetheringRequestParcel();
        request.tetheringType = TETHERING_WIFI;

        runAsNoPermission((result) -> {
            mTetheringConnector.startTethering(request, TEST_CALLER_PKG, result);
            verify(mTethering).isTetherProvisioningRequired();
            result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });

        runAsTetherPrivileged((result) -> {
            runStartTethering(result, request);
            verifyNoMoreInteractionsForTethering();
        });

        runAsWriteSettings((result) -> {
            runStartTethering(result, request);
            verify(mTethering).isTetherProvisioningRequired();
            verifyNoMoreInteractionsForTethering();
        });
    }

    @Test
    public void testStartTetheringWithExemptFromEntitlementCheck() throws Exception {
        final TetheringRequestParcel request = new TetheringRequestParcel();
        request.tetheringType = TETHERING_WIFI;
        request.exemptFromEntitlementCheck = true;

        runAsTetherPrivileged((result) -> {
            runStartTethering(result, request);
            verifyNoMoreInteractionsForTethering();
        });

        runAsWriteSettings((result) -> {
            mTetheringConnector.startTethering(request, TEST_CALLER_PKG, result);
            result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });
    }

    private void runStopTethering(final TestTetheringResult result) throws Exception {
        mTetheringConnector.stopTethering(TETHERING_WIFI, TEST_CALLER_PKG, result);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).stopTethering(TETHERING_WIFI);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testStopTethering() throws Exception {
        runAsNoPermission((result) -> {
            mTetheringConnector.stopTethering(TETHERING_WIFI, TEST_CALLER_PKG, result);
            verify(mTethering).isTetherProvisioningRequired();
            result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });

        runAsTetherPrivileged((result) -> {
            runStopTethering(result);
            verifyNoMoreInteractionsForTethering();
        });

        runAsWriteSettings((result) -> {
            runStopTethering(result);
            verify(mTethering).isTetherProvisioningRequired();
            verifyNoMoreInteractionsForTethering();
        });
    }

    private void runRequestLatestTetheringEntitlementResult() throws Exception {
        final MyResultReceiver result = new MyResultReceiver(null);
        mTetheringConnector.requestLatestTetheringEntitlementResult(TETHERING_WIFI, result,
                true /* showEntitlementUi */, TEST_CALLER_PKG);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).requestLatestTetheringEntitlementResult(eq(TETHERING_WIFI),
                eq(result), eq(true) /* showEntitlementUi */);
    }

    @Test
    public void testRequestLatestTetheringEntitlementResult() throws Exception {
        // Run as no permission.
        final MyResultReceiver result = new MyResultReceiver(null);
        mTetheringConnector.requestLatestTetheringEntitlementResult(TETHERING_WIFI, result,
                true /* showEntitlementUi */, TEST_CALLER_PKG);
        verify(mTethering).isTetherProvisioningRequired();
        result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
        verifyNoMoreInteractions(mTethering);

        runAsTetherPrivileged((none) -> {
            runRequestLatestTetheringEntitlementResult();
            verifyNoMoreInteractionsForTethering();
        });

        runAsWriteSettings((none) -> {
            runRequestLatestTetheringEntitlementResult();
            verify(mTethering).isTetherProvisioningRequired();
            verifyNoMoreInteractionsForTethering();
        });
    }

    private void runRegisterTetheringEventCallback() throws Exception {
        mTetheringConnector.registerTetheringEventCallback(mITetheringEventCallback,
                TEST_CALLER_PKG);
        verify(mTethering).registerTetheringEventCallback(eq(mITetheringEventCallback));
    }

    @Test
    public void testRegisterTetheringEventCallback() throws Exception {
        runAsNoPermission((result) -> {
            mTetheringConnector.registerTetheringEventCallback(mITetheringEventCallback,
                    TEST_CALLER_PKG);
            verify(mITetheringEventCallback).onCallbackStopped(
                    TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });

        runAsTetherPrivileged((none) -> {
            runRegisterTetheringEventCallback();
            verifyNoMoreInteractionsForTethering();
        });

        runAsAccessNetworkState((none) -> {
            runRegisterTetheringEventCallback();
            verifyNoMoreInteractionsForTethering();
        });
    }

    private void runUnregisterTetheringEventCallback() throws Exception {
        mTetheringConnector.unregisterTetheringEventCallback(mITetheringEventCallback,
                TEST_CALLER_PKG);
        verify(mTethering).unregisterTetheringEventCallback(eq(mITetheringEventCallback));
    }

    @Test
    public void testUnregisterTetheringEventCallback() throws Exception {
        runAsNoPermission((result) -> {
            mTetheringConnector.unregisterTetheringEventCallback(mITetheringEventCallback,
                    TEST_CALLER_PKG);
            verify(mITetheringEventCallback).onCallbackStopped(
                    TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });

        runAsTetherPrivileged((none) -> {
            runUnregisterTetheringEventCallback();
            verifyNoMoreInteractionsForTethering();
        });

        runAsAccessNetworkState((none) -> {
            runUnregisterTetheringEventCallback();
            verifyNoMoreInteractionsForTethering();
        });
    }

    private void runStopAllTethering(final TestTetheringResult result) throws Exception {
        mTetheringConnector.stopAllTethering(TEST_CALLER_PKG, result);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).untetherAll();
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testStopAllTethering() throws Exception {
        runAsNoPermission((result) -> {
            mTetheringConnector.stopAllTethering(TEST_CALLER_PKG, result);
            verify(mTethering).isTetherProvisioningRequired();
            result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });

        runAsTetherPrivileged((result) -> {
            runStopAllTethering(result);
            verifyNoMoreInteractionsForTethering();
        });

        runAsWriteSettings((result) -> {
            runStopAllTethering(result);
            verify(mTethering).isTetherProvisioningRequired();
            verifyNoMoreInteractionsForTethering();
        });
    }

    private void runIsTetheringSupported(final TestTetheringResult result) throws Exception {
        mTetheringConnector.isTetheringSupported(TEST_CALLER_PKG, result);
        verify(mTethering).isTetheringSupported();
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testIsTetheringSupported() throws Exception {
        runAsNoPermission((result) -> {
            mTetheringConnector.isTetheringSupported(TEST_CALLER_PKG, result);
            verify(mTethering).isTetherProvisioningRequired();
            result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
            verifyNoMoreInteractionsForTethering();
        });

        runAsTetherPrivileged((result) -> {
            runIsTetheringSupported(result);
            verifyNoMoreInteractionsForTethering();
        });

        runAsWriteSettings((result) -> {
            runIsTetheringSupported(result);
            verify(mTethering).isTetherProvisioningRequired();
            verifyNoMoreInteractionsForTethering();
        });
    }
}
