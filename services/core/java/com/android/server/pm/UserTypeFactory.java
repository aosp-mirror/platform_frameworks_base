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

import static android.content.pm.UserInfo.FLAG_ADMIN;
import static android.content.pm.UserInfo.FLAG_DEMO;
import static android.content.pm.UserInfo.FLAG_EPHEMERAL;
import static android.content.pm.UserInfo.FLAG_FULL;
import static android.content.pm.UserInfo.FLAG_GUEST;
import static android.content.pm.UserInfo.FLAG_MAIN;
import static android.content.pm.UserInfo.FLAG_MANAGED_PROFILE;
import static android.content.pm.UserInfo.FLAG_PRIMARY;
import static android.content.pm.UserInfo.FLAG_PROFILE;
import static android.content.pm.UserInfo.FLAG_RESTRICTED;
import static android.content.pm.UserInfo.FLAG_SYSTEM;
import static android.os.UserManager.USER_TYPE_FULL_DEMO;
import static android.os.UserManager.USER_TYPE_FULL_GUEST;
import static android.os.UserManager.USER_TYPE_FULL_RESTRICTED;
import static android.os.UserManager.USER_TYPE_FULL_SECONDARY;
import static android.os.UserManager.USER_TYPE_FULL_SYSTEM;
import static android.os.UserManager.USER_TYPE_PROFILE_CLONE;
import static android.os.UserManager.USER_TYPE_PROFILE_COMMUNAL;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;
import static android.os.UserManager.USER_TYPE_PROFILE_PRIVATE;
import static android.os.UserManager.USER_TYPE_PROFILE_TEST;
import static android.os.UserManager.USER_TYPE_SYSTEM_HEADLESS;

import static com.android.server.pm.UserTypeDetails.UNLIMITED_NUMBER_OF_USERS;

