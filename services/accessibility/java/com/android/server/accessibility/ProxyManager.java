/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.accessibility;

import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.Context.DEVICE_ID_INVALID;

import static com.android.internal.util.FunctionalUtils.ignoreRemoteException;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.annotation.NonNull;
import android.companion.virtual.VirtualDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import com.android.internal.util.IntPair;
import com.android.server.LocalServices;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Manages proxy connections.
 *
 * Currently this acts similarly to UiAutomationManager as a global manager, though ideally each
 * proxy connection will belong to a separate user state.
 *
 * TODO(241117292): Remove or cut down during simultaneous user refactoring.
 */
public class ProxyManager {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "ProxyManager";

    // Names used to populate ComponentName and ResolveInfo in connection.mA11yServiceInfo and in
    // the infos of connection.setInstalledAndEnabledServices
    static final String PROXY_COMPONENT_PACKAGE_NAME = "ProxyPackage";
    static final String PROXY_COMPONENT_CLASS_NAME = "ProxyClass";

    // AMS#mLock
    private final Object mLock;

    private final Context mContext;
    private final Handler mMainHandler;

    private final UiAutomationManager mUiAutomationManager;

    // Device Id -> state. Used to determine if we should notify AccessibilityManager clients of
    // updates.
    private final SparseIntArray mLastStates = new SparseIntArray();

    // Each display id entry in a SparseArray represents a proxy a11y user.
    private final SparseArray<ProxyAccessibilityServiceConnection> mProxyA11yServiceConnections =
            new SparseArray<>();

    private final AccessibilityWindowManager mA11yWindowManager;

    private AccessibilityInputFilter mA11yInputFilter;

    private VirtualDeviceManagerInternal mLocalVdm;

    private final SystemSupport mSystemSupport;

    /**
     * Callbacks into AccessibilityManagerService.
     */
    public interface SystemSupport {
        /**
         * Removes the device id from tracking.
         */
        void removeDeviceIdLocked(int deviceId);

        /**
         * Updates the windows tracking for the current user.
         */
        void updateWindowsForAccessibilityCallbackLocked();

        /**
         * Clears all caches.
         */
        void notifyClearAccessibilityCacheLocked();

        /**
         * Gets the clients for all users.
         */
        @NonNull
        RemoteCallbackList<IAccessibilityManagerClient> getGlobalClientsLocked();

        /**
         * Gets the clients for the current user.
         */
        @NonNull
        RemoteCallbackList<IAccessibilityManagerClient> getCurrentUserClientsLocked();
    }

    public ProxyManager(Object lock, AccessibilityWindowManager awm,
            Context context, Handler mainHandler, UiAutomationManager uiAutomationManager,
            SystemSupport systemSupport) {
        mLock = lock;
        mA11yWindowManager = awm;
        mContext = context;
        mMainHandler = mainHandler;
        mUiAutomationManager = uiAutomationManager;
        mSystemSupport = systemSupport;
        mLocalVdm = LocalServices.getService(VirtualDeviceManagerInternal.class);
    }

    /**
     * Creates the service connection.
     */
    public void registerProxy(IAccessibilityServiceClient client, int displayId,
            int id, AccessibilitySecurityPolicy securityPolicy,
            AbstractAccessibilityServiceConnection.SystemSupport systemSupport,
            AccessibilityTrace trace,
            WindowManagerInternal windowManagerInternal) throws RemoteException {
        if (DEBUG) {
            Slog.v(LOG_TAG, "Register proxy for display id: " + displayId);
        }

        VirtualDeviceManager vdm = mContext.getSystemService(VirtualDeviceManager.class);
        if (vdm == null) {
            return;
        }
        final int deviceId = vdm.getDeviceIdForDisplayId(displayId);

        // Set a default AccessibilityServiceInfo that is used before the proxy's info is
        // populated. A proxy has the touch exploration and window capabilities.
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.setCapabilities(AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION
                | AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
        final String componentClassDisplayName = PROXY_COMPONENT_CLASS_NAME + displayId;
        info.setComponentName(new ComponentName(PROXY_COMPONENT_PACKAGE_NAME,
                componentClassDisplayName));
        ProxyAccessibilityServiceConnection connection =
                new ProxyAccessibilityServiceConnection(mContext, info.getComponentName(), info,
                        id, mMainHandler, mLock, securityPolicy, systemSupport, trace,
                        windowManagerInternal,
                        mA11yWindowManager, displayId, deviceId);

        synchronized (mLock) {
            mProxyA11yServiceConnections.put(displayId, connection);
        }

        // If the client dies, make sure to remove the connection.
        IBinder.DeathRecipient deathRecipient =
                new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        client.asBinder().unlinkToDeath(this, 0);
                        clearConnectionAndUpdateState(displayId);
                    }
                };
        client.asBinder().linkToDeath(deathRecipient, 0);

