/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.net.NetworkInfo;
import android.os.Bundle;

/**
 * An abstract superclass for location providers that are implemented
 * outside of the core android platform.
 * A LocationProviderImpl can be installed using the
 * {@link LocationManager#installLocationProvider(LocationProviderImpl)} method.
 * Installing a location provider requires the
 * android.permission.INSTALL_LOCATION_PROVIDER permission.
 */
public abstract class LocationProviderImpl extends LocationProvider {

    private ILocationProvider.Stub mProvider = new ILocationProvider.Stub() {

        public boolean requiresNetwork() {
            return LocationProviderImpl.this.requiresNetwork();
        }

        public boolean requiresSatellite() {
            return LocationProviderImpl.this.requiresSatellite();
        }

        public boolean requiresCell() {
            return LocationProviderImpl.this.requiresCell();
        }

        public boolean hasMonetaryCost() {
            return LocationProviderImpl.this.hasMonetaryCost();
        }

        public boolean supportsAltitude() {
            return LocationProviderImpl.this.supportsAltitude();
        }

        public boolean supportsSpeed() {
            return LocationProviderImpl.this.supportsSpeed();
        }

        public boolean supportsBearing() {
            return LocationProviderImpl.this.supportsBearing();
        }

        public int getPowerRequirement() {
            return LocationProviderImpl.this.getPowerRequirement();
        }

        public int getAccuracy() {
            return LocationProviderImpl.this.getAccuracy();
        }

        public void enable() {
            LocationProviderImpl.this.enable();
        }

        public void disable() {
            LocationProviderImpl.this.disable();
        }

        public boolean isEnabled() {
            return LocationProviderImpl.this.isEnabled();
        }

        public int getStatus(Bundle extras) {
            return LocationProviderImpl.this.getStatus(extras);
        }

        public long getStatusUpdateTime() {
            return LocationProviderImpl.this.getStatusUpdateTime();
        }

        public void enableLocationTracking(boolean enable) {
            LocationProviderImpl.this.enableLocationTracking(enable);
        }

        public void setMinTime(long minTime) {
            LocationProviderImpl.this.setMinTime(minTime);
        }

        public void updateNetworkState(int state, NetworkInfo info) {
            LocationProviderImpl.this.updateNetworkState(state, info);
        }

        public void updateLocation(Location location) {
            LocationProviderImpl.this.updateLocation(location);
        }

        public boolean sendExtraCommand(String command, Bundle extras) {
            return LocationProviderImpl.this.sendExtraCommand(command, extras);
        }

        public void addListener(int uid) {
            LocationProviderImpl.this.addListener(uid);
        }

        public void removeListener(int uid) {
            LocationProviderImpl.this.removeListener(uid);
        }
    };

    public LocationProviderImpl(String name) {
        super(name);
    }

    /**
     * {@hide}
     */
    /* package */ ILocationProvider getInterface() {
        return mProvider;
    }

    /**
     * Enables the location provider
     */
    public abstract void enable();

    /**
     * Disables the location provider
     */
    public abstract void disable();

    /**
     * Returns true if the provider is currently enabled
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
     *
     * <p> If extras is non-null, additional status information may be
     * added to it in the form of provider-specific key/value pairs.
     */
    public abstract int getStatus(Bundle extras);

    /**
     * Returns the time at which the status was last updated. It is the
     * responsibility of the provider to appropriately set this value using
     * {@link android.os.SystemClock#elapsedRealtime SystemClock.elapsedRealtime()}.
     * there is a status update that it wishes to broadcast to all its
     * listeners. The provider should be careful not to broadcast
     * the same status again.
     *
     * @return time of last status update in millis since last reboot
     */
    public abstract long getStatusUpdateTime();

    /**
     * Notifies the location provider that clients are listening for locations.
     * Called with enable set to true when the first client is added and
     * called with enable set to false when the last client is removed.
     * This allows the provider to prepare for receiving locations,
     * and to shut down when no clients are remaining.
     *
     * @param enable true if location tracking should be enabled.
     */
    public abstract void enableLocationTracking(boolean enable);

    /**
     * Notifies the location provider of the smallest minimum time between updates amongst
     * all clients that are listening for locations.  This allows the provider to reduce
     * the frequency of updates to match the requested frequency.
     *
     * @param minTime the smallest minTime value over all listeners for this provider.
     */
    public abstract void setMinTime(long minTime);

    /**
     * Updates the network state for the given provider. This function must
     * be overwritten if {@link #requiresNetwork} returns true. The state is
     * {@link #TEMPORARILY_UNAVAILABLE} (disconnected), OR {@link #AVAILABLE}
     * (connected or connecting).
     *
     * @param state data state
     */
    public abstract void updateNetworkState(int state, NetworkInfo info);

    /**
     * Informs the provider when a new location has been computed by a different
     * location provider.  This is intended to be used as aiding data for the
     * receiving provider.
     *
     * @param location new location from other location provider
     */
    public abstract void updateLocation(Location location);

    /**
     * Implements addditional location provider specific additional commands.
     *
     * @param command name of the command to send to the provider.
     * @param extras optional arguments for the command (or null).
     * The provider may optionally fill the extras Bundle with results from the command.
     *
     * @return true if the command succeeds.
     */
    public abstract boolean sendExtraCommand(String command, Bundle extras);

    /**
     * Notifies the location provider when a new client is listening for locations.
     *
     * @param uid user ID of the new client.
     */
    public abstract void addListener(int uid);

    /**
     * Notifies the location provider when a client is no longer listening for locations.
     *
     * @param uid user ID of the client no longer listening.
     */
    public abstract void removeListener(int uid);
}

