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

import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Looper;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.SubscriptionManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.View;
import android.view.ViewGroup;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyguardIndicationControllerTest extends SysuiTestCase {

    private final String ORGANIZATION_NAME = "organization";
    private final String DISCLOSURE_WITH_ORGANIZATION_NAME = "managed by organization";

    private Context mContext = mock(Context.class);
    private DevicePolicyManager mDevicePolicyManager = mock(DevicePolicyManager.class);
    private ViewGroup mIndicationArea = mock(ViewGroup.class);
    private KeyguardIndicationTextView mDisclosure = mock(KeyguardIndicationTextView.class);

    private KeyguardIndicationController mController;

    @Before
    public void setUp() throws Exception {
        final Resources resources = mock(Resources.class);
        when(mContext.getResources()).thenReturn(resources);
        when(mContext.getContentResolver()).thenReturn(mock(ContentResolver.class));
        when(mContext.getPackageManager()).thenReturn(mock(PackageManager.class));
        when(mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)).thenReturn(
                mock(SubscriptionManager.class));
        when(mContext.getSystemService(Context.TRUST_SERVICE)).thenReturn(
                mock(TrustManager.class));
        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mDevicePolicyManager);

        when(resources.getString(R.string.do_disclosure_with_name, ORGANIZATION_NAME))
                .thenReturn(DISCLOSURE_WITH_ORGANIZATION_NAME);

        when(mIndicationArea.findViewById(R.id.keyguard_indication_enterprise_disclosure))
                .thenReturn(mDisclosure);
    }

    private void createController() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mController = new KeyguardIndicationController(mContext, mIndicationArea, null);
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
        verify(mDisclosure).switchIndication(DISCLOSURE_WITH_ORGANIZATION_NAME);
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
        verify(mDisclosure).switchIndication(DISCLOSURE_WITH_ORGANIZATION_NAME);
        verifyNoMoreInteractions(mDisclosure);
        reset(mDisclosure);

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        monitor.onKeyguardVisibilityChanged(false);
        monitor.onKeyguardVisibilityChanged(true);

        verify(mDisclosure).setVisibility(View.GONE);
        verifyNoMoreInteractions(mDisclosure);
    }
}
