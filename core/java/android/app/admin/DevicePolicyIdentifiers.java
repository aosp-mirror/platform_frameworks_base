/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.admin;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.UserManager;

import java.util.Objects;

/**
 * Class containing identifiers for policy APIs in {@link DevicePolicyManager}, for example they
 * will be passed in {@link PolicyUpdateReceiver#onPolicySetResult} and
 * {@link PolicyUpdateReceiver#onPolicyChanged} to communicate updates of a certain policy back
 * to the admin.
 */
public final class DevicePolicyIdentifiers {

    private DevicePolicyIdentifiers() {}

    /**
     * String identifier for {@link DevicePolicyManager#setAutoTimeZoneEnabled}.
     */
    public static final String AUTO_TIMEZONE_POLICY = "autoTimezone";

    /**
     * String identifier for {@link DevicePolicyManager#setPermissionGrantState}.
     */
    public static final String PERMISSION_GRANT_POLICY = "permissionGrant";

    /**
     * String identifier for {@link DevicePolicyManager#setSecurityLoggingEnabled}.
     */
    public static final String SECURITY_LOGGING_POLICY = "securityLogging";

    /**
     * String identifier for {@link DevicePolicyManager#setAuditLogEnabled}.
     *
     * @hide
     */
    @SystemApi
    public static final String AUDIT_LOGGING_POLICY = "auditLogging";

    /**
     * String identifier for {@link DevicePolicyManager#setLockTaskPackages}.
     */
    public static final String LOCK_TASK_POLICY = "lockTask";

    /**
     * String identifier for {@link DevicePolicyManager#setUserControlDisabledPackages}.
     */
    public static final String USER_CONTROL_DISABLED_PACKAGES_POLICY =
            "userControlDisabledPackages";

    /**
     * String identifier for {@link DevicePolicyManager#addPersistentPreferredActivity}.
     */
    public static final String PERSISTENT_PREFERRED_ACTIVITY_POLICY =
            "persistentPreferredActivity";

    /**
     * String identifier for {@link DevicePolicyManager#setUninstallBlocked}.
     */
    public static final String PACKAGE_UNINSTALL_BLOCKED_POLICY = "packageUninstallBlocked";

    /**
     * String identifier for {@link DevicePolicyManager#setApplicationRestrictions}.
     */
    public static final String APPLICATION_RESTRICTIONS_POLICY = "applicationRestrictions";

    /**
     * String identifier for {@link DevicePolicyManager#setResetPasswordToken}.
     */
    public static final String RESET_PASSWORD_TOKEN_POLICY = "resetPasswordToken";

    /**
     * String identifier for {@link DevicePolicyManager#setAccountManagementDisabled}.
     */
    public static final String ACCOUNT_MANAGEMENT_DISABLED_POLICY = "accountManagementDisabled";

    /**
     * String identifier for {@link DevicePolicyManager#setApplicationHidden}.
     */
    public static final String APPLICATION_HIDDEN_POLICY = "applicationHidden";

    /**
     * String identifier for {@link DevicePolicyManager#setCameraDisabled}.
     */
    public static final String CAMERA_DISABLED_POLICY = "cameraDisabled";

    /**
     * String identifier for {@link DevicePolicyManager#setStatusBarDisabled}.
     */
    public static final String STATUS_BAR_DISABLED_POLICY = "statusBarDisabled";

    /**
     * String identifier for {@link DevicePolicyManager#setPackagesSuspended}.
     */
    public static final String PACKAGES_SUSPENDED_POLICY = "packagesSuspended";

    /**
     * String identifier for {@link DevicePolicyManager#setKeyguardDisabledFeatures}.
     */
    public static final String KEYGUARD_DISABLED_FEATURES_POLICY = "keyguardDisabledFeatures";

    /**
     * String identifier for {@link DevicePolicyManager#setAutoTimeEnabled}.
     */
    public static final String AUTO_TIME_POLICY = "autoTime";

    /**
     * String identifier for {@link DevicePolicyManager#setBackupServiceEnabled}.
     */
    public static final String BACKUP_SERVICE_POLICY = "backupService";

    /**
     * String identifier for {@link DevicePolicyManager#setPermittedInputMethods}.
     *
     * @hide
     */
    @TestApi
    public static final String PERMITTED_INPUT_METHODS_POLICY = "permittedInputMethods";

    /**
     * String identifier for {@link DevicePolicyManager#setPersonalAppsSuspended}.
     *
     * @hide
     */
    @TestApi
    public static final String PERSONAL_APPS_SUSPENDED_POLICY = "personalAppsSuspended";

    /**
     * String identifier for {@link DevicePolicyManager#setScreenCaptureDisabled}.
     *
     * @hide
     */
    @TestApi
    public static final String SCREEN_CAPTURE_DISABLED_POLICY = "screenCaptureDisabled";

    /**
     * String identifier for {@link DevicePolicyManager#setTrustAgentConfiguration}.
     *
     * @hide
     */
    public static final String TRUST_AGENT_CONFIGURATION_POLICY = "trustAgentConfiguration";

    /**
     * String identifier for {@link DevicePolicyManager#addCrossProfileIntentFilter}.
     *
     * @hide
     */
    public static final String CROSS_PROFILE_INTENT_FILTER_POLICY = "crossProfileIntentFilter";

    /**
     * String identifier for {@link DevicePolicyManager#addCrossProfileWidgetProvider}.
     *
     * @hide
     */
    public static final String CROSS_PROFILE_WIDGET_PROVIDER_POLICY = "crossProfileWidgetProvider";

    /**
     * String identifier for {@link DevicePolicyManager#setContentProtectionPolicy}.
     */
    @FlaggedApi(android.view.contentprotection.flags.Flags.FLAG_MANAGE_DEVICE_POLICY_ENABLED)
    public static final String CONTENT_PROTECTION_POLICY = "contentProtection";

    /**
     * String identifier for {@link DevicePolicyManager#setUsbDataSignalingEnabled}.
     */
    public static final String USB_DATA_SIGNALING_POLICY = "usbDataSignaling";

    /**
     * String identifier for {@link DevicePolicyManager#setRequiredPasswordComplexity}.
     */
    public static final String PASSWORD_COMPLEXITY_POLICY = "passwordComplexity";

    /**
     * @hide
     */
    public static final String USER_RESTRICTION_PREFIX = "userRestriction_";

    /**
     * Returns a string identifier for the provided user restrictions, see
     * {@link DevicePolicyManager#addUserRestriction} and {@link UserManager} for the list of
     * available restrictions.
     */
    @NonNull
    public static String getIdentifierForUserRestriction(
            @UserManager.UserRestrictionKey @NonNull String restriction) {
        Objects.requireNonNull(restriction);
        return USER_RESTRICTION_PREFIX + restriction;
    }
}
