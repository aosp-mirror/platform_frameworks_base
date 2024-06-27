/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.biometrics;

import static android.Manifest.permission.TEST_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.WRITE_DEVICE_CONFIG;

import static com.android.internal.util.FrameworkStatsLog.AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_BIOMETRIC_MANAGER_CAN_AUTHENTICATE;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.security.keystore.KeyProperties;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * A class that contains biometric utilities. For authentication, see {@link BiometricPrompt}.
 */
@SystemService(Context.BIOMETRIC_SERVICE)
public class BiometricManager {

    private static final String TAG = "BiometricManager";

    /**
     * No error detected.
     */
    public static final int BIOMETRIC_SUCCESS =
            BiometricConstants.BIOMETRIC_SUCCESS;

    /**
     * The hardware is unavailable. Try again later.
     */
    public static final int BIOMETRIC_ERROR_HW_UNAVAILABLE =
            BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;

    /**
     * The user does not have any biometrics enrolled.
     */
    public static final int BIOMETRIC_ERROR_NONE_ENROLLED =
            BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS;

    /**
     * There is no biometric hardware.
     */
    public static final int BIOMETRIC_ERROR_NO_HARDWARE =
            BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT;

    /**
     * A security vulnerability has been discovered and the sensor is unavailable until a
     * security update has addressed this issue. This error can be received if for example,
     * authentication was requested with {@link Authenticators#BIOMETRIC_STRONG}, but the
     * sensor's strength can currently only meet {@link Authenticators#BIOMETRIC_WEAK}.
     */
    public static final int BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED =
            BiometricConstants.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED;

    /**
     * Returned from {@link BiometricManager#getLastAuthenticationTime(int)} when no matching
     * successful authentication has been performed since boot.
     */
    @FlaggedApi(Flags.FLAG_LAST_AUTHENTICATION_TIME)
    public static final long BIOMETRIC_NO_AUTHENTICATION =
            BiometricConstants.BIOMETRIC_NO_AUTHENTICATION;

    private static final int GET_LAST_AUTH_TIME_ALLOWED_AUTHENTICATORS =
            Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_STRONG;

    /**
     * Enroll reason extra that can be used by settings to understand where this request came
     * from.
     * @hide
     */
    public static final String EXTRA_ENROLL_REASON = "enroll_reason";

