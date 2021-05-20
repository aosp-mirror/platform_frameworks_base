/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_DISABLED;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_NONE;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.android.server.devicepolicy.DevicePolicyManagerService.LOG_TAG;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.TEXT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManager;
import android.app.admin.FactoryResetProtectionPolicy;
import android.app.admin.PasswordPolicy;
import android.graphics.Color;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.UserRestrictionsUtils;
import com.android.server.utils.Slogf;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

class ActiveAdmin {
    private static final String TAG_DISABLE_KEYGUARD_FEATURES = "disable-keyguard-features";
    private static final String TAG_TEST_ONLY_ADMIN = "test-only-admin";
    private static final String TAG_DISABLE_CAMERA = "disable-camera";
    private static final String TAG_DISABLE_CALLER_ID = "disable-caller-id";
    private static final String TAG_DISABLE_CONTACTS_SEARCH = "disable-contacts-search";
    private static final String TAG_DISABLE_BLUETOOTH_CONTACT_SHARING =
            "disable-bt-contacts-sharing";
    private static final String TAG_DISABLE_SCREEN_CAPTURE = "disable-screen-capture";
    private static final String TAG_DISABLE_ACCOUNT_MANAGEMENT = "disable-account-management";
    private static final String TAG_NEARBY_NOTIFICATION_STREAMING_POLICY =
            "nearby-notification-streaming-policy";
    private static final String TAG_NEARBY_APP_STREAMING_POLICY =
            "nearby-app-streaming-policy";
    private static final String TAG_REQUIRE_AUTO_TIME = "require_auto_time";
    private static final String TAG_FORCE_EPHEMERAL_USERS = "force_ephemeral_users";
    private static final String TAG_IS_NETWORK_LOGGING_ENABLED = "is_network_logging_enabled";
    private static final String TAG_ACCOUNT_TYPE = "account-type";
    private static final String TAG_PERMITTED_ACCESSIBILITY_SERVICES =
            "permitted-accessiblity-services";
    private static final String TAG_ENCRYPTION_REQUESTED = "encryption-requested";
    private static final String TAG_MANAGE_TRUST_AGENT_FEATURES = "manage-trust-agent-features";
    private static final String TAG_TRUST_AGENT_COMPONENT_OPTIONS = "trust-agent-component-options";
    private static final String TAG_TRUST_AGENT_COMPONENT = "component";
    private static final String TAG_PASSWORD_EXPIRATION_DATE = "password-expiration-date";
    private static final String TAG_PASSWORD_EXPIRATION_TIMEOUT = "password-expiration-timeout";
    private static final String TAG_GLOBAL_PROXY_EXCLUSION_LIST = "global-proxy-exclusion-list";
    private static final String TAG_GLOBAL_PROXY_SPEC = "global-proxy-spec";
    private static final String TAG_SPECIFIES_GLOBAL_PROXY = "specifies-global-proxy";
    private static final String TAG_PERMITTED_IMES = "permitted-imes";
    private static final String TAG_PERMITTED_NOTIFICATION_LISTENERS =
            "permitted-notification-listeners";
    private static final String TAG_MAX_FAILED_PASSWORD_WIPE = "max-failed-password-wipe";
    private static final String TAG_MAX_TIME_TO_UNLOCK = "max-time-to-unlock";
    private static final String TAG_STRONG_AUTH_UNLOCK_TIMEOUT = "strong-auth-unlock-timeout";
    private static final String TAG_MIN_PASSWORD_NONLETTER = "min-password-nonletter";
    private static final String TAG_MIN_PASSWORD_SYMBOLS = "min-password-symbols";
    private static final String TAG_MIN_PASSWORD_NUMERIC = "min-password-numeric";
    private static final String TAG_MIN_PASSWORD_LETTERS = "min-password-letters";
    private static final String TAG_MIN_PASSWORD_LOWERCASE = "min-password-lowercase";
    private static final String TAG_MIN_PASSWORD_UPPERCASE = "min-password-uppercase";
    private static final String TAG_PASSWORD_HISTORY_LENGTH = "password-history-length";
    private static final String TAG_MIN_PASSWORD_LENGTH = "min-password-length";
    private static final String TAG_PASSWORD_QUALITY = "password-quality";
    private static final String TAG_PASSWORD_QUALITY_APPLIES_TO_PARENT =
            "password-quality-applies-parent";
    private static final String TAG_POLICIES = "policies";
    private static final String TAG_CROSS_PROFILE_WIDGET_PROVIDERS =
            "cross-profile-widget-providers";
    private static final String TAG_PROVIDER = "provider";
    private static final String TAG_PACKAGE_LIST_ITEM  = "item";
    private static final String TAG_KEEP_UNINSTALLED_PACKAGES  = "keep-uninstalled-packages";
    private static final String TAG_USER_RESTRICTIONS = "user-restrictions";
    private static final String TAG_DEFAULT_ENABLED_USER_RESTRICTIONS =
            "default-enabled-user-restrictions";
    private static final String TAG_RESTRICTION = "restriction";
    private static final String TAG_SHORT_SUPPORT_MESSAGE = "short-support-message";
    private static final String TAG_LONG_SUPPORT_MESSAGE = "long-support-message";
    private static final String TAG_PARENT_ADMIN = "parent-admin";
    private static final String TAG_ORGANIZATION_COLOR = "organization-color";
    private static final String TAG_ORGANIZATION_NAME = "organization-name";
    private static final String TAG_IS_LOGOUT_ENABLED = "is_logout_enabled";
    private static final String TAG_START_USER_SESSION_MESSAGE = "start_user_session_message";
    private static final String TAG_END_USER_SESSION_MESSAGE = "end_user_session_message";
    private static final String TAG_METERED_DATA_DISABLED_PACKAGES =
            "metered_data_disabled_packages";
    private static final String TAG_CROSS_PROFILE_CALENDAR_PACKAGES =
            "cross-profile-calendar-packages";
    private static final String TAG_CROSS_PROFILE_CALENDAR_PACKAGES_NULL =
            "cross-profile-calendar-packages-null";
    private static final String TAG_CROSS_PROFILE_PACKAGES = "cross-profile-packages";
    private static final String TAG_FACTORY_RESET_PROTECTION_POLICY =
            "factory_reset_protection_policy";
    private static final String TAG_SUSPEND_PERSONAL_APPS = "suspend-personal-apps";
    private static final String TAG_PROFILE_MAXIMUM_TIME_OFF = "profile-max-time-off";
    private static final String TAG_PROFILE_OFF_DEADLINE = "profile-off-deadline";
    private static final String TAG_ALWAYS_ON_VPN_PACKAGE = "vpn-package";
    private static final String TAG_ALWAYS_ON_VPN_LOCKDOWN = "vpn-lockdown";
    private static final String TAG_COMMON_CRITERIA_MODE = "common-criteria-mode";
    private static final String TAG_PASSWORD_COMPLEXITY = "password-complexity";
    private static final String TAG_ORGANIZATION_ID = "organization-id";
    private static final String TAG_ENROLLMENT_SPECIFIC_ID = "enrollment-specific-id";
    private static final String TAG_ADMIN_CAN_GRANT_SENSORS_PERMISSIONS =
            "admin-can-grant-sensors-permissions";
    private static final String TAG_PREFERENTIAL_NETWORK_SERVICE_ENABLED =
            "preferential-network-service-enabled";
    private static final String TAG_USB_DATA_SIGNALING = "usb-data-signaling";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_LAST_NETWORK_LOGGING_NOTIFICATION = "last-notification";
    private static final String ATTR_NUM_NETWORK_LOGGING_NOTIFICATIONS = "num-notifications";

