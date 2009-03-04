/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.location;

import com.android.internal.location.CellState;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.os.Bundle;
import android.util.Config;
import android.util.Log;

/**
 * An abstract superclass for location provider implementations.
 * Location provider implementations are typically instantiated by the
 * location manager service in the system process, and location
 * information is made available to implementations via the manager.
 *
 * {@hide}
 */
public abstract class LocationProviderImpl extends LocationProvider {
    private static final String TAG = "LocationProviderImpl";

    private static ArrayList<LocationProviderImpl> sProviders =
        new ArrayList<LocationProviderImpl>();
    private static HashMap<String, LocationProviderImpl> sProvidersByName
        = new HashMap<String, LocationProviderImpl>();

    private boolean mLocationTracking = false;
    private long mMinTime = 0;

    protected LocationProviderImpl(String name) {
        super(name);
    }

    public static void addProvider(LocationProviderImpl provider) {
        sProviders.add(provider);
        sProvidersByName.put(provider.getName(), provider);
    }

    public static void removeProvider(LocationProviderImpl provider) {
        sProviders.remove(provider);
        sProvidersByName.remove(provider.getName());
    }

    public static List<LocationProviderImpl> getProviders() {
        return new ArrayList<LocationProviderImpl>(sProviders);
    }

    public static LocationProviderImpl getProvider(String name) {
        return sProvidersByName.get(name);
    }

    public static LocationProviderImpl loadFromClass(File classFile) {
        if (!classFile.exists()) {
            return null;
        }
        if (Config.LOGD) {
            Log.d(TAG, "Loading class specifier file " + classFile.getPath());
        }
        String className = null;
        try {
            BufferedReader br =
                new BufferedReader(new FileReader(classFile), 8192);
            className = br.readLine();
            br.close();
            Class providerClass = Class.forName(className);
            if (Config.LOGD) {
                Log.d(TAG, "Loading provider class " + providerClass.getName());
            }
            LocationProviderImpl provider =
                (LocationProviderImpl) providerClass.newInstance();
            if (Config.LOGD) {
                Log.d(TAG, "Got provider instance " + provider);
            }

            return provider;
        } catch (IOException ioe) {
            Log.e(TAG, "IOException loading config file " +
                  classFile.getPath(), ioe);
        } catch (IllegalAccessException iae) {
            Log.e(TAG, "IllegalAccessException loading class " +
                  className, iae);
        } catch (InstantiationException ie) {
            Log.e(TAG, "InstantiationException loading class " +
                  className, ie);
        } catch (ClassNotFoundException cnfe) {
            Log.e(TAG, "ClassNotFoundException loading class " +
                  className, cnfe);
        } catch (ClassCastException cce) {
            Log.e(TAG, "ClassCastException loading class " +
                  className, cce);
        }
        return null;
    }

    /**
     * Enables this provider.  When enabled, calls to {@link #getStatus()}
     * and {@link #getLocation} must be handled.  Hardware may be started up
     * when the provider is enabled.
     */
    public abstract void enable();

    /**
     * Disables this provider.  When disabled, calls to {@link #getStatus()}
     * and {@link #getLocation} need not be handled.  Hardware may be shut
     * down while the provider is disabled.
     */
    public abstract void disable();

    /**
     * Returns true if this provider is enabled, false otherwise;
     */
    public abstract boolean isEnabled();

    /**
     * Returns a information on the status of this provider.
     * {@link #OUT_OF_SERVICE} is returned if the provider is
     * out of service, and this is not expected to change in the near
     * future; {@link #TEMPORARILY_UNAVAILABLE} is returned if
     * the provider is temporarily unavailable but is expected to be
     * available shortly; and {@link #AVAILABLE} is returned
     * if the provider is currently available.
     */
    public int getStatus() {
        return getStatus(null);
    }

    /**
     * Returns a information on the status of this provider.
     * {@link #OUT_OF_SERVICE} is returned if the provider is
     * out of service, and this is not expected to change in the near
     * future; {@link #TEMPORARILY_UNAVAILABLE} is returned if
     * the provider is temporarily unavailable but is expected to be
     * available shortly; and {@link #AVAILABLE} is returned
     * if the provider is currently available.
     *
     * <p> If extras is non-null, additional status information may be
     * added to it in the form of provider-specific key/value pairs.
     */
    public abstract int getStatus(Bundle extras);

    /**
     * Returns the time at which the status was last updated. It is the
     * responsibility of the provider to appropriately set this value
     * using {@link android.os.SystemClock.elapsedRealtime()} each time
     * there is a status update that it wishes to broadcast to all its
     * listeners. The provider should be careful not to broadcast
     * the same status again.
     *
     * @return time of last status update in millis since last reboot
     */
    public long getStatusUpdateTime() {
        return 0;
    }

    /**
     * Sets a Location object with the information gathered
     * during the most recent fix.
     *
     * @param l location object to set
     * @return true if a location fix is available
     */
    public abstract boolean getLocation(Location l);

    /**
     * Notifies the location provider that clients are listening for locations.
     * Called with enable set to true when the first client is added and
     * called with enable set to false when the last client is removed.
     * This allows the provider to prepare for receiving locations,
     * and to shut down when no clients are remaining.
     *
     * @param enable true if location tracking should be enabled.
     */
    public void enableLocationTracking(boolean enable) {
        mLocationTracking = enable;
    }

    /**
     * Returns true if the provider has any listeners
     *
     * @return true if provider is being tracked
     */
    public boolean isLocationTracking() {
        return mLocationTracking;
    }

    /**
     * Notifies the location provider of the smallest minimum time between updates amongst
     * all clients that are listening for locations.  This allows the provider to reduce
     * the frequency of updates to match the requested frequency.
     *
     * @param minTime the smallest minTime value over all listeners for this provider.
     */
    public void setMinTime(long minTime) {
        mMinTime = minTime;
    }

    /**
     * Gets the smallest minimum time between updates amongst all the clients listening
     * for locations. By default this value is 0 (as frqeuently as possible)
     *
     * @return the smallest minTime value over all listeners for this provider
     */
    public long getMinTime() {
        return mMinTime;
    }

    /**
     * Updates the network state for the given provider. This function must
     * be overwritten if {@link #requiresNetwork} returns true. The state is
     * {@link #TEMPORARILY_UNAVAILABLE} (disconnected), OR {@link #AVAILABLE}
     * (connected or connecting).
     *
     * @param state data state
     */
    public void updateNetworkState(int state) {
    }

    /**
     * Updates the cell state for the given provider. This function must be
     * overwritten if {@link #requiresCell} returns true.
     *
     * @param state cell state
     */
    public void updateCellState(CellState state) {
    }

    /**
     * Implements addditional location provider specific additional commands.
     *
     * @param command name of the command to send to the provider.
     * @param extras optional arguments for the command (or null).
     * The provider may optionally fill the extras Bundle with results from the command.
     *
     * @return true if the command succeeds. 
     */
    public boolean sendExtraCommand(String command, Bundle extras) {
        return false;
    }
}