    /**
     * @hide
     */
    @IntDef({BIOMETRIC_SUCCESS,
            BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BIOMETRIC_ERROR_NONE_ENROLLED,
            BIOMETRIC_ERROR_NO_HARDWARE,
            BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface BiometricError {}

    /**
     * Types of authenticators, defined at a level of granularity supported by
     * {@link BiometricManager} and {@link BiometricPrompt}.
     *
     * <p>Types may combined via bitwise OR into a single integer representing multiple
     * authenticators (e.g. <code>DEVICE_CREDENTIAL | BIOMETRIC_WEAK</code>).
     *
     * @see #canAuthenticate(int)
     * @see BiometricPrompt.Builder#setAllowedAuthenticators(int)
     */
    public interface Authenticators {
        /**
         * An {@link IntDef} representing valid combinations of authenticator types.
         * @hide
         */
        @IntDef(flag = true, value = {
                BIOMETRIC_STRONG,
                BIOMETRIC_WEAK,
                BIOMETRIC_CONVENIENCE,
                DEVICE_CREDENTIAL,
                MANDATORY_BIOMETRICS,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface Types {}

        /**
         * Empty set with no authenticators specified.
         *
         * <p>This constant is intended for use by {@link android.provider.DeviceConfig} to adjust
         * the reported strength of a biometric sensor. It is not a valid parameter for any of the
         * public {@link android.hardware.biometrics} APIs.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(WRITE_DEVICE_CONFIG)
        int EMPTY_SET = 0x0000;

        /**
         * Placeholder for the theoretical strongest biometric security tier.
         * @hide
         */
        int BIOMETRIC_MAX_STRENGTH = 0x0001;

        /**
         * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
         * requirements for <strong>Class 3</strong> (formerly <strong>Strong</strong>), as defined
         * by the Android CDD.
         *
         * <p>This corresponds to {@link KeyProperties#AUTH_BIOMETRIC_STRONG} during key generation.
         *
         * @see android.security.keystore.KeyGenParameterSpec.Builder
         */
        int BIOMETRIC_STRONG = 0x000F;

        /**
         * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
         * requirements for <strong>Class 2</strong> (formerly <strong>Weak</strong>), as defined by
         * the Android CDD.
         *
         * <p>Note that this is a superset of {@link #BIOMETRIC_STRONG} and is defined such that
         * {@code BIOMETRIC_STRONG | BIOMETRIC_WEAK == BIOMETRIC_WEAK}.
         */
        int BIOMETRIC_WEAK = 0x00FF;

        /**
         * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
         * requirements for <strong>Class 1</strong> (formerly <strong>Convenience</strong>), as
         * defined by the Android CDD.
         *
         * <p>This constant is intended for use by {@link android.provider.DeviceConfig} to adjust
         * the reported strength of a biometric sensor. It is not a valid parameter for any of the
         * public {@link android.hardware.biometrics} APIs.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(WRITE_DEVICE_CONFIG)
        int BIOMETRIC_CONVENIENCE = 0x0FFF;

        /**
         * Placeholder for the theoretical weakest biometric security tier.
         * @hide
         */
        int BIOMETRIC_MIN_STRENGTH = 0x7FFF;

        /**
         * The non-biometric credential used to secure the device (i.e., PIN, pattern, or password).
         * This should typically only be used in combination with a biometric auth type, such as
         * {@link #BIOMETRIC_WEAK}.
         *
         * <p>This corresponds to {@link KeyProperties#AUTH_DEVICE_CREDENTIAL} during key
         * generation.
         *
         * @see android.security.keystore.KeyGenParameterSpec.Builder
         */
        int DEVICE_CREDENTIAL = 1 << 15;

        /**
         * The bit is used to request for mandatory biometrics.
         *
         * <p> The requirements to trigger mandatory biometrics are as follows:
         * 1. User must have enabled the toggle for mandatory biometrics is settings
         * 2. User must have enrollments for all {@link #BIOMETRIC_STRONG} sensors available
         * 3. The device must not be in a trusted location
         * </p>
         *
         * <p> If all the above conditions are satisfied, only {@link #BIOMETRIC_STRONG} sensors
         * will be eligible for authentication, and device credential fallback will be dropped.
         * @hide
         */
        int MANDATORY_BIOMETRICS = 1 << 16;

    }

    /**
     * @hide
     * returns a string representation of an authenticator type.
     */
    @NonNull public static String authenticatorToStr(@Authenticators.Types int authenticatorType) {
        switch(authenticatorType) {
            case Authenticators.BIOMETRIC_STRONG:
                return "BIOMETRIC_STRONG";
            case Authenticators.BIOMETRIC_WEAK:
                return "BIOMETRIC_WEAK";
            case Authenticators.BIOMETRIC_CONVENIENCE:
                return "BIOMETRIC_CONVENIENCE";
            case Authenticators.DEVICE_CREDENTIAL:
                return "DEVICE_CREDENTIAL";
            default:
                return "Unknown authenticator type: " + authenticatorType;
        }
    }

    /**
     * Provides localized strings for an application that uses {@link BiometricPrompt} to
     * authenticate the user.
     */
    public static class Strings {
        @NonNull private final Context mContext;
        @NonNull private final IAuthService mService;
        @Authenticators.Types int mAuthenticators;

        private Strings(@NonNull Context context, @NonNull IAuthService service,
                @Authenticators.Types int authenticators) {
            mContext = context;
            mService = service;
            mAuthenticators = authenticators;
        }

        /**
         * Provides a localized string that can be used as the label for a button that invokes
         * {@link BiometricPrompt}.
         *
         * <p>When possible, this method should use the given authenticator requirements to more
         * precisely specify the authentication type that will be used. For example, if
         * <strong>Class 3</strong> biometric authentication is requested on a device with a
         * <strong>Class 3</strong> fingerprint sensor and a <strong>Class 2</strong> face sensor,
         * the returned string should indicate that fingerprint authentication will be used.
         *
         * <p>This method should also try to specify which authentication method(s) will be used in
         * practice when multiple authenticators meet the given requirements. For example, if
         * biometric authentication is requested on a device with both face and fingerprint sensors
         * but the user has selected face as their preferred method, the returned string should
         * indicate that face authentication will be used.
         *
         * <p>This method may return {@code null} if none of the requested authenticator types are
         * available, but this should <em>not</em> be relied upon for checking the status of
         * authenticators. Instead, use {@link #canAuthenticate(int)}.
         *
         * @return The label for a button that invokes {@link BiometricPrompt} for authentication.
         */
        @RequiresPermission(USE_BIOMETRIC)
        @Nullable
        public CharSequence getButtonLabel() {
            final int userId = mContext.getUserId();
            final String opPackageName = mContext.getOpPackageName();
            try {
                return mService.getButtonLabel(userId, opPackageName, mAuthenticators);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Provides a localized string that can be shown while the user is authenticating with
         * {@link BiometricPrompt}.
         *
         * <p>When possible, this method should use the given authenticator requirements to more
         * precisely specify the authentication type that will be used. For example, if
         * <strong>Class 3</strong> biometric authentication is requested on a device with a
         * <strong>Class 3</strong> fingerprint sensor and a <strong>Class 2</strong> face sensor,
         * the returned string should indicate that fingerprint authentication will be used.
         *
         * <p>This method should also try to specify which authentication method(s) will be used in
         * practice when multiple authenticators meet the given requirements. For example, if
         * biometric authentication is requested on a device with both face and fingerprint sensors
         * but the user has selected face as their preferred method, the returned string should
         * indicate that face authentication will be used.
         *
         * <p>This method may return {@code null} if none of the requested authenticator types are
         * available, but this should <em>not</em> be relied upon for checking the status of
         * authenticators. Instead, use {@link #canAuthenticate(int)}.
         *
         * @return The label for a button that invokes {@link BiometricPrompt} for authentication.
         */
        @RequiresPermission(USE_BIOMETRIC)
        @Nullable
        public CharSequence getPromptMessage() {
            final int userId = mContext.getUserId();
            final String opPackageName = mContext.getOpPackageName();
            try {
                return mService.getPromptMessage(userId, opPackageName, mAuthenticators);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Provides a localized string that can be shown as the title for an app setting that
         * enables authentication with {@link BiometricPrompt}.
         *
         * <p>When possible, this method should use the given authenticator requirements to more
         * precisely specify the authentication type that will be used. For example, if
         * <strong>Class 3</strong> biometric authentication is requested on a device with a
         * <strong>Class 3</strong> fingerprint sensor and a <strong>Class 2</strong> face sensor,
         * the returned string should indicate that fingerprint authentication will be used.
         *
         * <p>This method should <em>not</em> try to specify which authentication method(s) will be
         * used in practice when multiple authenticators meet the given requirements. For example,
         * if biometric authentication is requested on a device with both face and fingerprint
         * sensors, the returned string should indicate that either face or fingerprint
         * authentication may be used, regardless of whether the user has enrolled or selected
         * either as their preferred method.
         *
         * <p>This method may return {@code null} if none of the requested authenticator types are
         * supported by the system, but this should <em>not</em> be relied upon for checking the
         * status of authenticators. Instead, use {@link #canAuthenticate(int)} or
         * {@link android.content.pm.PackageManager#hasSystemFeature(String)}.
         *
         * @return The label for a button that invokes {@link BiometricPrompt} for authentication.
         */
        @RequiresPermission(USE_BIOMETRIC)
        @Nullable
        public CharSequence getSettingName() {
            final int userId = mContext.getUserId();
            final String opPackageName = mContext.getOpPackageName();
            try {
                return mService.getSettingName(userId, opPackageName, mAuthenticators);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    @NonNull private final Context mContext;
    @NonNull private final IAuthService mService;

    /**
     * @hide
     * @param context
     * @param service
     */
    public BiometricManager(@NonNull Context context, @NonNull IAuthService service) {
        mContext = context;
        mService = service;
    }

    /**
     * @return A list of {@link SensorProperties}
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(TEST_BIOMETRIC)
    public List<SensorProperties> getSensorProperties() {
        try {
            final List<SensorPropertiesInternal> internalProperties =
                    mService.getSensorProperties(mContext.getOpPackageName());
            final List<SensorProperties> properties = new ArrayList<>();
            for (SensorPropertiesInternal internalProp : internalProperties) {
                properties.add(SensorProperties.from(internalProp));
            }
            return properties;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves a test session for BiometricManager/BiometricPrompt.
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(TEST_BIOMETRIC)
    public BiometricTestSession createTestSession(int sensorId) {
        try {
            return new BiometricTestSession(mContext, sensorId,
                    (context, sensorId1, callback) -> mService
                            .createTestSession(sensorId1, callback, context.getOpPackageName()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the package where BiometricPrompt's UI is implemented.
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(TEST_BIOMETRIC)
    public String getUiPackage() {
        try {
            return mService.getUiPackage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Determine if biometrics can be used. In other words, determine if
     * {@link BiometricPrompt} can be expected to be shown (hardware available, templates enrolled,
     * user-enabled). This is the equivalent of {@link #canAuthenticate(int)} with
     * {@link Authenticators#BIOMETRIC_WEAK}
     *
     * @return {@link #BIOMETRIC_ERROR_NONE_ENROLLED} if the user does not have any strong
     *     biometrics enrolled, or {@link #BIOMETRIC_ERROR_HW_UNAVAILABLE} if none are currently
     *     supported/enabled. Returns {@link #BIOMETRIC_SUCCESS} if a strong biometric can currently
     *     be used (enrolled and available).
     *
     * @deprecated See {@link #canAuthenticate(int)}.
     */
    @Deprecated
    @RequiresPermission(USE_BIOMETRIC)
    @BiometricError
    public int canAuthenticate() {
        @BiometricError final int result = canAuthenticate(mContext.getUserId(),
                Authenticators.BIOMETRIC_WEAK);

        FrameworkStatsLog.write(FrameworkStatsLog.AUTH_MANAGER_CAN_AUTHENTICATE_INVOKED,
                false /* isAllowedAuthenticatorsSet */, Authenticators.EMPTY_SET, result);
        FrameworkStatsLog.write(FrameworkStatsLog.AUTH_DEPRECATED_API_USED,
                AUTH_DEPRECATED_APIUSED__DEPRECATED_API__API_BIOMETRIC_MANAGER_CAN_AUTHENTICATE,
                mContext.getApplicationInfo().uid,
                mContext.getApplicationInfo().targetSdkVersion);

        return result;
    }

    /**
     * Determine if any of the provided authenticators can be used. In other words, determine if
     * {@link BiometricPrompt} can be expected to be shown (hardware available, templates enrolled,
     * user-enabled).
     *
     * For biometric authenticators, determine if the device can currently authenticate with at
     * least the requested strength. For example, invoking this API with
     * {@link Authenticators#BIOMETRIC_WEAK} on a device that currently only has
     * {@link Authenticators#BIOMETRIC_STRONG} enrolled will return {@link #BIOMETRIC_SUCCESS}.
     *
     * Invoking this API with {@link Authenticators#DEVICE_CREDENTIAL} can be used to determine
     * if the user has a PIN/Pattern/Password set up.
     *
     * @param authenticators bit field consisting of constants defined in {@link Authenticators}.
     *                       If multiple authenticators are queried, a logical OR will be applied.
     *                       For example, if {@link Authenticators#DEVICE_CREDENTIAL} |
     *                       {@link Authenticators#BIOMETRIC_STRONG} is queried and only
     *                       {@link Authenticators#DEVICE_CREDENTIAL} is set up, this API will
     *                       return {@link #BIOMETRIC_SUCCESS}
     *
     * @return {@link #BIOMETRIC_ERROR_NONE_ENROLLED} if the user does not have any of the
     *     requested authenticators enrolled, or {@link #BIOMETRIC_ERROR_HW_UNAVAILABLE} if none are
     *     currently supported/enabled. Returns {@link #BIOMETRIC_SUCCESS} if one of the requested
     *     authenticators can currently be used (enrolled and available).
     */
    @RequiresPermission(USE_BIOMETRIC)
    @BiometricError
    public int canAuthenticate(@Authenticators.Types int authenticators) {
        @BiometricError final int result = canAuthenticate(mContext.getUserId(), authenticators);

        FrameworkStatsLog.write(FrameworkStatsLog.AUTH_MANAGER_CAN_AUTHENTICATE_INVOKED,
                true /* isAllowedAuthenticatorsSet */, authenticators, result);

        return result;
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @BiometricError
    public int canAuthenticate(int userId, @Authenticators.Types int authenticators) {
        if (mService != null) {
            try {
                final String opPackageName = mContext.getOpPackageName();
                return mService.canAuthenticate(opPackageName, userId, authenticators);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "canAuthenticate(): Service not connected");
            return BIOMETRIC_ERROR_HW_UNAVAILABLE;
        }
    }

    /**
     * Produces an instance of the {@link Strings} class, which provides localized strings for an
     * application, given a set of allowed authenticator types.
     *
     * @param authenticators A bit field representing the types of {@link Authenticators} that may
     *                       be used for authentication.
     * @return A {@link Strings} collection for the given allowed authenticator types.
     */
    @RequiresPermission(USE_BIOMETRIC)
    @NonNull
    public Strings getStrings(@Authenticators.Types int authenticators) {
        return new Strings(mContext, mService, authenticators);
    }

    /**
     * @hide
     * @param userId
     * @return
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public boolean hasEnrolledBiometrics(int userId) {
        if (mService != null) {
            try {
                return mService.hasEnrolledBiometrics(userId, mContext.getOpPackageName());
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception in hasEnrolledBiometrics(): " + e);
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * Listens for changes to biometric eligibility on keyguard from user settings.
     * @param callback
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback) {
        if (mService != null) {
            try {
                mService.registerEnabledOnKeyguardCallback(callback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "registerEnabledOnKeyguardCallback(): Service not connected");
        }
    }

    /**
     * Registers listener for changes to biometric authentication state.
     * Only sends callbacks for events that occur after the callback has been registered.
     * @param listener Listener for changes to biometric authentication state
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void registerAuthenticationStateListener(AuthenticationStateListener listener) {
        if (mService != null) {
            try {
                mService.registerAuthenticationStateListener(listener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "registerAuthenticationStateListener(): Service not connected");
        }
    }

    /**
     * Unregisters listener for changes to biometric authentication state.
     * @param listener Listener for changes to biometric authentication state
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void unregisterAuthenticationStateListener(AuthenticationStateListener listener) {
        if (mService != null) {
            try {
                mService.unregisterAuthenticationStateListener(listener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "unregisterAuthenticationStateListener(): Service not connected");
        }
    }


    /**
     * Requests all {@link Authenticators.Types#BIOMETRIC_STRONG} sensors to have their
     * authenticatorId invalidated for the specified user. This happens when enrollments have been
     * added on devices with multiple biometric sensors.
     *
     * @param userId userId that the authenticatorId should be invalidated for
     * @param fromSensorId sensor that triggered the invalidation request
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void invalidateAuthenticatorIds(int userId, int fromSensorId,
            @NonNull IInvalidationCallback callback) {
        if (mService != null) {
            try {
                mService.invalidateAuthenticatorIds(userId, fromSensorId, callback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Get a list of AuthenticatorIDs for biometric authenticators which have 1) enrolled templates,
     * and 2) meet the requirements for integrating with Keystore. The AuthenticatorIDs are known
     * in Keystore land as SIDs, and are used during key generation.
     * @hide
     */
    public long[] getAuthenticatorIds() {
        return getAuthenticatorIds(UserHandle.myUserId());
    }

    /**
     * Get a list of AuthenticatorIDs for biometric authenticators which have 1) enrolled templates,
     * and 2) meet the requirements for integrating with Keystore. The AuthenticatorIDs are known
     * in Keystore land as SIDs, and are used during key generation.
     *
     * @param userId Android user ID for user to look up.
     *
     * @hide
     */
    public long[] getAuthenticatorIds(int userId) {
        if (mService != null) {
            try {
                return mService.getAuthenticatorIds(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "getAuthenticatorIds(): Service not connected");
            return new long[0];
        }
    }

    /**
     * Requests all other biometric sensors to resetLockout. Note that this is a "time bound"
     * See the {@link android.hardware.biometrics.fingerprint.ISession#resetLockout(int,
     * HardwareAuthToken)} and {@link android.hardware.biometrics.face.ISession#resetLockout(int,
     * HardwareAuthToken)} documentation for complete details.
     *
     * @param token A binder from the caller, for the service to linkToDeath
     * @param opPackageName Caller's package name
     * @param fromSensorId The originating sensor that just authenticated. Note that this MUST
     *                     be a sensor that meets {@link Authenticators#BIOMETRIC_STRONG} strength.
     *                     The strength will also be enforced on the BiometricService side.
     * @param userId The user that authentication succeeded for, and also the user that resetLockout
     *               should be applied to.
     * @param hardwareAuthToken A valid HAT generated upon successful biometric authentication. Note
     *                          that it is not necessary for the HAT to contain a challenge.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void resetLockoutTimeBound(IBinder token, String opPackageName, int fromSensorId,
            int userId, byte[] hardwareAuthToken) {
        if (mService != null) {
            try {
                mService.resetLockoutTimeBound(token, opPackageName, fromSensorId, userId,
                        hardwareAuthToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Notifies AuthService that keyguard has been dismissed for the given userId.
     *
     * @param userId
     * @param hardwareAuthToken
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void resetLockout(int userId, byte[] hardwareAuthToken) {
        if (mService != null) {
            try {
                mService.resetLockout(userId, hardwareAuthToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

    }

    /**
     * Gets the last time the user successfully authenticated using one of the given authenticators.
     * The returned value is time in
     * {@link android.os.SystemClock#elapsedRealtime SystemClock.elapsedRealtime()} (time since
     * boot, including sleep).
     * <p>
     * {@link BiometricManager#BIOMETRIC_NO_AUTHENTICATION} is returned in the case where there
     * has been no successful authentication using any of the given authenticators since boot.
     * <p>
     * Currently, only {@link Authenticators#DEVICE_CREDENTIAL} and
     * {@link Authenticators#BIOMETRIC_STRONG} are accepted. {@link IllegalArgumentException} will
     * be thrown if {@code authenticators} contains other authenticator types.
     * <p>
     * Note that this may return successful authentication times even if the device is currently
     * locked. You may use {@link KeyguardManager#isDeviceLocked()} to determine if the device
     * is unlocked or not. Additionally, this method may return valid times for an authentication
     * method that is no longer available. For instance, if the user unlocked the device with a
     * {@link Authenticators#BIOMETRIC_STRONG} authenticator but then deleted that authenticator
     * (e.g., fingerprint data), this method will still return the time of that unlock for
     * {@link Authenticators#BIOMETRIC_STRONG} if it is the most recent successful event. The caveat
     * is that {@link BiometricManager#BIOMETRIC_NO_AUTHENTICATION} will be returned if the device
     * no longer has a secure lock screen at all, even if there were successful authentications
     * performed before the lock screen was made insecure.
     *
     * @param authenticators bit field consisting of constants defined in {@link Authenticators}.
     * @return the time of last authentication or
     * {@link BiometricManager#BIOMETRIC_NO_AUTHENTICATION}
     * @throws IllegalArgumentException if {@code authenticators} contains values other than
     * {@link Authenticators#DEVICE_CREDENTIAL} and {@link Authenticators#BIOMETRIC_STRONG} or is
     * 0.
     */
    @RequiresPermission(USE_BIOMETRIC)
    @ElapsedRealtimeLong
    @FlaggedApi(Flags.FLAG_LAST_AUTHENTICATION_TIME)
    public long getLastAuthenticationTime(
            @BiometricManager.Authenticators.Types int authenticators) {
        if (authenticators == 0
                || (GET_LAST_AUTH_TIME_ALLOWED_AUTHENTICATORS & authenticators) != authenticators) {
            throw new IllegalArgumentException(
                    "Only BIOMETRIC_STRONG and DEVICE_CREDENTIAL authenticators may be used.");
        }

        if (mService != null) {
            try {
                return mService.getLastAuthenticationTime(UserHandle.myUserId(), authenticators);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            return BIOMETRIC_NO_AUTHENTICATION;
        }
    }
}

