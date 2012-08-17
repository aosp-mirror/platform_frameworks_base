/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;

/**
 * Interface provides the {@link com.android.server.ConnectivityService}
 * with three services. Events to the ConnectivityService when
 * changes occur, an API for controlling the network and storage
 * for network specific information.
 *
 * The Connectivity will call startMonitoring before any other
 * method is called.
 *
 * {@hide}
 */
public interface NetworkStateTracker {

    /**
     * -------------------------------------------------------------
     * Event Interface back to ConnectivityService.
     *
     * The events that are to be sent back to the Handler passed
     * to startMonitoring when the particular event occurs.
     * -------------------------------------------------------------
     */

    // Share the event space with ConnectivityService (which we can't see, but
    // must send events to).  If you change these, change ConnectivityService
    // too.
    static final int MIN_NETWORK_STATE_TRACKER_EVENT = 1;
    static final int MAX_NETWORK_STATE_TRACKER_EVENT = 100;

    /**
     * The network state has changed and the NetworkInfo object
     * contains the new state.
     *
     * msg.what = EVENT_STATE_CHANGED
     * msg.obj = NetworkInfo object
     */
    public static final int EVENT_STATE_CHANGED = 1;

    /**
     * msg.what = EVENT_CONFIGURATION_CHANGED
     * msg.obj = NetworkInfo object
     */
    public static final int EVENT_CONFIGURATION_CHANGED = 3;

    /**
     * msg.what = EVENT_RESTORE_DEFAULT_NETWORK
     * msg.obj = FeatureUser object
     */
    public static final int EVENT_RESTORE_DEFAULT_NETWORK = 6;

    /**
     * msg.what = EVENT_NETWORK_SUBTYPE_CHANGED
     * msg.obj = NetworkInfo object
     */
    public static final int EVENT_NETWORK_SUBTYPE_CHANGED = 7;

    /**
     * -------------------------------------------------------------
     * Control Interface
     * -------------------------------------------------------------
     */
    /**
     * Begin monitoring data connectivity.
     *
     * This is the first method called when this interface is used.
     *
     * @param context is the current Android context
     * @param target is the Hander to which to return the events.
     */
    public void startMonitoring(Context context, Handler target);

    /**
     * Fetch NetworkInfo for the network
     */
    public NetworkInfo getNetworkInfo();

    /**
     * Return the LinkProperties for the connection.
     *
     * @return a copy of the LinkProperties, is never null.
     */
    public LinkProperties getLinkProperties();

    /**
     * A capability is an Integer/String pair, the capabilities
     * are defined in the class LinkSocket#Key.
     *
     * @return a copy of this connections capabilities, may be empty but never null.
     */
    public LinkCapabilities getLinkCapabilities();

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName();

    /**
     * Disable connectivity to a network
     * @return {@code true} if a teardown occurred, {@code false} if the
     * teardown did not occur.
     */
    public boolean teardown();

    /**
     * Reenable connectivity to a network after a {@link #teardown()}.
     * @return {@code true} if we're connected or expect to be connected
     */
    public boolean reconnect();

    /**
     * Turn the wireless radio off for a network.
     * @param turnOn {@code true} to turn the radio on, {@code false}
     */
    public boolean setRadio(boolean turnOn);

    /**
     * Returns an indication of whether this network is available for
     * connections. A value of {@code false} means that some quasi-permanent
     * condition prevents connectivity to this network.
     *
     * NOTE that this is broken on multi-connection devices.  Should be fixed in J release
     * TODO - fix on multi-pdp devices
     */
    public boolean isAvailable();

    /**
     * User control of data connection through this network, typically persisted
     * internally.
     */
    public void setUserDataEnable(boolean enabled);

    /**
     * Policy control of data connection through this network, typically not
     * persisted internally. Usually used when {@link NetworkPolicy#limitBytes}
     * is passed.
     */
    public void setPolicyDataEnable(boolean enabled);

    /**
     * -------------------------------------------------------------
     * Storage API used by ConnectivityService for saving
     * Network specific information.
     * -------------------------------------------------------------
     */

    /**
     * Check if private DNS route is set for the network
     */
    public boolean isPrivateDnsRouteSet();

    /**
     * Set a flag indicating private DNS route is set
     */
    public void privateDnsRouteSet(boolean enabled);

    /**
     * Check if default route is set
     */
    public boolean isDefaultRouteSet();

    /**
     * Set a flag indicating default route is set for the network
     */
    public void defaultRouteSet(boolean enabled);

    /**
     * Check if tear down was requested
     */
    public boolean isTeardownRequested();

    /**
     * Indicate tear down requested from connectivity
     */
    public void setTeardownRequested(boolean isRequested);

    /**
     * An external dependency has been met/unmet
     */
    public void setDependencyMet(boolean met);
}
