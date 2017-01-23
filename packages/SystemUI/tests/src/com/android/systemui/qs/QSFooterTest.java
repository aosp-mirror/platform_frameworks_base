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

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.util.LayoutInflaterBuilder;
import com.android.systemui.utils.TestableImageView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class QSFooterTest extends SysuiTestCase {

    private final String MANAGING_ORGANIZATION = "organization";
    private final String DEVICE_OWNER_PACKAGE = "TestDPC";
    private final String VPN_PACKAGE = "TestVPN";

    private ViewGroup mRootView;
    private TextView mFooterText;
    private TestableImageView mFooterIcon;
    private TestableImageView mFooterIcon2;
    private QSFooter mFooter;
    private SecurityController mSecurityController = mock(SecurityController.class);

    @Before
    public void setUp() {
        injectTestDependency(SecurityController.class, mSecurityController);
        injectTestDependency(Dependency.BG_LOOPER, Looper.getMainLooper());
        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE,
                new LayoutInflaterBuilder(mContext)
                        .replace("ImageView", TestableImageView.class)
                        .build());
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> mFooter = new QSFooter(null, mContext));
        waitForIdleSync(h);
        mRootView = (ViewGroup) mFooter.getView();
        mFooterText = (TextView) mRootView.findViewById(R.id.footer_text);
        mFooterIcon = (TestableImageView) mRootView.findViewById(R.id.footer_icon);
        mFooterIcon2 = (TestableImageView) mRootView.findViewById(R.id.footer_icon2);
        mFooter.setHostEnvironment(null);
    }

    @Test
    public void testUnmanaged() {
        when(mSecurityController.isDeviceManaged()).thenReturn(false);
        when(mSecurityController.isVpnEnabled()).thenReturn(false);
        when(mSecurityController.isVpnBranded()).thenReturn(false);
        mFooter.refreshState();

        waitForIdleSync(mFooter.mHandler);
        assertEquals(View.GONE, mRootView.getVisibility());
    }

    @Test
    public void testManagedNoOwnerName() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName()).thenReturn(null);
        mFooter.refreshState();

        waitForIdleSync(mFooter.mHandler);
        assertEquals(mContext.getString(R.string.do_disclosure_generic), mFooterText.getText());
        assertEquals(View.VISIBLE, mRootView.getVisibility());
    }

    @Test
    public void testManagedOwnerName() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        mFooter.refreshState();

        waitForIdleSync(mFooter.mHandler);
        assertEquals(mContext.getString(R.string.do_disclosure_with_name, MANAGING_ORGANIZATION),
                mFooterText.getText());
        assertEquals(View.VISIBLE, mRootView.getVisibility());
    }

    @Test
    public void testNetworkLoggingEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);
        when(mSecurityController.isVpnEnabled()).thenReturn(false);
        mFooter.refreshState();

        waitForIdleSync(mFooter.mHandler);
        assertEquals(View.VISIBLE, mFooterIcon.getVisibility());
        assertEquals(R.drawable.ic_qs_network_logging, mFooterIcon.getLastImageResource());
        assertEquals(View.INVISIBLE, mFooterIcon2.getVisibility());
    }

    @Test
    public void testVpnEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(false);
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.isVpnBranded()).thenReturn(false);
        mFooter.refreshState();

        waitForIdleSync(mFooter.mHandler);
        assertEquals(View.VISIBLE, mFooterIcon.getVisibility());
        // -1 == never set.
        assertEquals(-1, mFooterIcon.getLastImageResource());
        assertEquals(View.INVISIBLE, mFooterIcon2.getVisibility());
    }

    @Test
    public void testNetworkLoggingAndVpnEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.isVpnBranded()).thenReturn(false);
        mFooter.refreshState();

        waitForIdleSync(mFooter.mHandler);
        assertEquals(View.VISIBLE, mFooterIcon.getVisibility());
        assertEquals(View.VISIBLE, mFooterIcon2.getVisibility());
        // -1 == never set.
        assertEquals(-1, mFooterIcon.getLastImageResource());
        assertEquals(-1, mFooterIcon2.getLastImageResource());
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
                mContext.getString(R.string.monitoring_description_do_header_with_name,
                        MANAGING_ORGANIZATION, DEVICE_OWNER_PACKAGE) :
                mContext.getString(R.string.monitoring_description_do_header_generic,
                        DEVICE_OWNER_PACKAGE));
        message.append("\n\n");
        message.append(mContext.getString(R.string.monitoring_description_do_body));
        message.append(mContext.getString(
                R.string.monitoring_description_do_learn_more_separator));
        message.append(mContext.getString(R.string.monitoring_description_do_learn_more),
                mFooter.new EnterprisePrivacySpan(), 0);
        return message;
    }
}