    DeviceAdminInfo info;

    static final int DEF_PASSWORD_HISTORY_LENGTH = 0;
    int passwordHistoryLength = DEF_PASSWORD_HISTORY_LENGTH;

    @NonNull
    PasswordPolicy mPasswordPolicy = new PasswordPolicy();
    boolean mPasswordPolicyAppliesToParent = true;

    @DevicePolicyManager.PasswordComplexity
    int mPasswordComplexity = PASSWORD_COMPLEXITY_NONE;

    @DevicePolicyManager.NearbyStreamingPolicy
    int mNearbyNotificationStreamingPolicy = NEARBY_STREAMING_DISABLED;

    @DevicePolicyManager.NearbyStreamingPolicy
    int mNearbyAppStreamingPolicy = NEARBY_STREAMING_DISABLED;

    @Nullable
    FactoryResetProtectionPolicy mFactoryResetProtectionPolicy = null;

    static final long DEF_MAXIMUM_TIME_TO_UNLOCK = 0;
    long maximumTimeToUnlock = DEF_MAXIMUM_TIME_TO_UNLOCK;

    long strongAuthUnlockTimeout = 0; // admin doesn't participate by default

    static final int DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE = 0;
    int maximumFailedPasswordsForWipe = DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE;

    static final long DEF_PASSWORD_EXPIRATION_TIMEOUT = 0;
    long passwordExpirationTimeout = DEF_PASSWORD_EXPIRATION_TIMEOUT;

    static final long DEF_PASSWORD_EXPIRATION_DATE = 0;
    long passwordExpirationDate = DEF_PASSWORD_EXPIRATION_DATE;

    static final int DEF_KEYGUARD_FEATURES_DISABLED = 0; // none

    int disabledKeyguardFeatures = DEF_KEYGUARD_FEATURES_DISABLED;

    boolean encryptionRequested = false;
    boolean testOnlyAdmin = false;
    boolean disableCamera = false;
    boolean disableCallerId = false;
    boolean disableContactsSearch = false;
    boolean disableBluetoothContactSharing = true;
    boolean disableScreenCapture = false;
    boolean requireAutoTime = false;
    boolean forceEphemeralUsers = false;
    boolean isNetworkLoggingEnabled = false;
    boolean isLogoutEnabled = false;

    // one notification after enabling + one more after reboots
    static final int DEF_MAXIMUM_NETWORK_LOGGING_NOTIFICATIONS_SHOWN = 2;
    int numNetworkLoggingNotifications = 0;
    long lastNetworkLoggingNotificationTimeMs = 0; // Time in milliseconds since epoch

    ActiveAdmin parentAdmin;
    final boolean isParent;

    static class TrustAgentInfo {
        public PersistableBundle options;
        TrustAgentInfo(PersistableBundle bundle) {
            options = bundle;
        }
    }

    // The list of packages which are not allowed to use metered data.
    List<String> meteredDisabledPackages;

    final Set<String> accountTypesWithManagementDisabled = new ArraySet<>();

    // The list of permitted accessibility services package namesas set by a profile
    // or device owner. Null means all accessibility services are allowed, empty means
    // none except system services are allowed.
    List<String> permittedAccessiblityServices;

    // The list of permitted input methods package names as set by a profile or device owner.
    // Null means all input methods are allowed, empty means none except system imes are
    // allowed.
    List<String> permittedInputMethods;

    // The list of packages allowed to use a NotificationListenerService to receive events for
    // notifications from this user. Null means that all packages are allowed. Empty list means
    // that only packages from the system are allowed.
    List<String> permittedNotificationListeners;

    // List of package names to keep cached.
    List<String> keepUninstalledPackages;

    // TODO: review implementation decisions with frameworks team
    boolean specifiesGlobalProxy = false;
    String globalProxySpec = null;
    String globalProxyExclusionList = null;

    @NonNull
    ArrayMap<String, TrustAgentInfo> trustAgentInfos = new ArrayMap<>();

    List<String> crossProfileWidgetProviders;

    Bundle userRestrictions;

    // User restrictions that have already been enabled by default for this admin (either when
    // setting the device or profile owner, or during a system update if one of those "enabled
    // by default" restrictions is newly added).
    final Set<String> defaultEnabledRestrictionsAlreadySet = new ArraySet<>();

    // Support text provided by the admin to display to the user.
    CharSequence shortSupportMessage = null;
    CharSequence longSupportMessage = null;

    // Background color of confirm credentials screen. Default: teal.
    static final int DEF_ORGANIZATION_COLOR = Color.parseColor("#00796B");
    int organizationColor = DEF_ORGANIZATION_COLOR;

    // Default title of confirm credentials screen
    String organizationName = null;

    // Message for user switcher
    String startUserSessionMessage = null;
    String endUserSessionMessage = null;

    // The allow list of packages that can access cross profile calendar APIs.
    // This allow list should be in default an empty list, which indicates that no package
    // is allow listed.
    List<String> mCrossProfileCalendarPackages = Collections.emptyList();

    // The allow list of packages that the admin has enabled to be able to request consent from
    // the user to communicate cross-profile. By default, no packages are allowed, which is
    // represented as an empty list.
    List<String> mCrossProfilePackages = Collections.emptyList();

    // Whether the admin explicitly requires personal apps to be suspended
    boolean mSuspendPersonalApps = false;
    // Maximum time the profile owned by this admin can be off.
    long mProfileMaximumTimeOffMillis = 0;
    // Time by which the profile should be turned on according to System.currentTimeMillis().
    long mProfileOffDeadline = 0;

    public String mAlwaysOnVpnPackage;
    public boolean mAlwaysOnVpnLockdown;
    boolean mCommonCriteriaMode;
    public String mOrganizationId;
    public String mEnrollmentSpecificId;
    public boolean mAdminCanGrantSensorsPermissions;
    public boolean mPreferentialNetworkServiceEnabled =
            DevicePolicyManager.PREFERENTIAL_NETWORK_SERVICE_ENABLED_DEFAULT;

    private static final boolean USB_DATA_SIGNALING_ENABLED_DEFAULT = true;
    boolean mUsbDataSignalingEnabled = USB_DATA_SIGNALING_ENABLED_DEFAULT;

