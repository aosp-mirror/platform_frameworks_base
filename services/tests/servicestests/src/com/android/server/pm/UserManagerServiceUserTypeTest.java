/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.UserInfo.FLAG_DEMO;
import static android.content.pm.UserInfo.FLAG_EPHEMERAL;
import static android.content.pm.UserInfo.FLAG_FULL;
import static android.content.pm.UserInfo.FLAG_GUEST;
import static android.content.pm.UserInfo.FLAG_MANAGED_PROFILE;
import static android.content.pm.UserInfo.FLAG_PROFILE;
import static android.content.pm.UserInfo.FLAG_RESTRICTED;
import static android.content.pm.UserInfo.FLAG_SYSTEM;

import static com.android.server.pm.UserTypeDetails.UNLIMITED_NUMBER_OF_USERS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.servicestests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Tests for {@link UserTypeDetails} and {@link UserTypeFactory}.
 *
 * <p>Run with: atest UserManagerServiceUserTypeTest
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@MediumTest
public class UserManagerServiceUserTypeTest {

    private Resources mResources;

    @Before
    public void setup() {
        mResources = InstrumentationRegistry.getTargetContext().getResources();
    }
    @Test
    public void testUserTypeBuilder_createUserType() {
        final Bundle restrictions = makeRestrictionsBundle("r1", "r2");
        final Bundle systemSettings = makeSettingsBundle("s1", "s2");
        final Bundle secureSettings = makeSettingsBundle("secure_s1", "secure_s2");
        final List<DefaultCrossProfileIntentFilter> filters = List.of(
                new DefaultCrossProfileIntentFilter.Builder(
                DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                /* flags= */0,
                /* letsPersonalDataIntoProfile= */false).build());
        final UserProperties.Builder userProps = new UserProperties.Builder()
                .setShowInLauncher(17)
                .setUseParentsContacts(true)
                .setCrossProfileIntentFilterAccessControl(10)
                .setCrossProfileIntentResolutionStrategy(1)
                .setMediaSharedWithParent(true)
                .setCredentialShareableWithParent(false)
                .setAuthAlwaysRequiredToDisableQuietMode(true)
                .setAllowStoppingUserWithDelayedLocking(true)
                .setShowInSettings(900)
                .setShowInSharingSurfaces(20)
                .setShowInQuietMode(30)
                .setInheritDevicePolicy(340)
                .setDeleteAppWithParent(true)
                .setAlwaysVisible(true)
                .setCrossProfileContentSharingStrategy(1)
                .setProfileApiVisibility(34)
                .setItemsRestrictedOnHomeScreen(true);

        final UserTypeDetails type = new UserTypeDetails.Builder()
                .setName("a.name")
                .setEnabled(1)
                .setMaxAllowed(21)
                .setBaseType(FLAG_PROFILE)
                .setDefaultUserInfoPropertyFlags(FLAG_EPHEMERAL)
                .setBadgeLabels(23, 24, 25)
                .setBadgeColors(26, 27)
                .setIconBadge(28)
                .setBadgePlain(29)
                .setBadgeNoBackground(30)
                .setMaxAllowedPerParent(32)
                .setStatusBarIcon(33)
                .setLabels(34, 35, 36)
                .setDefaultRestrictions(restrictions)
                .setDefaultSystemSettings(systemSettings)
                .setDefaultSecureSettings(secureSettings)
                .setDefaultCrossProfileIntentFilters(filters)
                .setDefaultUserProperties(userProps)
                .createUserTypeDetails();

        assertEquals("a.name", type.getName());
        assertTrue(type.isEnabled());
        assertEquals(21, type.getMaxAllowed());
        assertEquals(FLAG_PROFILE | FLAG_EPHEMERAL, type.getDefaultUserInfoFlags());
        assertEquals(28, type.getIconBadge());
        assertEquals(29, type.getBadgePlain());
        assertEquals(30, type.getBadgeNoBackground());
        assertEquals(32, type.getMaxAllowedPerParent());
        assertEquals(33, type.getStatusBarIcon());
        assertEquals(34, type.getLabel(0));
        assertEquals(35, type.getLabel(1));
        assertEquals(36, type.getLabel(2));

        assertTrue(UserRestrictionsUtils.areEqual(restrictions, type.getDefaultRestrictions()));
        assertNotSame(restrictions, type.getDefaultRestrictions());

        assertNotSame(systemSettings, type.getDefaultSystemSettings());
        assertEquals(systemSettings.size(), type.getDefaultSystemSettings().size());
        for (String key : systemSettings.keySet()) {
            assertEquals(
                    systemSettings.getString(key),
                    type.getDefaultSystemSettings().getString(key));
        }

        assertNotSame(secureSettings, type.getDefaultSecureSettings());
        assertEquals(secureSettings.size(), type.getDefaultSecureSettings().size());
        for (String key : secureSettings.keySet()) {
            assertEquals(
                    secureSettings.getString(key),
                    type.getDefaultSecureSettings().getString(key));
        }

        assertNotSame(filters, type.getDefaultCrossProfileIntentFilters());
        assertEquals(filters.size(), type.getDefaultCrossProfileIntentFilters().size());
        for (int i = 0; i < filters.size(); i++) {
            assertEquals(filters.get(i), type.getDefaultCrossProfileIntentFilters().get(i));
        }

        assertEquals(17, type.getDefaultUserPropertiesReference().getShowInLauncher());
        assertTrue(type.getDefaultUserPropertiesReference().getUseParentsContacts());
        assertEquals(10, type.getDefaultUserPropertiesReference()
                .getCrossProfileIntentFilterAccessControl());
        assertEquals(1, type.getDefaultUserPropertiesReference()
                .getCrossProfileIntentResolutionStrategy());
        assertTrue(type.getDefaultUserPropertiesReference().isMediaSharedWithParent());
        assertFalse(type.getDefaultUserPropertiesReference().isCredentialShareableWithParent());
        assertTrue(type.getDefaultUserPropertiesReference()
                .isAuthAlwaysRequiredToDisableQuietMode());
        assertTrue(type.getDefaultUserPropertiesReference()
                .getAllowStoppingUserWithDelayedLocking());
        assertEquals(900, type.getDefaultUserPropertiesReference().getShowInSettings());
        assertEquals(20, type.getDefaultUserPropertiesReference().getShowInSharingSurfaces());
        assertEquals(30,
                type.getDefaultUserPropertiesReference().getShowInQuietMode());
        assertEquals(340, type.getDefaultUserPropertiesReference()
                .getInheritDevicePolicy());
        assertTrue(type.getDefaultUserPropertiesReference().getDeleteAppWithParent());
        assertTrue(type.getDefaultUserPropertiesReference().getAlwaysVisible());
        assertEquals(1, type.getDefaultUserPropertiesReference()
                .getCrossProfileContentSharingStrategy());
        assertEquals(34, type.getDefaultUserPropertiesReference().getProfileApiVisibility());
        assertTrue(type.getDefaultUserPropertiesReference().areItemsRestrictedOnHomeScreen());

        assertEquals(23, type.getBadgeLabel(0));
        assertEquals(24, type.getBadgeLabel(1));
        assertEquals(25, type.getBadgeLabel(2));
        assertEquals(25, type.getBadgeLabel(3));
        assertEquals(25, type.getBadgeLabel(4));
        assertEquals(Resources.ID_NULL, type.getBadgeLabel(-1));

        assertEquals(26, type.getBadgeColor(0));
        assertEquals(27, type.getBadgeColor(1));
        assertEquals(27, type.getBadgeColor(2));
        assertEquals(27, type.getBadgeColor(3));
        assertEquals(Resources.ID_NULL, type.getBadgeColor(-100));

        assertTrue(type.hasBadge());
    }

