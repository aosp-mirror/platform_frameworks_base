/*
 * Copyright (C) 2007, The Android Open Source Project
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

    /** Action to start the activity of installing a new profile. */
    public static final String ACTION_VPN_INSTALL_PROFILE =
            PACKAGE_PREFIX + "INSTALL_PROFILE";
    /**
     * Key to the installation path in the intent of installing a new profile.
     */
    public static final String KEY_INSTALLATION_PATH = "install_path";
    public static final String DEFAULT_INSTALLATION_PATH =
            "/data/local/tmp/vpn";

    // Action to start VPN installation monitor service
    private static final String SERVICE_VPN_INSTALL_MONITOR =
            PACKAGE_PREFIX + "INSTALLATION_MONITOR";

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

    private String getServiceActionName(VpnType type) {
        return PACKAGE_PREFIX + type.getServiceName();
    }

    /**
     * Starts the VPN service of the specified type.
     */
    public boolean startService(VpnType type) {
        String serviceAction = getServiceActionName(type);
        if (serviceAction != null) {
            Log.i(TAG, "start service: " + serviceAction);
            mContext.startService(new Intent(serviceAction));
            return true;
        } else {
            Log.w(TAG, "unknown vpn type to start service for: " + type);
            return false;
        }
    }

    /**
     * Stops the VPN service of the specified type.
     */
    public void stopService(VpnType type) {
        String serviceAction = getServiceActionName(type);
        if (serviceAction != null) {
            Log.i(TAG, "stop service for: " + type);
            mContext.stopService(new Intent(serviceAction));
        } else {
            Log.w(TAG, "unknown vpn type to stop service for: " + type);
        }
    }

    /**
     * Binds the specified ServiceConnection with the VPN service of the
     * specified type.
     */
    public boolean bindService(VpnType type, ServiceConnection c) {
        String serviceAction = getServiceActionName(type);
        if (serviceAction == null) {
            Log.w(TAG, "unknown vpn type to bind service for: " + type);
            return false;
        }
        if (!mContext.bindService(new Intent(serviceAction), c, 0)) {
            Log.w(TAG, "failed to connect to service: " + type);
            return false;
        } else {
            Log.v(TAG, "succeeded to connect to service: " + type);
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

    /**
     * Starts the installation monitor service.
     * The service monitors the default installtion path (under /data/local/tmp)
     * and automatically starts the activity to create a new profile when new
     * configuration files appear in that path.
     */
    public void startInstallationMonitorService() {
        mContext.startService(new Intent(SERVICE_VPN_INSTALL_MONITOR));
    }

    /** Stops the installation monitor service. */
    public void stopInstallationMonitorService() {
        mContext.stopService(new Intent(SERVICE_VPN_INSTALL_MONITOR));
    }

    /** Starts the VPN settings activity. */
    public void startSettingsActivity() {
        Intent intent = new Intent(ACTION_VPN_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    /**
     * Starts the activity to install a customized profile.
     * @param installPath the path where all the configuration files are located
     */
    public void startInstallProfileActivity(String installPath) {
        Intent intent = new Intent(ACTION_VPN_INSTALL_PROFILE);
        intent.putExtra(KEY_INSTALLATION_PATH, installPath);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }
}