    ActiveAdmin(DeviceAdminInfo info, boolean isParent) {
        this.info = info;
        this.isParent = isParent;
    }

    ActiveAdmin getParentActiveAdmin() {
        Preconditions.checkState(!isParent);

        if (parentAdmin == null) {
            parentAdmin = new ActiveAdmin(info, /* parent */ true);
        }
        return parentAdmin;
    }

    boolean hasParentActiveAdmin() {
        return parentAdmin != null;
    }

    int getUid() {
        return info.getActivityInfo().applicationInfo.uid;
    }

    public UserHandle getUserHandle() {
        return UserHandle.of(UserHandle.getUserId(info.getActivityInfo().applicationInfo.uid));
    }

    void writeToXml(TypedXmlSerializer out)
            throws IllegalArgumentException, IllegalStateException, IOException {
        out.startTag(null, TAG_POLICIES);
        info.writePoliciesToXml(out);
        out.endTag(null, TAG_POLICIES);
        if (mPasswordPolicy.quality != PASSWORD_QUALITY_UNSPECIFIED) {
            writeAttributeValueToXml(
                    out, TAG_PASSWORD_QUALITY, mPasswordPolicy.quality);
            if (mPasswordPolicy.length != PasswordPolicy.DEF_MINIMUM_LENGTH) {
                writeAttributeValueToXml(
                        out, TAG_MIN_PASSWORD_LENGTH, mPasswordPolicy.length);
            }
            if (mPasswordPolicy.upperCase != PasswordPolicy.DEF_MINIMUM_UPPER_CASE) {
                writeAttributeValueToXml(
                        out, TAG_MIN_PASSWORD_UPPERCASE, mPasswordPolicy.upperCase);
            }
            if (mPasswordPolicy.lowerCase != PasswordPolicy.DEF_MINIMUM_LOWER_CASE) {
                writeAttributeValueToXml(
                        out, TAG_MIN_PASSWORD_LOWERCASE, mPasswordPolicy.lowerCase);
            }
            if (mPasswordPolicy.letters != PasswordPolicy.DEF_MINIMUM_LETTERS) {
                writeAttributeValueToXml(
                        out, TAG_MIN_PASSWORD_LETTERS, mPasswordPolicy.letters);
            }
            if (mPasswordPolicy.numeric != PasswordPolicy.DEF_MINIMUM_NUMERIC) {
                writeAttributeValueToXml(
                        out, TAG_MIN_PASSWORD_NUMERIC, mPasswordPolicy.numeric);
            }
            if (mPasswordPolicy.symbols != PasswordPolicy.DEF_MINIMUM_SYMBOLS) {
                writeAttributeValueToXml(
                        out, TAG_MIN_PASSWORD_SYMBOLS, mPasswordPolicy.symbols);
            }
            if (mPasswordPolicy.nonLetter > PasswordPolicy.DEF_MINIMUM_NON_LETTER) {
                writeAttributeValueToXml(
                        out, TAG_MIN_PASSWORD_NONLETTER, mPasswordPolicy.nonLetter);
            }

            writeAttributeValueToXml(out, TAG_PASSWORD_QUALITY_APPLIES_TO_PARENT,
                    mPasswordPolicyAppliesToParent);
        }
        if (passwordHistoryLength != DEF_PASSWORD_HISTORY_LENGTH) {
            writeAttributeValueToXml(
                    out, TAG_PASSWORD_HISTORY_LENGTH, passwordHistoryLength);
        }
        if (maximumTimeToUnlock != DEF_MAXIMUM_TIME_TO_UNLOCK) {
            writeAttributeValueToXml(
                    out, TAG_MAX_TIME_TO_UNLOCK, maximumTimeToUnlock);
        }
        if (strongAuthUnlockTimeout != DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS) {
            writeAttributeValueToXml(
                    out, TAG_STRONG_AUTH_UNLOCK_TIMEOUT, strongAuthUnlockTimeout);
        }
        if (maximumFailedPasswordsForWipe != DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE) {
            writeAttributeValueToXml(
                    out, TAG_MAX_FAILED_PASSWORD_WIPE, maximumFailedPasswordsForWipe);
        }
        if (specifiesGlobalProxy) {
            writeAttributeValueToXml(
                    out, TAG_SPECIFIES_GLOBAL_PROXY, specifiesGlobalProxy);
            if (globalProxySpec != null) {
                writeAttributeValueToXml(out, TAG_GLOBAL_PROXY_SPEC, globalProxySpec);
            }
            if (globalProxyExclusionList != null) {
                writeAttributeValueToXml(
                        out, TAG_GLOBAL_PROXY_EXCLUSION_LIST, globalProxyExclusionList);
            }
        }
        if (passwordExpirationTimeout != DEF_PASSWORD_EXPIRATION_TIMEOUT) {
            writeAttributeValueToXml(
                    out, TAG_PASSWORD_EXPIRATION_TIMEOUT, passwordExpirationTimeout);
        }
        if (passwordExpirationDate != DEF_PASSWORD_EXPIRATION_DATE) {
            writeAttributeValueToXml(
                    out, TAG_PASSWORD_EXPIRATION_DATE, passwordExpirationDate);
        }
        if (encryptionRequested) {
            writeAttributeValueToXml(
                    out, TAG_ENCRYPTION_REQUESTED, encryptionRequested);
        }
        if (testOnlyAdmin) {
            writeAttributeValueToXml(
                    out, TAG_TEST_ONLY_ADMIN, testOnlyAdmin);
        }
        if (disableCamera) {
            writeAttributeValueToXml(
                    out, TAG_DISABLE_CAMERA, disableCamera);
        }
        if (disableCallerId) {
            writeAttributeValueToXml(
                    out, TAG_DISABLE_CALLER_ID, disableCallerId);
        }
        if (disableContactsSearch) {
            writeAttributeValueToXml(
                    out, TAG_DISABLE_CONTACTS_SEARCH, disableContactsSearch);
        }
        if (!disableBluetoothContactSharing) {
            writeAttributeValueToXml(
                    out, TAG_DISABLE_BLUETOOTH_CONTACT_SHARING, disableBluetoothContactSharing);
        }
        if (disableScreenCapture) {
            writeAttributeValueToXml(
                    out, TAG_DISABLE_SCREEN_CAPTURE, disableScreenCapture);
        }
        if (requireAutoTime) {
            writeAttributeValueToXml(
                    out, TAG_REQUIRE_AUTO_TIME, requireAutoTime);
        }
        if (forceEphemeralUsers) {
            writeAttributeValueToXml(
                    out, TAG_FORCE_EPHEMERAL_USERS, forceEphemeralUsers);
        }
        if (isNetworkLoggingEnabled) {
            out.startTag(null, TAG_IS_NETWORK_LOGGING_ENABLED);
            out.attributeBoolean(null, ATTR_VALUE, isNetworkLoggingEnabled);
            out.attributeInt(null, ATTR_NUM_NETWORK_LOGGING_NOTIFICATIONS,
                    numNetworkLoggingNotifications);
            out.attributeLong(null, ATTR_LAST_NETWORK_LOGGING_NOTIFICATION,
                    lastNetworkLoggingNotificationTimeMs);
            out.endTag(null, TAG_IS_NETWORK_LOGGING_ENABLED);
        }
        if (disabledKeyguardFeatures != DEF_KEYGUARD_FEATURES_DISABLED) {
            writeAttributeValueToXml(
                    out, TAG_DISABLE_KEYGUARD_FEATURES, disabledKeyguardFeatures);
        }
        if (!accountTypesWithManagementDisabled.isEmpty()) {
            writeAttributeValuesToXml(
                    out, TAG_DISABLE_ACCOUNT_MANAGEMENT, TAG_ACCOUNT_TYPE,
                    accountTypesWithManagementDisabled);
        }
        if (!trustAgentInfos.isEmpty()) {
            Set<Map.Entry<String, TrustAgentInfo>> set = trustAgentInfos.entrySet();
            out.startTag(null, TAG_MANAGE_TRUST_AGENT_FEATURES);
            for (Map.Entry<String, TrustAgentInfo> entry : set) {
                TrustAgentInfo trustAgentInfo = entry.getValue();
                out.startTag(null, TAG_TRUST_AGENT_COMPONENT);
                out.attribute(null, ATTR_VALUE, entry.getKey());
                if (trustAgentInfo.options != null) {
                    out.startTag(null, TAG_TRUST_AGENT_COMPONENT_OPTIONS);
                    try {
                        trustAgentInfo.options.saveToXml(out);
                    } catch (XmlPullParserException e) {
                        Slogf.e(LOG_TAG, e, "Failed to save TrustAgent options");
                    }
                    out.endTag(null, TAG_TRUST_AGENT_COMPONENT_OPTIONS);
                }
                out.endTag(null, TAG_TRUST_AGENT_COMPONENT);
            }
            out.endTag(null, TAG_MANAGE_TRUST_AGENT_FEATURES);
        }
        if (crossProfileWidgetProviders != null && !crossProfileWidgetProviders.isEmpty()) {
            writeAttributeValuesToXml(
                    out, TAG_CROSS_PROFILE_WIDGET_PROVIDERS, TAG_PROVIDER,
                    crossProfileWidgetProviders);
        }
        writePackageListToXml(out, TAG_PERMITTED_ACCESSIBILITY_SERVICES,
                permittedAccessiblityServices);
        writePackageListToXml(out, TAG_PERMITTED_IMES, permittedInputMethods);
        writePackageListToXml(out, TAG_PERMITTED_NOTIFICATION_LISTENERS,
                permittedNotificationListeners);
        writePackageListToXml(out, TAG_KEEP_UNINSTALLED_PACKAGES, keepUninstalledPackages);
        writePackageListToXml(out, TAG_METERED_DATA_DISABLED_PACKAGES, meteredDisabledPackages);
        if (hasUserRestrictions()) {
            UserRestrictionsUtils.writeRestrictions(
                    out, userRestrictions, TAG_USER_RESTRICTIONS);
        }
        if (!defaultEnabledRestrictionsAlreadySet.isEmpty()) {
            writeAttributeValuesToXml(out, TAG_DEFAULT_ENABLED_USER_RESTRICTIONS,
                    TAG_RESTRICTION,
                    defaultEnabledRestrictionsAlreadySet);
        }
        if (!TextUtils.isEmpty(shortSupportMessage)) {
            writeTextToXml(out, TAG_SHORT_SUPPORT_MESSAGE, shortSupportMessage.toString());
        }
        if (!TextUtils.isEmpty(longSupportMessage)) {
            writeTextToXml(out, TAG_LONG_SUPPORT_MESSAGE, longSupportMessage.toString());
        }
        if (parentAdmin != null) {
            out.startTag(null, TAG_PARENT_ADMIN);
            parentAdmin.writeToXml(out);
            out.endTag(null, TAG_PARENT_ADMIN);
        }
        if (organizationColor != DEF_ORGANIZATION_COLOR) {
            writeAttributeValueToXml(out, TAG_ORGANIZATION_COLOR, organizationColor);
        }
        if (organizationName != null) {
            writeTextToXml(out, TAG_ORGANIZATION_NAME, organizationName);
        }
        if (isLogoutEnabled) {
            writeAttributeValueToXml(out, TAG_IS_LOGOUT_ENABLED, isLogoutEnabled);
        }
        if (startUserSessionMessage != null) {
            writeTextToXml(out, TAG_START_USER_SESSION_MESSAGE, startUserSessionMessage);
        }
        if (endUserSessionMessage != null) {
            writeTextToXml(out, TAG_END_USER_SESSION_MESSAGE, endUserSessionMessage);
        }
        if (mCrossProfileCalendarPackages == null) {
            out.startTag(null, TAG_CROSS_PROFILE_CALENDAR_PACKAGES_NULL);
            out.endTag(null, TAG_CROSS_PROFILE_CALENDAR_PACKAGES_NULL);
        } else {
            writePackageListToXml(out, TAG_CROSS_PROFILE_CALENDAR_PACKAGES,
                    mCrossProfileCalendarPackages);
        }
        writePackageListToXml(out, TAG_CROSS_PROFILE_PACKAGES, mCrossProfilePackages);
        if (mFactoryResetProtectionPolicy != null) {
            out.startTag(null, TAG_FACTORY_RESET_PROTECTION_POLICY);
            mFactoryResetProtectionPolicy.writeToXml(out);
            out.endTag(null, TAG_FACTORY_RESET_PROTECTION_POLICY);
        }
        if (mSuspendPersonalApps) {
            writeAttributeValueToXml(out, TAG_SUSPEND_PERSONAL_APPS, mSuspendPersonalApps);
        }
        if (mProfileMaximumTimeOffMillis != 0) {
            writeAttributeValueToXml(out, TAG_PROFILE_MAXIMUM_TIME_OFF,
                    mProfileMaximumTimeOffMillis);
        }
        if (mProfileMaximumTimeOffMillis != 0) {
            writeAttributeValueToXml(out, TAG_PROFILE_OFF_DEADLINE, mProfileOffDeadline);
        }
        if (!TextUtils.isEmpty(mAlwaysOnVpnPackage)) {
            writeAttributeValueToXml(out, TAG_ALWAYS_ON_VPN_PACKAGE, mAlwaysOnVpnPackage);
        }
        if (mAlwaysOnVpnLockdown) {
            writeAttributeValueToXml(out, TAG_ALWAYS_ON_VPN_LOCKDOWN, mAlwaysOnVpnLockdown);
        }
        if (mCommonCriteriaMode) {
            writeAttributeValueToXml(out, TAG_COMMON_CRITERIA_MODE, mCommonCriteriaMode);
        }

        if (mPasswordComplexity != PASSWORD_COMPLEXITY_NONE) {
            writeAttributeValueToXml(out, TAG_PASSWORD_COMPLEXITY, mPasswordComplexity);
        }
        if (mNearbyNotificationStreamingPolicy != NEARBY_STREAMING_DISABLED) {
            writeAttributeValueToXml(out, TAG_NEARBY_NOTIFICATION_STREAMING_POLICY,
                    mNearbyNotificationStreamingPolicy);
        }
        if (mNearbyAppStreamingPolicy != NEARBY_STREAMING_DISABLED) {
            writeAttributeValueToXml(out, TAG_NEARBY_APP_STREAMING_POLICY,
                    mNearbyAppStreamingPolicy);
        }
        if (!TextUtils.isEmpty(mOrganizationId)) {
            writeTextToXml(out, TAG_ORGANIZATION_ID, mOrganizationId);
        }
        if (!TextUtils.isEmpty(mEnrollmentSpecificId)) {
            writeTextToXml(out, TAG_ENROLLMENT_SPECIFIC_ID, mEnrollmentSpecificId);
        }
        writeAttributeValueToXml(out, TAG_ADMIN_CAN_GRANT_SENSORS_PERMISSIONS,
                mAdminCanGrantSensorsPermissions);
        writeAttributeValueToXml(out, TAG_PREFERENTIAL_NETWORK_SERVICE_ENABLED,
                mPreferentialNetworkServiceEnabled);
        if (mUsbDataSignalingEnabled != USB_DATA_SIGNALING_ENABLED_DEFAULT) {
            writeAttributeValueToXml(out, TAG_USB_DATA_SIGNALING, mUsbDataSignalingEnabled);
        }
    }

