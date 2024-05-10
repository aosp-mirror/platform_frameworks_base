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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.RemoteException;

import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * This class provides an interface for apps to manage platform VPN profiles
 *
 * <p>Apps can use this API to provide profiles with which the platform can set up a VPN without
 * further app intermediation. When a VPN profile is present and the app is selected as an always-on
 * VPN, the platform will directly trigger the negotiation of the VPN without starting or waking the
 * app (unlike VpnService).
 *
 * <p>VPN apps using supported protocols should preferentially use this API over the {@link
 * VpnService} API for ease-of-development and reduced maintenance burden. This also give the user
 * the guarantee that VPN network traffic is not subjected to on-device packet interception.
 *
 * @see Ikev2VpnProfile
 */
public class VpnManager {
    /** Type representing a lack of VPN @hide */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int TYPE_VPN_NONE = -1;

    /**
     * A VPN created by an app using the {@link VpnService} API.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int TYPE_VPN_SERVICE = 1;

    /**
     * A VPN created using a {@link VpnManager} API such as {@link #startProvisionedVpnProfile}.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int TYPE_VPN_PLATFORM = 2;

    /**
     * An IPsec VPN created by the built-in LegacyVpnRunner.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int TYPE_VPN_LEGACY = 3;

    /**
     * An VPN created by OEM code through other means than {@link VpnService} or {@link VpnManager}.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int TYPE_VPN_OEM = 4;

    /**
     * Channel for VPN notifications.
     * @hide
     */
    public static final String NOTIFICATION_CHANNEL_VPN = "VPN";

