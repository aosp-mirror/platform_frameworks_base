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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static android.support.test.internal.runner.junit4.statement.UiThreadStatement.runOnUiThread;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.internal.runner.junit4.statement.UiThreadStatement;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;
import android.view.ViewGroup;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyguardIndicationControllerTest extends SysuiTestCase {

    private final String ORGANIZATION_NAME = "organization";

    private String mDisclosureWithOrganization;

    private DevicePolicyManager mDevicePolicyManager = mock(DevicePolicyManager.class);
    private ViewGroup mIndicationArea = mock(ViewGroup.class);
    private KeyguardIndicationTextView mDisclosure = mock(KeyguardIndicationTextView.class);

    private KeyguardIndicationController mController;
    private WakeLockFake mWakeLock;
    private Instrumentation mInstrumentation;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();

        mContext.addMockSystemService(Context.DEVICE_POLICY_SERVICE, mDevicePolicyManager);
        mContext.addMockSystemService(Context.TRUST_SERVICE, mock(TrustManager.class));
        mContext.addMockSystemService(Context.FINGERPRINT_SERVICE, mock(FingerprintManager.class));
        mDisclosureWithOrganization = mContext.getString(R.string.do_disclosure_with_name,
                ORGANIZATION_NAME);

        when(mIndicationArea.findViewById(R.id.keyguard_indication_enterprise_disclosure))
                .thenReturn(mDisclosure);

        mWakeLock = new WakeLockFake();
    }

    private void createController() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mController = new KeyguardIndicationController(mContext, mIndicationArea, null, mWakeLock);
    }

    @Test
    public void unmanaged() {
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        createController();

        verify(mDisclosure).setVisibility(View.GONE);
        verifyNoMoreInteractions(mDisclosure);
    }

    @Test
    public void managedNoOwnerName() {
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn(null);
        createController();

        verify(mDisclosure).setVisibility(View.VISIBLE);
        verify(mDisclosure).switchIndication(R.string.do_disclosure_generic);
        verifyNoMoreInteractions(mDisclosure);
    }

    @Test
    public void managedOwnerName() {
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn(ORGANIZATION_NAME);
        createController();

        verify(mDisclosure).setVisibility(View.VISIBLE);
        verify(mDisclosure).switchIndication(mDisclosureWithOrganization);
        verifyNoMoreInteractions(mDisclosure);
    }

    @Test
    public void updateOnTheFly() {
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        createController();

        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
        reset(mDisclosure);

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn(null);
        monitor.onKeyguardVisibilityChanged(true);

        verify(mDisclosure).setVisibility(View.VISIBLE);
        verify(mDisclosure).switchIndication(R.string.do_disclosure_generic);
        verifyNoMoreInteractions(mDisclosure);
        reset(mDisclosure);

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn(ORGANIZATION_NAME);
        monitor.onKeyguardVisibilityChanged(false);
        monitor.onKeyguardVisibilityChanged(true);

        verify(mDisclosure).setVisibility(View.VISIBLE);
        verify(mDisclosure).switchIndication(mDisclosureWithOrganization);
        verifyNoMoreInteractions(mDisclosure);
        reset(mDisclosure);

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        monitor.onKeyguardVisibilityChanged(false);
        monitor.onKeyguardVisibilityChanged(true);

        verify(mDisclosure).setVisibility(View.GONE);
        verifyNoMoreInteractions(mDisclosure);
    }

    @Test
    public void transientIndication_holdsWakeLock_whenDozing() {
        createController();

        mController.setDozing(true);
        mController.showTransientIndication("Test");

        assertTrue(mWakeLock.isHeld());
    }

    @Test
    public void transientIndication_releasesWakeLock_afterHiding() {
        createController();

        mController.setDozing(true);
        mController.showTransientIndication("Test");
        mController.hideTransientIndication();

        assertFalse(mWakeLock.isHeld());
    }

    @Ignore("Flaky")
    @Test
    public void transientIndication_releasesWakeLock_afterHidingDelayed() throws Throwable {
        mInstrumentation.runOnMainSync(() -> {
            createController();

            mController.setDozing(true);
            mController.showTransientIndication("Test");
            mController.hideTransientIndicationDelayed(0);
        });
        mInstrumentation.waitForIdleSync();

        boolean[] held = new boolean[2];
        mInstrumentation.runOnMainSync(() -> {
            held[0] = mWakeLock.isHeld();
            held[1] = true;
        });
        assertFalse("wake lock still held", held[0]);
        assertTrue("held was not written yet", held[1]);
    }
}
