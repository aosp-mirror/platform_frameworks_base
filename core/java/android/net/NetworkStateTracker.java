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

/**
 * Interface for connectivity service to act on a network interface.
 * All state information for a network should be kept in a Tracker class.
 * This interface defines network-type-independent functions that should
 * be implemented by the Tracker class.
 *
 * {@hide}
 */
public interface NetworkStateTracker {

    public static final int EVENT_STATE_CHANGED = 1;
    public static final int EVENT_SCAN_RESULTS_AVAILABLE = 2;
    /**
     * arg1: 1 to show, 0 to hide
     * arg2: ID of the notification
     * obj: Notification (if showing)
     */
    public static final int EVENT_NOTIFICATION_CHANGED = 3;
    public static final int EVENT_CONFIGURATION_CHANGED = 4;
    public static final int EVENT_ROAMING_CHANGED = 5;
    public static final int EVENT_NETWORK_SUBTYPE_CHANGED = 6;
    public static final int EVENT_RESTORE_DEFAULT_NETWORK = 7;

    /**
     * Fetch NetworkInfo for the network
     */
    public NetworkInfo getNetworkInfo();

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName();

    /**
     * Return the DNS property names for this network.
     */
    public String[] getDnsPropNames();

    /**
     * Fetch interface name of the interface
     */
    public String getInterfaceName();

    /**
     * Check if private DNS route is set for the network
     */
    public boolean isPrivateDnsRouteSet();

    /**
     * Set a flag indicating private DNS route is set
     */
    public void privateDnsRouteSet(boolean enabled);

    /**
     * Fetch default gateway address for the network
     */
    public int getDefaultGatewayAddr();

    /**
     * Check if default route is set
     */
    public boolean isDefaultRouteSet();

    /**
     * Set a flag indicating default route is set for the network
     */
    public void defaultRouteSet(boolean enabled);

    /**
     * Indicate tear down requested from connectivity
     */
    public void setTeardownRequested(boolean isRequested);

    /**
     * Check if tear down was requested
     */
    public boolean isTeardownRequested();

    /**
     * Release the wakelock, if any, that may be held while handling a
     * disconnect operation.
     */
    public void releaseWakeLock();

    public void startMonitoring();

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
     */
    public boolean isAvailable();

    /**
     * Tells the underlying networking system that the caller wants to
     * begin using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature to be used
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     */
    public int startUsingNetworkFeature(String feature, int callingPid, int callingUid);

    /**
     * Tells the underlying networking system that the caller is finished
     * using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature that is no longer needed.
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     */
    public int stopUsingNetworkFeature(String feature, int callingPid, int callingUid);

    /**
     * Interprets scan results. This will be called at a safe time for
     * processing, and from a safe thread.
     */
    public void interpretScanResultsAvailable();

}