import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Class for creating all {@link UserTypeDetails} on the device.
 *
 * This class is responsible both for defining the AOSP use types, as well as reading in customized
 * user types from {@link com.android.internal.R.xml#config_user_types}.
 *
 * Tests are located in {@link UserManagerServiceUserTypeTest}.
 * @hide
 */
public final class UserTypeFactory {

    private static final String LOG_TAG = "UserTypeFactory";

    /** This is a utility class, so no instantiable constructor. */
    private UserTypeFactory() {}

    /**
     * Obtains the user types (built-in and customized) for this device.
     *
     * @return mapping from the name of each user type to its {@link UserTypeDetails} object
     */
    public static ArrayMap<String, UserTypeDetails> getUserTypes() {
        final ArrayMap<String, UserTypeDetails.Builder> builders = getDefaultBuilders();

        try (XmlResourceParser parser =
                     Resources.getSystem().getXml(com.android.internal.R.xml.config_user_types)) {
            customizeBuilders(builders, parser);
        }

        final ArrayMap<String, UserTypeDetails> types = new ArrayMap<>(builders.size());
        for (int i = 0; i < builders.size(); i++) {
            types.put(builders.keyAt(i), builders.valueAt(i).createUserTypeDetails());
        }
        return types;
    }

    private static ArrayMap<String, UserTypeDetails.Builder> getDefaultBuilders() {
        final ArrayMap<String, UserTypeDetails.Builder> builders = new ArrayMap<>();

        builders.put(USER_TYPE_PROFILE_MANAGED, getDefaultTypeProfileManaged());
        builders.put(USER_TYPE_FULL_SYSTEM, getDefaultTypeFullSystem());
        builders.put(USER_TYPE_FULL_SECONDARY, getDefaultTypeFullSecondary());
        builders.put(USER_TYPE_FULL_GUEST, getDefaultTypeFullGuest());
        builders.put(USER_TYPE_FULL_DEMO, getDefaultTypeFullDemo());
        builders.put(USER_TYPE_FULL_RESTRICTED, getDefaultTypeFullRestricted());
        builders.put(USER_TYPE_SYSTEM_HEADLESS, getDefaultTypeSystemHeadless());
        builders.put(USER_TYPE_PROFILE_CLONE, getDefaultTypeProfileClone());
        builders.put(USER_TYPE_PROFILE_COMMUNAL, getDefaultTypeProfileCommunal());
        builders.put(USER_TYPE_PROFILE_PRIVATE, getDefaultTypeProfilePrivate());
        if (Build.IS_DEBUGGABLE) {
            builders.put(USER_TYPE_PROFILE_TEST, getDefaultTypeProfileTest());
        }

        return builders;
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_PROFILE_CLONE}
     * configuration.
     */
    // TODO(b/182396009): Add default restrictions, if needed for clone user type.
    private static UserTypeDetails.Builder getDefaultTypeProfileClone() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_PROFILE_CLONE)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(1)
                .setLabel(0)
                .setIconBadge(com.android.internal.R.drawable.ic_clone_icon_badge)
                .setBadgePlain(com.android.internal.R.drawable.ic_clone_badge)
                // Clone doesn't use BadgeNoBackground, so just set to BadgePlain as a placeholder.
                .setBadgeNoBackground(com.android.internal.R.drawable.ic_clone_badge)
                .setStatusBarIcon(Resources.ID_NULL)
                .setBadgeLabels(
                        com.android.internal.R.string.clone_profile_label_badge)
                .setBadgeColors(
                        com.android.internal.R.color.system_neutral2_800)
                .setDarkThemeBadgeColors(
                        com.android.internal.R.color.system_neutral2_900)
                .setDefaultRestrictions(null)
                .setDefaultCrossProfileIntentFilters(getDefaultCloneCrossProfileIntentFilter())
                .setDefaultSecureSettings(getDefaultNonManagedProfileSecureSettings())
                .setDefaultUserProperties(new UserProperties.Builder()
                        .setStartWithParent(true)
                        .setShowInLauncher(UserProperties.SHOW_IN_LAUNCHER_WITH_PARENT)
                        .setShowInSettings(UserProperties.SHOW_IN_SETTINGS_WITH_PARENT)
                        .setInheritDevicePolicy(UserProperties.INHERIT_DEVICE_POLICY_FROM_PARENT)
                        .setUseParentsContacts(true)
                        .setUpdateCrossProfileIntentFiltersOnOTA(true)
                        .setCrossProfileIntentFilterAccessControl(
                                UserProperties.CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_SYSTEM)
                        .setCrossProfileIntentResolutionStrategy(UserProperties
                                .CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_NO_FILTERING)
                        .setMediaSharedWithParent(true)
                        .setCredentialShareableWithParent(true)
                        .setDeleteAppWithParent(true));
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
                .setStatusBarIcon(com.android.internal.R.drawable.stat_sys_managed_profile_status)
                .setBadgeLabels(
                        com.android.internal.R.string.managed_profile_label_badge,
                        com.android.internal.R.string.managed_profile_label_badge_2,
                        com.android.internal.R.string.managed_profile_label_badge_3)
                .setBadgeColors(
                        com.android.internal.R.color.profile_badge_1,
                        com.android.internal.R.color.profile_badge_2,
                        com.android.internal.R.color.profile_badge_3)
                .setDarkThemeBadgeColors(
                        com.android.internal.R.color.profile_badge_1_dark,
                        com.android.internal.R.color.profile_badge_2_dark,
                        com.android.internal.R.color.profile_badge_3_dark)
                .setDefaultRestrictions(getDefaultProfileRestrictions())
                .setDefaultSecureSettings(getDefaultManagedProfileSecureSettings())
                .setDefaultCrossProfileIntentFilters(getDefaultManagedCrossProfileIntentFilter())
                .setDefaultUserProperties(new UserProperties.Builder()
                        .setStartWithParent(true)
                        .setShowInLauncher(UserProperties.SHOW_IN_LAUNCHER_SEPARATE)
                        .setShowInSettings(UserProperties.SHOW_IN_SETTINGS_SEPARATE)
                        .setAuthAlwaysRequiredToDisableQuietMode(false)
                        .setCredentialShareableWithParent(true));
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_PROFILE_TEST}
     * configuration (for userdebug builds). For now it just uses managed profile badges.
     */
    private static UserTypeDetails.Builder getDefaultTypeProfileTest() {
        final Bundle restrictions = getDefaultProfileRestrictions();
        restrictions.putBoolean(UserManager.DISALLOW_FUN, true);

        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_PROFILE_TEST)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(2)
                .setLabel(0)
                .setIconBadge(com.android.internal.R.drawable.ic_test_icon_badge_experiment)
                .setBadgePlain(com.android.internal.R.drawable.ic_test_badge_experiment)
                .setBadgeNoBackground(com.android.internal.R.drawable.ic_test_badge_no_background)
                .setStatusBarIcon(com.android.internal.R.drawable.ic_test_badge_experiment)
                .setBadgeLabels(
                        com.android.internal.R.string.managed_profile_label_badge,
                        com.android.internal.R.string.managed_profile_label_badge_2,
                        com.android.internal.R.string.managed_profile_label_badge_3)
                .setBadgeColors(
                        com.android.internal.R.color.profile_badge_1,
                        com.android.internal.R.color.profile_badge_2,
                        com.android.internal.R.color.profile_badge_3)
                .setDarkThemeBadgeColors(
                        com.android.internal.R.color.profile_badge_1_dark,
                        com.android.internal.R.color.profile_badge_2_dark,
                        com.android.internal.R.color.profile_badge_3_dark)
                .setDefaultRestrictions(restrictions)
                .setDefaultSecureSettings(getDefaultNonManagedProfileSecureSettings());
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_PROFILE_COMMUNAL}
     * configuration. For now it just uses managed profile badges.
     */
    private static UserTypeDetails.Builder getDefaultTypeProfileCommunal() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_PROFILE_COMMUNAL)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowed(1)
                .setEnabled(UserManager.isCommunalProfileEnabled() ? 1 : 0)
                .setLabel(0)
                .setIconBadge(com.android.internal.R.drawable.ic_test_icon_badge_experiment)
                .setBadgePlain(com.android.internal.R.drawable.ic_test_badge_experiment)
                .setBadgeNoBackground(com.android.internal.R.drawable.ic_test_badge_no_background)
                .setStatusBarIcon(com.android.internal.R.drawable.ic_test_badge_experiment)
                .setBadgeLabels(
                        com.android.internal.R.string.managed_profile_label_badge,
                        com.android.internal.R.string.managed_profile_label_badge_2,
                        com.android.internal.R.string.managed_profile_label_badge_3)
                .setBadgeColors(
                        com.android.internal.R.color.profile_badge_1,
                        com.android.internal.R.color.profile_badge_2,
                        com.android.internal.R.color.profile_badge_3)
                .setDarkThemeBadgeColors(
                        com.android.internal.R.color.profile_badge_1_dark,
                        com.android.internal.R.color.profile_badge_2_dark,
                        com.android.internal.R.color.profile_badge_3_dark)
                .setDefaultRestrictions(getDefaultProfileRestrictions())
                .setDefaultSecureSettings(getDefaultNonManagedProfileSecureSettings())
                .setDefaultUserProperties(new UserProperties.Builder()
                        .setStartWithParent(false)
                        .setShowInLauncher(UserProperties.SHOW_IN_LAUNCHER_SEPARATE)
                        .setShowInSettings(UserProperties.SHOW_IN_SETTINGS_SEPARATE)
                        .setCredentialShareableWithParent(false)
                        .setAlwaysVisible(true));
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_PROFILE_PRIVATE}
     * configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeProfilePrivate() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_PROFILE_PRIVATE)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(1)
                .setLabel(0)
                .setIconBadge(com.android.internal.R.drawable.ic_private_profile_icon_badge)
                .setBadgePlain(com.android.internal.R.drawable.ic_private_profile_badge)
                // Private Profile doesn't use BadgeNoBackground, so just set to BadgePlain
                // as a placeholder.
                .setBadgeNoBackground(com.android.internal.R.drawable.ic_private_profile_badge)
                .setStatusBarIcon(com.android.internal.R.drawable.stat_sys_private_profile_status)
                .setBadgeLabels(
                        com.android.internal.R.string.private_profile_label_badge)
                .setBadgeColors(
                        R.color.black)
                .setDarkThemeBadgeColors(
                        R.color.white)
                .setDefaultRestrictions(getDefaultProfileRestrictions())
                .setDefaultSecureSettings(getDefaultNonManagedProfileSecureSettings())
                .setDefaultUserProperties(new UserProperties.Builder()
                        .setStartWithParent(true)
                        .setCredentialShareableWithParent(true)
                        .setAuthAlwaysRequiredToDisableQuietMode(true)
                        .setMediaSharedWithParent(false)
                        .setShowInLauncher(UserProperties.SHOW_IN_LAUNCHER_SEPARATE)
                        .setShowInSettings(UserProperties.SHOW_IN_SETTINGS_SEPARATE)
                        .setHideInSettingsInQuietMode(true)
                        .setCrossProfileIntentFilterAccessControl(
                                UserProperties.CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_SYSTEM)
                        .setInheritDevicePolicy(UserProperties.INHERIT_DEVICE_POLICY_FROM_PARENT));
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_FULL_SECONDARY}
     * configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeFullSecondary() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_FULL_SECONDARY)
                .setBaseType(FLAG_FULL)
                .setMaxAllowed(UNLIMITED_NUMBER_OF_USERS)
                .setDefaultRestrictions(getDefaultSecondaryUserRestrictions());
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_FULL_GUEST} configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeFullGuest() {
        final boolean ephemeralGuests = Resources.getSystem()
                .getBoolean(com.android.internal.R.bool.config_guestUserEphemeral);
        final int flags = FLAG_GUEST | (ephemeralGuests ? FLAG_EPHEMERAL : 0);

        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_FULL_GUEST)
                .setBaseType(FLAG_FULL)
                .setDefaultUserInfoPropertyFlags(flags)
                .setMaxAllowed(1)
                .setDefaultRestrictions(getDefaultGuestUserRestrictions());
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_FULL_DEMO} configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeFullDemo() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_FULL_DEMO)
                .setBaseType(FLAG_FULL)
                .setDefaultUserInfoPropertyFlags(FLAG_DEMO)
                .setMaxAllowed(UNLIMITED_NUMBER_OF_USERS)
                .setDefaultRestrictions(null);
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
                .setMaxAllowed(UNLIMITED_NUMBER_OF_USERS)
                // NB: UserManagerService.createRestrictedProfile() applies hardcoded restrictions.
                .setDefaultRestrictions(null);
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_FULL_SYSTEM} configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeFullSystem() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_FULL_SYSTEM)
                .setBaseType(FLAG_SYSTEM | FLAG_FULL)
                .setDefaultUserInfoPropertyFlags(FLAG_PRIMARY | FLAG_ADMIN | FLAG_MAIN)
                .setMaxAllowed(1);
    }

    /**
     * Returns the Builder for the default {@link UserManager#USER_TYPE_SYSTEM_HEADLESS}
     * configuration.
     */
    private static UserTypeDetails.Builder getDefaultTypeSystemHeadless() {
        return new UserTypeDetails.Builder()
                .setName(USER_TYPE_SYSTEM_HEADLESS)
                .setBaseType(FLAG_SYSTEM)
                .setDefaultUserInfoPropertyFlags(FLAG_PRIMARY | FLAG_ADMIN)
                .setMaxAllowed(1);
    }

    private static Bundle getDefaultSecondaryUserRestrictions() {
        final Bundle restrictions = new Bundle();
        restrictions.putBoolean(UserManager.DISALLOW_OUTGOING_CALLS, true);
        restrictions.putBoolean(UserManager.DISALLOW_SMS, true);
        return restrictions;
    }

    private static Bundle getDefaultGuestUserRestrictions() {
        // Guest inherits the secondary user's restrictions, plus has some extra ones.
        final Bundle restrictions = getDefaultSecondaryUserRestrictions();
        restrictions.putBoolean(UserManager.DISALLOW_CONFIG_WIFI, true);
        restrictions.putBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true);
        restrictions.putBoolean(UserManager.DISALLOW_CONFIG_CREDENTIALS, true);
        return restrictions;
    }

    private static Bundle getDefaultProfileRestrictions() {
        final Bundle restrictions = new Bundle();
        restrictions.putBoolean(UserManager.DISALLOW_WALLPAPER, true);
        return restrictions;
    }

    private static Bundle getDefaultManagedProfileSecureSettings() {
        // Only add String values to the bundle, settings are written as Strings eventually
        final Bundle settings = new Bundle();
        settings.putString(
                android.provider.Settings.Secure.MANAGED_PROFILE_CONTACT_REMOTE_SEARCH, "1");
        settings.putString(
                android.provider.Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED, "1");
        return settings;
    }

    private static List<DefaultCrossProfileIntentFilter>
            getDefaultManagedCrossProfileIntentFilter() {
        return DefaultCrossProfileIntentFiltersUtils.getDefaultManagedProfileFilters();
    }

    private static List<DefaultCrossProfileIntentFilter> getDefaultCloneCrossProfileIntentFilter() {
        return DefaultCrossProfileIntentFiltersUtils.getDefaultCloneProfileFilters();
    }

    /** Gets a default bundle, keyed by Settings.Secure String names, for non-managed profiles. */
    private static Bundle getDefaultNonManagedProfileSecureSettings() {
        final Bundle settings = new Bundle();
        // Non-managed profiles go through neither SetupWizard nor DPC flows, so we automatically
        // mark them as setup.
        settings.putString(android.provider.Settings.Secure.USER_SETUP_COMPLETE, "1");
        return settings;
    }

    /**
     * Reads the given xml parser to obtain device user-type customization, and updates the given
     * map of {@link UserTypeDetails.Builder}s accordingly.
     * <p>
     * The xml file can specify the attributes according to the set... methods below.
     */
    // TODO(b/176973369): Add parsing logic to support custom settings/filters
    //  in config_user_types.xml
    @VisibleForTesting
    static void customizeBuilders(ArrayMap<String, UserTypeDetails.Builder> builders,
            XmlResourceParser parser) {
        try {
            XmlUtils.beginDocument(parser, "user-types");
            for (XmlUtils.nextElement(parser);
                    parser.getEventType() != XmlResourceParser.END_DOCUMENT;
                    XmlUtils.nextElement(parser)) {
                final boolean isProfile;
                final String elementName = parser.getName();
                if ("profile-type".equals(elementName)) {
                    isProfile = true;
                } else if ("full-type".equals(elementName)) {
                    isProfile = false;
                } else if ("change-user-type".equals(elementName)) {
                    // parsed in parseUserUpgrades
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    Slog.w(LOG_TAG, "Skipping unknown element " + elementName + " in "
                                + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }

                String typeName = parser.getAttributeValue(null, "name");
                if (typeName == null || typeName.equals("")) {
                    Slog.w(LOG_TAG, "Skipping user type with no name in "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }
                typeName = typeName.intern();

                UserTypeDetails.Builder builder;
                if (typeName.startsWith("android.")) {
                    // typeName refers to a AOSP-defined type which we are modifying.
                    Slog.i(LOG_TAG, "Customizing user type " + typeName);
                    builder = builders.get(typeName);
                    if (builder == null) {
                        throw new IllegalArgumentException("Illegal custom user type name "
                                + typeName + ": Non-AOSP user types cannot start with 'android.'");
                    }
                    final boolean isValid =
                            (isProfile && builder.getBaseType() == UserInfo.FLAG_PROFILE)
                            || (!isProfile && builder.getBaseType() == UserInfo.FLAG_FULL);
                    if (!isValid) {
                        throw new IllegalArgumentException("Wrong base type to customize user type "
                                + "(" + typeName + "), which is type "
                                + UserInfo.flagsToString(builder.getBaseType()));
                    }
                } else if (isProfile) {
                    // typeName refers to a new OEM-defined profile type which we are defining.
                    Slog.i(LOG_TAG, "Creating custom user type " + typeName);
                    builder = new UserTypeDetails.Builder();
                    builder.setName(typeName);
                    builder.setBaseType(FLAG_PROFILE);
                    builders.put(typeName, builder);
                } else {
                    throw new IllegalArgumentException("Creation of non-profile user type "
                            + "(" + typeName + ") is not currently supported.");
                }

                // Process the attributes (other than name).
                if (isProfile) {
                    setIntAttribute(parser, "max-allowed-per-parent",
                            builder::setMaxAllowedPerParent);
                    setResAttribute(parser, "icon-badge", builder::setIconBadge);
                    setResAttribute(parser, "badge-plain", builder::setBadgePlain);
                    setResAttribute(parser, "badge-no-background", builder::setBadgeNoBackground);
                    setResAttribute(parser, "status-bar-icon", builder::setStatusBarIcon);
                }

                setIntAttribute(parser, "enabled", builder::setEnabled);
                setIntAttribute(parser, "max-allowed", builder::setMaxAllowed);

                // Process child elements.
                final int depth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, depth)) {
                    final String childName = parser.getName();
                    if ("default-restrictions".equals(childName)) {
                        final Bundle restrictions = UserRestrictionsUtils
                                .readRestrictions(XmlUtils.makeTyped(parser));
                        builder.setDefaultRestrictions(restrictions);
                    } else if (isProfile && "badge-labels".equals(childName)) {
                        setResAttributeArray(parser, builder::setBadgeLabels);
                    } else if (isProfile && "badge-colors".equals(childName)) {
                        setResAttributeArray(parser, builder::setBadgeColors);
                    } else if (isProfile && "badge-colors-dark".equals(childName)) {
                        setResAttributeArray(parser, builder::setDarkThemeBadgeColors);
                    } else if ("user-properties".equals(childName)) {
                        builder.getDefaultUserProperties()
                                .updateFromXml(XmlUtils.makeTyped(parser));
                    } else {
                        Slog.w(LOG_TAG, "Unrecognized tag " + childName + " in "
                                + parser.getPositionDescription());
                    }
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Slog.w(LOG_TAG, "Cannot read user type configuration file.", e);
        }
    }

    /**
     * If the given attribute exists, gets the int stored in it and performs the given fcn using it.
     * The stored value must be an int or NumberFormatException will be thrown.
     *
     * @param parser xml parser from which to read the attribute
     * @param attributeName name of the attribute
     * @param fcn one-int-argument function,
     *            like {@link UserTypeDetails.Builder#setMaxAllowedPerParent(int)}
     */
    private static void setIntAttribute(XmlResourceParser parser, String attributeName,
            Consumer<Integer> fcn) {
        final String intValue = parser.getAttributeValue(null, attributeName);
        if (intValue == null) {
            return;
        }
        try {
            fcn.accept(Integer.parseInt(intValue));
        } catch (NumberFormatException e) {
            Slog.e(LOG_TAG, "Cannot parse value of '" + intValue + "' for " + attributeName
                    + " in " + parser.getPositionDescription(), e);
            throw e;
        }
    }

    /**
     * If the given attribute exists, gets the resId stored in it (or 0 if it is not a valid resId)
     * and performs the given fcn using it.
     *
     * @param parser xml parser from which to read the attribute
     * @param attributeName name of the attribute
     * @param fcn one-argument function, like {@link UserTypeDetails.Builder#setIconBadge(int)}
     */
    private static void setResAttribute(XmlResourceParser parser, String attributeName,
            Consumer<Integer> fcn) {
        if (parser.getAttributeValue(null, attributeName) == null) {
            // Attribute is not present, i.e. use the default value.
            return;
        }
        final int resId = parser.getAttributeResourceValue(null, attributeName, Resources.ID_NULL);
        fcn.accept(resId);
    }

    /**
     * Gets the resIds stored in "item" elements (in their "res" attribute) at the current depth.
     * Then performs the given fcn using the int[] array of these resIds.
     * <p>
     * Each xml element is expected to be of the form {@code <item res="someResValue" />}.
     *
     * @param parser xml parser from which to read the elements and their attributes
     * @param fcn function, like {@link UserTypeDetails.Builder#setBadgeColors(int...)}
     */
    private static void setResAttributeArray(XmlResourceParser parser, Consumer<int[]> fcn)
            throws IOException, XmlPullParserException {

        ArrayList<Integer> resList = new ArrayList<>();
        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            final String elementName = parser.getName();
            if (!"item".equals(elementName)) {
                Slog.w(LOG_TAG, "Skipping unknown child element " + elementName + " in "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }
            final int resId = parser.getAttributeResourceValue(null, "res", -1);
            if (resId == -1) {
                continue;
            }
            resList.add(resId);
        }

        int[] result = new int[resList.size()];
        for (int i = 0; i < resList.size(); i++) {
            result[i] = resList.get(i);
        }
        fcn.accept(result);
    }

    /**
     * Returns the user type version of the config XML file.
     * @return user type version defined in XML file, 0 if none.
     */
    public static int getUserTypeVersion() {
        try (XmlResourceParser parser =
                     Resources.getSystem().getXml(com.android.internal.R.xml.config_user_types)) {
            return getUserTypeVersion(parser);
        }
    }

    @VisibleForTesting
    static int getUserTypeVersion(XmlResourceParser parser) {
        int version = 0;

        try {
            XmlUtils.beginDocument(parser, "user-types");
            String versionValue = parser.getAttributeValue(null, "version");
            if (versionValue != null) {
                try {
                    version = Integer.parseInt(versionValue);
                } catch (NumberFormatException e) {
                    Slog.e(LOG_TAG, "Cannot parse value of '" + versionValue + "' for version in "
                            + parser.getPositionDescription(), e);
                    throw e;
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Slog.w(LOG_TAG, "Cannot read user type configuration file.", e);
        }

        return version;
    }

    /**
     * Obtains the user type upgrades for this device.
     * @return The list of user type upgrades.
     */
    public static List<UserTypeUpgrade> getUserTypeUpgrades() {
        final List<UserTypeUpgrade> userUpgrades;
        try (XmlResourceParser parser =
                     Resources.getSystem().getXml(com.android.internal.R.xml.config_user_types)) {
            userUpgrades = parseUserUpgrades(getDefaultBuilders(), parser);
        }
        return userUpgrades;
    }

    @VisibleForTesting
    static List<UserTypeUpgrade> parseUserUpgrades(
            ArrayMap<String, UserTypeDetails.Builder> builders, XmlResourceParser parser) {
        final List<UserTypeUpgrade> userUpgrades = new ArrayList<>();

        try {
            XmlUtils.beginDocument(parser, "user-types");
            for (XmlUtils.nextElement(parser);
                    parser.getEventType() != XmlResourceParser.END_DOCUMENT;
                    XmlUtils.nextElement(parser)) {
                final String elementName = parser.getName();
                if ("change-user-type".equals(elementName)) {
                    final String fromType = parser.getAttributeValue(null, "from");
                    final String toType = parser.getAttributeValue(null, "to");
                    // Check that the base type doesn't change.
                    // Currently, only the base type of PROFILE is supported.
                    validateUserTypeIsProfile(fromType, builders);
                    validateUserTypeIsProfile(toType, builders);

                    final int maxVersionToConvert;
                    try {
                        maxVersionToConvert = Integer.parseInt(
                                parser.getAttributeValue(null, "whenVersionLeq"));
                    } catch (NumberFormatException e) {
                        Slog.e(LOG_TAG, "Cannot parse value of whenVersionLeq in "
                                + parser.getPositionDescription(), e);
                        throw e;
                    }

                    UserTypeUpgrade userTypeUpgrade = new UserTypeUpgrade(fromType, toType,
                            maxVersionToConvert);
                    userUpgrades.add(userTypeUpgrade);
                    continue;
                } else {
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Slog.w(LOG_TAG, "Cannot read user type configuration file.", e);
        }

        return userUpgrades;
    }

    private static void validateUserTypeIsProfile(String userType,
            ArrayMap<String, UserTypeDetails.Builder> builders) {
        UserTypeDetails.Builder builder = builders.get(userType);
        if (builder != null && builder.getBaseType() != FLAG_PROFILE) {
            throw new IllegalArgumentException("Illegal upgrade of user type " + userType
                    + " : Can only upgrade profiles user types");
        }
    }

    /**
     * Contains details required for an upgrade operation for {@link UserTypeDetails};
     */
    public static class UserTypeUpgrade {
        private final String mFromType;
        private final String mToType;
        private final int mUpToVersion;

        public UserTypeUpgrade(String fromType, String toType, int upToVersion) {
            mFromType = fromType;
            mToType = toType;
            mUpToVersion = upToVersion;
        }

        public String getFromType() {
            return mFromType;
        }

        public String getToType() {
            return mToType;
        }

        public int getUpToVersion() {
            return mUpToVersion;
        }
    }
}
