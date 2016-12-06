/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;
import android.content.res.Resources;
import android.os.Looper;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.SecurityController;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertEquals;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class QSFooterTest extends SysuiTestCase {

    private final String MANAGING_ORGANIZATION = "organization";
    private final String DEVICE_OWNER_PACKAGE = "TestDPC";
    private final String VPN_PACKAGE = "TestVPN";

    private ViewGroup mRootView = mock(ViewGroup.class);
    private TextView mFooterText = mock(TextView.class);
    private QSFooter mFooter;
    private Resources mResources;
    private SecurityController mSecurityController = mock(SecurityController.class);

    @Before
    public void setUp() {
        when(mRootView.findViewById(R.id.footer_text)).thenReturn(mFooterText);
        when(mRootView.findViewById(R.id.footer_icon)).thenReturn(mock(ImageView.class));
        final LayoutInflater layoutInflater = mock(LayoutInflater.class);
        when(layoutInflater.inflate(eq(R.layout.quick_settings_footer), anyObject(), anyBoolean()))
                .thenReturn(mRootView);
        final Context context = mock(Context.class);
        when(context.getSystemService(Context.LAYOUT_INFLATER_SERVICE)).thenReturn(layoutInflater);
        mResources = mContext.getResources();
        when(context.getResources()).thenReturn(mResources);
        mFooter = new QSFooter(null, context);
        reset(mRootView);
        mFooter.setHostEnvironment(null, mSecurityController, Looper.getMainLooper());
    }

    @Test
    public void testUnmanaged() {
        when(mSecurityController.isDeviceManaged()).thenReturn(false);
        when(mSecurityController.isVpnEnabled()).thenReturn(false);
        when(mSecurityController.isVpnBranded()).thenReturn(false);
        mFooter.refreshState();

        waitForIdleSync(mFooter.mHandler);
        verify(mRootView).setVisibility(View.GONE);
        verifyNoMoreInteractions(mRootView);
    }

    @Test
    public void testManagedNoOwnerName() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName()).thenReturn(null);
        mFooter.refreshState();

        waitForIdleSync(mFooter.mHandler);
        verify(mFooterText).setText(mResources.getString(R.string.do_disclosure_generic));
        verifyNoMoreInteractions(mFooterText);
        verify(mRootView).setVisibility(View.VISIBLE);
        verifyNoMoreInteractions(mRootView);
    }

    @Test
    public void testManagedOwnerName() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        mFooter.refreshState();

        waitForIdleSync(mFooter.mHandler);
        verify(mFooterText).setText(mResources.getString(R.string.do_disclosure_with_name,
                MANAGING_ORGANIZATION));
        verifyNoMoreInteractions(mFooterText);
        verify(mRootView).setVisibility(View.VISIBLE);
        verifyNoMoreInteractions(mRootView);
    }

    @Test
    public void testGetMessageWithNoOrganizationAndNoVPN() {
        assertEquals(getExpectedMessage(false /* hasDeviceOwnerOrganization */, false /* hasVPN */),
                mFooter.getMessage(DEVICE_OWNER_PACKAGE,
                        null /* profileOwnerPackage */,
                        null /* primaryVpn */,
                        null /* profileVpn */,
                        null /* deviceOwnerOrganization */,
                        false /* hasProfileOwner */,
                        false /* isBranded */));
    }

    @Test
    public void testGetMessageWithNoOrganizationAndVPN() {
        assertEquals(getExpectedMessage(false /* hasDeviceOwnerOrganization */, true /* hasVPN */),
                mFooter.getMessage(DEVICE_OWNER_PACKAGE,
                        null /* profileOwnerPackage */,
                        VPN_PACKAGE,
                        null /* profileVpn */,
                        null /* deviceOwnerOrganization */,
                        false /* hasProfileOwner */,
                        false /* isBranded */));
    }

    @Test
    public void testGetMessageWithOrganizationAndNoVPN() {
        assertEquals(getExpectedMessage(true /* hasDeviceOwnerOrganization */, false /* hasVPN */),
                mFooter.getMessage(DEVICE_OWNER_PACKAGE,
                        null /* profileOwnerPackage */,
                        null /* primaryVpn */,
                        null /* profileVpn */,
                        MANAGING_ORGANIZATION,
                        false /* hasProfileOwner */,
                        false /* isBranded */));
    }

    @Test
    public void testGetMessageWithOrganizationAndVPN() {
        assertEquals(getExpectedMessage(true /* hasDeviceOwnerOrganization */, true /* hasVPN */),
                mFooter.getMessage(DEVICE_OWNER_PACKAGE,
                        null /* profileOwnerPackage */,
                        VPN_PACKAGE,
                        null /* profileVpn */,
                        MANAGING_ORGANIZATION,
                        false /* hasProfileOwner */,
                        false /* isBranded */));
    }

    private CharSequence getExpectedMessage(boolean hasDeviceOwnerOrganization, boolean hasVPN) {
        final SpannableStringBuilder message = new SpannableStringBuilder();
        message.append(hasDeviceOwnerOrganization ?
                mResources.getString(R.string.monitoring_description_do_header_with_name,
                        MANAGING_ORGANIZATION, DEVICE_OWNER_PACKAGE) :
                mResources.getString(R.string.monitoring_description_do_header_generic,
                        DEVICE_OWNER_PACKAGE));
        message.append("\n\n");
        message.append(mResources.getString(R.string.monitoring_description_do_body));
        if (hasVPN) {
            message.append("\n\n");
            message.append(mResources.getString(R.string.monitoring_description_do_body_vpn,
                    VPN_PACKAGE));
        }
        message.append(mResources.getString(
                R.string.monitoring_description_do_learn_more_separator));
        message.append(mResources.getString(R.string.monitoring_description_do_learn_more),
                mFooter.new EnterprisePrivacySpan(), 0);
        return message;
    }
}
