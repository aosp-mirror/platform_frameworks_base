/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.devicepolicy.DevicePolicyEngine.DEVICE_LOCK_CONTROLLER_ROLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.AccountTypePolicyKey;
import android.app.admin.BooleanPolicyValue;
import android.app.admin.DevicePolicyIdentifiers;
import android.app.admin.DevicePolicyManager;
import android.app.admin.IntegerPolicyValue;
import android.app.admin.IntentFilterPolicyKey;
import android.app.admin.LockTaskPolicy;
import android.app.admin.NoArgsPolicyKey;
import android.app.admin.PackagePermissionPolicyKey;
import android.app.admin.PackagePolicyKey;
import android.app.admin.PolicyKey;
import android.app.admin.PolicyValue;
import android.app.admin.UserRestrictionPolicyKey;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserManager;

import com.android.internal.util.function.QuadFunction;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.utils.Slogf;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PolicyDefinition<V> {

    static final String TAG = "PolicyDefinition";

    private static final int POLICY_FLAG_NONE = 0;

    // Only use this flag if a policy can not be applied locally.
    private static final int POLICY_FLAG_GLOBAL_ONLY_POLICY = 1;

    // Only use this flag if a policy can not be applied globally.
    private static final int POLICY_FLAG_LOCAL_ONLY_POLICY = 1 << 1;

    // Only use this flag if a policy is inheritable by child profile from parent.
    private static final int POLICY_FLAG_INHERITABLE = 1 << 2;

    // Use this flag if admin policies should be treated independently of each other and should not
    // have any resolution logic applied, this should only be used for very limited policies were
    // this would make sense and the enforcing logic should handle it appropriately, e.g.
    // application restrictions set by different admins for a single package should not be merged,
    // but saved and queried independent of each other.
    // Currently, support is  added for local only policies, if you need to add a non coexistable
    // global policy please add support.
    private static final int POLICY_FLAG_NON_COEXISTABLE_POLICY = 1 << 3;

    // Add this flag to any policy that is a user restriction, the reason for this is that there
    // are some special APIs to handle user restriction policies and this is the way we can identify
    // them.
    private static final int POLICY_FLAG_USER_RESTRICTION_POLICY = 1 << 4;

    // Only invoke the policy enforcer callback when the policy value changes, and do not invoke the
    // callback in other cases such as device reboots.
    private static final int POLICY_FLAG_SKIP_ENFORCEMENT_IF_UNCHANGED = 1 << 5;

    private static final MostRestrictive<Boolean> FALSE_MORE_RESTRICTIVE = new MostRestrictive<>(
            List.of(new BooleanPolicyValue(false), new BooleanPolicyValue(true)));

    private static final MostRestrictive<Boolean> TRUE_MORE_RESTRICTIVE = new MostRestrictive<>(
            List.of(new BooleanPolicyValue(true), new BooleanPolicyValue(false)));

    static PolicyDefinition<Boolean> AUTO_TIMEZONE = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.AUTO_TIMEZONE_POLICY),
            // auto timezone is disabled by default, hence enabling it is more restrictive.
            TRUE_MORE_RESTRICTIVE,
            POLICY_FLAG_GLOBAL_ONLY_POLICY,
            (Boolean value, Context context, Integer userId, PolicyKey policyKey) ->
                    PolicyEnforcerCallbacks.setAutoTimezoneEnabled(value, context),
            new BooleanPolicySerializer());

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (packageName and permission name)
    // when reading the policies from xml.
    static final PolicyDefinition<Integer> GENERIC_PERMISSION_GRANT =
            new PolicyDefinition<>(
                    new PackagePermissionPolicyKey(DevicePolicyIdentifiers.PERMISSION_GRANT_POLICY),
                    // TODO: is this really the best mechanism, what makes denied more
                    //  restrictive than
                    //  granted?
                    new MostRestrictive<>(
                            List.of(
                                    new IntegerPolicyValue(
                                            DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED),
                                    new IntegerPolicyValue(
                                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED),
                                    new IntegerPolicyValue(
                                            DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT))),
                    POLICY_FLAG_LOCAL_ONLY_POLICY,
                    PolicyEnforcerCallbacks::setPermissionGrantState,
                    new IntegerPolicySerializer());

    /**
     * Passing in {@code null} for {@code packageName} or {@code permissionName} will return a
     * {@link #GENERIC_PERMISSION_GRANT}.
     */
    static PolicyDefinition<Integer> PERMISSION_GRANT(
            @NonNull String packageName, @NonNull String permissionName) {
        if (packageName == null || permissionName == null) {
            return GENERIC_PERMISSION_GRANT;
        }
        return GENERIC_PERMISSION_GRANT.createPolicyDefinition(
                new PackagePermissionPolicyKey(
                        DevicePolicyIdentifiers.PERMISSION_GRANT_POLICY,
                        packageName,
                        permissionName));
    }

    static PolicyDefinition<Boolean> SECURITY_LOGGING = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.SECURITY_LOGGING_POLICY),
            TRUE_MORE_RESTRICTIVE,
            POLICY_FLAG_GLOBAL_ONLY_POLICY,
            PolicyEnforcerCallbacks::enforceSecurityLogging,
            new BooleanPolicySerializer());

    static PolicyDefinition<Boolean> AUDIT_LOGGING = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.AUDIT_LOGGING_POLICY),
            TRUE_MORE_RESTRICTIVE,
            POLICY_FLAG_GLOBAL_ONLY_POLICY,
            PolicyEnforcerCallbacks::enforceAuditLogging,
            new BooleanPolicySerializer());

    static PolicyDefinition<LockTaskPolicy> LOCK_TASK = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.LOCK_TASK_POLICY),
            new TopPriority<>(List.of(
                    EnforcingAdmin.getRoleAuthorityOf(DEVICE_LOCK_CONTROLLER_ROLE),
                    EnforcingAdmin.DPC_AUTHORITY)),
            POLICY_FLAG_LOCAL_ONLY_POLICY,
            (LockTaskPolicy value, Context context, Integer userId, PolicyKey policyKey) ->
                    PolicyEnforcerCallbacks.setLockTask(value, context, userId),
            new LockTaskPolicySerializer());

    static PolicyDefinition<Set<String>> USER_CONTROLLED_DISABLED_PACKAGES =
            new PolicyDefinition<>(
                    new NoArgsPolicyKey(
                            DevicePolicyIdentifiers.USER_CONTROL_DISABLED_PACKAGES_POLICY),
                    new PackageSetUnion(),
                    PolicyEnforcerCallbacks::setUserControlDisabledPackages,
                    new PackageSetPolicySerializer());

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (i.e. packageName) when reading the policies from
    // xml.
    static PolicyDefinition<ComponentName> GENERIC_PERSISTENT_PREFERRED_ACTIVITY =
            new PolicyDefinition<>(
                    new IntentFilterPolicyKey(
                            DevicePolicyIdentifiers.PERSISTENT_PREFERRED_ACTIVITY_POLICY),
            new TopPriority<>(List.of(
                    EnforcingAdmin.getRoleAuthorityOf(DEVICE_LOCK_CONTROLLER_ROLE),
                    EnforcingAdmin.DPC_AUTHORITY)),
            POLICY_FLAG_LOCAL_ONLY_POLICY,
            PolicyEnforcerCallbacks::addPersistentPreferredActivity,
            new ComponentNamePolicySerializer());

    /**
     * Passing in {@code null} for {@code intentFilter} will return
     * {@link #GENERIC_PERSISTENT_PREFERRED_ACTIVITY}.
     */
    static PolicyDefinition<ComponentName> PERSISTENT_PREFERRED_ACTIVITY(
            IntentFilter intentFilter) {
        if (intentFilter == null) {
            return GENERIC_PERSISTENT_PREFERRED_ACTIVITY;
        }
        return GENERIC_PERSISTENT_PREFERRED_ACTIVITY.createPolicyDefinition(
                new IntentFilterPolicyKey(
                        DevicePolicyIdentifiers.PERSISTENT_PREFERRED_ACTIVITY_POLICY,
                        intentFilter));
    }

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (i.e. packageName) when reading the policies from
    // xml.
    static PolicyDefinition<Boolean> GENERIC_PACKAGE_UNINSTALL_BLOCKED =
            new PolicyDefinition<>(
                    new PackagePolicyKey(
                            DevicePolicyIdentifiers.PACKAGE_UNINSTALL_BLOCKED_POLICY),
                    TRUE_MORE_RESTRICTIVE,
                    POLICY_FLAG_LOCAL_ONLY_POLICY,
                    PolicyEnforcerCallbacks::setUninstallBlocked,
                    new BooleanPolicySerializer());

    /**
     * Passing in {@code null} for {@code packageName} will return
     * {@link #GENERIC_PACKAGE_UNINSTALL_BLOCKED}.
     */
    static PolicyDefinition<Boolean> PACKAGE_UNINSTALL_BLOCKED(
            String packageName) {
        if (packageName == null) {
            return GENERIC_PACKAGE_UNINSTALL_BLOCKED;
        }
        return GENERIC_PACKAGE_UNINSTALL_BLOCKED.createPolicyDefinition(
                new PackagePolicyKey(
                        DevicePolicyIdentifiers.PACKAGE_UNINSTALL_BLOCKED_POLICY, packageName));
    }

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (i.e. packageName) when reading the policies from
    // xml.
    static PolicyDefinition<Bundle> GENERIC_APPLICATION_RESTRICTIONS =
            new PolicyDefinition<>(
                    new PackagePolicyKey(
                            DevicePolicyIdentifiers.APPLICATION_RESTRICTIONS_POLICY),
                    // Don't need to take in a resolution mechanism since its never used, but might
                    // need some refactoring to not always assume a non-null mechanism.
                    new MostRecent<>(),
                    // Only invoke the enforcement callback during policy change and not other state
                    POLICY_FLAG_LOCAL_ONLY_POLICY | POLICY_FLAG_INHERITABLE
                            | POLICY_FLAG_NON_COEXISTABLE_POLICY
                            | POLICY_FLAG_SKIP_ENFORCEMENT_IF_UNCHANGED,
                    PolicyEnforcerCallbacks::setApplicationRestrictions,
                    new BundlePolicySerializer());

    /**
     * Passing in {@code null} for {@code packageName} will return
     * {@link #GENERIC_APPLICATION_RESTRICTIONS}.
     */
    static PolicyDefinition<Bundle> APPLICATION_RESTRICTIONS(String packageName) {
        if (packageName == null) {
            return GENERIC_APPLICATION_RESTRICTIONS;
        }
        return GENERIC_APPLICATION_RESTRICTIONS.createPolicyDefinition(
                new PackagePolicyKey(
                        DevicePolicyIdentifiers.APPLICATION_RESTRICTIONS_POLICY, packageName));
    }

    static PolicyDefinition<Long> RESET_PASSWORD_TOKEN = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.RESET_PASSWORD_TOKEN_POLICY),
            // Don't need to take in a resolution mechanism since its never used, but might
            // need some refactoring to not always assume a non-null mechanism.
            new MostRecent<>(),
            POLICY_FLAG_LOCAL_ONLY_POLICY | POLICY_FLAG_NON_COEXISTABLE_POLICY,
            // DevicePolicyManagerService handles the enforcement, this just takes care of storage
            PolicyEnforcerCallbacks::noOp,
            new LongPolicySerializer());

    static PolicyDefinition<Integer> KEYGUARD_DISABLED_FEATURES = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.KEYGUARD_DISABLED_FEATURES_POLICY),
            new FlagUnion(),
            POLICY_FLAG_LOCAL_ONLY_POLICY,
            // Nothing is enforced for keyguard features, we just need to store it
            PolicyEnforcerCallbacks::noOp,
            new IntegerPolicySerializer());

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (i.e. packageName) when reading the policies from
    // xml.
    static PolicyDefinition<Boolean> GENERIC_APPLICATION_HIDDEN =
            new PolicyDefinition<>(
                    new PackagePolicyKey(
                            DevicePolicyIdentifiers.APPLICATION_HIDDEN_POLICY),
                    // TODO(b/276713779): Don't need to take in a resolution mechanism since its
                    //  never used, but might need some refactoring to not always assume a non-null
                    //  mechanism.
                    TRUE_MORE_RESTRICTIVE,
                    POLICY_FLAG_LOCAL_ONLY_POLICY | POLICY_FLAG_INHERITABLE,
                    PolicyEnforcerCallbacks::setApplicationHidden,
                    new BooleanPolicySerializer());

    /**
     * Passing in {@code null} for {@code packageName} will return
     * {@link #GENERIC_APPLICATION_HIDDEN}.
     */
    static PolicyDefinition<Boolean> APPLICATION_HIDDEN(String packageName) {
        if (packageName == null) {
            return GENERIC_APPLICATION_HIDDEN;
        }
        return GENERIC_APPLICATION_HIDDEN.createPolicyDefinition(
                new PackagePolicyKey(
                        DevicePolicyIdentifiers.APPLICATION_HIDDEN_POLICY, packageName));
    }

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (i.e. packageName) when reading the policies from
    // xml.
    static PolicyDefinition<Boolean> GENERIC_ACCOUNT_MANAGEMENT_DISABLED =
            new PolicyDefinition<>(
                    new AccountTypePolicyKey(
                            DevicePolicyIdentifiers.ACCOUNT_MANAGEMENT_DISABLED_POLICY),
                    TRUE_MORE_RESTRICTIVE,
                    POLICY_FLAG_LOCAL_ONLY_POLICY | POLICY_FLAG_INHERITABLE,
                    // Nothing is enforced, we just need to store it
                    PolicyEnforcerCallbacks::noOp,
                    new BooleanPolicySerializer());

    /**
     * Passing in {@code null} for {@code accountType} will return
     * {@link #GENERIC_ACCOUNT_MANAGEMENT_DISABLED}.
     */
    static PolicyDefinition<Boolean> ACCOUNT_MANAGEMENT_DISABLED(String accountType) {
        if (accountType == null) {
            return GENERIC_ACCOUNT_MANAGEMENT_DISABLED;
        }
        return GENERIC_ACCOUNT_MANAGEMENT_DISABLED.createPolicyDefinition(
                new AccountTypePolicyKey(
                        DevicePolicyIdentifiers.ACCOUNT_MANAGEMENT_DISABLED_POLICY, accountType));
    }

    static PolicyDefinition<Set<String>> PERMITTED_INPUT_METHODS = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.PERMITTED_INPUT_METHODS_POLICY),
            new MostRecent<>(),
            POLICY_FLAG_LOCAL_ONLY_POLICY | POLICY_FLAG_INHERITABLE,
            PolicyEnforcerCallbacks::noOp,
            new PackageSetPolicySerializer());


    static PolicyDefinition<Boolean> SCREEN_CAPTURE_DISABLED = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.SCREEN_CAPTURE_DISABLED_POLICY),
            TRUE_MORE_RESTRICTIVE,
            POLICY_FLAG_INHERITABLE,
            PolicyEnforcerCallbacks::setScreenCaptureDisabled,
            new BooleanPolicySerializer());

    static PolicyDefinition<Boolean> PERSONAL_APPS_SUSPENDED = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.PERSONAL_APPS_SUSPENDED_POLICY),
            new MostRecent<>(),
            POLICY_FLAG_LOCAL_ONLY_POLICY | POLICY_FLAG_INHERITABLE,
            PolicyEnforcerCallbacks::setPersonalAppsSuspended,
            new BooleanPolicySerializer());

    static PolicyDefinition<Boolean> USB_DATA_SIGNALING = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.USB_DATA_SIGNALING_POLICY),
            // usb data signaling is enabled by default, hence disabling it is more restrictive.
            FALSE_MORE_RESTRICTIVE,
            POLICY_FLAG_GLOBAL_ONLY_POLICY,
            (Boolean value, Context context, Integer userId, PolicyKey policyKey) ->
                PolicyEnforcerCallbacks.setUsbDataSignalingEnabled(value, context),
            new BooleanPolicySerializer());

    static PolicyDefinition<Integer> CONTENT_PROTECTION = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.CONTENT_PROTECTION_POLICY),
            new MostRecent<>(),
            POLICY_FLAG_LOCAL_ONLY_POLICY,
            PolicyEnforcerCallbacks::setContentProtectionPolicy,
            new IntegerPolicySerializer());

    static PolicyDefinition<Integer> PASSWORD_COMPLEXITY = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.PASSWORD_COMPLEXITY_POLICY),
            new MostRestrictive<>(
                    List.of(
                            new IntegerPolicyValue(
                                    DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH),
                            new IntegerPolicyValue(
                                    DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM),
                            new IntegerPolicyValue(
                                    DevicePolicyManager.PASSWORD_COMPLEXITY_LOW),
                            new IntegerPolicyValue(
                                    DevicePolicyManager.PASSWORD_COMPLEXITY_NONE))),
            POLICY_FLAG_LOCAL_ONLY_POLICY,
            PolicyEnforcerCallbacks::noOp,
            new IntegerPolicySerializer());

    static PolicyDefinition<Set<String>> PACKAGES_SUSPENDED =
            new PolicyDefinition<>(
                    new NoArgsPolicyKey(
                            DevicePolicyIdentifiers.PACKAGES_SUSPENDED_POLICY),
                    new PackageSetUnion(),
                    PolicyEnforcerCallbacks::noOp,
                    new PackageSetPolicySerializer());

    private static final Map<String, PolicyDefinition<?>> POLICY_DEFINITIONS = new HashMap<>();
    private static Map<String, Integer> USER_RESTRICTION_FLAGS = new HashMap<>();

    // TODO(b/277218360): Revisit policies that should be marked as global-only.
    static {
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.AUTO_TIMEZONE_POLICY, AUTO_TIMEZONE);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.PERMISSION_GRANT_POLICY,
                GENERIC_PERMISSION_GRANT);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.SECURITY_LOGGING_POLICY,
                SECURITY_LOGGING);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.AUDIT_LOGGING_POLICY,
                AUDIT_LOGGING);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.LOCK_TASK_POLICY, LOCK_TASK);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.USER_CONTROL_DISABLED_PACKAGES_POLICY,
                USER_CONTROLLED_DISABLED_PACKAGES);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.PERSISTENT_PREFERRED_ACTIVITY_POLICY,
                GENERIC_PERSISTENT_PREFERRED_ACTIVITY);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.PACKAGE_UNINSTALL_BLOCKED_POLICY,
                GENERIC_PACKAGE_UNINSTALL_BLOCKED);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.APPLICATION_RESTRICTIONS_POLICY,
                GENERIC_APPLICATION_RESTRICTIONS);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.RESET_PASSWORD_TOKEN_POLICY,
                RESET_PASSWORD_TOKEN);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.KEYGUARD_DISABLED_FEATURES_POLICY,
                KEYGUARD_DISABLED_FEATURES);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.APPLICATION_HIDDEN_POLICY,
                GENERIC_APPLICATION_HIDDEN);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.ACCOUNT_MANAGEMENT_DISABLED_POLICY,
                GENERIC_ACCOUNT_MANAGEMENT_DISABLED);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.PERMITTED_INPUT_METHODS_POLICY,
                PERMITTED_INPUT_METHODS);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.SCREEN_CAPTURE_DISABLED_POLICY,
                SCREEN_CAPTURE_DISABLED);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.PERSONAL_APPS_SUSPENDED_POLICY,
                PERSONAL_APPS_SUSPENDED);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.USB_DATA_SIGNALING_POLICY,
                USB_DATA_SIGNALING);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.CONTENT_PROTECTION_POLICY,
                CONTENT_PROTECTION);
        // Intentionally not flagged since if the flag is flipped off on a device already
        // having PASSWORD_COMPLEXITY policy in the on-device XML, it will cause the
        // deserialization logic to break due to seeing an unknown tag.
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.PASSWORD_COMPLEXITY_POLICY,
                PASSWORD_COMPLEXITY);
        POLICY_DEFINITIONS.put(DevicePolicyIdentifiers.PACKAGES_SUSPENDED_POLICY,
                PACKAGES_SUSPENDED);

        // User Restriction Policies
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_MODIFY_ACCOUNTS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_WIFI, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_CHANGE_WIFI_STATE, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_WIFI_TETHERING, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_GRANT_ADMIN, /* flags= */ 0);
        // TODO: set as global only once we get rid of the mapping
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_SHARING_ADMIN_CONFIGURED_WIFI, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_WIFI_DIRECT, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_ADD_WIFI_CONFIG, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_LOCALE, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_INSTALL_APPS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_UNINSTALL_APPS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_SHARE_LOCATION, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_AIRPLANE_MODE, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_BRIGHTNESS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_AMBIENT_DISPLAY, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
                POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_BLUETOOTH, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_BLUETOOTH, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_BLUETOOTH_SHARING, /* flags= */ 0);
        // This effectively always applies globally, but it can be set on the profile
        // parent, check the javadocs on the restriction for more info.
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_USB_FILE_TRANSFER, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_CREDENTIALS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_REMOVE_USER, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_REMOVE_MANAGED_PROFILE, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_DEBUGGING_FEATURES, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_VPN, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_LOCATION, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_DATE_TIME, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_CONFIG_TETHERING, /* flags= */ 0);
        // This effectively always applies globally, but it can be set on the profile
        // parent, check the javadocs on the restriction for more info.
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_NETWORK_RESET, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_FACTORY_RESET, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_ADD_USER, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_ADD_MANAGED_PROFILE, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_ADD_CLONE_PROFILE, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_ADD_PRIVATE_PROFILE, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.ENSURE_VERIFY_APPS, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_CELL_BROADCASTS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_APPS_CONTROL, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_UNMUTE_MICROPHONE, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_ADJUST_VOLUME, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_OUTGOING_CALLS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_SMS, /* flags= */ 0);
        // TODO: check if its global only
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_FUN, /* flags= */ 0);
        // TODO: check if its global only
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CREATE_WINDOWS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE, /* flags= */ 0);
        // TODO: check if its global only
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_OUTGOING_BEAM, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_WALLPAPER, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_SET_WALLPAPER, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_SAFE_BOOT, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_RECORD_AUDIO, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_RUN_IN_BACKGROUND, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CAMERA, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_UNMUTE_DEVICE, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_DATA_ROAMING, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_SET_USER_ICON, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_OEM_UNLOCK, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_UNIFIED_PASSWORD, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.ALLOW_PARENT_PROFILE_APP_LINKING, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_AUTOFILL, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONTENT_CAPTURE, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONTENT_SUGGESTIONS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_USER_SWITCH, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_PRINTING, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_CONFIG_PRIVATE_DNS, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_MICROPHONE_TOGGLE, /* flags= */ 0);
        // TODO: According the UserRestrictionsUtils, this is global only, need to confirm.
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CAMERA_TOGGLE, /* flags= */ 0);
        // TODO: check if its global only
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_BIOMETRIC, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_CONFIG_DEFAULT_APPS, /* flags= */ 0);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_CELLULAR_2G, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_ULTRA_WIDEBAND_RADIO, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(
                UserManager.DISALLOW_SIM_GLOBALLY,
                POLICY_FLAG_GLOBAL_ONLY_POLICY);
        USER_RESTRICTION_FLAGS.put(UserManager.DISALLOW_ASSIST_CONTENT, /* flags= */ 0);
        if (com.android.net.thread.platform.flags.Flags.threadUserRestrictionEnabled()) {
            USER_RESTRICTION_FLAGS.put(
                    UserManager.DISALLOW_THREAD_NETWORK, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        }

        for (String key : USER_RESTRICTION_FLAGS.keySet()) {
            createAndAddUserRestrictionPolicyDefinition(key, USER_RESTRICTION_FLAGS.get(key));
        }
    }

    private final PolicyKey mPolicyKey;
    private final ResolutionMechanism<V> mResolutionMechanism;
    private final int mPolicyFlags;
    // A function that accepts  policy to apply, context, userId, callback arguments, and returns
    // true if the policy has been enforced successfully.
    private final QuadFunction<V, Context, Integer, PolicyKey, Boolean> mPolicyEnforcerCallback;
    private final PolicySerializer<V> mPolicySerializer;

    private PolicyDefinition<V> createPolicyDefinition(PolicyKey key) {
        return new PolicyDefinition<>(key, mResolutionMechanism, mPolicyFlags,
                mPolicyEnforcerCallback, mPolicySerializer);
    }

    static PolicyDefinition<Boolean> getPolicyDefinitionForUserRestriction(
            @UserManager.UserRestrictionKey String restriction) {
        String key = DevicePolicyIdentifiers.getIdentifierForUserRestriction(restriction);

        if (!POLICY_DEFINITIONS.containsKey(key)) {
            throw new IllegalArgumentException("Unsupported user restriction " + restriction);
        }
        // All user restrictions are of type boolean
        return (PolicyDefinition<Boolean>) POLICY_DEFINITIONS.get(key);
    }

    @NonNull
    PolicyKey getPolicyKey() {
        return mPolicyKey;
    }

    @NonNull
    ResolutionMechanism<V> getResolutionMechanism() {
        return mResolutionMechanism;
    }
    /**
     * Returns {@code true} if the policy is a global policy by nature and can't be applied locally.
     */
    boolean isGlobalOnlyPolicy() {
        return (mPolicyFlags & POLICY_FLAG_GLOBAL_ONLY_POLICY) != 0;
    }

    /**
     * Returns {@code true} if the policy is a local policy by nature and can't be applied globally.
     */
    boolean isLocalOnlyPolicy() {
        return (mPolicyFlags & POLICY_FLAG_LOCAL_ONLY_POLICY) != 0;
    }

    /**
     * Returns {@code true} if the policy is inheritable by child profiles.
     */
    boolean isInheritable() {
        return (mPolicyFlags & POLICY_FLAG_INHERITABLE) != 0;
    }

    /**
     * Returns {@code true} if the policy engine should not try to resolve policies set by different
     * admins and should just store it and pass it on to the enforcing logic.
     */
    boolean isNonCoexistablePolicy() {
        return (mPolicyFlags & POLICY_FLAG_NON_COEXISTABLE_POLICY) != 0;
    }

    boolean isUserRestrictionPolicy() {
        return (mPolicyFlags & POLICY_FLAG_USER_RESTRICTION_POLICY) != 0;
    }

    boolean shouldSkipEnforcementIfNotChanged() {
        return (mPolicyFlags & POLICY_FLAG_SKIP_ENFORCEMENT_IF_UNCHANGED) != 0;
    }

    @Nullable
    PolicyValue<V> resolvePolicy(LinkedHashMap<EnforcingAdmin, PolicyValue<V>> adminsPolicy) {
        return mResolutionMechanism.resolve(adminsPolicy);
    }

    boolean enforcePolicy(@Nullable V value, Context context, int userId) {
        return mPolicyEnforcerCallback.apply(value, context, userId, mPolicyKey);
    }

    private static void createAndAddUserRestrictionPolicyDefinition(
            String restriction, int flags) {
        String identifier = DevicePolicyIdentifiers.getIdentifierForUserRestriction(restriction);
        UserRestrictionPolicyKey key = new UserRestrictionPolicyKey(identifier, restriction);
        flags |= (POLICY_FLAG_USER_RESTRICTION_POLICY | POLICY_FLAG_INHERITABLE);
        PolicyDefinition<Boolean> definition = new PolicyDefinition<>(
                key,
                TRUE_MORE_RESTRICTIVE,
                flags,
                PolicyEnforcerCallbacks::setUserRestriction,
                new BooleanPolicySerializer());
        POLICY_DEFINITIONS.put(key.getIdentifier(), definition);
    }


    /**
     * Callers must ensure that {@code policyType} have implemented an appropriate
     * {@link Object#equals} implementation.
     */
    private PolicyDefinition(
            @NonNull  PolicyKey key,
            ResolutionMechanism<V> resolutionMechanism,
            QuadFunction<V, Context, Integer, PolicyKey, Boolean> policyEnforcerCallback,
            PolicySerializer<V> policySerializer) {
        this(key, resolutionMechanism, POLICY_FLAG_NONE, policyEnforcerCallback, policySerializer);
    }

    /**
     * Callers must ensure that custom {@code policyKeys} and {@code V} have an appropriate
     * {@link Object#equals} and {@link Object#hashCode()} implementation.
     */
    private PolicyDefinition(
            @NonNull  PolicyKey policyKey,
            ResolutionMechanism<V> resolutionMechanism,
            int policyFlags,
            QuadFunction<V, Context, Integer, PolicyKey, Boolean> policyEnforcerCallback,
            PolicySerializer<V> policySerializer) {
        Objects.requireNonNull(policyKey);
        mPolicyKey = policyKey;
        mResolutionMechanism = resolutionMechanism;
        mPolicyFlags = policyFlags;
        mPolicyEnforcerCallback = policyEnforcerCallback;
        mPolicySerializer = policySerializer;

        if (isNonCoexistablePolicy() && !isLocalOnlyPolicy()) {
            throw new UnsupportedOperationException("Non-coexistable global policies not supported,"
                    + "please add support.");
        }
        // TODO: maybe use this instead of manually adding to the map
//        sPolicyDefinitions.put(policyDefinitionKey, this);
    }

    void saveToXml(TypedXmlSerializer serializer) throws IOException {
        mPolicyKey.saveToXml(serializer);
    }

    @Nullable
    static <V> PolicyDefinition<V> readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        // TODO: can we avoid casting?
        PolicyKey policyKey = readPolicyKeyFromXml(parser);
        if (policyKey == null) {
            Slogf.wtf(TAG, "Error parsing PolicyDefinition, PolicyKey is null.");
            return null;
        }
        PolicyDefinition<V> genericPolicyDefinition =
                (PolicyDefinition<V>) POLICY_DEFINITIONS.get(policyKey.getIdentifier());
        if (genericPolicyDefinition == null) {
            Slogf.wtf(TAG, "Unknown generic policy key: " + policyKey);
            return null;
        }
        return genericPolicyDefinition.createPolicyDefinition(policyKey);
    }

    @Nullable
    static <V> PolicyKey readPolicyKeyFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        // TODO: can we avoid casting?
        PolicyKey policyKey = PolicyKey.readGenericPolicyKeyFromXml(parser);
        if (policyKey == null) {
            Slogf.wtf(TAG, "Error parsing PolicyKey, GenericPolicyKey is null");
            return null;
        }
        PolicyDefinition<PolicyValue<V>> genericPolicyDefinition =
                (PolicyDefinition<PolicyValue<V>>) POLICY_DEFINITIONS.get(
                        policyKey.getIdentifier());
        if (genericPolicyDefinition == null) {
            Slogf.wtf(TAG, "Error parsing PolicyKey, Unknown generic policy key: " + policyKey);
            return null;
        }
        return genericPolicyDefinition.mPolicyKey.readFromXml(parser);
    }

    void savePolicyValueToXml(TypedXmlSerializer serializer, V value)
            throws IOException {
        mPolicySerializer.saveToXml(serializer, value);
    }

    @Nullable
    PolicyValue<V> readPolicyValueFromXml(TypedXmlPullParser parser) {
        return mPolicySerializer.readFromXml(parser);
    }

    @Override
    public String toString() {
        return "PolicyDefinition{ mPolicyKey= " + mPolicyKey + ", mResolutionMechanism= "
                + mResolutionMechanism + ", mPolicyFlags= " + mPolicyFlags + " }";
    }
}