    /**
     * Action sent in {@link android.content.Intent}s to VpnManager clients when an event occurred.
     *
     * <p>If the provisioning application declares a service handling this intent action, but is not
     * already running, it will be started. Upon starting, the application is granted a short grace
     * period to run in the background even while the device is idle to handle any potential
     * failures. Applications requiring long-running actions triggered by one of these events should
     * declare a foreground service to prevent being killed once the grace period expires.
     *
     * This action will have a category of either {@link #CATEGORY_EVENT_IKE_ERROR},
     * {@link #CATEGORY_EVENT_NETWORK_ERROR}, or {@link #CATEGORY_EVENT_DEACTIVATED_BY_USER},
     * that the app can use to filter events it's interested in reacting to.
     *
     * It will also contain the following extras :
     * <ul>
     *   <li>{@link #EXTRA_SESSION_KEY}, a {@code String} for the session key, as returned by
     *       {@link #startProvisionedVpnProfileSession}.
     *   <li>{@link #EXTRA_TIMESTAMP_MILLIS}, a long for the timestamp at which the error occurred,
     *       in milliseconds since the epoch, as returned by
     *       {@link java.lang.System#currentTimeMillis}.
     *   <li>{@link #EXTRA_UNDERLYING_NETWORK}, a {@link Network} containing the underlying
     *       network at the time the error occurred, or null if none. Note that this network
     *       may have disconnected already.
     *   <li>{@link #EXTRA_UNDERLYING_NETWORK_CAPABILITIES}, a {@link NetworkCapabilities} for
     *       the underlying network at the time the error occurred.
     *   <li>{@link #EXTRA_UNDERLYING_LINK_PROPERTIES}, a {@link LinkProperties} for the underlying
     *       network at the time the error occurred.
     * </ul>
     * When this event is an error, either {@link #CATEGORY_EVENT_IKE_ERROR} or
     * {@link #CATEGORY_EVENT_NETWORK_ERROR}, the following extras will be populated :
     * <ul>
     *   <li>{@link #EXTRA_ERROR_CLASS}, an {@code int} for the class of error, either
     *       {@link #ERROR_CLASS_RECOVERABLE} or {@link #ERROR_CLASS_NOT_RECOVERABLE}.
     *   <li>{@link #EXTRA_ERROR_CODE}, an {@code int} error code specific to the error. See
     *       {@link #EXTRA_ERROR_CODE} for details.
     * </ul>
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_VPN_MANAGER_EVENT = "android.net.action.VPN_MANAGER_EVENT";

    /**
     * An IKE protocol error occurred.
     *
     * Codes (in {@link #EXTRA_ERROR_CODE}) are the codes from
     * {@link android.net.ipsec.ike.exceptions.IkeProtocolException}, as defined by IANA in
     * "IKEv2 Notify Message Types - Error Types".
     */
    @SdkConstant(SdkConstant.SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_EVENT_IKE_ERROR = "android.net.category.EVENT_IKE_ERROR";

    /**
     * A network error occurred.
     *
     * Error codes (in {@link #EXTRA_ERROR_CODE}) are ERROR_CODE_NETWORK_*.
     */
    @SdkConstant(SdkConstant.SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_EVENT_NETWORK_ERROR =
            "android.net.category.EVENT_NETWORK_ERROR";

    /**
     * The user deactivated the VPN.
     *
     * This can happen either when the user turns the VPN off explicitly, or when they select
     * a different VPN provider.
     */
    @SdkConstant(SdkConstant.SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_EVENT_DEACTIVATED_BY_USER =
            "android.net.category.EVENT_DEACTIVATED_BY_USER";

    /**
     * The always-on state of this VPN was changed
     *
     * <p>This may be the result of a user changing VPN settings, or a Device Policy Manager app
     * having changed the VPN policy.
     */
    @SdkConstant(SdkConstant.SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED =
            "android.net.category.EVENT_ALWAYS_ON_STATE_CHANGED";

    /**
     * The VpnProfileState at the time that this event occurred.
     *
     * <p>This extra may be null if the VPN was revoked by the user, or the profile was deleted.
     */
    public static final String EXTRA_VPN_PROFILE_STATE = "android.net.extra.VPN_PROFILE_STATE";

    /**
     * The key of the session that experienced this event, as a {@code String}.
     *
     * This is the same key that was returned by {@link #startProvisionedVpnProfileSession}.
     */
    public static final String EXTRA_SESSION_KEY = "android.net.extra.SESSION_KEY";

    /**
     * The network that was underlying the VPN when the event occurred, as a {@link Network}.
     *
     * <p>This extra will be null if there was no underlying network at the time of the event, or
     *    the underlying network has no bearing on the event, as in the case of:
     * <ul>
     *   <li>CATEGORY_EVENT_DEACTIVATED_BY_USER
     *   <li>CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED
     * </ul>
     */
    public static final String EXTRA_UNDERLYING_NETWORK = "android.net.extra.UNDERLYING_NETWORK";

    /**
     * The {@link NetworkCapabilities} of the underlying network when the event occurred.
     *
     * <p>This extra will be null if there was no underlying network at the time of the event, or
     *    the underlying network has no bearing on the event, as in the case of:
     * <ul>
     *   <li>CATEGORY_EVENT_DEACTIVATED_BY_USER
     *   <li>CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED
     * </ul>
     */
    public static final String EXTRA_UNDERLYING_NETWORK_CAPABILITIES =
            "android.net.extra.UNDERLYING_NETWORK_CAPABILITIES";

    /**
     * The {@link LinkProperties} of the underlying network when the event occurred.
     *
     * <p>This extra will be null if there was no underlying network at the time of the event, or
     *    the underlying network has no bearing on the event, as in the case of:
     * <ul>
     *   <li>CATEGORY_EVENT_DEACTIVATED_BY_USER
     *   <li>CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED
     * </ul>
     */
    public static final String EXTRA_UNDERLYING_LINK_PROPERTIES =
            "android.net.extra.UNDERLYING_LINK_PROPERTIES";

    /**
     * A {@code long} timestamp containing the time at which the event occurred.
     *
     * This is a number of milliseconds since the epoch, suitable to be compared with
     * {@link java.lang.System#currentTimeMillis}.
     */
    public static final String EXTRA_TIMESTAMP_MILLIS = "android.net.extra.TIMESTAMP_MILLIS";

    /**
     * Extra for the error class, as an {@code int}.
     *
     * This is always either {@link #ERROR_CLASS_NOT_RECOVERABLE} or
     * {@link #ERROR_CLASS_RECOVERABLE}. This extra is only populated for error categories.
     */
    public static final String EXTRA_ERROR_CLASS = "android.net.extra.ERROR_CLASS";

    /**
     * Extra for an error code, as an {@code int}.
     *
     * <ul>
     *   <li>For {@link #CATEGORY_EVENT_NETWORK_ERROR}, this is one of the
     *       {@code ERROR_CODE_NETWORK_*} constants.
     *   <li>For {@link #CATEGORY_EVENT_IKE_ERROR}, this is one of values defined in
     *       {@link android.net.ipsec.ike.exceptions.IkeProtocolException}.ERROR_TYPE_*.
     * </ul>
     * For non-error categories, this extra is not populated.
     */
    public static final String EXTRA_ERROR_CODE = "android.net.extra.ERROR_CODE";

    /**
     * {@link #EXTRA_ERROR_CLASS} coding for a non-recoverable error.
     *
     * This error is fatal, e.g. configuration error. The stack will not retry connection.
     */
    public static final int ERROR_CLASS_NOT_RECOVERABLE = 1;

    /**
     * {@link #EXTRA_ERROR_CLASS} coding for a recoverable error.
     *
     * The stack experienced an error but will retry with exponential backoff, e.g. network timeout.
     */
    public static final int ERROR_CLASS_RECOVERABLE = 2;

    /**
     * An {@link #EXTRA_ERROR_CODE} for {@link #CATEGORY_EVENT_NETWORK_ERROR} to indicate that the
     * network host isn't known.
     *
     * This happens when domain name resolution could not resolve an IP address for the
     * specified host. {@see java.net.UnknownHostException}
     */
    public static final int ERROR_CODE_NETWORK_UNKNOWN_HOST = 0;

    /**
     * An {@link #EXTRA_ERROR_CODE} for {@link #CATEGORY_EVENT_NETWORK_ERROR} indicating a timeout.
     *
     * For Ikev2 VPNs, this happens typically after a retransmission failure.
     * {@see android.net.ipsec.ike.exceptions.IkeTimeoutException}
     */
    public static final int ERROR_CODE_NETWORK_PROTOCOL_TIMEOUT = 1;

    /**
     * An {@link #EXTRA_ERROR_CODE} for {@link #CATEGORY_EVENT_NETWORK_ERROR} indicating that
     * network connectivity was lost.
     *
     * The most common reason for this error is that the underlying network was disconnected,
     * {@see android.net.ipsec.ike.exceptions.IkeNetworkLostException}.
     */
    public static final int ERROR_CODE_NETWORK_LOST = 2;

    /**
     * An {@link #EXTRA_ERROR_CODE} for {@link #CATEGORY_EVENT_NETWORK_ERROR} indicating an
     * input/output error.
     *
     * This code happens when reading or writing to sockets on the underlying networks was
     * terminated by an I/O error. {@see IOException}.
     */
    public static final int ERROR_CODE_NETWORK_IO = 3;

    /** @hide */
    @IntDef(value = {TYPE_VPN_NONE, TYPE_VPN_SERVICE, TYPE_VPN_PLATFORM, TYPE_VPN_LEGACY,
            TYPE_VPN_OEM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface VpnType {}

    @NonNull private final Context mContext;
    @NonNull private final IVpnManager mService;

    private static Intent getIntentForConfirmation() {
        final Intent intent = new Intent();
        final ComponentName componentName = ComponentName.unflattenFromString(
                Resources.getSystem().getString(
                        com.android.internal.R.string.config_platformVpnConfirmDialogComponent));
        intent.setComponent(componentName);
        return intent;
    }

    /**
     * Create an instance of the VpnManager with the given context.
     *
     * <p>Internal only. Applications are expected to obtain an instance of the VpnManager via the
     * {@link Context.getSystemService()} method call.
     *
     * @hide
     */
    public VpnManager(@NonNull Context ctx, @NonNull IVpnManager service) {
        mContext = checkNotNull(ctx, "missing Context");
        mService = checkNotNull(service, "missing IVpnManager");
    }

    /**
     * Install a VpnProfile configuration keyed on the calling app's package name.
     *
     * <p>This method returns {@code null} if user consent has already been granted, or an {@link
     * Intent} to a system activity. If an intent is returned, the application should launch the
     * activity using {@link Activity#startActivityForResult} to request user consent. The activity
     * may pop up a dialog to require user action, and the result will come back via its {@link
     * Activity#onActivityResult}. If the result is {@link Activity#RESULT_OK}, the user has
     * consented, and the VPN profile can be started.
     *
     * @param profile the VpnProfile provided by this package. Will override any previous VpnProfile
     *     stored for this package.
     * @return an Intent requesting user consent to start the VPN, or null if consent is not
     *     required based on privileges or previous user consent.
     */
    @Nullable
    public Intent provisionVpnProfile(@NonNull PlatformVpnProfile profile) {
        final VpnProfile internalProfile;

        try {
            internalProfile = profile.toVpnProfile();
        } catch (GeneralSecurityException | IOException e) {
            // Conversion to VpnProfile failed; this is an invalid profile. Both of these exceptions
            // indicate a failure to convert a PrivateKey or X509Certificate to a Base64 encoded
            // string as required by the VpnProfile.
            throw new IllegalArgumentException("Failed to serialize PlatformVpnProfile", e);
        }

        try {
            // Profile can never be null; it either gets set, or an exception is thrown.
            if (mService.provisionVpnProfile(internalProfile, mContext.getOpPackageName())) {
                return null;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return getIntentForConfirmation();
    }

    /**
     * Delete the VPN profile configuration that was provisioned by the calling app
     *
     * @throws SecurityException if this would violate user settings
     */
    public void deleteProvisionedVpnProfile() {
        try {
            mService.deleteVpnProfile(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request the startup of a previously provisioned VPN.
     *
     * @return A unique key corresponding to this session.
     * @throws SecurityException exception if user or device settings prevent this VPN from being
     *         setup, or if user consent has not been granted
     */
    @NonNull
    public String startProvisionedVpnProfileSession() {
        try {
            return mService.startVpnProfile(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request the startup of a previously provisioned VPN.
     *
     * @throws SecurityException exception if user or device settings prevent this VPN from being
     *         setup, or if user consent has not been granted
     * @deprecated This method is replaced by startProvisionedVpnProfileSession which returns a
     *             session key for the caller to diagnose the errors.
     */
    @Deprecated
    public void startProvisionedVpnProfile() {
        startProvisionedVpnProfileSession();
    }

    /** Tear down the VPN provided by the calling app (if any) */
    public void stopProvisionedVpnProfile() {
        try {
            mService.stopVpnProfile(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the VPN configuration for the given user ID.
     * @hide
     */
    @Nullable
    public VpnConfig getVpnConfig(@UserIdInt int userId) {
        try {
            return mService.getVpnConfig(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve the VpnProfileState for the profile provisioned by the calling package.
     *
     * @return the VpnProfileState with current information, or null if there was no profile
     *         provisioned and started by the calling package.
     */
    @Nullable
    public VpnProfileState getProvisionedVpnProfileState() {
        try {
            return mService.getProvisionedVpnProfileState(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets all VPN settings back to factory defaults.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void factoryReset() {
        try {
            mService.factoryReset();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Prepare for a VPN application.
     * VPN permissions are checked in the {@link Vpn} class. If the caller is not {@code userId},
     * {@link android.Manifest.permission.INTERACT_ACROSS_USERS_FULL} permission is required.
     *
     * @param oldPackage Package name of the application which currently controls VPN, which will
     *                   be replaced. If there is no such application, this should should either be
     *                   {@code null} or {@link VpnConfig.LEGACY_VPN}.
     * @param newPackage Package name of the application which should gain control of VPN, or
     *                   {@code null} to disable.
     * @param userId User for whom to prepare the new VPN.
     *
     * @hide
     */
    public boolean prepareVpn(@Nullable String oldPackage, @Nullable String newPackage,
            int userId) {
        try {
            return mService.prepareVpn(oldPackage, newPackage, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether the VPN package has the ability to launch VPNs without user intervention. This
     * method is used by system-privileged apps. VPN permissions are checked in the {@link Vpn}
     * class. If the caller is not {@code userId}, {@link
     * android.Manifest.permission.INTERACT_ACROSS_USERS_FULL} permission is required.
     *
     * @param packageName The package for which authorization state should change.
     * @param userId User for whom {@code packageName} is installed.
     * @param vpnType The {@link VpnManager.VpnType} constant representing what class of VPN
     *     permissions should be granted. When unauthorizing an app, {@link
     *     VpnManager.TYPE_VPN_NONE} should be used.
     * @hide
     */
    public void setVpnPackageAuthorization(
            String packageName, int userId, @VpnManager.VpnType int vpnType) {
        try {
            mService.setVpnPackageAuthorization(packageName, userId, vpnType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if a VPN app supports always-on mode.
     *
     * In order to support the always-on feature, an app has to
     * <ul>
     *     <li>target {@link VERSION_CODES#N API 24} or above, and
     *     <li>not opt out through the {@link VpnService#SERVICE_META_DATA_SUPPORTS_ALWAYS_ON}
     *         meta-data field.
     * </ul>
     *
     * @param userId The identifier of the user for whom the VPN app is installed.
     * @param vpnPackage The canonical package name of the VPN app.
     * @return {@code true} if and only if the VPN app exists and supports always-on mode.
     * @hide
     */
    public boolean isAlwaysOnVpnPackageSupportedForUser(int userId, @Nullable String vpnPackage) {
        try {
            return mService.isAlwaysOnVpnPackageSupported(userId, vpnPackage);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Configures an always-on VPN connection through a specific application.
     * This connection is automatically granted and persisted after a reboot.
     *
     * <p>The designated package should declare a {@link VpnService} in its
     *    manifest guarded by {@link android.Manifest.permission.BIND_VPN_SERVICE},
     *    otherwise the call will fail.
     *
     * @param userId The identifier of the user to set an always-on VPN for.
     * @param vpnPackage The package name for an installed VPN app on the device, or {@code null}
     *                   to remove an existing always-on VPN configuration.
     * @param lockdownEnabled {@code true} to disallow networking when the VPN is not connected or
     *        {@code false} otherwise.
     * @param lockdownAllowlist The list of packages that are allowed to access network directly
     *         when VPN is in lockdown mode but is not running. Non-existent packages are ignored so
     *         this method must be called when a package that should be allowed is installed or
     *         uninstalled.
     * @return {@code true} if the package is set as always-on VPN controller;
     *         {@code false} otherwise.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_ALWAYS_ON_VPN)
    public boolean setAlwaysOnVpnPackageForUser(int userId, @Nullable String vpnPackage,
            boolean lockdownEnabled, @Nullable List<String> lockdownAllowlist) {
        try {
            return mService.setAlwaysOnVpnPackage(
                    userId, vpnPackage, lockdownEnabled, lockdownAllowlist);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the package name of the currently set always-on VPN application.
     * If there is no always-on VPN set, or the VPN is provided by the system instead
     * of by an app, {@code null} will be returned.
     *
     * @return Package name of VPN controller responsible for always-on VPN,
     *         or {@code null} if none is set.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_ALWAYS_ON_VPN)
    public String getAlwaysOnVpnPackageForUser(int userId) {
        try {
            return mService.getAlwaysOnVpnPackage(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return whether always-on VPN is in lockdown mode.
     *
     * @hide
     **/
    @RequiresPermission(android.Manifest.permission.CONTROL_ALWAYS_ON_VPN)
    public boolean isVpnLockdownEnabled(int userId) {
        try {
            return mService.isVpnLockdownEnabled(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the application exclusion list for the specified VPN profile.
     *
     * <p>If an app in the set of excluded apps is not installed for the given user, it will be
     * skipped in the list of app exclusions. If apps are installed or removed, any active VPN will
     * have its UID set updated automatically. If the caller is not {@code userId},
     * {@link android.Manifest.permission.INTERACT_ACROSS_USERS_FULL} permission is required.
     *
     * <p>This will ONLY affect VpnManager profiles. As such, the NETWORK_SETTINGS provider MUST NOT
     * allow configuration of these options if the application has not provided a VPN profile.
     *
     * @param userId the identifier of the user to set app exclusion list
     * @param vpnPackage The package name for an installed VPN app on the device
     * @param excludedApps the app exclusion list
     * @throws IllegalStateException exception if vpn for the @code userId} is not ready yet.
     *
     * @return whether setting the list is successful or not
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    public boolean setAppExclusionList(int userId, @NonNull String vpnPackage,
            @NonNull List<String> excludedApps) {
        try {
            return mService.setAppExclusionList(userId, vpnPackage, excludedApps);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the application exclusion list for the specified VPN profile. If the caller is not
     * {@code userId}, {@link android.Manifest.permission.INTERACT_ACROSS_USERS_FULL} permission
     * is required.
     *
     * @param userId the identifier of the user to set app exclusion list
     * @param vpnPackage The package name for an installed VPN app on the device
     * @return the list of packages for the specified VPN profile or null if no corresponding VPN
     *         profile configured.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    @Nullable
    public List<String> getAppExclusionList(int userId, @NonNull String vpnPackage) {
        try {
            return mService.getAppExclusionList(userId, vpnPackage);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return the list of packages that are allowed to access network when always-on VPN is in
     * lockdown mode but not connected. Returns {@code null} when VPN lockdown is not active.
     *
     * @hide
     **/
    @RequiresPermission(android.Manifest.permission.CONTROL_ALWAYS_ON_VPN)
    public List<String> getVpnLockdownAllowlist(int userId) {
        try {
            return mService.getVpnLockdownAllowlist(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the legacy VPN information for the specified user ID.
     * @hide
     */
    public LegacyVpnInfo getLegacyVpnInfo(@UserIdInt int userId) {
        try {
            return mService.getLegacyVpnInfo(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Starts a legacy VPN.
     *
     * Legacy VPN is deprecated starting from Android S. So this API shouldn't be called if the
     * initial SDK version of device is Android S+. Otherwise, UnsupportedOperationException will be
     * thrown.
     * @hide
     */
    public void startLegacyVpn(VpnProfile profile) {
        try {
            mService.startLegacyVpn(profile);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Informs the service that legacy lockdown VPN state should be updated (e.g., if its keystore
     * entry has been updated). If the LockdownVpn mechanism is enabled, updates the vpn
     * with a reload of its profile.
     *
     * <p>This method can only be called by the system UID
     * @return a boolean indicating success
     *
     * @hide
     */
    public boolean updateLockdownVpn() {
        try {
            return mService.updateLockdownVpn();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the vpn profile owned by the calling uid with the given name from the vpn database.
     *
     * <p>Note this method should not be used for platform VPN profiles. </p>
     *
     * @param name The name of the profile to retrieve.
     * @return the unstructured blob for the matching vpn profile.
     * Returns null if no profile with a matching name was found.
     * @hide
     */
    @Nullable
    public byte[] getFromVpnProfileStore(@NonNull String name) {
        try {
            return mService.getFromVpnProfileStore(name);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Put the given vpn profile owned by the calling uid with the given name into the vpn database.
     * Existing profiles with the same name will be replaced.
     *
     * <p>Note this method should not be used for platform VPN profiles.
     * To update a platform VPN, use provisionVpnProfile() instead. </p>
     *
     * @param name The name of the profile to put.
     * @param blob The profile.
     * @return true if the profile was successfully added. False otherwise.
     * @hide
     */
    public boolean putIntoVpnProfileStore(@NonNull String name, @NonNull byte[] blob) {
        try {
            return mService.putIntoVpnProfileStore(name, blob);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes the vpn profile owned by the calling uid with the given name from the vpn database.
     *
     * <p>Note this method should not be used for platform VPN profiles.
     * To remove a platform VPN, use deleteVpnProfile() instead.</p>
     *
     * @param name The name of the profile to be removed.
     * @return true if a profile was removed. False if no profile with a matching name was found.
     * @hide
     */
    public boolean removeFromVpnProfileStore(@NonNull String name) {
        try {
            return mService.removeFromVpnProfileStore(name);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of the name suffixes of the vpn profiles owned by the calling uid in the vpn
     * database matching the given prefix, sorted in ascending order.
     *
     * <p>Note this method should not be used for platform VPN profiles. </p>
     *
     * @param prefix The prefix to match.
     * @return an array of strings representing the name suffixes stored in the profile database
     * matching the given prefix. The return value may be empty but never null.
     * @hide
     */
    @NonNull
    public String[] listFromVpnProfileStore(@NonNull String prefix) {
        try {
            return mService.listFromVpnProfileStore(prefix);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
