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

package com.android.server.connectivity.tethering;

import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.net.IIntResultListener;
import android.net.ITetheringConnector;
import android.net.ITetheringEventCallback;
import android.net.TetheringRequestParcel;
import android.os.ResultReceiver;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.connectivity.tethering.MockTetheringService.MockTetheringConnector;

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
    private static final String TEST_CALLER_PKG = "test_pkg";
    @Mock private ITetheringEventCallback mITetheringEventCallback;
    @Rule public ServiceTestRule mServiceTestRule;
    private Tethering mTethering;
    private Intent mMockServiceIntent;
    private ITetheringConnector mTetheringConnector;

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

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mServiceTestRule = new ServiceTestRule();
        mMockServiceIntent = new Intent(
                InstrumentationRegistry.getTargetContext(),
                MockTetheringService.class);
        final MockTetheringConnector mockConnector =
                (MockTetheringConnector) mServiceTestRule.bindService(mMockServiceIntent);
        mTetheringConnector = mockConnector.getTetheringConnector();
        final MockTetheringService service = mockConnector.getService();
        mTethering = service.getTethering();
        verify(mTethering).startStateMachineUpdaters();
        when(mTethering.hasTetherableConfiguration()).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        mServiceTestRule.unbindService();
    }

    @Test
    public void testTether() throws Exception {
        when(mTethering.tether(TEST_IFACE_NAME)).thenReturn(TETHER_ERROR_NO_ERROR);
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.tether(TEST_IFACE_NAME, TEST_CALLER_PKG, result);
        verify(mTethering).hasTetherableConfiguration();
        verify(mTethering).tether(TEST_IFACE_NAME);
        verifyNoMoreInteractions(mTethering);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testUntether() throws Exception {
        when(mTethering.untether(TEST_IFACE_NAME)).thenReturn(TETHER_ERROR_NO_ERROR);
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.untether(TEST_IFACE_NAME, TEST_CALLER_PKG, result);
        verify(mTethering).hasTetherableConfiguration();
        verify(mTethering).untether(TEST_IFACE_NAME);
        verifyNoMoreInteractions(mTethering);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testSetUsbTethering() throws Exception {
        when(mTethering.setUsbTethering(true /* enable */)).thenReturn(TETHER_ERROR_NO_ERROR);
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.setUsbTethering(true /* enable */, TEST_CALLER_PKG, result);
        verify(mTethering).hasTetherableConfiguration();
        verify(mTethering).setUsbTethering(true /* enable */);
        verifyNoMoreInteractions(mTethering);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testStartTethering() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        final TetheringRequestParcel request = new TetheringRequestParcel();
        request.tetheringType = TETHERING_WIFI;
        mTetheringConnector.startTethering(request, TEST_CALLER_PKG, result);
        verify(mTethering).hasTetherableConfiguration();
        verify(mTethering).startTethering(eq(request), eq(result));
        verifyNoMoreInteractions(mTethering);
    }

    @Test
    public void testStopTethering() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.stopTethering(TETHERING_WIFI, TEST_CALLER_PKG, result);
        verify(mTethering).hasTetherableConfiguration();
        verify(mTethering).stopTethering(TETHERING_WIFI);
        verifyNoMoreInteractions(mTethering);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testRequestLatestTetheringEntitlementResult() throws Exception {
        final ResultReceiver result = new ResultReceiver(null);
        mTetheringConnector.requestLatestTetheringEntitlementResult(TETHERING_WIFI, result,
                true /* showEntitlementUi */, TEST_CALLER_PKG);
        verify(mTethering).hasTetherableConfiguration();
        verify(mTethering).requestLatestTetheringEntitlementResult(eq(TETHERING_WIFI),
                eq(result), eq(true) /* showEntitlementUi */);
        verifyNoMoreInteractions(mTethering);
    }

    @Test
    public void testRegisterTetheringEventCallback() throws Exception {
        mTetheringConnector.registerTetheringEventCallback(mITetheringEventCallback,
                TEST_CALLER_PKG);
        verify(mTethering).registerTetheringEventCallback(eq(mITetheringEventCallback));
        verifyNoMoreInteractions(mTethering);
    }

    @Test
    public void testUnregisterTetheringEventCallback() throws Exception {
        mTetheringConnector.unregisterTetheringEventCallback(mITetheringEventCallback,
                TEST_CALLER_PKG);
        verify(mTethering).unregisterTetheringEventCallback(
                eq(mITetheringEventCallback));
        verifyNoMoreInteractions(mTethering);
    }

    @Test
    public void testStopAllTethering() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.stopAllTethering(TEST_CALLER_PKG, result);
        verify(mTethering).hasTetherableConfiguration();
        verify(mTethering).untetherAll();
        verifyNoMoreInteractions(mTethering);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testIsTetheringSupported() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.isTetheringSupported(TEST_CALLER_PKG, result);
        verify(mTethering).hasTetherableConfiguration();
        verifyNoMoreInteractions(mTethering);
        result.assertResult(TETHER_ERROR_NO_ERROR);
    }
}
