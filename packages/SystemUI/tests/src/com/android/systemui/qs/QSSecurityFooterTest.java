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

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.LayoutInflaterBuilder;
import android.testing.TestableImageView;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.SecurityController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/*
 * Compile and run the whole SystemUI test suite:
   runtest --path frameworks/base/packages/SystemUI/tests
 *
 * Compile and run just this class:
   runtest --path \
   frameworks/base/packages/SystemUI/tests/src/com/android/systemui/qs/QSSecurityFooterTest.java
*/

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class QSSecurityFooterTest extends SysuiTestCase {

    private final String MANAGING_ORGANIZATION = "organization";
    private final String DEVICE_OWNER_PACKAGE = "TestDPC";
    private final String VPN_PACKAGE = "TestVPN";
    private final String VPN_PACKAGE_2 = "TestVPN 2";

    private ViewGroup mRootView;
    private TextView mFooterText;
    private TestableImageView mFooterIcon;
    private QSSecurityFooter mFooter;
    private SecurityController mSecurityController = mock(SecurityController.class);
    private UserManager mUserManager;

    @Before
    public void setUp() {
        mDependency.injectTestDependency(SecurityController.class, mSecurityController);
        mDependency.injectTestDependency(Dependency.BG_LOOPER,
                TestableLooper.get(this).getLooper());
        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE,
                new LayoutInflaterBuilder(mContext)
                        .replace("ImageView", TestableImageView.class)
                        .build());
        mUserManager = Mockito.mock(UserManager.class);
        mContext.addMockSystemService(Context.USER_SERVICE, mUserManager);
        mFooter = new QSSecurityFooter(null, mContext);
        mRootView = (ViewGroup) mFooter.getView();
        mFooterText = mRootView.findViewById(R.id.footer_text);
        mFooterIcon = mRootView.findViewById(R.id.footer_icon);
        mFooter.setHostEnvironment(null);
    }

    @Test
    public void testUnmanaged() {
        when(mSecurityController.isDeviceManaged()).thenReturn(false);
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(false);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(View.GONE, mRootView.getVisibility());
    }

    @Test
    public void testManagedNoOwnerName() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName()).thenReturn(null);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management),
                     mFooterText.getText());
        assertEquals(View.VISIBLE, mRootView.getVisibility());
        assertEquals(View.VISIBLE, mFooterIcon.getVisibility());
        // -1 == never set.
        assertEquals(-1, mFooterIcon.getLastImageResource());
    }

    @Test
    public void testManagedOwnerName() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_named_management,
                                        MANAGING_ORGANIZATION),
                mFooterText.getText());
        assertEquals(View.VISIBLE, mRootView.getVisibility());
        assertEquals(View.VISIBLE, mFooterIcon.getVisibility());
        // -1 == never set.
        assertEquals(-1, mFooterIcon.getLastImageResource());
    }

    @Test
    public void testManagedDemoMode() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName()).thenReturn(null);
        final UserInfo mockUserInfo = Mockito.mock(UserInfo.class);
        when(mockUserInfo.isDemo()).thenReturn(true);
        when(mUserManager.getUserInfo(anyInt())).thenReturn(mockUserInfo);
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.DEVICE_DEMO_MODE, 1);

        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(View.GONE, mRootView.getVisibility());
    }

    @Test
    public void testNetworkLoggingEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_monitoring),
                mFooterText.getText());
        assertEquals(View.VISIBLE, mFooterIcon.getVisibility());
        // -1 == never set.
        assertEquals(-1, mFooterIcon.getLastImageResource());

        // Same situation, but with organization name set
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(
                             R.string.quick_settings_disclosure_named_management_monitoring,
                             MANAGING_ORGANIZATION),
                     mFooterText.getText());
    }

    @Test
    public void testManagedCACertsInstalled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.hasCACertInCurrentUser()).thenReturn(true);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_monitoring),
                mFooterText.getText());
    }

    @Test
    public void testManagedOneVpnEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn(VPN_PACKAGE);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_named_vpn,
                                        VPN_PACKAGE),
                     mFooterText.getText());
        assertEquals(View.VISIBLE, mFooterIcon.getVisibility());
        assertEquals(R.drawable.stat_sys_vpn_ic, mFooterIcon.getLastImageResource());

        // Same situation, but with organization name set
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(
                              R.string.quick_settings_disclosure_named_management_named_vpn,
                              MANAGING_ORGANIZATION, VPN_PACKAGE),
                     mFooterText.getText());
    }

    @Test
    public void testManagedTwoVpnsEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn(VPN_PACKAGE);
        when(mSecurityController.getWorkProfileVpnName()).thenReturn(VPN_PACKAGE_2);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_vpns),
                     mFooterText.getText());
        assertEquals(View.VISIBLE, mFooterIcon.getVisibility());
        assertEquals(R.drawable.stat_sys_vpn_ic, mFooterIcon.getLastImageResource());

        // Same situation, but with organization name set
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_named_management_vpns,
                                        MANAGING_ORGANIZATION),
                     mFooterText.getText());
    }

    @Test
    public void testNetworkLoggingAndVpnEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn("VPN Test App");
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(View.VISIBLE, mFooterIcon.getVisibility());
        assertEquals(R.drawable.stat_sys_vpn_ic, mFooterIcon.getLastImageResource());
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_monitoring),
                mFooterText.getText());
    }

    @Test
    public void testWorkProfileCACertsInstalled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(false);
        when(mSecurityController.hasCACertInWorkProfile()).thenReturn(true);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        // -1 == never set.
        assertEquals(-1, mFooterIcon.getLastImageResource());
        assertEquals(mContext.getString(
                             R.string.quick_settings_disclosure_managed_profile_monitoring),
                     mFooterText.getText());

        // Same situation, but with organization name set
        when(mSecurityController.getWorkProfileOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(
                             R.string.quick_settings_disclosure_named_managed_profile_monitoring,
                             MANAGING_ORGANIZATION),
                     mFooterText.getText());
    }

    @Test
    public void testCACertsInstalled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(false);
        when(mSecurityController.hasCACertInCurrentUser()).thenReturn(true);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        // -1 == never set.
        assertEquals(-1, mFooterIcon.getLastImageResource());
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_monitoring),
                     mFooterText.getText());
    }

    @Test
    public void testTwoVpnsEnabled() {
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn(VPN_PACKAGE);
        when(mSecurityController.getWorkProfileVpnName()).thenReturn(VPN_PACKAGE_2);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(R.drawable.stat_sys_vpn_ic, mFooterIcon.getLastImageResource());
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_vpns),
                     mFooterText.getText());
    }

    @Test
    public void testWorkProfileVpnEnabled() {
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getWorkProfileVpnName()).thenReturn(VPN_PACKAGE_2);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(R.drawable.stat_sys_vpn_ic, mFooterIcon.getLastImageResource());
        assertEquals(mContext.getString(
                             R.string.quick_settings_disclosure_managed_profile_named_vpn,
                             VPN_PACKAGE_2),
                     mFooterText.getText());
    }

    @Test
    public void testProfileOwnerOfOrganizationOwnedDeviceNoName() {
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(true);

        mFooter.refreshState();
        TestableLooper.get(this).processAllMessages();

        assertEquals(mContext.getString(
                R.string.quick_settings_disclosure_management),
                mFooterText.getText());
    }

    @Test
    public void testProfileOwnerOfOrganizationOwnedDeviceWithName() {
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(true);
        when(mSecurityController.getWorkProfileOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);

        mFooter.refreshState();
        TestableLooper.get(this).processAllMessages();

        assertEquals(mContext.getString(
                R.string.quick_settings_disclosure_named_management,
                MANAGING_ORGANIZATION),
                mFooterText.getText());
    }

    @Test
    public void testVpnEnabled() {
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn(VPN_PACKAGE);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(R.drawable.stat_sys_vpn_ic, mFooterIcon.getLastImageResource());
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_named_vpn,
                                        VPN_PACKAGE),
                     mFooterText.getText());

        when(mSecurityController.hasWorkProfile()).thenReturn(true);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();
        assertEquals(mContext.getString(
                             R.string.quick_settings_disclosure_personal_profile_named_vpn,
                             VPN_PACKAGE),
                     mFooterText.getText());
    }

    @Test
    public void testGetManagementMessage_noManagement() {
        assertEquals(null, mFooter.getManagementMessage(
                /* isDeviceManaged= */ false,
                MANAGING_ORGANIZATION,
                /* isProfileOwnerOfOrganizationOwnedDevice= */ false,
                MANAGING_ORGANIZATION));
    }

    @Test
    public void testGetManagementMessage_deviceOwner() {
        assertEquals(mContext.getString(R.string.monitoring_description_named_management,
                                        MANAGING_ORGANIZATION),
                     mFooter.getManagementMessage(
                             /* isDeviceManaged= */ true,
                             MANAGING_ORGANIZATION,
                             /* isProfileOwnerOfOrganizationOwnedDevice= */ false,
                             /* workProfileOrganizationName= */ null));
        assertEquals(mContext.getString(R.string.monitoring_description_management),
                     mFooter.getManagementMessage(
                             /* isDeviceManaged= */ true,
                             /* organizationName= */ null,
                             /* isProfileOwnerOfOrganizationOwnedDevice= */ false,
                             /* workProfileOrganizationName= */ null));
    }

    @Test
    public void testGetManagementMessage_profileOwnerOfOrganizationOwnedDevice() {
        assertEquals(mContext.getString(R.string.monitoring_description_named_management,
                MANAGING_ORGANIZATION),
                mFooter.getManagementMessage(
                        /* isDeviceManaged= */ false,
                        /* organizationName= */ null,
                        /* isProfileOwnerOfOrganizationOwnedDevice= */ true,
                        MANAGING_ORGANIZATION));
        assertEquals(mContext.getString(R.string.monitoring_description_management),
                mFooter.getManagementMessage(
                        /* isDeviceManaged= */ false,
                        /* organizationName= */ null,
                        /* isProfileOwnerOfOrganizationOwnedDevice= */ true,
                        /* workProfileOrganizationName= */ null));
    }

    @Test
    public void testGetCaCertsMessage() {
        assertEquals(null, mFooter.getCaCertsMessage(true, false, false));
        assertEquals(null, mFooter.getCaCertsMessage(false, false, false));
        assertEquals(mContext.getString(R.string.monitoring_description_management_ca_certificate),
                     mFooter.getCaCertsMessage(true, true, true));
        assertEquals(mContext.getString(R.string.monitoring_description_management_ca_certificate),
                     mFooter.getCaCertsMessage(true, false, true));
        assertEquals(mContext.getString(
                         R.string.monitoring_description_managed_profile_ca_certificate),
                     mFooter.getCaCertsMessage(false, false, true));
        assertEquals(mContext.getString(
                         R.string.monitoring_description_ca_certificate),
                     mFooter.getCaCertsMessage(false, true, false));
    }

    @Test
    public void testGetNetworkLoggingMessage() {
        assertEquals(null, mFooter.getNetworkLoggingMessage(false));
        assertEquals(mContext.getString(R.string.monitoring_description_management_network_logging),
                     mFooter.getNetworkLoggingMessage(true));
    }

    @Test
    public void testGetVpnMessage() {
        assertEquals(null, mFooter.getVpnMessage(true, true, null, null));
        assertEquals(addLink(mContext.getString(R.string.monitoring_description_two_named_vpns,
                                 VPN_PACKAGE, VPN_PACKAGE_2)),
                     mFooter.getVpnMessage(true, true, VPN_PACKAGE, VPN_PACKAGE_2));
        assertEquals(addLink(mContext.getString(R.string.monitoring_description_two_named_vpns,
                                 VPN_PACKAGE, VPN_PACKAGE_2)),
                     mFooter.getVpnMessage(false, true, VPN_PACKAGE, VPN_PACKAGE_2));
        assertEquals(addLink(mContext.getString(R.string.monitoring_description_named_vpn,
                                 VPN_PACKAGE)),
                     mFooter.getVpnMessage(true, false, VPN_PACKAGE, null));
        assertEquals(addLink(mContext.getString(R.string.monitoring_description_named_vpn,
                                 VPN_PACKAGE)),
                     mFooter.getVpnMessage(false, false, VPN_PACKAGE, null));
        assertEquals(addLink(mContext.getString(R.string.monitoring_description_named_vpn,
                                 VPN_PACKAGE_2)),
                     mFooter.getVpnMessage(true, true, null, VPN_PACKAGE_2));
        assertEquals(addLink(mContext.getString(
                                 R.string.monitoring_description_managed_profile_named_vpn,
                                 VPN_PACKAGE_2)),
                     mFooter.getVpnMessage(false, true, null, VPN_PACKAGE_2));
        assertEquals(addLink(mContext.getString(
                                 R.string.monitoring_description_personal_profile_named_vpn,
                                 VPN_PACKAGE)),
                     mFooter.getVpnMessage(false, true, VPN_PACKAGE, null));
    }

    @Test
    public void testConfigSubtitleVisibility() {
        View view = LayoutInflater.from(mContext)
                .inflate(R.layout.quick_settings_footer_dialog, null);

        // Device Management subtitle should be shown when there is Device Management section only
        // Other sections visibility will be set somewhere else so it will not be tested here
        mFooter.configSubtitleVisibility(true, false, false, false, view);
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.device_management_subtitle).getVisibility());

        // If there are multiple sections, all subtitles should be shown
        mFooter.configSubtitleVisibility(true, true, false, false, view);
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.device_management_subtitle).getVisibility());
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.ca_certs_subtitle).getVisibility());

        // If there are multiple sections, all subtitles should be shown
        mFooter.configSubtitleVisibility(true, true, true, true, view);
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.device_management_subtitle).getVisibility());
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.ca_certs_subtitle).getVisibility());
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.network_logging_subtitle).getVisibility());
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.vpn_subtitle).getVisibility());

        // If there are multiple sections, all subtitles should be shown, event if there is no
        // Device Management section
        mFooter.configSubtitleVisibility(false, true, true, true, view);
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.ca_certs_subtitle).getVisibility());
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.network_logging_subtitle).getVisibility());
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.vpn_subtitle).getVisibility());

        // If there is only 1 section, the title should be hidden
        mFooter.configSubtitleVisibility(false, true, false, false, view);
        assertEquals(View.GONE,
                view.findViewById(R.id.ca_certs_subtitle).getVisibility());
        mFooter.configSubtitleVisibility(false, false, true, false, view);
        assertEquals(View.GONE,
                view.findViewById(R.id.network_logging_subtitle).getVisibility());
        mFooter.configSubtitleVisibility(false, false, false, true, view);
        assertEquals(View.GONE,
                view.findViewById(R.id.vpn_subtitle).getVisibility());
    }

    @Test
    public void testNoClickWhenGone() {
        QSTileHost mockHost = mock(QSTileHost.class);
        mFooter.setHostEnvironment(mockHost);
        mFooter.refreshState();

        TestableLooper.get(this).processAllMessages();

        assertFalse(mFooter.hasFooter());
        mFooter.onClick(mFooter.getView());

        // Proxy for dialog being created
        verify(mockHost, never()).collapsePanels();
    }

    private CharSequence addLink(CharSequence description) {
        final SpannableStringBuilder message = new SpannableStringBuilder();
        message.append(description);
        message.append(mContext.getString(R.string.monitoring_description_vpn_settings_separator));
        message.append(mContext.getString(R.string.monitoring_description_vpn_settings),
                mFooter.new VpnSpan(), 0);
        return message;
    }
}
