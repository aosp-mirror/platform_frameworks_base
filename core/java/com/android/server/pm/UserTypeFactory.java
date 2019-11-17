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
import static android.os.UserManager.USER_TYPE_FULL_DEMO;
import static android.os.UserManager.USER_TYPE_FULL_GUEST;
import static android.os.UserManager.USER_TYPE_FULL_RESTRICTED;
import static android.os.UserManager.USER_TYPE_FULL_SECONDARY;
import static android.os.UserManager.USER_TYPE_FULL_SYSTEM;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;
import static android.os.UserManager.USER_TYPE_SYSTEM_HEADLESS;

import static com.android.server.pm.UserTypeDetails.UNLIMITED_NUMBER_OF_USERS;

import android.content.res.Resources;
import android.os.UserManager;
import android.util.ArrayMap;

/**
 * Class for creating all {@link UserTypeDetails} on the device.
 *
 * Tests are located in UserManagerServiceUserTypeTest.java.
 * @hide
 */
public final class UserTypeFactory {

    /** This is a utility class, so no instantiable constructor. */
    private UserTypeFactory() {}

    /**
     * Obtains the user types (built-in and customized) for this device.
     *
     * @return mapping from the name of each user type to its {@link UserTypeDetails} object
     */
    public static ArrayMap<String, UserTypeDetails> getUserTypes() {
        final ArrayMap<String, UserTypeDetails> map = new ArrayMap<>();
        // TODO(b/142482943): Read an xml file for OEM customized types.
        //                    Remember to disallow "android." namespace
        // TODO(b/142482943): Read an xml file to get any overrides for the built-in types.
        final int maxManagedProfiles = 1;
        map.put(USER_TYPE_PROFILE_MANAGED,
                getDefaultTypeProfileManaged().setMaxAllowedPerParent(maxManagedProfiles)
                        .createUserTypeDetails());
        map.put(USER_TYPE_FULL_SYSTEM, getDefaultTypeSystemFull().createUserTypeDetails());
        map.put(USER_TYPE_FULL_SECONDARY, getDefaultTypeFullSecondary().createUserTypeDetails());
        map.put(USER_TYPE_FULL_GUEST, getDefaultTypeFullGuest().createUserTypeDetails());
        map.put(USER_TYPE_FULL_DEMO, getDefaultTypeFullDemo().createUserTypeDetails());
        map.put(USER_TYPE_FULL_RESTRICTED, getDefaultTypeFullRestricted().createUserTypeDetails());
        map.put(USER_TYPE_SYSTEM_HEADLESS, getDefaultTypeSystemHeadless().createUserTypeDetails());
        return map;
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_PROFILE_MANAGED}
     * configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeProfileManaged() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_PROFILE_MANAGED)
                .setBaseType(FLAG_PROFILE)
                .setDefaultUserInfoPropertyFlags(FLAG_MANAGED_PROFILE)
                .setMaxAllowedPerParent(1)
                .setLabel(0)
                .setIconBadge(com.android.internal.R.drawable.ic_corp_icon_badge_case)
                .setBadgePlain(com.android.internal.R.drawable.ic_corp_badge_case)
                .setBadgeNoBackground(com.android.internal.R.drawable.ic_corp_badge_no_background)
                .setBadgeLabels(
                        com.android.internal.R.string.managed_profile_label_badge,
                        com.android.internal.R.string.managed_profile_label_badge_2,
                        com.android.internal.R.string.managed_profile_label_badge_3)
                .setBadgeColors(
                        com.android.internal.R.color.profile_badge_1,
                        com.android.internal.R.color.profile_badge_2,
                        com.android.internal.R.color.profile_badge_3);
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_FULL_SECONDARY}
     * configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeFullSecondary() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_FULL_SECONDARY)
                .setBaseType(FLAG_FULL)
                .setMaxAllowed(UNLIMITED_NUMBER_OF_USERS);
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_FULL_GUEST} configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeFullGuest() {
        final boolean ephemeralGuests = Resources.getSystem()
                .getBoolean(com.android.internal.R.bool.config_guestUserEphemeral);
        final int flags = FLAG_GUEST | (ephemeralGuests ? FLAG_EPHEMERAL : 0);

        // TODO(b/142482943): Put UMS.initDefaultGuestRestrictions() here; then fetch them from here

        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_FULL_GUEST)
                .setBaseType(FLAG_FULL)
                .setDefaultUserInfoPropertyFlags(flags)
                .setMaxAllowed(1);
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_FULL_DEMO} configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeFullDemo() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_FULL_DEMO)
                .setBaseType(FLAG_FULL)
                .setDefaultUserInfoPropertyFlags(FLAG_DEMO)
                .setMaxAllowed(UNLIMITED_NUMBER_OF_USERS);
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_FULL_RESTRICTED}
     * configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeFullRestricted() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_FULL_RESTRICTED)
                .setBaseType(FLAG_FULL)
                .setDefaultUserInfoPropertyFlags(FLAG_RESTRICTED)
                .setMaxAllowed(UNLIMITED_NUMBER_OF_USERS);
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_FULL_SYSTEM} configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeSystemFull() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_FULL_SYSTEM)
                .setBaseType(FLAG_SYSTEM | FLAG_FULL);
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_SYSTEM_HEADLESS}
     * configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeSystemHeadless() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_SYSTEM_HEADLESS)
                .setBaseType(FLAG_SYSTEM);
    }
}
