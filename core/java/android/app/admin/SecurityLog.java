/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.admin.flags.Flags;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.EventLog.Event;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

/**
 * Definitions for working with security logs.
 *
 * <p>Device owner apps can control the logging with
 * {@link DevicePolicyManager#setSecurityLoggingEnabled}. When security logs are enabled, device
 * owner apps receive periodic callbacks from {@link DeviceAdminReceiver#onSecurityLogsAvailable},
 * at which time new batch of logs can be collected via
 * {@link DevicePolicyManager#retrieveSecurityLogs}. {@link SecurityEvent} describes the type and
 * format of security logs being collected.
 */
public class SecurityLog {

    private static final String PROPERTY_LOGGING_ENABLED = "persist.logd.security";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "TAG_" }, value = {
            TAG_ADB_SHELL_INTERACTIVE,
            TAG_ADB_SHELL_CMD,
            TAG_SYNC_RECV_FILE,
            TAG_SYNC_SEND_FILE,
            TAG_APP_PROCESS_START,
            TAG_KEYGUARD_DISMISSED,
            TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT,
            TAG_KEYGUARD_SECURED,
            TAG_OS_STARTUP,
            TAG_OS_SHUTDOWN,
            TAG_LOGGING_STARTED,
            TAG_LOGGING_STOPPED,
            TAG_MEDIA_MOUNT,
            TAG_MEDIA_UNMOUNT,
            TAG_LOG_BUFFER_SIZE_CRITICAL,
            TAG_PASSWORD_EXPIRATION_SET,
            TAG_PASSWORD_COMPLEXITY_SET,
            TAG_PASSWORD_HISTORY_LENGTH_SET,
            TAG_MAX_SCREEN_LOCK_TIMEOUT_SET,
            TAG_MAX_PASSWORD_ATTEMPTS_SET,
            TAG_KEYGUARD_DISABLED_FEATURES_SET,
            TAG_REMOTE_LOCK,
            TAG_USER_RESTRICTION_ADDED,
            TAG_USER_RESTRICTION_REMOVED,
            TAG_WIPE_FAILURE,
            TAG_KEY_GENERATED,
            TAG_KEY_IMPORT,
            TAG_KEY_DESTRUCTION,
            TAG_CERT_AUTHORITY_INSTALLED,
            TAG_CERT_AUTHORITY_REMOVED,
            TAG_CRYPTO_SELF_TEST_COMPLETED,
            TAG_KEY_INTEGRITY_VIOLATION,
            TAG_CERT_VALIDATION_FAILURE,
            TAG_CAMERA_POLICY_SET,
            TAG_PASSWORD_COMPLEXITY_REQUIRED,
            TAG_PASSWORD_CHANGED,
            TAG_WIFI_CONNECTION,
            TAG_WIFI_DISCONNECTION,
            TAG_BLUETOOTH_CONNECTION,
            TAG_BLUETOOTH_DISCONNECTION,
            TAG_PACKAGE_INSTALLED,
            TAG_PACKAGE_UPDATED,
            TAG_PACKAGE_UNINSTALLED,
            TAG_BACKUP_SERVICE_TOGGLED,
    })
    public @interface SecurityLogTag {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "LEVEL_" }, value = {
            LEVEL_INFO,
            LEVEL_WARNING,
            LEVEL_ERROR
    })
    public @interface SecurityLogLevel {}

    /**
     * Indicates that an ADB interactive shell was opened via "adb shell".
     * There is no extra payload in the log event.
     */
    public static final int TAG_ADB_SHELL_INTERACTIVE =
            SecurityLogTags.SECURITY_ADB_SHELL_INTERACTIVE;

    /**
     * Indicates that a shell command was issued over ADB via {@code adb shell <command>}
     * The log entry contains a {@code String} payload containing the shell command, accessible
     * via {@link SecurityEvent#getData()}. If security logging is enabled on organization-owned
     * managed profile devices, the shell command will be redacted to an empty string.
     */
    public static final int TAG_ADB_SHELL_CMD = SecurityLogTags.SECURITY_ADB_SHELL_COMMAND;

    /**
     * Indicates that a file was pulled from the device via the adb daemon, for example via
     * {@code adb pull}. The log entry contains a {@code String} payload containing the path of the
     * pulled file on the device, accessible via {@link SecurityEvent#getData()}.
     */
    public static final int TAG_SYNC_RECV_FILE = SecurityLogTags.SECURITY_ADB_SYNC_RECV;

    /**
     * Indicates that a file was pushed to the device via the adb daemon, for example via
     * {@code adb push}. The log entry contains a {@code String} payload containing the destination
     * path of the pushed file, accessible via {@link SecurityEvent#getData()}.
     */
    public static final int TAG_SYNC_SEND_FILE = SecurityLogTags.SECURITY_ADB_SYNC_SEND;

    /**
     * Indicates that an app process was started. The log entry contains the following
     * information about the process encapsulated in an {@link Object} array, accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] process name ({@code String})
     * <li> [1] exact start time in milliseconds according to {@code System.currentTimeMillis()}
     *      ({@code Long})
     * <li> [2] app uid ({@code Integer})
     * <li> [3] app pid ({@code Integer})
     * <li> [4] seinfo tag ({@code String})
     * <li> [5] SHA-256 hash of the base APK in hexadecimal ({@code String})
     * If security logging is enabled on organization-owned managed profile devices, only events
     * happening inside the managed profile will be visible.
     */
    public static final int TAG_APP_PROCESS_START = SecurityLogTags.SECURITY_APP_PROCESS_START;

    /**
     * Indicates that keyguard has been dismissed. This event is only logged if the device
     * has a secure keyguard. It is logged regardless of how keyguard is dismissed, including
     * via PIN/pattern/password, biometrics or via a trust agent.
     * There is no extra payload in the log event.
     * @see #TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT
     */
    public static final int TAG_KEYGUARD_DISMISSED = SecurityLogTags.SECURITY_KEYGUARD_DISMISSED;

    /**
     * Indicates that there has been an authentication attempt to dismiss the keyguard. The log
     * entry contains the following information about the attempt encapsulated in an {@link Object}
     * array, accessible via {@link SecurityEvent#getData()}:
     * <li> [0] attempt result ({@code Integer}, 1 for successful, 0 for unsuccessful)
     * <li> [1] strength of authentication method ({@code Integer}, 1 if strong authentication
     *      method was used, 0 otherwise)
     */
    public static final int TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT =
            SecurityLogTags.SECURITY_KEYGUARD_DISMISS_AUTH_ATTEMPT;

    /**
     * Indicates that the device has been locked, either by the user or by a timeout. There is no
     * extra payload in the log event.
     */
    public static final int TAG_KEYGUARD_SECURED = SecurityLogTags.SECURITY_KEYGUARD_SECURED;

    /**
     * Indicates that the Android OS has started. The log entry contains the following information
     * about the startup time software integrity check encapsulated in an {@link Object} array,
     * accessible via {@link SecurityEvent#getData()}:
     * <li> [0] Verified Boot state ({@code String})
     * <li> [1] dm-verity mode ({@code String}).
     * <p>Verified Boot state can be one of the following:
     * <li> {@code green} indicates that there is a full chain of trust extending from the
     * bootloader to verified partitions including the bootloader, boot partition, and all verified
     * partitions.
     * <li> {@code yellow} indicates that the boot partition has been verified using the embedded
     * certificate and the signature is valid.
     * <li> {@code orange} indicates that the device may be freely modified. Device integrity is
     * left to the user to verify out-of-band.
     * <p>dm-verity mode can be one of the following:
     * <li> {@code enforcing} indicates that the device will be restarted when corruption is
     * detected.
     * <li> {@code eio} indicates that an I/O error will be returned for an attempt to read
     * corrupted data blocks.
     * <li> {@code disabled} indicates that integrity check is disabled.
     * For details see Verified Boot documentation.
     */
    public static final int TAG_OS_STARTUP = SecurityLogTags.SECURITY_OS_STARTUP;

    /**
     * Indicates that the Android OS has shutdown. There is no extra payload in the log event.
     */
    public static final int TAG_OS_SHUTDOWN = SecurityLogTags.SECURITY_OS_SHUTDOWN;

    /**
     * Indicates start-up of audit logging. There is no extra payload in the log event.
     */
    public static final int TAG_LOGGING_STARTED = SecurityLogTags.SECURITY_LOGGING_STARTED;

    /**
     * Indicates shutdown of audit logging. There is no extra payload in the log event.
     */
    public static final int TAG_LOGGING_STOPPED = SecurityLogTags.SECURITY_LOGGING_STOPPED;

    /**
     * Indicates that removable media has been mounted on the device. The log entry contains the
     * following information about the event, encapsulated in an {@link Object} array and
     * accessible via {@link SecurityEvent#getData()}:
     * <li> [0] mount point ({@code String})
     * <li> [1] volume label ({@code String}). Redacted to empty string on organization-owned
     *     managed profile devices.
     */
    public static final int TAG_MEDIA_MOUNT = SecurityLogTags.SECURITY_MEDIA_MOUNTED;

    /**
     * Indicates that removable media was unmounted from the device. The log entry contains the
     * following information about the event, encapsulated in an {@link Object} array and
     * accessible via {@link SecurityEvent#getData()}:
     * <li> [0] mount point ({@code String})
     * <li> [1] volume label ({@code String}). Redacted to empty string on organization-owned
     *     managed profile devices.
     */
    public static final int TAG_MEDIA_UNMOUNT = SecurityLogTags.SECURITY_MEDIA_UNMOUNTED;

    /**
     * Indicates that the audit log buffer has reached 90% of its capacity. There is no extra
     * payload in the log event.
     */
    public static final int TAG_LOG_BUFFER_SIZE_CRITICAL =
            SecurityLogTags.SECURITY_LOG_BUFFER_SIZE_CRITICAL;

    /**
     * Indicates that an admin has set a password expiration timeout. The log entry contains the
     * following information about the event, encapsulated in an {@link Object} array and accessible
     * via {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] target user ID ({@code Integer})
     * <li> [3] new password expiration timeout in milliseconds ({@code Long}).
     * @see DevicePolicyManager#setPasswordExpirationTimeout(ComponentName, long)
     */
    public static final int TAG_PASSWORD_EXPIRATION_SET =
            SecurityLogTags.SECURITY_PASSWORD_EXPIRATION_SET;

    /**
     * Indicates that an admin has set a requirement for password complexity. The log entry contains
     * the following information about the event, encapsulated in an {@link Object} array and
     * accessible via {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] target user ID ({@code Integer})
     * <li> [3] minimum password length ({@code Integer})
     * <li> [4] password quality constraint ({@code Integer})
     * <li> [5] minimum number of letters ({@code Integer})
     * <li> [6] minimum number of non-letters ({@code Integer})
     * <li> [7] minimum number of digits ({@code Integer})
     * <li> [8] minimum number of uppercase letters ({@code Integer})
     * <li> [9] minimum number of lowercase letters ({@code Integer})
     * <li> [10] minimum number of symbols ({@code Integer})
     *
     * @see DevicePolicyManager#setPasswordMinimumLength(ComponentName, int)
     * @see DevicePolicyManager#setPasswordQuality(ComponentName, int)
     * @see DevicePolicyManager#setPasswordMinimumLetters(ComponentName, int)
     * @see DevicePolicyManager#setPasswordMinimumNonLetter(ComponentName, int)
     * @see DevicePolicyManager#setPasswordMinimumLowerCase(ComponentName, int)
     * @see DevicePolicyManager#setPasswordMinimumUpperCase(ComponentName, int)
     * @see DevicePolicyManager#setPasswordMinimumNumeric(ComponentName, int)
     * @see DevicePolicyManager#setPasswordMinimumSymbols(ComponentName, int)
     */
    public static final int TAG_PASSWORD_COMPLEXITY_SET =
            SecurityLogTags.SECURITY_PASSWORD_COMPLEXITY_SET;

    /**
     * Indicates that an admin has set a password history length. The log entry contains the
     * following information about the event encapsulated in an {@link Object} array, accessible
     * via {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] target user ID ({@code Integer})
     * <li> [3] new password history length value ({@code Integer})
     * @see DevicePolicyManager#setPasswordHistoryLength(ComponentName, int)
     */
    public static final int TAG_PASSWORD_HISTORY_LENGTH_SET =
            SecurityLogTags.SECURITY_PASSWORD_HISTORY_LENGTH_SET;

    /**
     * Indicates that an admin has set a maximum screen lock timeout. The log entry contains the
     * following information about the event encapsulated in an {@link Object} array, accessible
     * via {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] target user ID ({@code Integer})
     * <li> [3] new screen lock timeout in milliseconds ({@code Long})
     * @see DevicePolicyManager#setMaximumTimeToLock(ComponentName, long)
     */
    public static final int TAG_MAX_SCREEN_LOCK_TIMEOUT_SET =
            SecurityLogTags.SECURITY_MAX_SCREEN_LOCK_TIMEOUT_SET;

    /**
     * Indicates that an admin has set a maximum number of failed password attempts before wiping
     * data. The log entry contains the following information about the event encapsulated in an
     * {@link Object} array, accessible via {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] target user ID ({@code Integer})
     * <li> [3] new maximum number of failed password attempts ({@code Integer})
     * @see DevicePolicyManager#setMaximumFailedPasswordsForWipe(ComponentName, int)
     */
    public static final int TAG_MAX_PASSWORD_ATTEMPTS_SET =
            SecurityLogTags.SECURITY_MAX_PASSWORD_ATTEMPTS_SET;

    /**
     * Indicates that an admin has set disabled keyguard features. The log entry contains the
     * following information about the event encapsulated in an {@link Object} array, accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] target user ID ({@code Integer})
     * <li> [3] disabled keyguard feature mask ({@code Integer}).
     * @see DevicePolicyManager#setKeyguardDisabledFeatures(ComponentName, int)
     */
    public static final int TAG_KEYGUARD_DISABLED_FEATURES_SET =
            SecurityLogTags.SECURITY_KEYGUARD_DISABLED_FEATURES_SET;

    /**
     * Indicates that an admin remotely locked the device or profile. The log entry contains the
     * following information about the event encapsulated in an {@link Object} array, accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String}),
     * <li> [1] admin user ID ({@code Integer}).
     * <li> [2] target user ID ({@code Integer})
     */
    public static final int TAG_REMOTE_LOCK = SecurityLogTags.SECURITY_REMOTE_LOCK;

    /**
     * Indicates a failure to wipe device or user data. There is no extra payload in the log event.
     */
    public static final int TAG_WIPE_FAILURE = SecurityLogTags.SECURITY_WIPE_FAILED;

    /**
     * Indicates that a cryptographic key was generated. The log entry contains the following
     * information about the event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] result ({@code Integer}, 0 if operation failed, 1 if succeeded)
     * <li> [1] alias of the key ({@code String})
     * <li> [2] requesting process uid ({@code Integer}).
     *
     * If security logging is enabled on organization-owned managed profile devices, only events
     * happening inside the managed profile will be visible.
     */
    public static final int TAG_KEY_GENERATED =
            SecurityLogTags.SECURITY_KEY_GENERATED;

    /**
     * Indicates that a cryptographic key was imported. The log entry contains the following
     * information about the event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] result ({@code Integer}, 0 if operation failed, 1 if succeeded)
     * <li> [1] alias of the key ({@code String})
     * <li> [2] requesting process uid ({@code Integer}).
     *
     * If security logging is enabled on organization-owned managed profile devices, only events
     * happening inside the managed profile will be visible.
     */
    public static final int TAG_KEY_IMPORT = SecurityLogTags.SECURITY_KEY_IMPORTED;

    /**
     * Indicates that a cryptographic key was destroyed. The log entry contains the following
     * information about the event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] result ({@code Integer}, 0 if operation failed, 1 if succeeded)
     * <li> [1] alias of the key ({@code String})
     * <li> [2] requesting process uid ({@code Integer}).
     *
     * If security logging is enabled on organization-owned managed profile devices, only events
     * happening inside the managed profile will be visible.
     */
    public static final int TAG_KEY_DESTRUCTION = SecurityLogTags.SECURITY_KEY_DESTROYED;

    /**
     * Indicates that a new root certificate has been installed into system's trusted credential
     * storage. The log entry contains the following information about the event, encapsulated in an
     * {@link Object} array and accessible via {@link SecurityEvent#getData()}:
     * <li> [0] result ({@code Integer}, 0 if operation failed, 1 if succeeded)
     * <li> [1] subject of the certificate ({@code String}).
     * <li> [2] which user the certificate is installed for ({@code Integer}), only available from
     *   version {@link android.os.Build.VERSION_CODES#R}.
     *
     * If security logging is enabled on organization-owned managed profile devices, only events
     * happening inside the managed profile will be visible.
     */
    public static final int TAG_CERT_AUTHORITY_INSTALLED =
            SecurityLogTags.SECURITY_CERT_AUTHORITY_INSTALLED;

    /**
     * Indicates that a new root certificate has been removed from system's trusted credential
     * storage. The log entry contains the following information about the event, encapsulated in an
     * {@link Object} array and accessible via {@link SecurityEvent#getData()}:
     * <li> [0] result ({@code Integer}, 0 if operation failed, 1 if succeeded)
     * <li> [1] subject of the certificate ({@code String}).
     * <li> [2] which user the certificate is removed from ({@code Integer}), only available from
     *   version {@link android.os.Build.VERSION_CODES#R}.
     *
     * If security logging is enabled on organization-owned managed profile devices, only events
     * happening inside the managed profile will be visible.
     */
    public static final int TAG_CERT_AUTHORITY_REMOVED =
            SecurityLogTags.SECURITY_CERT_AUTHORITY_REMOVED;

    /**
     * Indicates that an admin has set a user restriction. The log entry contains the following
     * information about the event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] user restriction ({@code String})
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     */
    public static final int TAG_USER_RESTRICTION_ADDED =
            SecurityLogTags.SECURITY_USER_RESTRICTION_ADDED;

    /**
     * Indicates that an admin has removed a user restriction. The log entry contains the following
     * information about the event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] user restriction ({@code String})
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     */
    public static final int TAG_USER_RESTRICTION_REMOVED =
            SecurityLogTags.SECURITY_USER_RESTRICTION_REMOVED;

    /**
     * Indicates that cryptographic functionality self test has completed. The log entry contains an
     * {@code Integer} payload, indicating the result of the test (0 if the test failed, 1 if
     * succeeded) and accessible via {@link SecurityEvent#getData()}.
     */
    public static final int TAG_CRYPTO_SELF_TEST_COMPLETED =
            SecurityLogTags.SECURITY_CRYPTO_SELF_TEST_COMPLETED;

    /**
     * Indicates a failed cryptographic key integrity check. The log entry contains the following
     * information about the event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] alias of the key ({@code String})
     * <li> [1] owner application uid ({@code Integer}).
     *
     * If security logging is enabled on organization-owned managed profile devices, only events
     * happening inside the managed profile will be visible.
     */
    public static final int TAG_KEY_INTEGRITY_VIOLATION =
            SecurityLogTags.SECURITY_KEY_INTEGRITY_VIOLATION;

    /**
     * Indicates a failure to validate X.509v3 certificate. The log entry contains a {@code String}
     * payload indicating the failure reason, accessible via {@link SecurityEvent#getData()}.
     */
    public static final int TAG_CERT_VALIDATION_FAILURE =
            SecurityLogTags.SECURITY_CERT_VALIDATION_FAILURE;

    /**
     * Indicates that the admin has set policy to disable camera.
     * The log entry contains the following information about the event, encapsulated in an
     * {@link Object} array and accessible via {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] target user ID ({@code Integer})
     * <li> [3] whether the camera is disabled or not ({@code Integer}, 1 if it's disabled,
     *      0 if enabled)
     */
    public static final int TAG_CAMERA_POLICY_SET =
            SecurityLogTags.SECURITY_CAMERA_POLICY_SET;

    /**
     * Indicates that an admin has set a password complexity requirement, using the platform's
     * pre-defined complexity levels. The log entry contains the following information about the
     * event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] target user ID ({@code Integer})
     * <li> [3] Password complexity ({@code Integer})
     *
     * @see DevicePolicyManager#setRequiredPasswordComplexity(int)
     */
    public static final int TAG_PASSWORD_COMPLEXITY_REQUIRED =
            SecurityLogTags.SECURITY_PASSWORD_COMPLEXITY_REQUIRED;

    /**
     * Indicates that a user has just changed their lockscreen password.
     * The log entry contains the following information about the
     * event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] complexity for the new password ({@code Integer})
     * <li> [1] target user ID ({@code Integer})
     *
     * <p>Password complexity levels are defined as in
     * {@link DevicePolicyManager#getPasswordComplexity()}
     */
    public static final int TAG_PASSWORD_CHANGED = SecurityLogTags.SECURITY_PASSWORD_CHANGED;

    /**
     * Indicates that an event occurred as the device attempted to connect to
     * a managed WiFi network. The log entry contains the following information about the
     * event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] Last 2 octets of the network BSSID ({@code String}, in the form "xx:xx:xx:xx:AA:BB")
     * <li> [1] Type of event that occurred ({@code String}). Event types are CONNECTED,
     *      DISCONNECTED, ASSOCIATING, ASSOCIATED, EAP_METHOD_SELECTED, EAP_FAILURE,
     *      SSID_TEMP_DISABLED, and OPEN_SSL_FAILURE.
     * <li> [2] Optional human-readable failure reason, empty string if none ({@code String})
     */
    public static final int TAG_WIFI_CONNECTION = SecurityLogTags.SECURITY_WIFI_CONNECTION;

    /**
     * Indicates that the device disconnects from a managed WiFi network.
     * The log entry contains the following information about the
     * event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] Last 2 octets of the network BSSID ({@code String}, in the form "xx:xx:xx:xx:AA:BB")
     * <li> [1] Optional human-readable disconnection reason, empty string if none ({@code String})
     */
    public static final int TAG_WIFI_DISCONNECTION = SecurityLogTags.SECURITY_WIFI_DISCONNECTION;

    /**
     * Indicates that the device attempts to connect to a Bluetooth device.
     * The log entry contains the following information about the
     * event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] The MAC address of the Bluetooth device ({@code String})
     * <li> [1] Whether the connection is successful ({@code Integer}, 1 if successful, 0 otherwise)
     * <li> [2] Optional human-readable failure reason, empty string if none ({@code String})
     */
    public static final int TAG_BLUETOOTH_CONNECTION =
            SecurityLogTags.SECURITY_BLUETOOTH_CONNECTION;

    /**
     * Indicates that the device disconnects from a connected Bluetooth device.
     * The log entry contains the following information about the
     * event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] The MAC address of the connected Bluetooth device ({@code String})
     * <li> [1] Optional human-readable disconnection reason, empty string if none ({@code String})
     */
    public static final int TAG_BLUETOOTH_DISCONNECTION =
            SecurityLogTags.SECURITY_BLUETOOTH_DISCONNECTION;

    /**
     * Indicates that a package is installed.
     * The log entry contains the following information about the
     * event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] Name of the package being installed ({@code String})
     * <li> [1] Package version code ({@code Long})
     * <li> [2] UserId of the user that installed this package ({@code Integer})
     */
    public static final int TAG_PACKAGE_INSTALLED = SecurityLogTags.SECURITY_PACKAGE_INSTALLED;

    /**
     * Indicates that a package is updated.
     * The log entry contains the following information about the
     * event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] Name of the package being updated ({@code String})
     * <li> [1] Package version code ({@code Long})
     * <li> [2] UserId of the user that updated this package ({@code Integer})
     */
    public static final int TAG_PACKAGE_UPDATED = SecurityLogTags.SECURITY_PACKAGE_UPDATED;

    /**
     * Indicates that a package is uninstalled.
     * The log entry contains the following information about the
     * event, encapsulated in an {@link Object} array and accessible via
     * {@link SecurityEvent#getData()}:
     * <li> [0] Name of the package being uninstalled ({@code String})
     * <li> [1] Package version code ({@code Long})
     * <li> [2] UserId of the user that uninstalled this package ({@code Integer})
     */
    public static final int TAG_PACKAGE_UNINSTALLED = SecurityLogTags.SECURITY_PACKAGE_UNINSTALLED;

    /**
     * Indicates that an admin has enabled or disabled backup service. The log entry contains the
     * following information about the event encapsulated in an {@link Object} array, accessible
     * via {@link SecurityEvent#getData()}:
     * <li> [0] admin package name ({@code String})
     * <li> [1] admin user ID ({@code Integer})
     * <li> [2] backup service state ({@code Integer}, 1 for enabled, 0 for disabled)
     * @see DevicePolicyManager#setBackupServiceEnabled(ComponentName, boolean)
     */
    @FlaggedApi(Flags.FLAG_BACKUP_SERVICE_SECURITY_LOG_EVENT_ENABLED)
    public static final int TAG_BACKUP_SERVICE_TOGGLED =
            SecurityLogTags.SECURITY_BACKUP_SERVICE_TOGGLED;
    /**
     * Event severity level indicating that the event corresponds to normal workflow.
     */
    public static final int LEVEL_INFO = 1;

    /**
     * Event severity level indicating that the event may require admin attention.
     */
    public static final int LEVEL_WARNING = 2;

    /**
     * Event severity level indicating that the event requires urgent admin action.
     */
    public static final int LEVEL_ERROR = 3;

    /**
     * Returns if security logging is enabled. Log producers should only write new logs if this is
     * true. Under the hood this is the logical AND of whether device owner exists and whether
     * it enables logging by setting the system property {@link #PROPERTY_LOGGING_ENABLED}.
     * @hide
     */
    public static native boolean isLoggingEnabled();

    /**
     * @hide
     */
    public static void setLoggingEnabledProperty(boolean enabled) {
        SystemProperties.set(PROPERTY_LOGGING_ENABLED, enabled ? "true" : "false");
    }

    /**
     * @hide
     */
    public static boolean getLoggingEnabledProperty() {
        return SystemProperties.getBoolean(PROPERTY_LOGGING_ENABLED, false);
    }

    /**
     * A class representing a security event log entry.
     */
    public static final class SecurityEvent implements Parcelable {
        private Event mEvent;
        private long mId;

        /**
         * Constructor used by native classes to generate SecurityEvent instances.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ SecurityEvent(byte[] data) {
            this(0, data);
        }

        /**
         * Constructor used by Parcelable.Creator to generate SecurityEvent instances.
         * @hide
         */
        /* package */ SecurityEvent(Parcel source) {
            this(source.readLong(), source.createByteArray());
        }

        /** @hide */
        @TestApi
        public SecurityEvent(long id, byte[] data) {
            mId = id;
            mEvent = Event.fromBytes(data);
        }

        /**
         * Returns the timestamp in nano seconds when this event was logged.
         */
        public long getTimeNanos() {
            return mEvent.getTimeNanos();
        }

        /**
         * Returns the tag of this log entry, which specifies entry's semantics.
         */
        public @SecurityLogTag int getTag() {
            return mEvent.getTag();
        }

        /**
         * Returns the payload contained in this log entry or {@code null} if there is no payload.
         */
        public Object getData() {
            return mEvent.getData();
        }

        /** @hide */
        public int getIntegerData(int index) {
            return (Integer) ((Object[]) mEvent.getData())[index];
        }

        /** @hide */
        public String getStringData(int index) {
            return (String) ((Object[]) mEvent.getData())[index];
        }

        /**
         * @hide
         */
        public void setId(long id) {
            this.mId = id;
        }

        /**
         * Returns the id of the event, where the id monotonically increases for each event. The id
         * is reset when the device reboots, and when security logging is enabled.
         */
        public long getId() {
            return mId;
        }

        /**
         * Returns severity level for the event.
         */
        public @SecurityLogLevel int getLogLevel() {
            switch (getTag()) {
                case TAG_ADB_SHELL_INTERACTIVE:
                case TAG_ADB_SHELL_CMD:
                case TAG_SYNC_RECV_FILE:
                case TAG_SYNC_SEND_FILE:
                case TAG_APP_PROCESS_START:
                case TAG_KEYGUARD_DISMISSED:
                case TAG_KEYGUARD_SECURED:
                case TAG_OS_STARTUP:
                case TAG_OS_SHUTDOWN:
                case TAG_LOGGING_STARTED:
                case TAG_LOGGING_STOPPED:
                case TAG_MEDIA_MOUNT:
                case TAG_MEDIA_UNMOUNT:
                case TAG_PASSWORD_EXPIRATION_SET:
                case TAG_PASSWORD_COMPLEXITY_SET:
                case TAG_PASSWORD_HISTORY_LENGTH_SET:
                case TAG_MAX_SCREEN_LOCK_TIMEOUT_SET:
                case TAG_MAX_PASSWORD_ATTEMPTS_SET:
                case TAG_USER_RESTRICTION_ADDED:
                case TAG_USER_RESTRICTION_REMOVED:
                case TAG_CAMERA_POLICY_SET:
                case TAG_PASSWORD_COMPLEXITY_REQUIRED:
                case TAG_PASSWORD_CHANGED:
                    return LEVEL_INFO;
                case TAG_CERT_AUTHORITY_REMOVED:
                case TAG_CRYPTO_SELF_TEST_COMPLETED:
                    return getSuccess() ? LEVEL_INFO : LEVEL_ERROR;
                case TAG_CERT_AUTHORITY_INSTALLED:
                case TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT:
                case TAG_KEY_IMPORT:
                case TAG_KEY_DESTRUCTION:
                case TAG_KEY_GENERATED:
                    return getSuccess() ? LEVEL_INFO : LEVEL_WARNING;
                case TAG_LOG_BUFFER_SIZE_CRITICAL:
                case TAG_WIPE_FAILURE:
                case TAG_KEY_INTEGRITY_VIOLATION:
                    return LEVEL_ERROR;
                case TAG_CERT_VALIDATION_FAILURE:
                    return LEVEL_WARNING;
                default:
                    return LEVEL_INFO;
            }
        }

        // Success/failure if present is encoded as an integer in the first (0th) element of data.
        private boolean getSuccess() {
            final Object data = getData();
            if (data == null || !(data instanceof Object[])) {
                return false;
            }

            final Object[] array = (Object[]) data;
            return array.length >= 1 && array[0] instanceof Integer && (Integer) array[0] != 0;
        }

        /**
         * Returns a copy of the security event suitable to be consumed by the provided user.
         * This method will either return the original event itself if the event does not contain
         * any sensitive data; or a copy of itself but with sensitive information redacted; or
         * {@code null} if the entire event should not be accessed by the given user.
         *
         * @param accessingUser which user this security event is to be accessed, must be a
         *     concrete user id.
         * @hide
         */
        public SecurityEvent redact(int accessingUser) {
            // Which user the event is associated with, for the purpose of log redaction.
            final int userId;
            switch (getTag()) {
                case SecurityLog.TAG_ADB_SHELL_CMD:
                    return new SecurityEvent(getId(), mEvent.withNewData("").getBytes());
                case SecurityLog.TAG_MEDIA_MOUNT:
                case SecurityLog.TAG_MEDIA_UNMOUNT:
                    // Partial redaction
                    String mountPoint;
                    try {
                        mountPoint = getStringData(0);
                    } catch (Exception e) {
                        return null;
                    }
                    return new SecurityEvent(getId(),
                            mEvent.withNewData(new Object[] {mountPoint, ""}).getBytes());
                case SecurityLog.TAG_APP_PROCESS_START:
                    try {
                        userId = UserHandle.getUserId(getIntegerData(2));
                    } catch (Exception e) {
                        return null;
                    }
                    break;
                case SecurityLog.TAG_CERT_AUTHORITY_INSTALLED:
                case SecurityLog.TAG_CERT_AUTHORITY_REMOVED:
                case SecurityLog.TAG_PACKAGE_INSTALLED:
                case SecurityLog.TAG_PACKAGE_UPDATED:
                case SecurityLog.TAG_PACKAGE_UNINSTALLED:
                    try {
                        userId = getIntegerData(2);
                    } catch (Exception e) {
                        return null;
                    }
                    break;
                case SecurityLog.TAG_KEY_GENERATED:
                case SecurityLog.TAG_KEY_IMPORT:
                case SecurityLog.TAG_KEY_DESTRUCTION:
                    try {
                        userId = UserHandle.getUserId(getIntegerData(2));
                    } catch (Exception e) {
                        return null;
                    }
                    break;
                case SecurityLog.TAG_KEY_INTEGRITY_VIOLATION:
                    try {
                        userId = UserHandle.getUserId(getIntegerData(1));
                    } catch (Exception e) {
                        return null;
                    }
                    break;
                case SecurityLog.TAG_PASSWORD_CHANGED:
                    try {
                        userId = getIntegerData(1);
                    } catch (Exception e) {
                        return null;
                    }
                    break;
                default:
                    userId = UserHandle.USER_NULL;
            }
            // If the event is not user-specific, or matches the accessing user, return it
            // unmodified, else redact by returning null
            if (userId == UserHandle.USER_NULL || accessingUser == userId) {
                return this;
            } else {
                return null;
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(mId);
            dest.writeByteArray(mEvent.getBytes());
        }

        public static final @android.annotation.NonNull Parcelable.Creator<SecurityEvent> CREATOR =
                new Parcelable.Creator<SecurityEvent>() {
            @Override
            public SecurityEvent createFromParcel(Parcel source) {
                return new SecurityEvent(source);
            }

            @Override
            public SecurityEvent[] newArray(int size) {
                return new SecurityEvent[size];
            }
        };

        /**
         * @hide
         */
        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SecurityEvent other = (SecurityEvent) o;
            return mEvent.equals(other.mEvent) && mId == other.mId;
        }

        /**
         * @hide
         */
        @Override
        public int hashCode() {
            return Objects.hash(mEvent, mId);
        }

        /** @hide */
        public boolean eventEquals(SecurityEvent other) {
            return other != null && mEvent.equals(other.mEvent);
        }
    }

    /**
     * Redacts events in-place according to which user will consume the events.
     *
     * @param accessingUser which user will consume the redacted events, or UserHandle.USER_ALL if
     *     redaction should be skipped.
     * @hide
     */
    public static void redactEvents(ArrayList<SecurityEvent> logList, int accessingUser) {
        if (accessingUser == UserHandle.USER_ALL) return;
        int end = 0;
        for (int i = 0; i < logList.size(); i++) {
            SecurityEvent event = logList.get(i);
            event = event.redact(accessingUser);
            if (event != null) {
                logList.set(end, event);
                end++;
            }
        }
        for (int i = logList.size() - 1; i >= end; i--) {
            logList.remove(i);
        }
    }

    /**
     * Retrieve all security logs and return immediately.
     * @hide
     */
    public static native void readEvents(Collection<SecurityEvent> output) throws IOException;

    /**
     * Retrieve all security logs since the given timestamp in nanoseconds and return immediately.
     * @hide
     */
    public static native void readEventsSince(long timestamp, Collection<SecurityEvent> output)
            throws IOException;

    /**
     * Retrieve all security logs before the last reboot. May return corrupted data due to
     * unreliable pstore.
     * @hide
     */
    public static native void readPreviousEvents(Collection<SecurityEvent> output)
            throws IOException;

    /**
     * Retrieve all security logs whose timestamp is equal to or greater than the given timestamp in
     * nanoseconds. This method will block until either the last log earlier than the given
     * timestamp is about to be pruned, or after a 2-hour timeout has passed.
     * @hide
     */
    public static native void readEventsOnWrapping(long timestamp, Collection<SecurityEvent> output)
            throws IOException;

    /**
     * Write a log entry to the underlying storage, with several payloads.
     * Supported types of payload are: integer, long, float, string plus array of supported types.
     *
     * <p>Security log is part of Android's device management capability that tracks
     * security-sensitive events for auditing purposes.
     *
     * @param tag the tag ID of the security event
     * @param payloads a list of payload values. Each tag dictates the expected payload types
     *                 and their meanings
     * @see DevicePolicyManager#setSecurityLoggingEnabled(ComponentName, boolean)
     *
     * @hide
     */
    // TODO(b/218658622): enforce WRITE_SECURITY_LOG in logd.
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_SECURITY_LOG)
    public static native int writeEvent(@SecurityLogTag int tag, @NonNull Object... payloads);
}
