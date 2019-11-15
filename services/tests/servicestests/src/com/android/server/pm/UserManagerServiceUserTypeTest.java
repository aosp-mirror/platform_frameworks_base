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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.UserManager;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Tests for {@link UserTypeDetails} and {@link UserTypeFactory}.
 *
 * <p>Run with: atest UserManagerServiceUserTypeTest
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class UserManagerServiceUserTypeTest {

    @Test
    public void testUserTypeBuilder_createUserType() {
        UserTypeDetails type = new UserTypeDetails.Builder()
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
                .setDefaultRestrictions(new ArrayList<>(Arrays.asList("r1", "r2")))
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
        assertEquals(new ArrayList<>(Arrays.asList("r1", "r2")), type.getDefaultRestrictions());


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
        assertEquals(UserTypeDetails.UNLIMITED_NUMBER_OF_USERS, type.getMaxAllowed());
        assertEquals(UserTypeDetails.UNLIMITED_NUMBER_OF_USERS, type.getMaxAllowedPerParent());
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

    /** Returns a minimal {@link UserTypeDetails.Builder} that can legitimately be created. */
    private UserTypeDetails.Builder getMinimalBuilder() {
        return new UserTypeDetails.Builder().setName("name").setBaseType(FLAG_FULL);
    }
}
