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
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.UserManager;
import android.util.ArrayMap;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.servicestests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link UserTypeDetails} and {@link UserTypeFactory}.
 *
 * <p>Run with: atest UserManagerServiceUserTypeTest
 */
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
        final UserTypeDetails type = new UserTypeDetails.Builder()
                .setName("a.name")
                .setEnabled(true)
                .setMaxAllowed(21)
                .setBaseType(FLAG_FULL)
                .setDefaultUserInfoPropertyFlags(FLAG_EPHEMERAL)
                .setBadgeLabels(23, 24, 25)
                .setBadgeColors(26, 27)
                .setIconBadge(28)
                .setBadgePlain(29)
                .setBadgeNoBackground(30)
                .setLabel(31)
                .setMaxAllowedPerParent(32)
                .setDefaultRestrictions(restrictions)
                .createUserTypeDetails();

        assertEquals("a.name", type.getName());
        assertTrue(type.isEnabled());
        assertEquals(21, type.getMaxAllowed());
        assertEquals(FLAG_FULL | FLAG_EPHEMERAL, type.getDefaultUserInfoFlags());
        assertEquals(28, type.getIconBadge());
        assertEquals(29, type.getBadgePlain());
        assertEquals(30, type.getBadgeNoBackground());
        assertEquals(31, type.getLabel());
        assertEquals(32, type.getMaxAllowedPerParent());
        assertTrue(UserRestrictionsUtils.areEqual(restrictions, type.getDefaultRestrictions()));
        assertNotSame(restrictions, type.getDefaultRestrictions());


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
        assertEquals(Resources.ID_NULL, type.getBadgeLabel(0));
        assertEquals(Resources.ID_NULL, type.getBadgeColor(0));
        assertEquals(Resources.ID_NULL, type.getLabel());
        assertTrue(type.getDefaultRestrictions().isEmpty());

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
        final ArrayMap<String, UserTypeDetails.Builder> builders = new ArrayMap<>();
        builders.put(userTypeAosp1, new UserTypeDetails.Builder()
                .setName(userTypeAosp1)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(31)
                .setDefaultRestrictions(restrictions));
        builders.put(userTypeAosp2, new UserTypeDetails.Builder()
                .setName(userTypeAosp1)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(32)
                .setIconBadge(401)
                .setBadgeColors(402, 403, 404)
                .setDefaultRestrictions(restrictions));

        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_profile);
        UserTypeFactory.customizeBuilders(builders, parser);

        // userTypeAosp1 should not be modified.
        UserTypeDetails aospType = builders.get(userTypeAosp1).createUserTypeDetails();
        assertEquals(31, aospType.getMaxAllowedPerParent());
        assertEquals(Resources.ID_NULL, aospType.getIconBadge());
        assertTrue(UserRestrictionsUtils.areEqual(restrictions, aospType.getDefaultRestrictions()));

        // userTypeAosp2 should be modified.
        aospType = builders.get(userTypeAosp2).createUserTypeDetails();
        assertEquals(12, aospType.getMaxAllowedPerParent());
        assertEquals(com.android.internal.R.drawable.ic_corp_icon_badge_case,
                aospType.getIconBadge());
        assertEquals(Resources.ID_NULL, aospType.getBadgePlain()); // No resId for 'garbage'
        assertEquals(com.android.internal.R.drawable.ic_corp_badge_no_background,
                aospType.getBadgeNoBackground());
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
                .setDefaultRestrictions(restrictions));

        final XmlResourceParser parser = mResources.getXml(R.xml.usertypes_test_full);
        UserTypeFactory.customizeBuilders(builders, parser);

        UserTypeDetails details = builders.get(userTypeFull).createUserTypeDetails();
        assertEquals(UNLIMITED_NUMBER_OF_USERS, details.getMaxAllowedPerParent());
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

    /** Returns a minimal {@link UserTypeDetails.Builder} that can legitimately be created. */
    private UserTypeDetails.Builder getMinimalBuilder() {
        return new UserTypeDetails.Builder().setName("name").setBaseType(FLAG_FULL);
    }

    /** Creates a Bundle of the given String restrictions, each set to true. */
    private Bundle makeRestrictionsBundle(String ... restrictions) {
        final Bundle bundle = new Bundle();
        for (String restriction : restrictions) {
            bundle.putBoolean(restriction, true);
        }
        return bundle;
    }
}