    @Test
    public void testUserTypeBuilder_defaults() {
        UserTypeDetails type = new UserTypeDetails.Builder()
                .setName("name") // Required (no default allowed)
                .setBaseType(FLAG_FULL) // Required (no default allowed)
                .createUserTypeDetails();

        assertTrue(type.isEnabled());
        assertEquals(UNLIMITED_NUMBER_OF_USERS, type.getMaxAllowed());
        assertEquals(UNLIMITED_NUMBER_OF_USERS, type.getMaxAllowedPerParent());
        assertEquals(FLAG_FULL, type.getDefaultUserInfoFlags());
        assertEquals(Resources.ID_NULL, type.getIconBadge());
        assertEquals(Resources.ID_NULL, type.getBadgePlain());
        assertEquals(Resources.ID_NULL, type.getBadgeNoBackground());
        assertEquals(Resources.ID_NULL, type.getStatusBarIcon());
        assertEquals(Resources.ID_NULL, type.getBadgeLabel(0));
        assertEquals(Resources.ID_NULL, type.getBadgeColor(0));
        assertEquals(Resources.ID_NULL, type.getLabel(0));
        assertTrue(type.getDefaultRestrictions().isEmpty());
        assertTrue(type.getDefaultSystemSettings().isEmpty());
        assertTrue(type.getDefaultSecureSettings().isEmpty());
        assertTrue(type.getDefaultCrossProfileIntentFilters().isEmpty());

        final UserProperties props = type.getDefaultUserPropertiesReference();
        assertNotNull(props);
        assertFalse(props.getStartWithParent());
        assertFalse(props.getUseParentsContacts());
        assertEquals(UserProperties.CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_ALL,
                props.getCrossProfileIntentFilterAccessControl());
        assertEquals(UserProperties.SHOW_IN_LAUNCHER_WITH_PARENT, props.getShowInLauncher());
        assertEquals(UserProperties.CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_DEFAULT,
                props.getCrossProfileIntentResolutionStrategy());
        assertFalse(props.isMediaSharedWithParent());
        assertFalse(props.isCredentialShareableWithParent());
        assertFalse(props.getDeleteAppWithParent());
        assertFalse(props.getAlwaysVisible());
        assertEquals(UserProperties.SHOW_IN_LAUNCHER_SEPARATE, props.getShowInSharingSurfaces());
        assertEquals(UserProperties.SHOW_IN_QUIET_MODE_PAUSED,
                props.getShowInQuietMode());
        assertEquals(UserProperties.CROSS_PROFILE_CONTENT_SHARING_NO_DELEGATION,
                props.getCrossProfileContentSharingStrategy());
        assertEquals(UserProperties.PROFILE_API_VISIBILITY_VISIBLE,
                props.getProfileApiVisibility());

        assertFalse(type.hasBadge());
    }