        mMainHandler.post(() -> {
            if (mA11yInputFilter != null) {
                mA11yInputFilter.disableFeaturesForDisplayIfInstalled(displayId);
            }
        });
        connection.initializeServiceInterface(client);
    }

    /**
     * Unregister the proxy based on display id.
     */
    public boolean unregisterProxy(int displayId) {
        return clearConnectionAndUpdateState(displayId);
    }

    /**
     * Clears all proxy connections belonging to {@code deviceId}.
     */
    public void clearConnections(int deviceId) {
        final IntArray displaysToClear = new IntArray();
        synchronized (mLock) {
            for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
                final ProxyAccessibilityServiceConnection proxy =
                        mProxyA11yServiceConnections.valueAt(i);
                if (proxy != null && proxy.getDeviceId() == deviceId) {
                    displaysToClear.add(proxy.getDisplayId());
                }
            }
        }
        for (int i = 0; i < displaysToClear.size(); i++) {
            clearConnectionAndUpdateState(displaysToClear.get(i));
        }
    }

    /**
     * Removes the system connection of an AccessibilityDisplayProxy.
     *
     * This will:
     * <ul>
     * <li> Reset Clients to belong to the default device if appropriate.
     * <li> Stop identifying the display's a11y windows as belonging to a proxy.
     * <li> Re-enable any input filters for the display.
     * <li> Notify AMS that a proxy has been removed.
     * </ul>
     *
     * @param displayId the display id of the connection to be cleared.
     * @return whether the proxy was removed.
     */
    private boolean clearConnectionAndUpdateState(int displayId) {
        boolean removedFromConnections = false;
        int deviceId = DEVICE_ID_INVALID;
        synchronized (mLock) {
            if (mProxyA11yServiceConnections.contains(displayId)) {
                deviceId = mProxyA11yServiceConnections.get(displayId).getDeviceId();
                mProxyA11yServiceConnections.remove(displayId);
                removedFromConnections = true;
            }
        }

        if (removedFromConnections) {
            updateStateForRemovedDisplay(displayId, deviceId);
        }

        if (DEBUG) {
            Slog.v(LOG_TAG, "Unregistered proxy for display id " + displayId + ": "
                    + removedFromConnections);
        }
        return removedFromConnections;
    }

    /**
     * When the connection is removed from tracking in ProxyManager, propagate changes to other a11y
     * system components like the input filter and IAccessibilityManagerClients.
     */
    private void updateStateForRemovedDisplay(int displayId, int deviceId) {
        mA11yWindowManager.stopTrackingDisplayProxy(displayId);
        // A11yInputFilter isn't thread-safe, so post on the system thread.
        mMainHandler.post(
                () -> {
                    if (mA11yInputFilter != null) {
                        final DisplayManager displayManager = (DisplayManager)
                                mContext.getSystemService(Context.DISPLAY_SERVICE);
                        final Display proxyDisplay = displayManager.getDisplay(displayId);
                        if (proxyDisplay != null) {
                            // A11yInputFilter isn't thread-safe, so post on the system thread.
                            mA11yInputFilter.enableFeaturesForDisplayIfInstalled(proxyDisplay);
                        }
                    }
                });
        // If there isn't an existing proxy for the device id, reset clients. Resetting
        // will usually happen, since in most cases there will only be one proxy for a
        // device.
        if (!isProxyedDeviceId(deviceId)) {
            synchronized (mLock) {
                mSystemSupport.removeDeviceIdLocked(deviceId);
                mLastStates.delete(deviceId);
            }
        } else {
            // Update with the states of the remaining proxies.
            onProxyChanged(deviceId);
        }
    }

    /**
     * Returns {@code true} if {@code displayId} is being proxy-ed.
     */
    public boolean isProxyedDisplay(int displayId) {
        synchronized (mLock) {
            final boolean tracked = mProxyA11yServiceConnections.contains(displayId);
            if (DEBUG) {
                Slog.v(LOG_TAG, "Tracking proxy display " + displayId + " : " + tracked);
            }
            return tracked;
        }
    }

    /**
     * Returns {@code true} if {@code deviceId} is being proxy-ed.
     */
    public boolean isProxyedDeviceId(int deviceId) {
        if (deviceId == DEVICE_ID_DEFAULT && deviceId == DEVICE_ID_INVALID) {
            return false;
        }
        boolean isTrackingDeviceId;
        synchronized (mLock) {
            isTrackingDeviceId = getFirstProxyForDeviceIdLocked(deviceId) != null;
        }
        if (DEBUG) {
            Slog.v(LOG_TAG, "Tracking device " + deviceId + " : " + isTrackingDeviceId);
        }
        return isTrackingDeviceId;
    }

    /**
     * Sends AccessibilityEvents to a proxy given the event's displayId.
     */
    public void sendAccessibilityEventLocked(AccessibilityEvent event) {
        final ProxyAccessibilityServiceConnection proxy =
                mProxyA11yServiceConnections.get(event.getDisplayId());
        if (proxy != null) {
            if (DEBUG) {
                Slog.v(LOG_TAG, "Send proxy event " + event + " for display id "
                        + event.getDisplayId());
            }
            proxy.notifyAccessibilityEvent(event);
        }
    }

    /**
     * Returns {@code true} if any proxy can retrieve windows.
     * TODO(b/250929565): Retrieve per connection/user state.
     */
    public boolean canRetrieveInteractiveWindowsLocked() {
        boolean observingWindows = false;
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            if (proxy.mRetrieveInteractiveWindows) {
                observingWindows = true;
                break;
            }
        }
        if (DEBUG) {
            Slog.v(LOG_TAG, "At least one proxy can retrieve windows: " + observingWindows);
        }
        return observingWindows;
    }

    /**
     * If there is at least one proxy, accessibility is enabled.
     */
    public int getStateLocked(int deviceId) {
        int clientState = 0;
        final boolean uiAutomationCanIntrospect = mUiAutomationManager.canIntrospect();
        if (uiAutomationCanIntrospect) {
            clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
        }
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            if (proxy != null && proxy.getDeviceId() == deviceId) {
                // Combine proxy states.
                clientState |= getStateForDisplayIdLocked(proxy);
            }
        }

        if (DEBUG) {
            Slog.v(LOG_TAG, "For device id " + deviceId + " a11y is enabled: "
                    + ((clientState & AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED) != 0));
            Slog.v(LOG_TAG, "For device id " + deviceId + " touch exploration is enabled: "
                    + ((clientState & AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED)
                            != 0));
        }
        return clientState;
    }

    /**
     * If there is at least one proxy, accessibility is enabled.
     */
    private int getStateForDisplayIdLocked(ProxyAccessibilityServiceConnection proxy) {
        int clientState = 0;
        if (proxy != null) {
            clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
            if (proxy.mRequestTouchExplorationMode) {
                clientState |= AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;
            }
        }

        if (DEBUG) {
            Slog.v(LOG_TAG, "Accessibility is enabled for all proxies: "
                    + ((clientState & AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED) != 0));
            Slog.v(LOG_TAG, "Touch exploration is enabled for all proxies: "
                    + ((clientState & AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED)
                            != 0));
        }
        return clientState;
    }

    /**
     * Gets the last state for a device.
     */
    private int getLastSentStateLocked(int deviceId) {
        return mLastStates.get(deviceId, 0);
    }

    /**
     * Sets the last state for a device.
     */
    private void setLastStateLocked(int deviceId, int proxyState) {
        mLastStates.put(deviceId, proxyState);
    }

    /**
     * Updates the relevant event types of the app clients that are shown on a display owned by the
     * specified device.
     *
     * A client belongs to a device id, so event types (and other state) is determined by the device
     * id. In most cases, a device owns a single display. But if multiple displays may belong to one
     * Virtual Device, the app clients will get the aggregated event types for all proxy-ed displays
     * belonging to a VirtualDevice.
     */
    private void updateRelevantEventTypesLocked(int deviceId) {
        if (!isProxyedDeviceId(deviceId)) {
            return;
        }
        mMainHandler.post(() -> {
            synchronized (mLock) {
                broadcastToClientsLocked(ignoreRemoteException(client -> {
                    int relevantEventTypes;
                    if (client.mDeviceId == deviceId) {
                        relevantEventTypes = computeRelevantEventTypesLocked(client);
                        if (client.mLastSentRelevantEventTypes != relevantEventTypes) {
                            client.mLastSentRelevantEventTypes = relevantEventTypes;
                            client.mCallback.setRelevantEventTypes(relevantEventTypes);
                        }
                    }
                }));
            }
        });
    }

    /**
     * Returns the relevant event types for a Client.
     */
    public int computeRelevantEventTypesLocked(AccessibilityManagerService.Client client) {
        int relevantEventTypes = 0;
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            if (proxy != null && proxy.getDeviceId() == client.mDeviceId) {
                relevantEventTypes |= proxy.getRelevantEventTypes();
                relevantEventTypes |= AccessibilityManagerService.isClientInPackageAllowlist(
                        mUiAutomationManager.getServiceInfo(), client)
                        ? mUiAutomationManager.getRelevantEventTypes()
                        : 0;
            }
        }
        if (DEBUG) {
            Slog.v(LOG_TAG, "Relevant event types for device id " + client.mDeviceId
                    + ": " + AccessibilityEvent.eventTypeToString(relevantEventTypes));
        }
        return relevantEventTypes;
    }

    /**
     * Adds the service interfaces to a list.
     * @param interfaces the list to add to.
     * @param deviceId the device id of the interested app client.
     */
    public void addServiceInterfacesLocked(@NonNull List<IAccessibilityServiceClient> interfaces,
            int deviceId) {
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            if (proxy != null && proxy.getDeviceId() == deviceId) {
                final IBinder proxyBinder = proxy.mService;
                final IAccessibilityServiceClient proxyInterface = proxy.mServiceInterface;
                if ((proxyBinder != null) && (proxyInterface != null)) {
                    interfaces.add(proxyInterface);
                }
            }
        }
    }

    /**
     * Gets the list of installed and enabled services for a device id.
     *
     * Note: Multiple display proxies may belong to the same device.
     */
    public List<AccessibilityServiceInfo> getInstalledAndEnabledServiceInfosLocked(int feedbackType,
            int deviceId) {
        List<AccessibilityServiceInfo> serviceInfos = new ArrayList<>();
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            if (proxy != null && proxy.getDeviceId() == deviceId) {
                // Return all proxy infos for ALL mask.
                if (feedbackType == AccessibilityServiceInfo.FEEDBACK_ALL_MASK) {
                    serviceInfos.addAll(proxy.getInstalledAndEnabledServices());
                } else if ((proxy.mFeedbackType & feedbackType) != 0) {
                    List<AccessibilityServiceInfo> proxyInfos =
                            proxy.getInstalledAndEnabledServices();
                    // Iterate through each info in the proxy.
                    for (AccessibilityServiceInfo info : proxyInfos) {
                        if ((info.feedbackType & feedbackType) != 0) {
                            serviceInfos.add(info);
                        }
                    }
                }
            }
        }
        return serviceInfos;
    }

    /**
     * Handles proxy changes.
     *
     * <p>
     * Changes include if the proxy is unregistered, its service info list has
     * changed, or its focus appearance has changed.
     * <p>
     * Some responses may include updating app clients. A client belongs to a device id, so state is
     * determined by the device id. In most cases, a device owns a single display. But if multiple
     * displays belong to one Virtual Device, the app clients will get a difference in
     * behavior depending on what is being updated.
     *
     * The following state methods are updated for AccessibilityManager clients belonging to a
     * proxied device:
     * <ul>
     * <li> A11yManager#setRelevantEventTypes - The combined event types of all proxies belonging to
     * a device id.
     * <li> A11yManager#setState - The combined states of all proxies belonging to a device id.
     * <li> A11yManager#notifyServicesStateChanged(timeout) - The highest of all proxies belonging
     * to a device id.
     * <li> A11yManager#setFocusAppearance - The appearance of the most recently updated display id
     * belonging to the device.
     * </ul>
     * This is similar to onUserStateChangeLocked and onClientChangeLocked, but does not require an
     * A11yUserState and only checks proxy-relevant settings.
     */
    public void onProxyChanged(int deviceId) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "onProxyChanged called for deviceId: " + deviceId);
        }
        //The following state updates are excluded:
        //  - Input-related state
        //  - Primary-device / hardware-specific state
        synchronized (mLock) {
            // A proxy may be registered after the client has been initialized in #addClient.
            // For example, a user does not turn on accessibility until after the app has launched.
            // Or the process was started with a default id context and should shift to a device.
            // Update device ids of the clients if necessary.
            updateDeviceIdsIfNeededLocked(deviceId);
            // Start tracking of all displays if necessary.
            mSystemSupport.updateWindowsForAccessibilityCallbackLocked();
            // Calls A11yManager#setRelevantEventTypes (test these)
            updateRelevantEventTypesLocked(deviceId);
            // Calls A11yManager#setState
            scheduleUpdateProxyClientsIfNeededLocked(deviceId);
            //Calls A11yManager#notifyServicesStateChanged(timeout)
            scheduleNotifyProxyClientsOfServicesStateChangeLocked(deviceId);
            // Calls A11yManager#setFocusAppearance
            updateFocusAppearanceLocked(deviceId);
            mSystemSupport.notifyClearAccessibilityCacheLocked();
        }
    }

    /**
     * Updates the states of the app AccessibilityManagers.
     */
    private void scheduleUpdateProxyClientsIfNeededLocked(int deviceId) {
        final int proxyState = getStateLocked(deviceId);
        if (DEBUG) {
            Slog.v(LOG_TAG, "State for device id " + deviceId + " is " + proxyState);
            Slog.v(LOG_TAG, "Last state for device id " + deviceId + " is "
                    + getLastSentStateLocked(deviceId));
        }
        if ((getLastSentStateLocked(deviceId)) != proxyState) {
            setLastStateLocked(deviceId, proxyState);
            mMainHandler.post(() -> {
                synchronized (mLock) {
                    broadcastToClientsLocked(ignoreRemoteException(client -> {
                        if (client.mDeviceId == deviceId) {
                            client.mCallback.setState(proxyState);
                        }
                    }));
                }
            });
        }
    }

    /**
     * Notifies AccessibilityManager of services state changes, which includes changes to the
     * list of service infos and timeouts.
     *
     * @see AccessibilityManager.AccessibilityServicesStateChangeListener
     */
    private void scheduleNotifyProxyClientsOfServicesStateChangeLocked(int deviceId) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "Notify services state change at device id " + deviceId);
        }
        mMainHandler.post(()-> {
            broadcastToClientsLocked(ignoreRemoteException(client -> {
                if (client.mDeviceId == deviceId) {
                    synchronized (mLock) {
                        client.mCallback.notifyServicesStateChanged(
                                getRecommendedTimeoutMillisLocked(deviceId));
                    }
                }
            }));
        });
    }

    /**
     * Updates the focus appearance of AccessibilityManagerClients.
     */
    private void updateFocusAppearanceLocked(int deviceId) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "Update proxy focus appearance at device id " + deviceId);
        }
        // Reasonably assume that all proxies belonging to a virtual device should have the
        // same focus appearance, and if they should be different these should belong to different
        // virtual devices.
        final ProxyAccessibilityServiceConnection proxy = getFirstProxyForDeviceIdLocked(deviceId);
        if (proxy != null) {
            mMainHandler.post(()-> {
                broadcastToClientsLocked(ignoreRemoteException(client -> {
                    if (client.mDeviceId == proxy.getDeviceId()) {
                        client.mCallback.setFocusAppearance(
                                proxy.getFocusStrokeWidthLocked(),
                                proxy.getFocusColorLocked());
                    }
                }));
            });
        }
    }

    private ProxyAccessibilityServiceConnection getFirstProxyForDeviceIdLocked(int deviceId) {
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            if (proxy != null && proxy.getDeviceId() == deviceId) {
                return proxy;
            }
        }
        return null;
    }

    private void broadcastToClientsLocked(
            @NonNull Consumer<AccessibilityManagerService.Client> clientAction) {
        final RemoteCallbackList<IAccessibilityManagerClient> userClients =
                mSystemSupport.getCurrentUserClientsLocked();
        final RemoteCallbackList<IAccessibilityManagerClient> globalClients =
                mSystemSupport.getGlobalClientsLocked();
        userClients.broadcastForEachCookie(clientAction);
        globalClients.broadcastForEachCookie(clientAction);
    }

    /**
     * Updates the timeout and notifies app clients.
     *
     * For real users, timeouts are tracked in A11yUserState. For proxies, timeouts are in the
     * service connection. The value in user state is preferred, but if this value is 0 the service
     * info value is used.
     *
     * This follows the pattern in readUserRecommendedUiTimeoutSettingsLocked.
     *
     * TODO(b/250929565): ProxyUserState or similar should hold the timeouts
     */
    public void updateTimeoutsIfNeeded(int nonInteractiveUiTimeout, int interactiveUiTimeout) {
        synchronized (mLock) {
            for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
                final ProxyAccessibilityServiceConnection proxy =
                        mProxyA11yServiceConnections.valueAt(i);
                if (proxy != null) {
                    if (proxy.updateTimeouts(nonInteractiveUiTimeout, interactiveUiTimeout)) {
                        scheduleNotifyProxyClientsOfServicesStateChangeLocked(proxy.getDeviceId());
                    }
                }
            }
        }
    }

    /**
     * Gets the recommended timeout belonging to a Virtual Device.
     *
     * This is the highest of all display proxies belonging to the virtual device.
     */
    public long getRecommendedTimeoutMillisLocked(int deviceId) {
        int combinedInteractiveTimeout = 0;
        int combinedNonInteractiveTimeout = 0;
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            if (proxy != null && proxy.getDeviceId() == deviceId) {
                final int proxyInteractiveUiTimeout =
                        (proxy != null) ? proxy.getInteractiveTimeout() : 0;
                final int nonInteractiveUiTimeout =
                        (proxy != null) ? proxy.getNonInteractiveTimeout() : 0;
                combinedInteractiveTimeout = Math.max(proxyInteractiveUiTimeout,
                        combinedInteractiveTimeout);
                combinedNonInteractiveTimeout = Math.max(nonInteractiveUiTimeout,
                        combinedNonInteractiveTimeout);
            }
        }
        return IntPair.of(combinedInteractiveTimeout, combinedNonInteractiveTimeout);
    }

    /**
     * Gets the first focus stroke width belonging to the device.
     */
    public int getFocusStrokeWidthLocked(int deviceId) {
        final ProxyAccessibilityServiceConnection proxy = getFirstProxyForDeviceIdLocked(deviceId);
        if (proxy != null) {
            return proxy.getFocusStrokeWidthLocked();
        }
        return 0;

    }

    /**
     * Gets the first focus color belonging to the device.
     */
    public int getFocusColorLocked(int deviceId) {
        final ProxyAccessibilityServiceConnection proxy = getFirstProxyForDeviceIdLocked(deviceId);
        if (proxy != null) {
            return proxy.getFocusColorLocked();
        }
        return 0;
    }

    /**
     * Returns the first device id given a UID.
     * @param callingUid the UID to check.
     * @return the first matching device id, or DEVICE_ID_INVALID.
     */
    public int getFirstDeviceIdForUidLocked(int callingUid) {
        int firstDeviceId = DEVICE_ID_INVALID;
        final VirtualDeviceManagerInternal localVdm = getLocalVdm();
        if (localVdm == null) {
            return firstDeviceId;
        }
        final Set<Integer> deviceIds = localVdm.getDeviceIdsForUid(callingUid);
        for (Integer uidDeviceId : deviceIds) {
            if (uidDeviceId != DEVICE_ID_DEFAULT && uidDeviceId != DEVICE_ID_INVALID) {
                firstDeviceId = uidDeviceId;
                break;
            }
        }
        return firstDeviceId;
    }

    /**
     * Sets a Client device id if the app uid belongs to the virtual device.
     */
    private void updateDeviceIdsIfNeededLocked(int deviceId) {
        final RemoteCallbackList<IAccessibilityManagerClient> userClients =
                mSystemSupport.getCurrentUserClientsLocked();
        final RemoteCallbackList<IAccessibilityManagerClient> globalClients =
                mSystemSupport.getGlobalClientsLocked();

        updateDeviceIdsIfNeededLocked(deviceId, userClients);
        updateDeviceIdsIfNeededLocked(deviceId, globalClients);
    }

    /**
     * Updates the device ids of IAccessibilityManagerClients if needed.
     */
    private void updateDeviceIdsIfNeededLocked(int deviceId,
            @NonNull RemoteCallbackList<IAccessibilityManagerClient> clients) {
        final VirtualDeviceManagerInternal localVdm = getLocalVdm();
        if (localVdm == null) {
            return;
        }

        for (int i = 0; i < clients.getRegisteredCallbackCount(); i++) {
            final AccessibilityManagerService.Client client =
                    ((AccessibilityManagerService.Client) clients.getRegisteredCallbackCookie(i));
            if (deviceId != DEVICE_ID_DEFAULT && deviceId != DEVICE_ID_INVALID
                    && localVdm.getDeviceIdsForUid(client.mUid).contains(deviceId)) {
                if (DEBUG) {
                    Slog.v(LOG_TAG, "Packages moved to device id " + deviceId + " are "
                            + Arrays.toString(client.mPackageNames));
                }
                client.mDeviceId = deviceId;
            }
        }
    }

    /**
     * Clears all proxy caches.
     */
    public void clearCacheLocked() {
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            proxy.notifyClearAccessibilityNodeInfoCache();
        }
    }

    /**
     * Sets the input filter for enabling and disabling features for proxy displays.
     */
    public void setAccessibilityInputFilter(AccessibilityInputFilter filter) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "Set proxy input filter to " + filter);
        }
        mA11yInputFilter = filter;
    }

    private VirtualDeviceManagerInternal getLocalVdm() {
        if (mLocalVdm == null) {
            mLocalVdm =  LocalServices.getService(VirtualDeviceManagerInternal.class);
        }
        return mLocalVdm;
    }

    /**
     * Prints information belonging to each display that is controlled by an
     * AccessibilityDisplayProxy.
     */
    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println();
            pw.println("Proxy manager state:");
            pw.println("    Number of proxy connections: " + mProxyA11yServiceConnections.size());
            pw.println("    Registered proxy connections:");
            final RemoteCallbackList<IAccessibilityManagerClient> userClients =
                    mSystemSupport.getCurrentUserClientsLocked();
            final RemoteCallbackList<IAccessibilityManagerClient> globalClients =
                    mSystemSupport.getGlobalClientsLocked();
            for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
                final ProxyAccessibilityServiceConnection proxy =
                        mProxyA11yServiceConnections.valueAt(i);
                if (proxy != null) {
                    proxy.dump(fd, pw, args);
                }
                pw.println();
                pw.println("        User clients for proxy's virtual device id");
                printClientsForDeviceId(pw, userClients, proxy.getDeviceId());
                pw.println();
                pw.println("        Global clients for proxy's virtual device id");
                printClientsForDeviceId(pw, globalClients, proxy.getDeviceId());

            }
        }
    }

    private void printClientsForDeviceId(PrintWriter pw,
            RemoteCallbackList<IAccessibilityManagerClient> clients, int deviceId) {
        if (clients != null) {
            for (int j = 0; j < clients.getRegisteredCallbackCount(); j++) {
                final AccessibilityManagerService.Client client =
                        (AccessibilityManagerService.Client)
                                clients.getRegisteredCallbackCookie(j);
                if (client.mDeviceId == deviceId) {
                    pw.println("            " + Arrays.toString(client.mPackageNames) + "\n");
                }
            }
        }
    }
}
