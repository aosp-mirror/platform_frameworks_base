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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.RemoteException;

import com.android.internal.net.VpnProfile;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.GeneralSecurityException;

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
    public static final int TYPE_VPN_NONE = -1;
    /** VPN service type code @hide */
    public static final int TYPE_VPN_SERVICE = 1;
    /** Platform VPN type code @hide */
    public static final int TYPE_VPN_PLATFORM = 2;

    /** @hide */
    @IntDef(value = {TYPE_VPN_NONE, TYPE_VPN_SERVICE, TYPE_VPN_PLATFORM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface VpnType {}

    @NonNull private final Context mContext;
    @NonNull private final IConnectivityManager mService;

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
    public VpnManager(@NonNull Context ctx, @NonNull IConnectivityManager service) {
        mContext = checkNotNull(ctx, "missing Context");
        mService = checkNotNull(service, "missing IConnectivityManager");
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
}