    @Test
    public void testUserTypeBuilder_nameIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserTypeDetails.Builder()
                        .setMaxAllowed(21)
                        .setBaseType(FLAG_FULL)
                        .createUserTypeDetails());
    }

    @Test
    public void testUserTypeBuilder_baseTypeIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserTypeDetails.Builder()
                        .setName("name")
                        .createUserTypeDetails());
    }

    @Test
    public void testUserTypeBuilder_colorIsRequiredIfBadged() {
        assertThrows(IllegalArgumentException.class,
                () -> getMinimalBuilder()
                        .setIconBadge(1)
                        .setBadgeLabels(2)
                        .createUserTypeDetails());
    }

    @Test
    public void testUserTypeBuilder_badgeLabelIsRequiredIfBadged() {
        assertThrows(IllegalArgumentException.class,
                () -> getMinimalBuilder()
                        .setIconBadge(1)
                        .setBadgeColors(2)
                        .createUserTypeDetails());
    }

    @Test
    public void testCheckUserTypeConsistency() {
        assertTrue(UserManagerService.checkUserTypeConsistency(FLAG_GUEST));
        assertTrue(UserManagerService.checkUserTypeConsistency(FLAG_GUEST | FLAG_EPHEMERAL));
        assertTrue(UserManagerService.checkUserTypeConsistency(FLAG_PROFILE));

        assertFalse(UserManagerService.checkUserTypeConsistency(FLAG_DEMO | FLAG_RESTRICTED));
        assertFalse(UserManagerService.checkUserTypeConsistency(FLAG_PROFILE | FLAG_SYSTEM));
        assertFalse(UserManagerService.checkUserTypeConsistency(FLAG_PROFILE | FLAG_FULL));
    }

    @Test
    public void testGetDefaultUserType() {
        // Simple example.
        assertEquals(UserManager.USER_TYPE_FULL_RESTRICTED,
                UserInfo.getDefaultUserType(FLAG_RESTRICTED));

        // Type plus a non-type flag.
        assertEquals(UserManager.USER_TYPE_FULL_GUEST,
                UserInfo.getDefaultUserType(FLAG_GUEST | FLAG_EPHEMERAL));

        // Two types, which is illegal.
        assertThrows(IllegalArgumentException.class,
                () -> UserInfo.getDefaultUserType(FLAG_MANAGED_PROFILE | FLAG_GUEST));

        // No type, which defaults to {@link UserManager#USER_TYPE_FULL_SECONDARY}.
        assertEquals(UserManager.USER_TYPE_FULL_SECONDARY,
                UserInfo.getDefaultUserType(FLAG_EPHEMERAL));
    }

    /** Tests {@link UserTypeFactory#customizeBuilders} for a reasonable xml file. */
    @Test
    public void testUserTypeFactoryCustomize_profile() throws Exception {
        final String userTypeAosp1 = "android.test.1"; // Profile user that is not customized
        final String userTypeAosp2 = "android.test.2"; // Profile user that is customized
        final String userTypeOem1 = "custom.test.1"; // Custom-defined profile

        // Mock some "AOSP defaults".
        final Bundle restrictions = makeRestrictionsBundle("no_config_vpn", "no_config_tethering");
        final UserProperties.Builder props = new UserProperties.Builder()
                .setShowInLauncher(19)
                .setStartWithParent(true)
                .setUseParentsContacts(true)
                .setCrossProfileIntentFilterAccessControl(10)
                .setCrossProfileIntentResolutionStrategy(1)
                .setMediaSharedWithParent(false)
                .setCredentialShareableWithParent(true)
                .setAuthAlwaysRequiredToDisableQuietMode(false)
                .setAllowStoppingUserWithDelayedLocking(false)
                .setShowInSettings(20)
                .setInheritDevicePolicy(21)
                .setShowInSharingSurfaces(22)
                .setShowInQuietMode(24)
                .setDeleteAppWithParent(true)
                .setAlwaysVisible(false)
                .setCrossProfileContentSharingStrategy(1)
                .setProfileApiVisibility(36)
                .setItemsRestrictedOnHomeScreen(false);

        final ArrayMap<String, UserTypeDetails.Builder> builders = new ArrayMap<>();
        builders.put(userTypeAosp1, new UserTypeDetails.Builder()
                .setName(userTypeAosp1)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(31)
                .setDefaultRestrictions(restrictions)
                .setDefaultUserProperties(props));
        builders.put(userTypeAosp2, new UserTypeDetails.Builder()
                .setName(userTypeAosp1)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(32)
                .setIconBadge(401)
                .setBadgeColors(402, 403, 404)
                .setDefaultRestrictions(restrictions)
                .setDefaultUserProperties(props));

        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_profile);
        UserTypeFactory.customizeBuilders(builders, parser);

        // userTypeAosp1 should not be modified.
        UserTypeDetails aospType = builders.get(userTypeAosp1).createUserTypeDetails();
        assertEquals(31, aospType.getMaxAllowedPerParent());
        assertEquals(Resources.ID_NULL, aospType.getIconBadge());
        assertTrue(UserRestrictionsUtils.areEqual(restrictions, aospType.getDefaultRestrictions()));
        assertEquals(19, aospType.getDefaultUserPropertiesReference().getShowInLauncher());
        assertEquals(10, aospType.getDefaultUserPropertiesReference()
                .getCrossProfileIntentFilterAccessControl());
        assertEquals(1, aospType.getDefaultUserPropertiesReference()
                .getCrossProfileIntentResolutionStrategy());
        assertTrue(aospType.getDefaultUserPropertiesReference().getStartWithParent());
        assertTrue(aospType.getDefaultUserPropertiesReference()
                .getUseParentsContacts());
        assertFalse(aospType.getDefaultUserPropertiesReference().isMediaSharedWithParent());
        assertTrue(aospType.getDefaultUserPropertiesReference()
                .isCredentialShareableWithParent());
        assertFalse(aospType.getDefaultUserPropertiesReference()
                .isAuthAlwaysRequiredToDisableQuietMode());
        assertFalse(aospType.getDefaultUserPropertiesReference()
                .getAllowStoppingUserWithDelayedLocking());
        assertEquals(20, aospType.getDefaultUserPropertiesReference().getShowInSettings());
        assertEquals(21, aospType.getDefaultUserPropertiesReference()
                .getInheritDevicePolicy());
        assertEquals(22, aospType.getDefaultUserPropertiesReference().getShowInSharingSurfaces());
        assertEquals(24,
                aospType.getDefaultUserPropertiesReference().getShowInQuietMode());
        assertTrue(aospType.getDefaultUserPropertiesReference().getDeleteAppWithParent());
        assertFalse(aospType.getDefaultUserPropertiesReference().getAlwaysVisible());
        assertEquals(1, aospType.getDefaultUserPropertiesReference()
                .getCrossProfileContentSharingStrategy());
        assertEquals(36, aospType.getDefaultUserPropertiesReference().getProfileApiVisibility());
        assertFalse(aospType.getDefaultUserPropertiesReference().areItemsRestrictedOnHomeScreen());

        // userTypeAosp2 should be modified.
        aospType = builders.get(userTypeAosp2).createUserTypeDetails();
        assertEquals(12, aospType.getMaxAllowedPerParent());
        assertEquals(com.android.internal.R.drawable.ic_corp_icon_badge_case,
                aospType.getIconBadge());
        assertEquals(Resources.ID_NULL, aospType.getBadgePlain()); // No resId for 'garbage'
        assertEquals(com.android.internal.R.drawable.ic_corp_badge_no_background,
                aospType.getBadgeNoBackground());
        assertEquals(com.android.internal.R.drawable.ic_test_badge_experiment,
                aospType.getStatusBarIcon());
        assertEquals(com.android.internal.R.string.managed_profile_label_badge,
                aospType.getBadgeLabel(0));
        assertEquals(com.android.internal.R.string.managed_profile_label_badge_2,
                aospType.getBadgeLabel(1));
        assertEquals(com.android.internal.R.string.managed_profile_label_badge_2,
                aospType.getBadgeLabel(2));
        assertEquals(com.android.internal.R.string.managed_profile_label_badge_2,
                aospType.getBadgeLabel(3));
        assertEquals(com.android.internal.R.color.profile_badge_1,
                aospType.getBadgeColor(0));
        assertEquals(com.android.internal.R.color.profile_badge_2,
                aospType.getBadgeColor(1));
        assertEquals(com.android.internal.R.color.profile_badge_2,
                aospType.getBadgeColor(2));
        assertEquals(com.android.internal.R.color.profile_badge_2,
                aospType.getBadgeColor(3));
        assertTrue(UserRestrictionsUtils.areEqual(
                makeRestrictionsBundle("no_remove_user", "no_bluetooth"),
                aospType.getDefaultRestrictions()));
        assertEquals(2020, aospType.getDefaultUserPropertiesReference().getShowInLauncher());
        assertEquals(20, aospType.getDefaultUserPropertiesReference()
                .getCrossProfileIntentFilterAccessControl());
        assertEquals(0, aospType.getDefaultUserPropertiesReference()
                .getCrossProfileIntentResolutionStrategy());
        assertFalse(aospType.getDefaultUserPropertiesReference().getStartWithParent());
        assertFalse(aospType.getDefaultUserPropertiesReference()
                .getUseParentsContacts());
        assertTrue(aospType.getDefaultUserPropertiesReference().isMediaSharedWithParent());
        assertFalse(aospType.getDefaultUserPropertiesReference()
                .isCredentialShareableWithParent());
        assertTrue(aospType.getDefaultUserPropertiesReference()
                .isAuthAlwaysRequiredToDisableQuietMode());
        assertTrue(aospType.getDefaultUserPropertiesReference()
                .getAllowStoppingUserWithDelayedLocking());
        assertEquals(23, aospType.getDefaultUserPropertiesReference().getShowInSettings());
        assertEquals(22,
                aospType.getDefaultUserPropertiesReference().getShowInSharingSurfaces());
        assertEquals(24,
                aospType.getDefaultUserPropertiesReference().getShowInQuietMode());
        assertEquals(450, aospType.getDefaultUserPropertiesReference()
                .getInheritDevicePolicy());
        assertFalse(aospType.getDefaultUserPropertiesReference().getDeleteAppWithParent());
        assertTrue(aospType.getDefaultUserPropertiesReference().getAlwaysVisible());
        assertEquals(0, aospType.getDefaultUserPropertiesReference()
                .getCrossProfileContentSharingStrategy());
        assertEquals(36, aospType.getDefaultUserPropertiesReference().getProfileApiVisibility());
        assertTrue(aospType.getDefaultUserPropertiesReference().areItemsRestrictedOnHomeScreen());

        // userTypeOem1 should be created.
        UserTypeDetails.Builder customType = builders.get(userTypeOem1);
        assertNotNull(customType);
        assertEquals(14, customType.createUserTypeDetails().getMaxAllowedPerParent());
    }

    /** Tests {@link UserTypeFactory#customizeBuilders} for customizing a FULL user. */
    @Test
    public void testUserTypeFactoryCustomize_full() throws Exception {
        final String userTypeFull = "android.test.1";

        // Mock "AOSP default".
        final Bundle restrictions = makeRestrictionsBundle("no_config_vpn", "no_config_tethering");
        final ArrayMap<String, UserTypeDetails.Builder> builders = new ArrayMap<>();
        builders.put(userTypeFull, new UserTypeDetails.Builder()
                .setName(userTypeFull)
                .setBaseType(FLAG_FULL)
                .setEnabled(0)
                .setDefaultRestrictions(restrictions));

        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_full);
        UserTypeFactory.customizeBuilders(builders, parser);

        UserTypeDetails details = builders.get(userTypeFull).createUserTypeDetails();
        assertEquals(UNLIMITED_NUMBER_OF_USERS, details.getMaxAllowedPerParent());
        assertFalse(details.isEnabled());
        assertEquals(17, details.getMaxAllowed());
        assertTrue(UserRestrictionsUtils.areEqual(
                makeRestrictionsBundle("no_remove_user", "no_bluetooth"),
                details.getDefaultRestrictions()));
        assertEquals(Resources.ID_NULL, details.getBadgeColor(0));
    }

    /**
     * Tests {@link UserTypeFactory#customizeBuilders} when custom user type deletes the
     * badge-colors and restrictions.
     */
    @Test
    public void testUserTypeFactoryCustomize_eraseArray() throws Exception {
        final String typeName = "android.test";

        final ArrayMap<String, UserTypeDetails.Builder> builders = new ArrayMap<>();
        builders.put(typeName, new UserTypeDetails.Builder()
                .setName(typeName)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(1)
                .setBadgeColors(501, 502)
                .setDefaultRestrictions(makeRestrictionsBundle("r1")));

        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_eraseArray);
        UserTypeFactory.customizeBuilders(builders, parser);

        UserTypeDetails typeDetails =  builders.get(typeName).createUserTypeDetails();
        assertEquals(2, typeDetails.getMaxAllowedPerParent());
        assertEquals(Resources.ID_NULL, typeDetails.getBadgeColor(0));
        assertEquals(Resources.ID_NULL, typeDetails.getBadgeColor(1));
        assertTrue(typeDetails.getDefaultRestrictions().isEmpty());
    }

    /** Tests {@link UserTypeFactory#customizeBuilders} when custom user type has illegal name. */
    @Test
    public void testUserTypeFactoryCustomize_illegalOemName() throws Exception {
        final String userTypeAosp = "android.aosp.legal";
        final String userTypeOem = "android.oem.illegal.name"; // Custom-defined profile

        final ArrayMap<String, UserTypeDetails.Builder> builders = new ArrayMap<>();
        builders.put(userTypeAosp, new UserTypeDetails.Builder()
                .setName(userTypeAosp)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(21));

        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_illegalOemName);

        // parser is illegal because non-AOSP user types cannot be prefixed with "android.".
        assertThrows(IllegalArgumentException.class,
                () -> UserTypeFactory.customizeBuilders(builders, parser));
    }

    /**
     * Tests {@link UserTypeFactory#customizeBuilders} when illegally customizing a non-profile as
     * a profile.
     */
    @Test
    public void testUserTypeFactoryCustomize_illegalUserBaseType() throws Exception {
        final String userTypeFull = "android.test";

        final ArrayMap<String, UserTypeDetails.Builder> builders = new ArrayMap<>();
        builders.put(userTypeFull, new UserTypeDetails.Builder()
                .setName(userTypeFull)
                .setBaseType(FLAG_FULL)
                .setMaxAllowedPerParent(21));

        XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_illegalUserBaseType);

        // parser is illegal because userTypeFull is FULL but the tag is for profile-type.
        assertThrows(IllegalArgumentException.class,
                () -> UserTypeFactory.customizeBuilders(builders, parser));
    }

    @Test
    public void testUserTypeFactoryVersion_versionMissing() {
        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_eraseArray);
        assertEquals(0, UserTypeFactory.getUserTypeVersion(parser));
    }

    @Test
    public void testUserTypeFactoryVersion_versionPresent() {
        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_profile);
        assertEquals(1234, UserTypeFactory.getUserTypeVersion(parser));
    }

    @Test
    public void testUserTypeFactoryUpgrades_validUpgrades() {
        final ArrayMap<String, UserTypeDetails.Builder> builders = new ArrayMap<>();
        builders.put("name", getMinimalBuilder());

        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_profile);
        List<UserTypeFactory.UserTypeUpgrade> upgrades = UserTypeFactory.parseUserUpgrades(builders,
                parser);

        assertFalse(upgrades.isEmpty());
        UserTypeFactory.UserTypeUpgrade upgrade = upgrades.get(0);
        assertEquals("android.test.1", upgrade.getFromType());
        assertEquals("android.test.2", upgrade.getToType());
        assertEquals(1233, upgrade.getUpToVersion());
    }

    @Test
    public void testUserTypeFactoryUpgrades_illegalBaseTypeUpgrade() {
        final String userTypeFull = "android.test.1";
        final ArrayMap<String, UserTypeDetails.Builder> builders = new ArrayMap<>();
        builders.put(userTypeFull, new UserTypeDetails.Builder()
                .setName(userTypeFull)
                .setBaseType(FLAG_FULL));

        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_full);

        // parser is illegal because the "to" upgrade type is not a profile, but a full user
        assertThrows(IllegalArgumentException.class,
                () -> UserTypeFactory.parseUserUpgrades(builders, parser));
    }

    /** Returns a minimal {@link UserTypeDetails.Builder} that can legitimately be created. */
    private UserTypeDetails.Builder getMinimalBuilder() {
        return new UserTypeDetails.Builder().setName("name").setBaseType(FLAG_FULL);
    }

    /** Creates a Bundle of the given String restrictions, each set to true. */
    public static Bundle makeRestrictionsBundle(String ... restrictions) {
        final Bundle bundle = new Bundle();
        for (String restriction : restrictions) {
            bundle.putBoolean(restriction, true);
        }
        return bundle;
    }

    /** Creates a Bundle of the given settings keys and puts true for the value. */
    private static Bundle makeSettingsBundle(String ... settings) {
        final Bundle bundle = new Bundle();
        for (String setting : settings) {
            bundle.putBoolean(setting, true);
        }
        return bundle;
    }
}