    void writeTextToXml(TypedXmlSerializer out, String tag, String text) throws IOException {
        out.startTag(null, tag);
        out.text(text);
        out.endTag(null, tag);
    }

    void writePackageListToXml(TypedXmlSerializer out, String outerTag,
            List<String> packageList)
            throws IllegalArgumentException, IllegalStateException, IOException {
        if (packageList == null) {
            return;
        }
        writeAttributeValuesToXml(out, outerTag, TAG_PACKAGE_LIST_ITEM, packageList);
    }

    void writeAttributeValueToXml(TypedXmlSerializer out, String tag, String value)
            throws IOException {
        out.startTag(null, tag);
        out.attribute(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    void writeAttributeValueToXml(TypedXmlSerializer out, String tag, int value)
            throws IOException {
        out.startTag(null, tag);
        out.attributeInt(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    void writeAttributeValueToXml(TypedXmlSerializer out, String tag, long value)
            throws IOException {
        out.startTag(null, tag);
        out.attributeLong(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    void writeAttributeValueToXml(TypedXmlSerializer out, String tag, boolean value)
            throws IOException {
        out.startTag(null, tag);
        out.attributeBoolean(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    void writeAttributeValuesToXml(TypedXmlSerializer out, String outerTag, String innerTag,
            @NonNull Collection<String> values) throws IOException {
        out.startTag(null, outerTag);
        for (String value : values) {
            out.startTag(null, innerTag);
            out.attribute(null, ATTR_VALUE, value);
            out.endTag(null, innerTag);
        }
        out.endTag(null, outerTag);
    }

    void readFromXml(TypedXmlPullParser parser, boolean shouldOverridePolicies)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != END_DOCUMENT
               && (type != END_TAG || parser.getDepth() > outerDepth)) {
            if (type == END_TAG || type == TEXT) {
                continue;
            }
            String tag = parser.getName();
            if (TAG_POLICIES.equals(tag)) {
                if (shouldOverridePolicies) {
                    Slogf.d(LOG_TAG, "Overriding device admin policies from XML.");
                    info.readPoliciesFromXml(parser);
                }
            } else if (TAG_PASSWORD_QUALITY.equals(tag)) {
                mPasswordPolicy.quality = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_MIN_PASSWORD_LENGTH.equals(tag)) {
                mPasswordPolicy.length = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_PASSWORD_HISTORY_LENGTH.equals(tag)) {
                passwordHistoryLength = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_MIN_PASSWORD_UPPERCASE.equals(tag)) {
                mPasswordPolicy.upperCase = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_MIN_PASSWORD_LOWERCASE.equals(tag)) {
                mPasswordPolicy.lowerCase = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_MIN_PASSWORD_LETTERS.equals(tag)) {
                mPasswordPolicy.letters = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_MIN_PASSWORD_NUMERIC.equals(tag)) {
                mPasswordPolicy.numeric = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_MIN_PASSWORD_SYMBOLS.equals(tag)) {
                mPasswordPolicy.symbols = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_MIN_PASSWORD_NONLETTER.equals(tag)) {
                mPasswordPolicy.nonLetter = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_PASSWORD_QUALITY_APPLIES_TO_PARENT.equals(tag)) {
                mPasswordPolicyAppliesToParent = parser.getAttributeBoolean(null, ATTR_VALUE);
            } else if (TAG_MAX_TIME_TO_UNLOCK.equals(tag)) {
                maximumTimeToUnlock = parser.getAttributeLong(null, ATTR_VALUE);
            } else if (TAG_STRONG_AUTH_UNLOCK_TIMEOUT.equals(tag)) {
                strongAuthUnlockTimeout = parser.getAttributeLong(null, ATTR_VALUE);
            } else if (TAG_MAX_FAILED_PASSWORD_WIPE.equals(tag)) {
                maximumFailedPasswordsForWipe = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_SPECIFIES_GLOBAL_PROXY.equals(tag)) {
                specifiesGlobalProxy = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_GLOBAL_PROXY_SPEC.equals(tag)) {
                globalProxySpec =
                    parser.getAttributeValue(null, ATTR_VALUE);
            } else if (TAG_GLOBAL_PROXY_EXCLUSION_LIST.equals(tag)) {
                globalProxyExclusionList =
                    parser.getAttributeValue(null, ATTR_VALUE);
            } else if (TAG_PASSWORD_EXPIRATION_TIMEOUT.equals(tag)) {
                passwordExpirationTimeout = parser.getAttributeLong(null, ATTR_VALUE);
            } else if (TAG_PASSWORD_EXPIRATION_DATE.equals(tag)) {
                passwordExpirationDate = parser.getAttributeLong(null, ATTR_VALUE);
            } else if (TAG_ENCRYPTION_REQUESTED.equals(tag)) {
                encryptionRequested = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_TEST_ONLY_ADMIN.equals(tag)) {
                testOnlyAdmin = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_DISABLE_CAMERA.equals(tag)) {
                disableCamera = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_DISABLE_CALLER_ID.equals(tag)) {
                disableCallerId = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_DISABLE_CONTACTS_SEARCH.equals(tag)) {
                disableContactsSearch = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_DISABLE_BLUETOOTH_CONTACT_SHARING.equals(tag)) {
                disableBluetoothContactSharing =
                        parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_DISABLE_SCREEN_CAPTURE.equals(tag)) {
                disableScreenCapture = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_REQUIRE_AUTO_TIME.equals(tag)) {
                requireAutoTime = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_FORCE_EPHEMERAL_USERS.equals(tag)) {
                forceEphemeralUsers = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_IS_NETWORK_LOGGING_ENABLED.equals(tag)) {
                isNetworkLoggingEnabled = parser.getAttributeBoolean(null, ATTR_VALUE, false);
                lastNetworkLoggingNotificationTimeMs = parser.getAttributeLong(null,
                        ATTR_LAST_NETWORK_LOGGING_NOTIFICATION);
                numNetworkLoggingNotifications = parser.getAttributeInt(null,
                        ATTR_NUM_NETWORK_LOGGING_NOTIFICATIONS);
            } else if (TAG_DISABLE_KEYGUARD_FEATURES.equals(tag)) {
                disabledKeyguardFeatures = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_DISABLE_ACCOUNT_MANAGEMENT.equals(tag)) {
                readAttributeValues(
                        parser, TAG_ACCOUNT_TYPE, accountTypesWithManagementDisabled);
            } else if (TAG_MANAGE_TRUST_AGENT_FEATURES.equals(tag)) {
                trustAgentInfos = getAllTrustAgentInfos(parser, tag);
            } else if (TAG_CROSS_PROFILE_WIDGET_PROVIDERS.equals(tag)) {
                crossProfileWidgetProviders = new ArrayList<>();
                readAttributeValues(parser, TAG_PROVIDER, crossProfileWidgetProviders);
            } else if (TAG_PERMITTED_ACCESSIBILITY_SERVICES.equals(tag)) {
                permittedAccessiblityServices = readPackageList(parser, tag);
            } else if (TAG_PERMITTED_IMES.equals(tag)) {
                permittedInputMethods = readPackageList(parser, tag);
            } else if (TAG_PERMITTED_NOTIFICATION_LISTENERS.equals(tag)) {
                permittedNotificationListeners = readPackageList(parser, tag);
            } else if (TAG_KEEP_UNINSTALLED_PACKAGES.equals(tag)) {
                keepUninstalledPackages = readPackageList(parser, tag);
            } else if (TAG_METERED_DATA_DISABLED_PACKAGES.equals(tag)) {
                meteredDisabledPackages = readPackageList(parser, tag);
            } else if (TAG_USER_RESTRICTIONS.equals(tag)) {
                userRestrictions = UserRestrictionsUtils.readRestrictions(parser);
            } else if (TAG_DEFAULT_ENABLED_USER_RESTRICTIONS.equals(tag)) {
                readAttributeValues(
                        parser, TAG_RESTRICTION, defaultEnabledRestrictionsAlreadySet);
            } else if (TAG_SHORT_SUPPORT_MESSAGE.equals(tag)) {
                type = parser.next();
                if (type == TypedXmlPullParser.TEXT) {
                    shortSupportMessage = parser.getText();
                } else {
                    Slogf.w(LOG_TAG, "Missing text when loading short support message");
                }
            } else if (TAG_LONG_SUPPORT_MESSAGE.equals(tag)) {
                type = parser.next();
                if (type == TypedXmlPullParser.TEXT) {
                    longSupportMessage = parser.getText();
                } else {
                    Slogf.w(LOG_TAG, "Missing text when loading long support message");
                }
            } else if (TAG_PARENT_ADMIN.equals(tag)) {
                Preconditions.checkState(!isParent);
                parentAdmin = new ActiveAdmin(info, /* parent */ true);
                parentAdmin.readFromXml(parser, shouldOverridePolicies);
            } else if (TAG_ORGANIZATION_COLOR.equals(tag)) {
                organizationColor = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_ORGANIZATION_NAME.equals(tag)) {
                type = parser.next();
                if (type == TypedXmlPullParser.TEXT) {
                    organizationName = parser.getText();
                } else {
                    Slogf.w(LOG_TAG, "Missing text when loading organization name");
                }
            } else if (TAG_IS_LOGOUT_ENABLED.equals(tag)) {
                isLogoutEnabled = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_START_USER_SESSION_MESSAGE.equals(tag)) {
                type = parser.next();
                if (type == TypedXmlPullParser.TEXT) {
                    startUserSessionMessage = parser.getText();
                } else {
                    Slogf.w(LOG_TAG, "Missing text when loading start session message");
                }
            } else if (TAG_END_USER_SESSION_MESSAGE.equals(tag)) {
                type = parser.next();
                if (type == TypedXmlPullParser.TEXT) {
                    endUserSessionMessage = parser.getText();
                } else {
                    Slogf.w(LOG_TAG, "Missing text when loading end session message");
                }
            } else if (TAG_CROSS_PROFILE_CALENDAR_PACKAGES.equals(tag)) {
                mCrossProfileCalendarPackages = readPackageList(parser, tag);
            } else if (TAG_CROSS_PROFILE_CALENDAR_PACKAGES_NULL.equals(tag)) {
                mCrossProfileCalendarPackages = null;
            } else if (TAG_CROSS_PROFILE_PACKAGES.equals(tag)) {
                mCrossProfilePackages = readPackageList(parser, tag);
            } else if (TAG_FACTORY_RESET_PROTECTION_POLICY.equals(tag)) {
                mFactoryResetProtectionPolicy = FactoryResetProtectionPolicy.readFromXml(
                            parser);
            } else if (TAG_SUSPEND_PERSONAL_APPS.equals(tag)) {
                mSuspendPersonalApps = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_PROFILE_MAXIMUM_TIME_OFF.equals(tag)) {
                mProfileMaximumTimeOffMillis =
                        parser.getAttributeLong(null, ATTR_VALUE);
            } else if (TAG_PROFILE_OFF_DEADLINE.equals(tag)) {
                mProfileOffDeadline =
                        parser.getAttributeLong(null, ATTR_VALUE);
            } else if (TAG_ALWAYS_ON_VPN_PACKAGE.equals(tag)) {
                mAlwaysOnVpnPackage = parser.getAttributeValue(null, ATTR_VALUE);
            } else if (TAG_ALWAYS_ON_VPN_LOCKDOWN.equals(tag)) {
                mAlwaysOnVpnLockdown = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_PREFERENTIAL_NETWORK_SERVICE_ENABLED.equals(tag)) {
                mPreferentialNetworkServiceEnabled = parser.getAttributeBoolean(null, ATTR_VALUE,
                        DevicePolicyManager.PREFERENTIAL_NETWORK_SERVICE_ENABLED_DEFAULT);
            } else if (TAG_COMMON_CRITERIA_MODE.equals(tag)) {
                mCommonCriteriaMode = parser.getAttributeBoolean(null, ATTR_VALUE, false);
            } else if (TAG_PASSWORD_COMPLEXITY.equals(tag)) {
                mPasswordComplexity = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_NEARBY_NOTIFICATION_STREAMING_POLICY.equals(tag)) {
                mNearbyNotificationStreamingPolicy = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_NEARBY_APP_STREAMING_POLICY.equals(tag)) {
                mNearbyAppStreamingPolicy = parser.getAttributeInt(null, ATTR_VALUE);
            } else if (TAG_ORGANIZATION_ID.equals(tag)) {
                type = parser.next();
                if (type == TypedXmlPullParser.TEXT) {
                    mOrganizationId = parser.getText();
                } else {
                    Slogf.w(LOG_TAG, "Missing Organization ID.");
                }
            } else if (TAG_ENROLLMENT_SPECIFIC_ID.equals(tag)) {
                type = parser.next();
                if (type == TypedXmlPullParser.TEXT) {
                    mEnrollmentSpecificId = parser.getText();
                } else {
                    Slogf.w(LOG_TAG, "Missing Enrollment-specific ID.");
                }
            } else if (TAG_ADMIN_CAN_GRANT_SENSORS_PERMISSIONS.equals(tag)) {
                mAdminCanGrantSensorsPermissions = parser.getAttributeBoolean(null, ATTR_VALUE,
                        false);
            } else if (TAG_USB_DATA_SIGNALING.equals(tag)) {
                mUsbDataSignalingEnabled = parser.getAttributeBoolean(null, ATTR_VALUE,
                        USB_DATA_SIGNALING_ENABLED_DEFAULT);
            } else {
                Slogf.w(LOG_TAG, "Unknown admin tag: %s", tag);
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private List<String> readPackageList(TypedXmlPullParser parser,
            String tag) throws XmlPullParserException, IOException {
        List<String> result = new ArrayList<String>();
        int outerDepth = parser.getDepth();
        int outerType;
        while ((outerType = parser.next()) != TypedXmlPullParser.END_DOCUMENT
                && (outerType != TypedXmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (outerType == TypedXmlPullParser.END_TAG || outerType == TypedXmlPullParser.TEXT) {
                continue;
            }
            String outerTag = parser.getName();
            if (TAG_PACKAGE_LIST_ITEM.equals(outerTag)) {
                String packageName = parser.getAttributeValue(null, ATTR_VALUE);
                if (packageName != null) {
                    result.add(packageName);
                } else {
                    Slogf.w(LOG_TAG, "Package name missing under %s", outerTag);
                }
            } else {
                Slogf.w(LOG_TAG, "Unknown tag under %s: ", tag, outerTag);
            }
        }
        return result;
    }

    private void readAttributeValues(
            TypedXmlPullParser parser, String tag, Collection<String> result)
            throws XmlPullParserException, IOException {
        result.clear();
        int outerDepthDAM = parser.getDepth();
        int typeDAM;
        while ((typeDAM = parser.next()) != END_DOCUMENT
                && (typeDAM != END_TAG || parser.getDepth() > outerDepthDAM)) {
            if (typeDAM == END_TAG || typeDAM == TEXT) {
                continue;
            }
            String tagDAM = parser.getName();
            if (tag.equals(tagDAM)) {
                result.add(parser.getAttributeValue(null, ATTR_VALUE));
            } else {
                Slogf.e(LOG_TAG, "Expected tag %s but found %s", tag, tagDAM);
            }
        }
    }

    @NonNull
    private ArrayMap<String, TrustAgentInfo> getAllTrustAgentInfos(
            TypedXmlPullParser parser, String tag) throws XmlPullParserException, IOException {
        int outerDepthDAM = parser.getDepth();
        int typeDAM;
        final ArrayMap<String, TrustAgentInfo> result = new ArrayMap<>();
        while ((typeDAM = parser.next()) != END_DOCUMENT
                && (typeDAM != END_TAG || parser.getDepth() > outerDepthDAM)) {
            if (typeDAM == END_TAG || typeDAM == TEXT) {
                continue;
            }
            String tagDAM = parser.getName();
            if (TAG_TRUST_AGENT_COMPONENT.equals(tagDAM)) {
                final String component = parser.getAttributeValue(null, ATTR_VALUE);
                final TrustAgentInfo trustAgentInfo = getTrustAgentInfo(parser, tag);
                result.put(component, trustAgentInfo);
            } else {
                Slogf.w(LOG_TAG, "Unknown tag under %s: %s", tag, tagDAM);
            }
        }
        return result;
    }

    private TrustAgentInfo getTrustAgentInfo(TypedXmlPullParser parser, String tag)
            throws XmlPullParserException, IOException  {
        int outerDepthDAM = parser.getDepth();
        int typeDAM;
        TrustAgentInfo result = new TrustAgentInfo(null);
        while ((typeDAM = parser.next()) != END_DOCUMENT
                && (typeDAM != END_TAG || parser.getDepth() > outerDepthDAM)) {
            if (typeDAM == END_TAG || typeDAM == TEXT) {
                continue;
            }
            String tagDAM = parser.getName();
            if (TAG_TRUST_AGENT_COMPONENT_OPTIONS.equals(tagDAM)) {
                result.options = PersistableBundle.restoreFromXml(parser);
            } else {
                Slogf.w(LOG_TAG, "Unknown tag under %s: %s", tag, tagDAM);
            }
        }
        return result;
    }

    boolean hasUserRestrictions() {
        return userRestrictions != null && userRestrictions.size() > 0;
    }

    Bundle ensureUserRestrictions() {
        if (userRestrictions == null) {
            userRestrictions = new Bundle();
        }
        return userRestrictions;
    }

    public void transfer(DeviceAdminInfo deviceAdminInfo) {
        if (hasParentActiveAdmin()) {
            parentAdmin.info = deviceAdminInfo;
        }
        info = deviceAdminInfo;
    }

    Bundle addSyntheticRestrictions(Bundle restrictions) {
        if (disableCamera) {
            restrictions.putBoolean(UserManager.DISALLOW_CAMERA, true);
        }
        if (requireAutoTime) {
            restrictions.putBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME, true);
        }
        return restrictions;
    }

    static Bundle removeDeprecatedRestrictions(Bundle restrictions) {
        for (String deprecatedRestriction: UserRestrictionsUtils.DEPRECATED_USER_RESTRICTIONS) {
            restrictions.remove(deprecatedRestriction);
        }
        return restrictions;
    }

    static Bundle filterRestrictions(Bundle restrictions, Predicate<String> filter) {
        Bundle result = new Bundle();
        for (String key : restrictions.keySet()) {
            if (!restrictions.getBoolean(key)) {
                continue;
            }
            if (filter.test(key)) {
                result.putBoolean(key, true);
            }
        }
        return result;
    }

    Bundle getEffectiveRestrictions() {
        return addSyntheticRestrictions(
                removeDeprecatedRestrictions(new Bundle(ensureUserRestrictions())));
    }

    Bundle getLocalUserRestrictions(int adminType) {
        return filterRestrictions(getEffectiveRestrictions(),
                key -> UserRestrictionsUtils.isLocal(adminType, key));
    }

    Bundle getGlobalUserRestrictions(int adminType) {
        return filterRestrictions(getEffectiveRestrictions(),
                key -> UserRestrictionsUtils.isGlobal(adminType, key));
    }

    void dump(IndentingPrintWriter pw) {
        pw.print("uid=");
        pw.println(getUid());

        pw.print("testOnlyAdmin=");
        pw.println(testOnlyAdmin);

        pw.println("policies:");
        ArrayList<DeviceAdminInfo.PolicyInfo> pols = info.getUsedPolicies();
        if (pols != null) {
            pw.increaseIndent();
            for (int i = 0; i < pols.size(); i++) {
                pw.println(pols.get(i).tag);
            }
            pw.decreaseIndent();
        }

        pw.print("passwordQuality=0x");
        pw.println(Integer.toHexString(mPasswordPolicy.quality));

        pw.print("minimumPasswordLength=");
        pw.println(mPasswordPolicy.length);

        pw.print("passwordHistoryLength=");
        pw.println(passwordHistoryLength);

        pw.print("minimumPasswordUpperCase=");
        pw.println(mPasswordPolicy.upperCase);

        pw.print("minimumPasswordLowerCase=");
        pw.println(mPasswordPolicy.lowerCase);

        pw.print("minimumPasswordLetters=");
        pw.println(mPasswordPolicy.letters);

        pw.print("minimumPasswordNumeric=");
        pw.println(mPasswordPolicy.numeric);

        pw.print("minimumPasswordSymbols=");
        pw.println(mPasswordPolicy.symbols);

        pw.print("minimumPasswordNonLetter=");
        pw.println(mPasswordPolicy.nonLetter);

        pw.print("passwordPolicyAppliesToParent=");
        pw.println(mPasswordPolicyAppliesToParent);

        pw.print("maximumTimeToUnlock=");
        pw.println(maximumTimeToUnlock);

        pw.print("strongAuthUnlockTimeout=");
        pw.println(strongAuthUnlockTimeout);

        pw.print("maximumFailedPasswordsForWipe=");
        pw.println(maximumFailedPasswordsForWipe);

        pw.print("specifiesGlobalProxy=");
        pw.println(specifiesGlobalProxy);

        pw.print("passwordExpirationTimeout=");
        pw.println(passwordExpirationTimeout);

        pw.print("passwordExpirationDate=");
        pw.println(passwordExpirationDate);

        if (globalProxySpec != null) {
            pw.print("globalProxySpec=");
            pw.println(globalProxySpec);
        }
        if (globalProxyExclusionList != null) {
            pw.print("globalProxyEclusionList=");
            pw.println(globalProxyExclusionList);
        }
        pw.print("encryptionRequested=");
        pw.println(encryptionRequested);

        pw.print("disableCamera=");
        pw.println(disableCamera);

        pw.print("disableCallerId=");
        pw.println(disableCallerId);

        pw.print("disableContactsSearch=");
        pw.println(disableContactsSearch);

        pw.print("disableBluetoothContactSharing=");
        pw.println(disableBluetoothContactSharing);

        pw.print("disableScreenCapture=");
        pw.println(disableScreenCapture);

        pw.print("requireAutoTime=");
        pw.println(requireAutoTime);

        pw.print("forceEphemeralUsers=");
        pw.println(forceEphemeralUsers);

        pw.print("isNetworkLoggingEnabled=");
        pw.println(isNetworkLoggingEnabled);

        pw.print("disabledKeyguardFeatures=");
        pw.println(disabledKeyguardFeatures);

        pw.print("crossProfileWidgetProviders=");
        pw.println(crossProfileWidgetProviders);

        if (permittedAccessiblityServices != null) {
            pw.print("permittedAccessibilityServices=");
            pw.println(permittedAccessiblityServices);
        }

        if (permittedInputMethods != null) {
            pw.print("permittedInputMethods=");
            pw.println(permittedInputMethods);
        }

        if (permittedNotificationListeners != null) {
            pw.print("permittedNotificationListeners=");
            pw.println(permittedNotificationListeners);
        }

        if (keepUninstalledPackages != null) {
            pw.print("keepUninstalledPackages=");
            pw.println(keepUninstalledPackages);
        }

        pw.print("organizationColor=");
        pw.println(organizationColor);

        if (organizationName != null) {
            pw.print("organizationName=");
            pw.println(organizationName);
        }

        pw.println("userRestrictions:");
        UserRestrictionsUtils.dumpRestrictions(pw, "  ", userRestrictions);

        pw.print("defaultEnabledRestrictionsAlreadySet=");
        pw.println(defaultEnabledRestrictionsAlreadySet);

        pw.print("isParent=");
        pw.println(isParent);

        if (parentAdmin != null) {
            pw.println("parentAdmin:");
            pw.increaseIndent();
            parentAdmin.dump(pw);
            pw.decreaseIndent();
        }

        if (mCrossProfileCalendarPackages != null) {
            pw.print("mCrossProfileCalendarPackages=");
            pw.println(mCrossProfileCalendarPackages);
        }

        pw.print("mCrossProfilePackages=");
        pw.println(mCrossProfilePackages);

        pw.print("mSuspendPersonalApps=");
        pw.println(mSuspendPersonalApps);

        pw.print("mProfileMaximumTimeOffMillis=");
        pw.println(mProfileMaximumTimeOffMillis);

        pw.print("mProfileOffDeadline=");
        pw.println(mProfileOffDeadline);

        pw.print("mAlwaysOnVpnPackage=");
        pw.println(mAlwaysOnVpnPackage);

        pw.print("mAlwaysOnVpnLockdown=");
        pw.println(mAlwaysOnVpnLockdown);

        pw.print("mPreferentialNetworkServiceEnabled=");
        pw.println(mPreferentialNetworkServiceEnabled);

        pw.print("mCommonCriteriaMode=");
        pw.println(mCommonCriteriaMode);

        pw.print("mPasswordComplexity=");
        pw.println(mPasswordComplexity);

        pw.print("mNearbyNotificationStreamingPolicy=");
        pw.println(mNearbyNotificationStreamingPolicy);

        pw.print("mNearbyAppStreamingPolicy=");
        pw.println(mNearbyAppStreamingPolicy);

        if (!TextUtils.isEmpty(mOrganizationId)) {
            pw.print("mOrganizationId=");
            pw.println(mOrganizationId);
        }

        if (!TextUtils.isEmpty(mEnrollmentSpecificId)) {
            pw.print("mEnrollmentSpecificId=");
            pw.println(mEnrollmentSpecificId);
        }

        pw.print("mAdminCanGrantSensorsPermissions");
        pw.println(mAdminCanGrantSensorsPermissions);

        pw.print("mUsbDataSignaling=");
        pw.println(mUsbDataSignalingEnabled);
    }
}
