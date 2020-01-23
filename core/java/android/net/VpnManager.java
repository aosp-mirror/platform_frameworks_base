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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;

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
    @NonNull private final Context mContext;
    @NonNull private final IConnectivityManager mService;

    /**
     * Create an instance of the VpnManger with the given context.
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
     * @param profile the PlatformVpnProfile provided by this package. Will override any previous
     *     PlatformVpnProfile stored for this package.
     * @return an intent to request user consent if needed (null otherwise).
     */
    @Nullable
    public Intent provisionVpnProfile(@NonNull PlatformVpnProfile profile) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** Delete the VPN profile configuration that was provisioned by the calling app */
    public void deleteProvisionedVpnProfile() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Request the startup of a previously provisioned VPN.
     *
     * @throws SecurityException exception if user or device settings prevent this VPN from being
     *     setup, or if user consent has not been granted
     */
    public void startProvisionedVpnProfile() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** Tear down the VPN provided by the calling app (if any) */
    public void stopProvisionedVpnProfile() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
