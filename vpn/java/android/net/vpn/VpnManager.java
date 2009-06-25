/*
 * Copyright (C) 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.util.Log;

/**
 * The class provides interface to manage all VPN-related tasks, including:
 * <ul>
 * <li>The list of supported VPN types.
 * <li>API's to start/stop the service of a particular type.
 * <li>API's to start the settings activity.
 * <li>API's to create a profile.
 * <li>API's to register/unregister a connectivity receiver and the keys to
 *      access the fields in a connectivity broadcast event.
 * </ul>
 * {@hide}
 */
public class VpnManager {
    // Action for broadcasting a connectivity state.
    private static final String ACTION_VPN_CONNECTIVITY = "vpn.connectivity";
    /** Key to the profile name of a connectivity broadcast event. */
    public static final String BROADCAST_PROFILE_NAME = "profile_name";
    /** Key to the connectivity state of a connectivity broadcast event. */
    public static final String BROADCAST_CONNECTION_STATE = "connection_state";

    public static final String PROFILES_PATH = "/data/misc/vpn/profiles";

    private static final String PACKAGE_PREFIX =
            VpnManager.class.getPackage().getName() + ".";

    // Action to start VPN service
    private static final String ACTION_VPN_SERVICE = PACKAGE_PREFIX + "SERVICE";

    // Action to start VPN settings
    private static final String ACTION_VPN_SETTINGS = PACKAGE_PREFIX + "SETTINGS";

    private static final String TAG = VpnManager.class.getSimpleName();

    /**
     * Returns all supported VPN types.
     */
    public static VpnType[] getSupportedVpnTypes() {
        return VpnType.values();
    }

    private Context mContext;

    /**
     * Creates a manager object with the specified context.
     */
    public VpnManager(Context c) {
        mContext = c;
    }

    /**
     * Creates a VPN profile of the specified type.
     *
     * @param type the VPN type
     * @return the profile object
     */
    public VpnProfile createVpnProfile(VpnType type) {
        return createVpnProfile(type, false);
    }

    /**
     * Creates a VPN profile of the specified type.
     *
     * @param type the VPN type
     * @param customized true if the profile is custom made
     * @return the profile object
     */
    public VpnProfile createVpnProfile(VpnType type, boolean customized) {
        try {
            VpnProfile p = (VpnProfile) type.getProfileClass().newInstance();
            p.setCustomized(customized);
            return p;
        } catch (InstantiationException e) {
            return null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Starts the VPN service to establish VPN connection.
     */
    public void startVpnService() {
        mContext.startService(new Intent(ACTION_VPN_SERVICE));
    }

    /**
     * Stops the VPN service.
     */
    public void stopVpnService() {
        mContext.stopService(new Intent(ACTION_VPN_SERVICE));
    }

    /**
     * Binds the specified ServiceConnection with the VPN service.
     */
    public boolean bindVpnService(ServiceConnection c) {
        if (!mContext.bindService(new Intent(ACTION_VPN_SERVICE), c, 0)) {
            Log.w(TAG, "failed to connect to VPN service");
            return false;
        } else {
            Log.d(TAG, "succeeded to connect to VPN service");
            return true;
        }
    }

    /** Broadcasts the connectivity state of the specified profile. */
    public void broadcastConnectivity(String profileName, VpnState s) {
        Intent intent = new Intent(ACTION_VPN_CONNECTIVITY);
        intent.putExtra(BROADCAST_PROFILE_NAME, profileName);
        intent.putExtra(BROADCAST_CONNECTION_STATE, s);
        mContext.sendBroadcast(intent);
    }

    public void registerConnectivityReceiver(BroadcastReceiver r) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(VpnManager.ACTION_VPN_CONNECTIVITY);
        mContext.registerReceiver(r, filter);
    }

    public void unregisterConnectivityReceiver(BroadcastReceiver r) {
        mContext.unregisterReceiver(r);
    }

    /** Starts the VPN settings activity. */
    public void startSettingsActivity() {
        Intent intent = new Intent(ACTION_VPN_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    /** Creates an intent to start the VPN settings activity. */
    public Intent createSettingsActivityIntent() {
        Intent intent = new Intent(ACTION_VPN_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
