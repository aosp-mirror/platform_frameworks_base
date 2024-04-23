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

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_DEFAULT;
import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.IdRes;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.common.shared.model.Icon;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.footer.domain.model.SecurityButtonConfig;
import com.android.systemui.res.R;
import com.android.systemui.security.data.model.SecurityModel;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.SecurityController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

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
    private static final String PARENTAL_CONTROLS_LABEL = "Parental Control App";
    private static final ComponentName DEVICE_OWNER_COMPONENT =
            new ComponentName("TestDPC", "Test");
    private static final int DEFAULT_ICON_ID = R.drawable.ic_info_outline;

    private QSSecurityFooterUtils mFooterUtils;
    @Mock
    private SecurityController mSecurityController;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    private TestableLooper mTestableLooper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        Looper looper = mTestableLooper.getLooper();
        Handler mainHandler = new Handler(looper);
        // TODO(b/259908270): remove
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                DevicePolicyManager.ADD_ISFINANCED_DEVICE_FLAG, "true",
                /* makeDefault= */ false);
        when(mUserTracker.getUserInfo()).thenReturn(mock(UserInfo.class));
        mFooterUtils = new QSSecurityFooterUtils(getContext(),
                getContext().getSystemService(DevicePolicyManager.class), mUserTracker,
                mainHandler, mActivityStarter, mSecurityController, looper,
                mDialogTransitionAnimator);

        when(mSecurityController.getDeviceOwnerComponentOnAnyUser())
                .thenReturn(DEVICE_OWNER_COMPONENT);
        when(mSecurityController.isFinancedDevice()).thenReturn(false);
        // TODO(b/259908270): remove
        when(mSecurityController.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_DEFAULT);
    }

    @Nullable
    private SecurityButtonConfig getButtonConfig() {
        SecurityModel securityModel = SecurityModel.create(mSecurityController);
        return mFooterUtils.getButtonConfig(securityModel);
    }

    private void assertIsDefaultIcon(Icon icon) {
        assertIsIconResource(icon, DEFAULT_ICON_ID);
    }

    private void assertIsIconResource(Icon icon, @IdRes int res) {
        assertThat(icon).isInstanceOf(Icon.Resource.class);
        assertEquals(res, ((Icon.Resource) icon).getRes());
    }

    private void assertIsIconDrawable(Icon icon, Drawable drawable) {
        assertThat(icon).isInstanceOf(Icon.Loaded.class);
        assertEquals(drawable, ((Icon.Loaded) icon).getDrawable());
    }

    @Test
    public void testUnmanaged() {
        when(mSecurityController.isDeviceManaged()).thenReturn(false);
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(false);
        assertNull(getButtonConfig());
    }

    @Test
    public void testManagedNoOwnerName() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName()).thenReturn(null);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management),
                     buttonConfig.getText());
        assertIsDefaultIcon(buttonConfig.getIcon());
    }

    @Test
    public void testManagedOwnerName() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_named_management,
                        MANAGING_ORGANIZATION),
                buttonConfig.getText());
        assertIsDefaultIcon(buttonConfig.getIcon());
    }

    @Test
    public void testManagedFinancedDeviceWithOwnerName() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        when(mSecurityController.isFinancedDevice()).thenReturn(true);
        // TODO(b/259908270): remove
        when(mSecurityController.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_FINANCED);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(
                        R.string.quick_settings_financed_disclosure_named_management,
                        MANAGING_ORGANIZATION),
                buttonConfig.getText());
        assertIsDefaultIcon(buttonConfig.getIcon());
    }

    @Test
    public void testManagedDemoMode() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName()).thenReturn(null);
        final UserInfo mockUserInfo = Mockito.mock(UserInfo.class);
        when(mockUserInfo.isDemo()).thenReturn(true);
        when(mUserTracker.getUserInfo()).thenReturn(mockUserInfo);
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.DEVICE_DEMO_MODE, 1);

        assertNull(getButtonConfig());
    }

    @Test
    public void testUntappableView_profileOwnerOfOrgOwnedDevice() {
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(true);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertFalse(buttonConfig.isClickable());
    }

    @Test
    public void testTappableView_profileOwnerOfOrgOwnedDevice_networkLoggingEnabled() {
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);
        when(mSecurityController.isWorkProfileOn()).thenReturn(true);
        when(mSecurityController.hasWorkProfile()).thenReturn(true);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertTrue(buttonConfig.isClickable());
    }

    @Test
    public void testUntappableView_profileOwnerOfOrgOwnedDevice_workProfileOff() {
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);
        when(mSecurityController.isWorkProfileOn()).thenReturn(false);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertFalse(buttonConfig.isClickable());
    }

    @Test
    public void testNetworkLoggingEnabled_deviceOwner() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_monitoring),
                buttonConfig.getText());
        assertIsDefaultIcon(buttonConfig.getIcon());

        // Same situation, but with organization name set
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(
                        R.string.quick_settings_disclosure_named_management_monitoring,
                        MANAGING_ORGANIZATION),
                buttonConfig.getText());
    }

    @Test
    public void testNetworkLoggingEnabled_managedProfileOwner_workProfileOn() {
        when(mSecurityController.hasWorkProfile()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);
        when(mSecurityController.isWorkProfileOn()).thenReturn(true);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(
                        R.string.quick_settings_disclosure_managed_profile_network_activity),
                buttonConfig.getText());
    }

    @Test
    public void testNetworkLoggingEnabled_managedProfileOwner_workProfileOff() {
        when(mSecurityController.hasWorkProfile()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);
        when(mSecurityController.isWorkProfileOn()).thenReturn(false);

        assertNull(getButtonConfig());
    }

    @Test
    public void testManagedCACertsInstalled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.hasCACertInCurrentUser()).thenReturn(true);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_monitoring),
                buttonConfig.getText());
    }

    @Test
    public void testManagedOneVpnEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn(VPN_PACKAGE);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_named_vpn,
                        VPN_PACKAGE),
                buttonConfig.getText());
        assertIsIconResource(buttonConfig.getIcon(), R.drawable.stat_sys_vpn_ic);

        // Same situation, but with organization name set
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(
                        R.string.quick_settings_disclosure_named_management_named_vpn,
                        MANAGING_ORGANIZATION, VPN_PACKAGE),
                buttonConfig.getText());
    }

    @Test
    public void testManagedTwoVpnsEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn(VPN_PACKAGE);
        when(mSecurityController.getWorkProfileVpnName()).thenReturn(VPN_PACKAGE_2);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_vpns),
                     buttonConfig.getText());
        assertIsIconResource(buttonConfig.getIcon(), R.drawable.stat_sys_vpn_ic);

        // Same situation, but with organization name set
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_named_management_vpns,
                                        MANAGING_ORGANIZATION),
                     buttonConfig.getText());
    }

    @Test
    public void testNetworkLoggingAndVpnEnabled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isNetworkLoggingEnabled()).thenReturn(true);
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn("VPN Test App");

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertIsIconResource(buttonConfig.getIcon(), R.drawable.stat_sys_vpn_ic);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_management_monitoring),
                buttonConfig.getText());
    }

    @Test
    public void testWorkProfileCACertsInstalled_workProfileOn() {
        when(mSecurityController.isDeviceManaged()).thenReturn(false);
        when(mSecurityController.hasCACertInWorkProfile()).thenReturn(true);
        when(mSecurityController.isWorkProfileOn()).thenReturn(true);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertIsDefaultIcon(buttonConfig.getIcon());
        assertEquals(mContext.getString(
                             R.string.quick_settings_disclosure_managed_profile_monitoring),
                     buttonConfig.getText());

        // Same situation, but with organization name set
        when(mSecurityController.getWorkProfileOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(
                             R.string.quick_settings_disclosure_named_managed_profile_monitoring,
                             MANAGING_ORGANIZATION),
                     buttonConfig.getText());
    }

    @Test
    public void testWorkProfileCACertsInstalled_workProfileOff() {
        when(mSecurityController.isDeviceManaged()).thenReturn(false);
        when(mSecurityController.hasCACertInWorkProfile()).thenReturn(true);
        when(mSecurityController.isWorkProfileOn()).thenReturn(false);

        assertNull(getButtonConfig());
    }

    @Test
    public void testCACertsInstalled() {
        when(mSecurityController.isDeviceManaged()).thenReturn(false);
        when(mSecurityController.hasCACertInCurrentUser()).thenReturn(true);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertIsDefaultIcon(buttonConfig.getIcon());
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_monitoring),
                     buttonConfig.getText());
    }

    @Test
    public void testTwoVpnsEnabled() {
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn(VPN_PACKAGE);
        when(mSecurityController.getWorkProfileVpnName()).thenReturn(VPN_PACKAGE_2);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertIsIconResource(buttonConfig.getIcon(), R.drawable.stat_sys_vpn_ic);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_vpns),
                     buttonConfig.getText());
    }

    @Test
    public void testWorkProfileVpnEnabled_workProfileOn() {
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getWorkProfileVpnName()).thenReturn(VPN_PACKAGE_2);
        when(mSecurityController.isWorkProfileOn()).thenReturn(true);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertIsIconResource(buttonConfig.getIcon(), R.drawable.stat_sys_vpn_ic);
        assertEquals(mContext.getString(
                             R.string.quick_settings_disclosure_managed_profile_named_vpn,
                             VPN_PACKAGE_2),
                     buttonConfig.getText());
    }

    @Test
    public void testWorkProfileVpnEnabled_workProfileOff() {
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getWorkProfileVpnName()).thenReturn(VPN_PACKAGE_2);
        when(mSecurityController.isWorkProfileOn()).thenReturn(false);

        assertNull(getButtonConfig());
    }

    @Test
    public void testProfileOwnerOfOrganizationOwnedDeviceNoName() {
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(true);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(
                R.string.quick_settings_disclosure_management),
                buttonConfig.getText());
    }

    @Test
    public void testProfileOwnerOfOrganizationOwnedDeviceWithName() {
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(true);
        when(mSecurityController.getWorkProfileOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(
                R.string.quick_settings_disclosure_named_management,
                MANAGING_ORGANIZATION),
                buttonConfig.getText());
    }

    @Test
    public void testVpnEnabled() {
        when(mSecurityController.isVpnEnabled()).thenReturn(true);
        when(mSecurityController.getPrimaryVpnName()).thenReturn(VPN_PACKAGE);

        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertIsIconResource(buttonConfig.getIcon(), R.drawable.stat_sys_vpn_ic);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_named_vpn,
                                        VPN_PACKAGE),
                     buttonConfig.getText());

        when(mSecurityController.hasWorkProfile()).thenReturn(true);
        buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(
                             R.string.quick_settings_disclosure_personal_profile_named_vpn,
                             VPN_PACKAGE),
                     buttonConfig.getText());
    }

    @Test
    public void testGetManagementTitleForNonFinancedDevice() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);

        assertEquals(mContext.getString(R.string.monitoring_title_device_owned),
                mFooterUtils.getManagementTitle(MANAGING_ORGANIZATION));
    }

    @Test
    public void testGetManagementTitleForFinancedDevice() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isFinancedDevice()).thenReturn(true);
        // TODO(b/259908270): remove
        when(mSecurityController.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_FINANCED);

        assertEquals(mContext.getString(R.string.monitoring_title_financed_device,
                MANAGING_ORGANIZATION),
                mFooterUtils.getManagementTitle(MANAGING_ORGANIZATION));
    }

    @Test
    public void testGetManagementMessage_noManagement() {
        assertEquals(null, mFooterUtils.getManagementMessage(
                /* isDeviceManaged= */ false, MANAGING_ORGANIZATION));
    }

    @Test
    public void testGetManagementMessage_deviceOwner() {
        assertEquals(mContext.getString(R.string.monitoring_description_named_management,
                                        MANAGING_ORGANIZATION),
                mFooterUtils.getManagementMessage(
                             /* isDeviceManaged= */ true, MANAGING_ORGANIZATION));
        assertEquals(mContext.getString(R.string.monitoring_description_management),
                mFooterUtils.getManagementMessage(
                             /* isDeviceManaged= */ true,
                             /* organizationName= */ null));
    }

    @Test
    public void testGetManagementMessage_deviceOwner_asFinancedDevice() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.isFinancedDevice()).thenReturn(true);
        // TODO(b/259908270): remove
        when(mSecurityController.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_FINANCED);

        assertEquals(mContext.getString(R.string.monitoring_financed_description_named_management,
                MANAGING_ORGANIZATION, MANAGING_ORGANIZATION),
                mFooterUtils.getManagementMessage(
                        /* isDeviceManaged= */ true, MANAGING_ORGANIZATION));
    }

    @Test
    public void testGetCaCertsMessage() {
        assertEquals(null, mFooterUtils.getCaCertsMessage(true, false, false));
        assertEquals(null, mFooterUtils.getCaCertsMessage(false, false, false));
        assertEquals(mContext.getString(R.string.monitoring_description_management_ca_certificate),
                mFooterUtils.getCaCertsMessage(true, true, true));
        assertEquals(mContext.getString(R.string.monitoring_description_management_ca_certificate),
                mFooterUtils.getCaCertsMessage(true, false, true));
        assertEquals(mContext.getString(
                         R.string.monitoring_description_managed_profile_ca_certificate),
                mFooterUtils.getCaCertsMessage(false, false, true));
        assertEquals(mContext.getString(
                         R.string.monitoring_description_ca_certificate),
                mFooterUtils.getCaCertsMessage(false, true, false));
    }

    @Test
    public void testGetNetworkLoggingMessage() {
        // Test network logging message on a device with a device owner.
        // Network traffic may be monitored on the device.
        assertEquals(null, mFooterUtils.getNetworkLoggingMessage(true, false));
        assertEquals(mContext.getString(R.string.monitoring_description_management_network_logging),
                mFooterUtils.getNetworkLoggingMessage(true, true));

        // Test network logging message on a device with a managed profile owner
        // Network traffic may be monitored on the work profile.
        assertEquals(null, mFooterUtils.getNetworkLoggingMessage(false, false));
        assertEquals(
                mContext.getString(R.string.monitoring_description_managed_profile_network_logging),
                mFooterUtils.getNetworkLoggingMessage(false, true));
    }

    @Test
    public void testGetVpnMessage() {
        assertEquals(null, mFooterUtils.getVpnMessage(true, true, null, null));
        assertEquals(addLink(mContext.getString(R.string.monitoring_description_two_named_vpns,
                                 VPN_PACKAGE, VPN_PACKAGE_2)),
                mFooterUtils.getVpnMessage(true, true, VPN_PACKAGE, VPN_PACKAGE_2));
        assertEquals(addLink(mContext.getString(R.string.monitoring_description_two_named_vpns,
                                 VPN_PACKAGE, VPN_PACKAGE_2)),
                mFooterUtils.getVpnMessage(false, true, VPN_PACKAGE, VPN_PACKAGE_2));
        assertEquals(addLink(mContext.getString(
                R.string.monitoring_description_managed_device_named_vpn, VPN_PACKAGE)),
                mFooterUtils.getVpnMessage(true, false, VPN_PACKAGE, null));
        assertEquals(addLink(mContext.getString(R.string.monitoring_description_named_vpn,
                                 VPN_PACKAGE)),
                mFooterUtils.getVpnMessage(false, false, VPN_PACKAGE, null));
        assertEquals(addLink(mContext.getString(
                R.string.monitoring_description_managed_device_named_vpn, VPN_PACKAGE_2)),
                mFooterUtils.getVpnMessage(true, true, null, VPN_PACKAGE_2));
        assertEquals(addLink(mContext.getString(
                                 R.string.monitoring_description_managed_profile_named_vpn,
                                 VPN_PACKAGE_2)),
                mFooterUtils.getVpnMessage(false, true, null, VPN_PACKAGE_2));
        assertEquals(addLink(mContext.getString(
                                 R.string.monitoring_description_personal_profile_named_vpn,
                                 VPN_PACKAGE)),
                mFooterUtils.getVpnMessage(false, true, VPN_PACKAGE, null));
    }

    @Test
    public void testConfigSubtitleVisibility() {
        View view = LayoutInflater.from(mContext)
                .inflate(R.layout.quick_settings_footer_dialog, null);

        // Device Management subtitle should be shown when there is Device Management section only
        // Other sections visibility will be set somewhere else so it will not be tested here
        mFooterUtils.configSubtitleVisibility(true, false, false, false, view);
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.device_management_subtitle).getVisibility());

        // If there are multiple sections, all subtitles should be shown
        mFooterUtils.configSubtitleVisibility(true, true, false, false, view);
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.device_management_subtitle).getVisibility());
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.ca_certs_subtitle).getVisibility());

        // If there are multiple sections, all subtitles should be shown
        mFooterUtils.configSubtitleVisibility(true, true, true, true, view);
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
        mFooterUtils.configSubtitleVisibility(false, true, true, true, view);
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.ca_certs_subtitle).getVisibility());
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.network_logging_subtitle).getVisibility());
        assertEquals(View.VISIBLE,
                view.findViewById(R.id.vpn_subtitle).getVisibility());

        // If there is only 1 section, the title should be hidden
        mFooterUtils.configSubtitleVisibility(false, true, false, false, view);
        assertEquals(View.GONE,
                view.findViewById(R.id.ca_certs_subtitle).getVisibility());
        mFooterUtils.configSubtitleVisibility(false, false, true, false, view);
        assertEquals(View.GONE,
                view.findViewById(R.id.network_logging_subtitle).getVisibility());
        mFooterUtils.configSubtitleVisibility(false, false, false, true, view);
        assertEquals(View.GONE,
                view.findViewById(R.id.vpn_subtitle).getVisibility());
    }

    @Test
    public void testParentalControls() {
        // Make sure the security footer is visible, so that the images are updated.
        when(mSecurityController.isProfileOwnerOfOrganizationOwnedDevice()).thenReturn(true);
        when(mSecurityController.isParentalControlsEnabled()).thenReturn(true);

        // We use the default icon when there is no admin icon.
        when(mSecurityController.getIcon(any())).thenReturn(null);
        SecurityButtonConfig buttonConfig = getButtonConfig();
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_parental_controls),
                buttonConfig.getText());
        assertIsDefaultIcon(buttonConfig.getIcon());

        Drawable testDrawable = new VectorDrawable();
        when(mSecurityController.getIcon(any())).thenReturn(testDrawable);
        assertNotNull(mSecurityController.getIcon(null));

        buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertEquals(mContext.getString(R.string.quick_settings_disclosure_parental_controls),
                buttonConfig.getText());
        assertIsIconDrawable(buttonConfig.getIcon(), testDrawable);

        // Ensure the primary icon is back to default after parental controls are gone
        when(mSecurityController.isParentalControlsEnabled()).thenReturn(false);
        buttonConfig = getButtonConfig();
        assertNotNull(buttonConfig);
        assertIsDefaultIcon(buttonConfig.getIcon());
    }

    @Test
    public void testParentalControlsDialog() {
        when(mSecurityController.isParentalControlsEnabled()).thenReturn(true);
        when(mSecurityController.getLabel(any())).thenReturn(PARENTAL_CONTROLS_LABEL);

        View view = mFooterUtils.createDialogView(getContext());
        TextView textView = (TextView) view.findViewById(R.id.parental_controls_title);
        assertEquals(PARENTAL_CONTROLS_LABEL, textView.getText());
    }

    @Test
    public void testCreateDialogViewForFinancedDevice() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        when(mSecurityController.isFinancedDevice()).thenReturn(true);
        // TODO(b/259908270): remove
        when(mSecurityController.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_FINANCED);

        View view = mFooterUtils.createDialogView(getContext());

        TextView managementSubtitle = view.findViewById(R.id.device_management_subtitle);
        assertEquals(View.VISIBLE, managementSubtitle.getVisibility());
        assertEquals(mContext.getString(R.string.monitoring_title_financed_device,
                MANAGING_ORGANIZATION), managementSubtitle.getText());
        TextView managementMessage = view.findViewById(R.id.device_management_warning);
        assertEquals(View.VISIBLE, managementMessage.getVisibility());
        assertEquals(mContext.getString(R.string.monitoring_financed_description_named_management,
                MANAGING_ORGANIZATION, MANAGING_ORGANIZATION), managementMessage.getText());
        assertEquals(mContext.getString(R.string.monitoring_button_view_policies),
                mFooterUtils.getSettingsButton());
    }

    @Test
    public void testFinancedDeviceUsesSettingsButtonText() {
        when(mSecurityController.isDeviceManaged()).thenReturn(true);
        when(mSecurityController.getDeviceOwnerOrganizationName())
                .thenReturn(MANAGING_ORGANIZATION);
        when(mSecurityController.isFinancedDevice()).thenReturn(true);
        // TODO(b/259908270): remove
        when(mSecurityController.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_FINANCED);

        Expandable expandable = mock(Expandable.class);
        when(expandable.dialogTransitionController(any())).thenReturn(
                mock(DialogTransitionAnimator.Controller.class));
        mFooterUtils.showDeviceMonitoringDialog(getContext(), expandable);
        ArgumentCaptor<AlertDialog> dialogCaptor = ArgumentCaptor.forClass(AlertDialog.class);

        mTestableLooper.processAllMessages();
        verify(mDialogTransitionAnimator).show(dialogCaptor.capture(), any());

        AlertDialog dialog = dialogCaptor.getValue();
        dialog.create();

        assertEquals(mFooterUtils.getSettingsButton(),
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText());

        dialog.dismiss();
    }

    private CharSequence addLink(CharSequence description) {
        final SpannableStringBuilder message = new SpannableStringBuilder();
        message.append(description);
        message.append(mContext.getString(R.string.monitoring_description_vpn_settings_separator));
        message.append(mContext.getString(R.string.monitoring_description_vpn_settings),
                mFooterUtils.new VpnSpan(), 0);
        return message;
    }
}
