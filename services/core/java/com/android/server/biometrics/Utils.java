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

package com.android.server.biometrics;

import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
import static com.android.server.biometrics.PreAuthInfo.AUTHENTICATOR_OK;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_DISABLED_BY_DEVICE_POLICY;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_HARDWARE_NOT_DETECTED;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_INSUFFICIENT_STRENGTH;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_LOCKOUT_PERMANENT;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_LOCKOUT_TIMED;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_NOT_ENABLED_FOR_APPS;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_NOT_ENROLLED;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_NO_HARDWARE;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_SENSOR_PRIVACY_ENABLED;
import static com.android.server.biometrics.PreAuthInfo.CREDENTIAL_NOT_ENROLLED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricPrompt.AuthenticationResultType;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorProperties;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.biometrics.sensors.BaseClientMonitor;

import java.util.List;

public class Utils {

    private static final String TAG = "BiometricUtils";

    public static boolean isDebugEnabled(Context context, int targetUserId) {
        if (targetUserId == UserHandle.USER_NULL) {
            return false;
        }

        if (!(Build.IS_ENG || Build.IS_USERDEBUG)) {
            return false;
        }

        if (Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.BIOMETRIC_DEBUG_ENABLED, 0,
                targetUserId) == 0) {
            return false;
        }
        return true;
    }

    /** If virtualized biometrics are supported (requires debug build). */
    public static boolean isVirtualEnabled(Context context) {
        return Build.isDebuggable()
                && Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.BIOMETRIC_VIRTUAL_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
    }

    /**
     * Combines {@link PromptInfo#setDeviceCredentialAllowed(boolean)} with
     * {@link PromptInfo#setAuthenticators(int)}, as the former is not flexible enough.
     */
    static void combineAuthenticatorBundles(PromptInfo promptInfo) {
        // Cache and remove explicit ALLOW_DEVICE_CREDENTIAL boolean flag from the bundle.
        final boolean deviceCredentialAllowed = promptInfo.isDeviceCredentialAllowed();
        promptInfo.setDeviceCredentialAllowed(false);

        final @Authenticators.Types int authenticators;
        if (promptInfo.getAuthenticators() != 0) {
            // Ignore ALLOW_DEVICE_CREDENTIAL flag if AUTH_TYPES_ALLOWED is defined.
            authenticators = promptInfo.getAuthenticators();
        } else {
            // Otherwise, use ALLOW_DEVICE_CREDENTIAL flag along with Weak+ biometrics by default.
            authenticators = deviceCredentialAllowed
                    ? Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK
                    : Authenticators.BIOMETRIC_WEAK;
        }

        promptInfo.setAuthenticators(authenticators);
    }

    /**
     * @param authenticators composed of one or more values from {@link Authenticators}
     * @return true if device credential is allowed.
     */
    static boolean isCredentialRequested(@Authenticators.Types int authenticators) {
        return (authenticators & Authenticators.DEVICE_CREDENTIAL) != 0;
    }

    /**
     * @param promptInfo should be first processed by
     * {@link #combineAuthenticatorBundles(PromptInfo)}
     * @return true if device credential is allowed.
     */
    static boolean isCredentialRequested(PromptInfo promptInfo) {
        return isCredentialRequested(promptInfo.getAuthenticators());
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param authenticators composed of one or more values from {@link Authenticators}
     * @return minimal allowed biometric strength or 0 if biometric authentication is not allowed.
     */
    static int getPublicBiometricStrength(@Authenticators.Types int authenticators) {
        // Only biometrics WEAK and above are allowed to integrate with the public APIs.
        return authenticators & Authenticators.BIOMETRIC_WEAK;
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param promptInfo should be first processed by
     * {@link #combineAuthenticatorBundles(PromptInfo)}
     * @return minimal allowed biometric strength or 0 if biometric authentication is not allowed.
     */
    static int getPublicBiometricStrength(PromptInfo promptInfo) {
        return getPublicBiometricStrength(promptInfo.getAuthenticators());
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param authenticators composed of one or more values from {@link Authenticators}
     * @return true if biometric authentication is allowed.
     */
    static boolean isBiometricRequested(@Authenticators.Types int authenticators) {
        return getPublicBiometricStrength(authenticators) != 0;
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param promptInfo should be first processed by
     * {@link #combineAuthenticatorBundles(PromptInfo)}
     * @return true if biometric authentication is allowed.
     */
    static boolean isBiometricRequested(PromptInfo promptInfo) {
        return getPublicBiometricStrength(promptInfo) != 0;
    }

    /**
     * @param sensorStrength the strength of the sensor
     * @param requestedStrength the strength that it must meet
     * @return true only if the sensor is at least as strong as the requested strength
     */
    public static boolean isAtLeastStrength(@Authenticators.Types int sensorStrength,
            @Authenticators.Types int requestedStrength) {
        // Clear out any bits that are not reserved for biometric
        sensorStrength &= Authenticators.BIOMETRIC_MIN_STRENGTH;

        // If the authenticator contains bits outside of the requested strength, it is too weak.
        if ((sensorStrength & ~requestedStrength) != 0) {
            return false;
        }

        for (int i = Authenticators.BIOMETRIC_MAX_STRENGTH;
                i <= requestedStrength; i = (i << 1) | 1) {
            if (i == sensorStrength) {
                return true;
            }
        }

        Slog.e(BiometricService.TAG, "Unknown sensorStrength: " + sensorStrength
                + ", requestedStrength: " + requestedStrength);
        return false;
    }

    /**
     * Checks if the authenticator configuration is a valid combination of the public APIs
     * @param promptInfo
     * @return
     */
    static boolean isValidAuthenticatorConfig(PromptInfo promptInfo) {
        final int authenticators = promptInfo.getAuthenticators();
        return isValidAuthenticatorConfig(authenticators);
    }

    /**
     * Checks if the authenticator configuration is a valid combination of the public APIs
     * @param authenticators
     * @return
     */
    static boolean isValidAuthenticatorConfig(int authenticators) {
        // The caller is not required to set the authenticators. But if they do, check the below.
        if (authenticators == 0) {
            return true;
        }

        // Check if any of the non-biometric and non-credential bits are set. If so, this is
        // invalid.
        final int testBits = ~(Authenticators.DEVICE_CREDENTIAL
                | Authenticators.BIOMETRIC_MIN_STRENGTH);
        if ((authenticators & testBits) != 0) {
            Slog.e(BiometricService.TAG, "Non-biometric, non-credential bits found."
                    + " Authenticators: " + authenticators);
            return false;
        }

        // Check that biometrics bits are either NONE, WEAK, or STRONG. If NONE, DEVICE_CREDENTIAL
        // should be set.
        final int biometricBits = authenticators & Authenticators.BIOMETRIC_MIN_STRENGTH;
        if (biometricBits == Authenticators.EMPTY_SET
                && isCredentialRequested(authenticators)) {
            return true;
        } else if (biometricBits == Authenticators.BIOMETRIC_STRONG) {
            return true;
        } else if (biometricBits == Authenticators.BIOMETRIC_WEAK) {
            return true;
        }

        Slog.e(BiometricService.TAG, "Unsupported biometric flags. Authenticators: "
                + authenticators);
        // Non-supported biometric flags are being used
        return false;
    }

    /**
     * Converts error codes from BiometricConstants, which are used in most of the internal plumbing
     * and eventually returned to {@link BiometricPrompt.AuthenticationCallback} to public
     * {@link BiometricManager} constants, which are used by APIs such as
     * {@link BiometricManager#canAuthenticate(int)}
     *
     * @param biometricConstantsCode see {@link BiometricConstants}
     * @return see {@link BiometricManager}
     */
    static int biometricConstantsToBiometricManager(int biometricConstantsCode) {
        final int biometricManagerCode;

        switch (biometricConstantsCode) {
            case BiometricConstants.BIOMETRIC_SUCCESS:
                biometricManagerCode = BiometricManager.BIOMETRIC_SUCCESS;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS:
            case BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT:
            case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT:
                biometricManagerCode = BiometricManager.BIOMETRIC_SUCCESS;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_SENSOR_PRIVACY_ENABLED:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
                break;
            default:
                Slog.e(BiometricService.TAG, "Unhandled result code: " + biometricConstantsCode);
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
                break;
        }
        return biometricManagerCode;
    }

    /**
     * Converts a {@link BiometricPrompt} dismissal reason to an authentication type at the level of
     * granularity supported by {@link BiometricPrompt.AuthenticationResult}.
     *
     * @param reason The reason that the {@link BiometricPrompt} was dismissed. Must be one of:
     *               {@link BiometricPrompt#DISMISSED_REASON_CREDENTIAL_CONFIRMED},
     *               {@link BiometricPrompt#DISMISSED_REASON_BIOMETRIC_CONFIRMED}, or
     *               {@link BiometricPrompt#DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED}
     * @return An integer representing the authentication type for {@link
     *         BiometricPrompt.AuthenticationResult}.
     * @throws IllegalArgumentException if given an invalid dismissal reason.
     */
    static @AuthenticationResultType int getAuthenticationTypeForResult(int reason) {
        switch (reason) {
            case BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED:
                return BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL;

            case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED:
            case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED:
                return BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC;

            default:
                throw new IllegalArgumentException("Unsupported dismissal reason: " + reason);
        }
    }


    static int authenticatorStatusToBiometricConstant(
            @PreAuthInfo.AuthenticatorStatus int status) {
        switch (status) {
            case BIOMETRIC_NO_HARDWARE:
            case BIOMETRIC_INSUFFICIENT_STRENGTH:
                return BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT;

            case AUTHENTICATOR_OK:
                return BiometricConstants.BIOMETRIC_SUCCESS;

            case BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE:
                return BiometricConstants.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED;

            case BIOMETRIC_NOT_ENROLLED:
                return BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS;

            case CREDENTIAL_NOT_ENROLLED:
                return BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL;

            case BIOMETRIC_LOCKOUT_TIMED:
                return BiometricConstants.BIOMETRIC_ERROR_LOCKOUT;

            case BIOMETRIC_LOCKOUT_PERMANENT:
                return BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
            case BIOMETRIC_SENSOR_PRIVACY_ENABLED:
                return BiometricConstants.BIOMETRIC_ERROR_SENSOR_PRIVACY_ENABLED;
            case BIOMETRIC_DISABLED_BY_DEVICE_POLICY:
            case BIOMETRIC_HARDWARE_NOT_DETECTED:
            case BIOMETRIC_NOT_ENABLED_FOR_APPS:
            default:
                return BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;
        }
    }

    static boolean isConfirmationSupported(@BiometricAuthenticator.Modality int modality) {
        switch (modality) {
            case BiometricAuthenticator.TYPE_FACE:
            case BiometricAuthenticator.TYPE_IRIS:
                return true;
            default:
                return false;
        }
    }

    static int removeBiometricBits(@Authenticators.Types int authenticators) {
        return authenticators & ~Authenticators.BIOMETRIC_MIN_STRENGTH;
    }

    public static boolean listContains(int[] haystack, int needle) {
        for (int i = 0; i < haystack.length; i++) {
            if (haystack[i] == needle) {
                return true;
            }
        }
        return false;
    }

    /** Same as checkPermission but also allows shell. */
    public static void checkPermissionOrShell(Context context, String permission) {
        if (Binder.getCallingUid() == Process.SHELL_UID) {
            return;
        }
        checkPermission(context, permission);
    }


    public static void checkPermission(Context context, String permission) {
        context.enforceCallingOrSelfPermission(permission,
                "Must have " + permission + " permission.");
    }

    public static boolean isCurrentUserOrProfile(Context context, int userId) {
        UserManager um = UserManager.get(context);
        if (um == null) {
            Slog.e(TAG, "Unable to get UserManager");
            return false;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            // Allow current user or profiles of the current user...
            for (int profileId : um.getEnabledProfileIds(ActivityManager.getCurrentUser())) {
                if (profileId == userId) {
                    return true;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return false;
    }

    public static boolean isStrongBiometric(int sensorId) {
        IBiometricService service = IBiometricService.Stub.asInterface(
                ServiceManager.getService(Context.BIOMETRIC_SERVICE));
        try {
            return Utils.isAtLeastStrength(service.getCurrentStrength(sensorId),
                    Authenticators.BIOMETRIC_STRONG);
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
            return false;
        }
    }

    /**
     * Returns the sensor's current strength, taking any updated strengths into effect.
     *
     * @param sensorId The sensor Id
     * @return see {@link BiometricManager.Authenticators}
     */
    public static @Authenticators.Types int getCurrentStrength(int sensorId) {
        IBiometricService service = IBiometricService.Stub.asInterface(
                ServiceManager.getService(Context.BIOMETRIC_SERVICE));
        try {
            return service.getCurrentStrength(sensorId);
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
            return Authenticators.EMPTY_SET;
        }
    }

    /**
     * Checks if a client package matches Keyguard and can perform internal biometric operations.
     *
     * @param context The system context.
     * @param clientPackage The name of the package to be checked against Keyguard.
     * @return Whether the given package matches Keyguard.
     */
    public static boolean isKeyguard(@NonNull Context context, @Nullable String clientPackage) {
        final boolean hasPermission = hasInternalPermission(context);
        final ComponentName keyguardComponent = ComponentName.unflattenFromString(
                context.getResources().getString(R.string.config_keyguardComponent));
        final String keyguardPackage = keyguardComponent != null
                ? keyguardComponent.getPackageName() : null;
        return hasPermission && keyguardPackage != null && keyguardPackage.equals(clientPackage);
    }

    /**
     * Checks if a client package matches the Android system and can perform internal biometric
     * operations.
     *
     * @param context The system context.
     * @param clientPackage The name of the package to be checked against the Android system.
     * @return Whether the given package matches the Android system.
     */
    public static boolean isSystem(@NonNull Context context, @Nullable String clientPackage) {
        return hasInternalPermission(context) && "android".equals(clientPackage);
    }

    /**
     * Checks if a client package matches Settings and can perform internal biometric operations.
     *
     * @param context The system context.
     * @param clientPackage The name of the package to be checked against Settings.
     * @return Whether the given package matches Settings.
     */
    public static boolean isSettings(@NonNull Context context, @Nullable String clientPackage) {
        return hasInternalPermission(context) && "com.android.settings".equals(clientPackage);
    }

    private static boolean hasInternalPermission(@NonNull Context context) {
        return context.checkCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static String getClientName(@Nullable BaseClientMonitor client) {
        return client != null ? client.getClass().getSimpleName() : "null";
    }

    private static boolean containsFlag(int haystack, int needle) {
        return (haystack & needle) != 0;
    }

    public static boolean isUserEncryptedOrLockdown(@NonNull LockPatternUtils lpu, int user) {
        final int strongAuth = lpu.getStrongAuthForUser(user);
        final boolean isEncrypted = containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_BOOT);
        final boolean isLockDown = containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW)
                || containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
        Slog.d(TAG, "isEncrypted: " + isEncrypted + " isLockdown: " + isLockDown);
        return isEncrypted || isLockDown;
    }

    public static boolean isForeground(int callingUid, int callingPid) {
        try {
            final List<ActivityManager.RunningAppProcessInfo> procs =
                    ActivityManager.getService().getRunningAppProcesses();
            if (procs == null) {
                Slog.e(TAG, "No running app processes found, defaulting to true");
                return true;
            }

            for (int i = 0; i < procs.size(); i++) {
                ActivityManager.RunningAppProcessInfo proc = procs.get(i);
                if (proc.pid == callingPid && proc.uid == callingUid
                        && proc.importance <= IMPORTANCE_FOREGROUND_SERVICE) {
                    return true;
                }
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "am.getRunningAppProcesses() failed");
        }
        return false;
    }

    /**
     * Converts from {@link BiometricManager.Authenticators} biometric strength to the internal
     * {@link SensorPropertiesInternal} strength.
     */
    public static @SensorProperties.Strength int authenticatorStrengthToPropertyStrength(
            @Authenticators.Types int strength) {
        switch (strength) {
            case BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE:
                return SensorProperties.STRENGTH_CONVENIENCE;
            case BiometricManager.Authenticators.BIOMETRIC_WEAK:
                return SensorProperties.STRENGTH_WEAK;
            case BiometricManager.Authenticators.BIOMETRIC_STRONG:
                return SensorProperties.STRENGTH_STRONG;
            default:
                throw new IllegalArgumentException("Unknown strength: " + strength);
        }
    }

    public static @Authenticators.Types int propertyStrengthToAuthenticatorStrength(
            @SensorProperties.Strength int strength) {
        switch (strength) {
            case SensorProperties.STRENGTH_CONVENIENCE:
                return Authenticators.BIOMETRIC_CONVENIENCE;
            case SensorProperties.STRENGTH_WEAK:
                return Authenticators.BIOMETRIC_WEAK;
            case SensorProperties.STRENGTH_STRONG:
                return Authenticators.BIOMETRIC_STRONG;
            default:
                throw new IllegalArgumentException("Unknown strength: " + strength);
        }
    }
}
