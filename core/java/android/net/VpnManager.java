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
 * VpnService} API for ease-of-development and reduced maintainance burden. This also give the user
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
     * @throws SecurityException exception if user or device settings prevent this VPN from being
     *     setup, or if user consent has not been granted
     */
    public void startProvisionedVpnProfile() {
        try {
            mService.startVpnProfile(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
}
