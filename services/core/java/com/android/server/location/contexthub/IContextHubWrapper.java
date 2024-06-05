/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.location.contexthub;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.chre.flags.Flags;
import android.hardware.contexthub.HostEndpointInfo;
import android.hardware.contexthub.MessageDeliveryStatus;
import android.hardware.contexthub.NanSessionRequest;
import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.TransactionResult;
import android.hardware.contexthub.V1_1.Setting;
import android.hardware.contexthub.V1_1.SettingValue;
import android.hardware.contexthub.V1_2.HubAppInfo;
import android.hardware.contexthub.V1_2.IContexthubCallback;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * @hide
 */
public abstract class IContextHubWrapper {
    private static final String TAG = "IContextHubWrapper";

    /**
     * The callback interface to use in registerCallback.
     */
    public interface ICallback {
        /**
         * @param transactionId The ID of the transaction that completed.
         * @param success       true if the transaction succeeded.
         */
        void handleTransactionResult(int transactionId, boolean success);

        /**
         * @param eventType The Context Hub event type defined by ContextHubService
         *                  .CONTEXT_HUB_EVENT_*.
         */
        void handleContextHubEvent(int eventType);

        /**
         * @param nanoappId The ID of the nanoapp that aborted.
         * @param abortCode The nanoapp-defined abort code.
         */
        void handleNanoappAbort(long nanoappId, int abortCode);

        /**
         * @param nanoappStateList The list of loaded nanoapps on the Context Hub.
         */
        void handleNanoappInfo(List<NanoAppState> nanoappStateList);

        /**
         * Handles a message from a nanoapp to a ContextHubClient.
         *
         * @param hostEndpointId            The host endpoint ID of the recipient.
         * @param message                   The message from the nanoapp.
         * @param nanoappPermissions        The list of permissions held by the nanoapp.
         * @param messagePermissions        The list of permissions required to receive the message.
         */
        void handleNanoappMessage(short hostEndpointId, NanoAppMessage message,
                List<String> nanoappPermissions, List<String> messagePermissions);

        /**
         * Handles a restart of the service.
         */
        void handleServiceRestart();

        /**
         * Handles a message delivery status.
         *
         * @param messageDeliveryStatus     The message delivery status to deliver.
         */
        void handleMessageDeliveryStatus(MessageDeliveryStatus messageDeliveryStatus);
    }

    /**
     * @return the IContextHubWrapper interface
     */
    public static IContextHubWrapper getContextHubWrapper() {
        IContextHubWrapper wrapper = maybeConnectToAidl();
        if (wrapper == null) {
            wrapper = maybeConnectTo1_2();
        }
        if (wrapper == null) {
            wrapper = maybeConnectTo1_1();
        }
        if (wrapper == null) {
            wrapper = maybeConnectTo1_0();
        }

        return wrapper;
    }

    /**
     * Attempts to connect to the Contexthub HAL 1.0 service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectTo1_0() {
        android.hardware.contexthub.V1_0.IContexthub proxy = null;
        try {
            proxy = android.hardware.contexthub.V1_0.IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return (proxy == null) ? null : new ContextHubWrapperV1_0(proxy);
    }

    /**
     * Attempts to connect to the Contexthub HAL 1.1 service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectTo1_1() {
        android.hardware.contexthub.V1_1.IContexthub proxy = null;
        try {
            proxy = android.hardware.contexthub.V1_1.IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return (proxy == null) ? null : new ContextHubWrapperV1_1(proxy);
    }

    /**
     * Attempts to connect to the Contexthub HAL 1.2 service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectTo1_2() {
        android.hardware.contexthub.V1_2.IContexthub proxy = null;
        try {
            proxy = android.hardware.contexthub.V1_2.IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return (proxy == null) ? null : new ContextHubWrapperV1_2(proxy);
    }

    /**
     * Attempts to connect to the AIDL HAL and returns the proxy IContextHub.
     */
    public static android.hardware.contexthub.IContextHub maybeConnectToAidlGetProxy() {
        android.hardware.contexthub.IContextHub proxy = null;
        final String aidlServiceName =
                android.hardware.contexthub.IContextHub.class.getCanonicalName() + "/default";
        if (ServiceManager.isDeclared(aidlServiceName)) {
            proxy = android.hardware.contexthub.IContextHub.Stub.asInterface(
                    ServiceManager.waitForService(aidlServiceName));
            if (proxy == null) {
                Log.e(TAG, "Context Hub AIDL service was declared but was not found");
            }
        } else {
            Log.d(TAG, "Context Hub AIDL service is not declared");
        }
        return proxy;
    }

    /**
     * Attempts to connect to the Contexthub HAL AIDL service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectToAidl() {
        android.hardware.contexthub.IContextHub proxy = maybeConnectToAidlGetProxy();
        return proxy == null ? null : new ContextHubWrapperAidl(proxy);
    }

    /**
     * Calls the appropriate getHubs function depending on the HAL version.
     */
    public abstract Pair<List<ContextHubInfo>, List<String>> getHubs() throws RemoteException;

    /**
     * @return True if this version of the Contexthub HAL supports Location setting notifications.
     */
    public abstract boolean supportsLocationSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a user Location setting change.
     *
     * @param enabled True if the Location setting has been enabled.
     */
    public abstract void onLocationSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports WiFi availability setting
     * notifications.
     */
    public abstract boolean supportsWifiSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a user WiFi availability setting change.
     *
     * @param enabled true if the WiFi availability setting has been enabled.
     */
    public abstract void onWifiSettingChanged(boolean enabled);

    /**
     * Notifies the Contexthub implementation of a user WiFi main setting change.
     *
     * @param enabled true if the WiFi main setting has been enabled.
     */
    public abstract void onWifiMainSettingChanged(boolean enabled);

    /**
     * Notifies the Contexthub implementation of a user WiFi scanning setting change.
     *
     * @param enabled true if the WiFi scanning setting has been enabled.
     */
    public abstract void onWifiScanningSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports airplane mode setting
     * notifications.
     */
    public abstract boolean supportsAirplaneModeSettingNotifications();

    /**
     * Notifies the Contexthub implementation of an airplane mode setting change.
     *
     * @param enabled true if the airplane mode setting has been enabled.
     */
    public abstract void onAirplaneModeSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports microphone setting
     * notifications.
     */
    public abstract boolean supportsMicrophoneSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a microphone setting change.
     */
    public abstract void onMicrophoneSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports BT availability setting
     * notifications.
     */
    public abstract boolean supportsBtSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a BT main setting change.
     */
    public abstract void onBtMainSettingChanged(boolean enabled);

    /**
     * Notifies the Contexthub implementation of a BT scanning setting change.
     */
    public abstract void onBtScanningSettingChanged(boolean enabled);

    /**
     * Invoked whenever a host client connects with the framework.
     *
     * @param info The host endpoint info.
     */
    public void onHostEndpointConnected(HostEndpointInfo info) {}

    /**
     * Invoked whenever a host client disconnects from the framework.
     *
     * @param hostEndpointId The ID of the host endpoint that disconnected.
     */
    public void onHostEndpointDisconnected(short hostEndpointId) {}

    /**
     * Sends a message to the Context Hub.
     *
     * @param hostEndpointId         The host endpoint ID of the sender.
     * @param contextHubId           The ID of the Context Hub to send the message to.
     * @param message                The message to send.
     * @return the result of the message sending.
     */
    @ContextHubTransaction.Result
    public abstract int sendMessageToContextHub(short hostEndpointId, int contextHubId,
            NanoAppMessage message) throws RemoteException;

    /**
     * Sends a transaction status to the Context Hub.
     *
     * @param contextHubId The ID of the context hub to sent the status to.
     * @param status       The status of the transaction.
     * @return the result of the message sending.
     */
    @ContextHubTransaction.Result
    public abstract int sendMessageDeliveryStatusToContextHub(
            int contextHubId, MessageDeliveryStatus status);

    /**
     * Loads a nanoapp on the Context Hub.
     *
     * @param contextHubId  The ID of the Context Hub to load the nanoapp to.
     * @param binary        The nanoapp binary to load.
     * @param transactionId The transaction ID of this load.
     * @return the result of this load transaction.
     */
    @ContextHubTransaction.Result
    public abstract int loadNanoapp(int contextHubId, NanoAppBinary binary,
            int transactionId) throws RemoteException;

    /**
     * Unloads a nanoapp on the Context Hub. Semantics are similar to loadNanoapp().
     */
    @ContextHubTransaction.Result
    public abstract int unloadNanoapp(int contextHubId, long nanoappId,
            int transactionId) throws RemoteException;

    /**
     * Enables a nanoapp on the Context Hub. Semantics are similar to loadNanoapp().
     */
    @ContextHubTransaction.Result
    public abstract int enableNanoapp(int contextHubId, long nanoappId,
            int transactionId) throws RemoteException;

    /**
     * Disables a nanoapp on the Context Hub. Semantics are similar to loadNanoapp().
     */
    @ContextHubTransaction.Result
    public abstract int disableNanoapp(int contextHubId, long nanoappId,
            int transactionId) throws RemoteException;

    /**
     * Queries a list of nanoapp from the Context hub.
     *
     * @param contextHubId The ID of the Context Hub to query.
     * @return the result of this query transaction.
     */
    @ContextHubTransaction.Result
    public abstract int queryNanoapps(int contextHubId) throws RemoteException;

    /**
     * Provides the list of preloaded nanoapp IDs on the system. The output of this API must
     * not change.
     *
     * @param contextHubId  The context Hub ID.
     *
     * @return The list of preloaded nanoapp IDs.
     */
    public abstract long[] getPreloadedNanoappIds(int contextHubId);

    /**
     * Registers a callback with the Context Hub.
     *
     * @param contextHubId The ID of the Context Hub to register the callback with.
     * @param callback     The callback to register.
     */
    public abstract void registerCallback(int contextHubId, @NonNull ICallback callback)
            throws RemoteException;

    /**
     * Registers an existing callback with the Context Hub.
     *
     * @param contextHubId The ID of the Context Hub to register the callback with.
     */
    public abstract void registerExistingCallback(int contextHubId) throws RemoteException;

    /**
     * Puts the context hub in and out of test mode. Test mode is a clean state
     * where tests can be executed in the same environment. If enable is true,
     * this will enable test mode by unloading all nanoapps. If enable is false,
     * this will disable test mode and reverse the actions of enabling test mode
     * by loading all preloaded nanoapps. This puts CHRE in a normal state.
     *
     * This should only be used for a test environment, either through a
     * @TestApi or development tools. This should not be used in a production
     * environment.
     *
     * @param enable If true, put the context hub in test mode. If false, disable
     *               test mode.
     * @return       If true, the operation was successful; false otherwise.
     */
    public abstract boolean setTestMode(boolean enable);

    private static class ContextHubWrapperAidl extends IContextHubWrapper
            implements IBinder.DeathRecipient {
        private android.hardware.contexthub.IContextHub mHub;

        private final Map<Integer, ContextHubAidlCallback> mAidlCallbackMap =
                    new HashMap<>();

        private Runnable mHandleServiceRestartCallback = null;

        // Use this thread in case where the execution requires to be on a service thread.
        // For instance, AppOpsManager.noteOp requires the UPDATE_APP_OPS_STATS permission.
        private HandlerThread mHandlerThread =
                new HandlerThread("Context Hub AIDL callback", Process.THREAD_PRIORITY_BACKGROUND);
        private Handler mHandler;

        private class ContextHubAidlCallback extends
                android.hardware.contexthub.IContextHubCallback.Stub {
            private final int mContextHubId;
            private final ICallback mCallback;
            // 9a17008d-6bf1-445a-9011-6d21bd985b6c
            private static final byte[] UUID = {-102, 23, 0, -115, 107, -15, 68, 90,
                                                -112, 17, 109, 33, -67, -104, 91, 108};
            private static final String NAME = "ContextHubService";

            ContextHubAidlCallback(int contextHubId, ICallback callback) {
                mContextHubId = contextHubId;
                mCallback = callback;
            }

            public void handleNanoappInfo(android.hardware.contexthub.NanoappInfo[] appInfo) {
                List<NanoAppState> nanoAppStateList =
                        ContextHubServiceUtil.createNanoAppStateList(appInfo);
                mHandler.post(() -> {
                    mCallback.handleNanoappInfo(nanoAppStateList);
                });
            }

            public void handleContextHubMessage(android.hardware.contexthub.ContextHubMessage msg,
                    String[] msgContentPerms) {
                mHandler.post(() -> {
                    mCallback.handleNanoappMessage((short) msg.hostEndPoint,
                            ContextHubServiceUtil.createNanoAppMessage(msg),
                            new ArrayList<>(Arrays.asList(msg.permissions)),
                            new ArrayList<>(Arrays.asList(msgContentPerms)));
                });
            }

            public void handleContextHubAsyncEvent(int evt) {
                mHandler.post(() -> {
                    mCallback.handleContextHubEvent(
                            ContextHubServiceUtil.toContextHubEventFromAidl(evt));
                });
            }

            public void handleTransactionResult(int transactionId, boolean success) {
                mHandler.post(() -> {
                    mCallback.handleTransactionResult(transactionId, success);
                });
            }

            public void handleNanSessionRequest(NanSessionRequest request) {
                // TODO(271471342): Implement
            }

            public void handleMessageDeliveryStatus(
                    char hostEndpointId,
                    MessageDeliveryStatus messageDeliveryStatus) {
                if (Flags.reliableMessageImplementation()) {
                    mHandler.post(() -> {
                        mCallback.handleMessageDeliveryStatus(messageDeliveryStatus);
                    });
                } else {
                    Log.w(TAG, "handleMessageDeliveryStatus called when the "
                            + "reliableMessageImplementation flag is disabled");
                }
            }

            public byte[] getUuid() {
                return UUID;
            }

            public String getName() {
                return NAME;
            }

            @Override
            public String getInterfaceHash() {
                return android.hardware.contexthub.IContextHubCallback.HASH;
            }

            @Override
            public int getInterfaceVersion() {
                return android.hardware.contexthub.IContextHubCallback.VERSION;
            }
        }

        ContextHubWrapperAidl(android.hardware.contexthub.IContextHub hub) {
            setHub(hub);
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
            linkWrapperToHubDeath();
        }

        private synchronized android.hardware.contexthub.IContextHub getHub() {
            return mHub;
        }

        private synchronized void setHub(android.hardware.contexthub.IContextHub hub) {
            mHub = hub;
        }

        @Override
        public void binderDied() {
            Log.i(TAG, "Context Hub AIDL HAL died");

            setHub(maybeConnectToAidlGetProxy());
            if (getHub() == null) {
                // TODO(b/256860015): Make this reconnection more robust
                Log.e(TAG, "Could not reconnect to Context Hub AIDL HAL");
                return;
            }
            linkWrapperToHubDeath();

            if (mHandleServiceRestartCallback != null) {
                mHandleServiceRestartCallback.run();
            } else {
                Log.e(TAG, "mHandleServiceRestartCallback is not set");
            }
        }

        public Pair<List<ContextHubInfo>, List<String>> getHubs() throws RemoteException {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return new Pair<List<ContextHubInfo>, List<String>>(new ArrayList<ContextHubInfo>(),
                        new ArrayList<String>());
            }

            Set<String> supportedPermissions = new HashSet<>();
            ArrayList<ContextHubInfo> hubInfoList = new ArrayList<>();
            for (android.hardware.contexthub.ContextHubInfo hubInfo : hub.getContextHubs()) {
                hubInfoList.add(new ContextHubInfo(hubInfo));
                for (String permission : hubInfo.supportedPermissions) {
                    supportedPermissions.add(permission);
                }
            }
            return new Pair(hubInfoList, new ArrayList<String>(supportedPermissions));
        }

        public boolean supportsLocationSettingNotifications() {
            return true;
        }

        public boolean supportsWifiSettingNotifications() {
            return true;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return true;
        }

        public boolean supportsMicrophoneSettingNotifications() {
            return true;
        }

        public boolean supportsBtSettingNotifications() {
            return true;
        }

        public void onLocationSettingChanged(boolean enabled) {
            onSettingChanged(android.hardware.contexthub.Setting.LOCATION, enabled);
        }

        public void onWifiSettingChanged(boolean enabled) {
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
            onSettingChanged(android.hardware.contexthub.Setting.AIRPLANE_MODE, enabled);
        }

        public void onMicrophoneSettingChanged(boolean enabled) {
            onSettingChanged(android.hardware.contexthub.Setting.MICROPHONE, enabled);
        }

        public void onWifiMainSettingChanged(boolean enabled) {
            onSettingChanged(android.hardware.contexthub.Setting.WIFI_MAIN, enabled);
        }

        public void onWifiScanningSettingChanged(boolean enabled) {
            onSettingChanged(android.hardware.contexthub.Setting.WIFI_SCANNING, enabled);
        }

        public void onBtMainSettingChanged(boolean enabled) {
            onSettingChanged(android.hardware.contexthub.Setting.BT_MAIN, enabled);
        }

        public void onBtScanningSettingChanged(boolean enabled) {
            onSettingChanged(android.hardware.contexthub.Setting.BT_SCANNING, enabled);
        }

        @Override
        public void onHostEndpointConnected(HostEndpointInfo info) {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return;
            }

            try {
                hub.onHostEndpointConnected(info);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Exception in onHostEndpointConnected" + e.getMessage());
            }
        }

        @Override
        public void onHostEndpointDisconnected(short hostEndpointId) {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return;
            }

            try {
                hub.onHostEndpointDisconnected((char) hostEndpointId);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Exception in onHostEndpointDisconnected" + e.getMessage());
            }
        }

        @ContextHubTransaction.Result
        public int sendMessageToContextHub(short hostEndpointId, int contextHubId,
                NanoAppMessage message) throws RemoteException {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }

            try {
                var msg = ContextHubServiceUtil.createAidlContextHubMessage(
                        hostEndpointId, message);
                hub.sendMessageToHub(contextHubId, msg);
                return ContextHubTransaction.RESULT_SUCCESS;
            } catch (RemoteException | ServiceSpecificException e) {
                return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
            } catch (IllegalArgumentException e) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }
        }

        @ContextHubTransaction.Result
        public int sendMessageDeliveryStatusToContextHub(int contextHubId,
                MessageDeliveryStatus status) {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }

            try {
                hub.sendMessageDeliveryStatusToHub(contextHubId, status);
                return ContextHubTransaction.RESULT_SUCCESS;
            } catch (RemoteException | ServiceSpecificException e) {
                return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
            } catch (IllegalArgumentException e) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }
        }

        @ContextHubTransaction.Result
        public int loadNanoapp(int contextHubId, NanoAppBinary binary,
                int transactionId) throws RemoteException {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }

            android.hardware.contexthub.NanoappBinary aidlNanoAppBinary =
                    ContextHubServiceUtil.createAidlNanoAppBinary(binary);
            try {
                hub.loadNanoapp(contextHubId, aidlNanoAppBinary, transactionId);
                return ContextHubTransaction.RESULT_SUCCESS;
            } catch (RemoteException | ServiceSpecificException | UnsupportedOperationException e) {
                return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
            } catch (IllegalArgumentException e) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }
        }

        @ContextHubTransaction.Result
        public int unloadNanoapp(int contextHubId, long nanoappId, int transactionId)
                throws RemoteException {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }

            try {
                hub.unloadNanoapp(contextHubId, nanoappId, transactionId);
                return ContextHubTransaction.RESULT_SUCCESS;
            } catch (RemoteException | ServiceSpecificException | UnsupportedOperationException e) {
                return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
            } catch (IllegalArgumentException e) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }
        }

        @ContextHubTransaction.Result
        public int enableNanoapp(int contextHubId, long nanoappId, int transactionId)
                throws RemoteException {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }

            try {
                hub.enableNanoapp(contextHubId, nanoappId, transactionId);
                return ContextHubTransaction.RESULT_SUCCESS;
            } catch (RemoteException | ServiceSpecificException | UnsupportedOperationException e) {
                return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
            } catch (IllegalArgumentException e) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }
        }

        @ContextHubTransaction.Result
        public int disableNanoapp(int contextHubId, long nanoappId, int transactionId)
                throws RemoteException {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }

            try {
                hub.disableNanoapp(contextHubId, nanoappId, transactionId);
                return ContextHubTransaction.RESULT_SUCCESS;
            } catch (RemoteException | ServiceSpecificException | UnsupportedOperationException e) {
                return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
            } catch (IllegalArgumentException e) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }
        }

        @ContextHubTransaction.Result
        public int queryNanoapps(int contextHubId) throws RemoteException {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }

            try {
                hub.queryNanoapps(contextHubId);
                return ContextHubTransaction.RESULT_SUCCESS;
            } catch (RemoteException | ServiceSpecificException | UnsupportedOperationException e) {
                return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
            } catch (IllegalArgumentException e) {
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }
        }

        public long[] getPreloadedNanoappIds(int contextHubId) {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return null;
            }

            try {
                return hub.getPreloadedNanoappIds(contextHubId);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while getting preloaded nanoapp IDs: " + e.getMessage());
                return null;
            }
        }

        public void registerExistingCallback(int contextHubId) {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return;
            }

            ContextHubAidlCallback callback = mAidlCallbackMap.get(contextHubId);
            if (callback == null) {
                Log.e(TAG, "Could not find existing callback to register for context hub ID = "
                        + contextHubId);
                return;
            }

            try {
                hub.registerCallback(contextHubId, callback);
            } catch (RemoteException | ServiceSpecificException | IllegalArgumentException e) {
                Log.e(TAG, "Exception while registering callback: " + e.getMessage());
            }
        }

        public void registerCallback(int contextHubId, ICallback callback) {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return;
            }

            mHandleServiceRestartCallback = callback::handleServiceRestart;
            mAidlCallbackMap.put(contextHubId, new ContextHubAidlCallback(contextHubId, callback));
            registerExistingCallback(contextHubId);
        }

        public boolean setTestMode(boolean enable) {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return false;
            }

            try {
                hub.setTestMode(enable);
                return true;
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Exception while setting test mode (enable: "
                        + (enable ? "true" : "false") + "): " + e.getMessage());
                return false;
            }
        }

        private void onSettingChanged(byte setting, boolean enabled) {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return;
            }

            try {
                hub.onSettingChanged(setting, enabled);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Exception while sending setting update: " + e.getMessage());
            }
        }

        /**
         * Links the mHub death handler to this
         */
        private void linkWrapperToHubDeath() {
            android.hardware.contexthub.IContextHub hub = getHub();
            if (hub == null) {
                return;
            }

            try {
                hub.asBinder().linkToDeath(this, 0);
            } catch (RemoteException exception) {
                Log.e(TAG, "Context Hub AIDL service death receipt could not be linked");
            }
        }
    }

    /**
     * An abstract call that defines methods common to all HIDL IContextHubWrappers.
     */
    private abstract static class ContextHubWrapperHidl extends IContextHubWrapper {
        private android.hardware.contexthub.V1_0.IContexthub mHub;

        protected ICallback mCallback = null;

        protected final Map<Integer, ContextHubWrapperHidlCallback> mHidlCallbackMap =
                    new HashMap<>();

        protected class ContextHubWrapperHidlCallback extends IContexthubCallback.Stub {
            private final int mContextHubId;
            private final ICallback mCallback;

            ContextHubWrapperHidlCallback(int contextHubId, ICallback callback) {
                mContextHubId = contextHubId;
                mCallback = callback;
            }

            @Override
            public void handleClientMsg(ContextHubMsg message) {
                mCallback.handleNanoappMessage(message.hostEndPoint,
                        ContextHubServiceUtil.createNanoAppMessage(message),
                        Collections.emptyList() /* nanoappPermissions */,
                        Collections.emptyList() /* messagePermissions */);
            }

            @Override
            public void handleTxnResult(int transactionId, int result) {
                mCallback.handleTransactionResult(transactionId,
                        result == TransactionResult.SUCCESS);
            }

            @Override
            public void handleHubEvent(int eventType) {
                mCallback.handleContextHubEvent(
                        ContextHubServiceUtil.toContextHubEvent(eventType));
            }

            @Override
            public void handleAppAbort(long nanoAppId, int abortCode) {
                mCallback.handleNanoappAbort(nanoAppId, abortCode);
            }

            @Override
            public void handleAppsInfo(
                    ArrayList<android.hardware.contexthub.V1_0.HubAppInfo> nanoAppInfoList) {
                handleAppsInfo_1_2(ContextHubServiceUtil.toHubAppInfo_1_2(nanoAppInfoList));
            }

            @Override
            public void handleClientMsg_1_2(android.hardware.contexthub.V1_2.ContextHubMsg message,
                    ArrayList<String> messagePermissions) {
                mCallback.handleNanoappMessage(message.msg_1_0.hostEndPoint,
                        ContextHubServiceUtil.createNanoAppMessage(message.msg_1_0),
                        message.permissions, messagePermissions);
            }

            @Override
            public void handleAppsInfo_1_2(ArrayList<HubAppInfo> nanoAppInfoList) {
                List<NanoAppState> nanoAppStateList =
                        ContextHubServiceUtil.createNanoAppStateList(nanoAppInfoList);
                mCallback.handleNanoappInfo(nanoAppStateList);
            }
        }

        ContextHubWrapperHidl(android.hardware.contexthub.V1_0.IContexthub hub) {
            mHub = hub;
        }

        @ContextHubTransaction.Result
        public int sendMessageToContextHub(short hostEndpointId, int contextHubId,
                NanoAppMessage message) throws RemoteException {
            if (message.isReliable()) {
                Log.e(TAG, "Reliable messages are only supported with the AIDL HAL");
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            }
            ContextHubMsg messageToNanoApp =
                    ContextHubServiceUtil.createHidlContextHubMessage(hostEndpointId, message);
            return ContextHubServiceUtil.toTransactionResult(
                    mHub.sendMessageToHub(contextHubId, messageToNanoApp));
        }

        @ContextHubTransaction.Result
        public int sendMessageDeliveryStatusToContextHub(int contextHubId,
                MessageDeliveryStatus status) {
            // Only supported on the AIDL implementation.
            return ContextHubTransaction.RESULT_FAILED_NOT_SUPPORTED;
        }

        @ContextHubTransaction.Result
        public int loadNanoapp(int contextHubId, NanoAppBinary binary,
                int transactionId) throws RemoteException {
            android.hardware.contexthub.V1_0.NanoAppBinary hidlNanoAppBinary =
                    ContextHubServiceUtil.createHidlNanoAppBinary(binary);
            return ContextHubServiceUtil.toTransactionResult(mHub.loadNanoApp(
                    contextHubId, hidlNanoAppBinary, transactionId));
        }

        @ContextHubTransaction.Result
        public int unloadNanoapp(int contextHubId, long nanoappId, int transactionId)
                throws RemoteException {
            return ContextHubServiceUtil.toTransactionResult(mHub.unloadNanoApp(
                    contextHubId, nanoappId, transactionId));
        }

        @ContextHubTransaction.Result
        public int enableNanoapp(int contextHubId, long nanoappId, int transactionId)
                throws RemoteException {
            return ContextHubServiceUtil.toTransactionResult(mHub.enableNanoApp(
                    contextHubId, nanoappId, transactionId));
        }

        @ContextHubTransaction.Result
        public int disableNanoapp(int contextHubId, long nanoappId, int transactionId)
                throws RemoteException {
            return ContextHubServiceUtil.toTransactionResult(mHub.disableNanoApp(
                    contextHubId, nanoappId, transactionId));
        }

        @ContextHubTransaction.Result
        public int queryNanoapps(int contextHubId) throws RemoteException {
            return ContextHubServiceUtil.toTransactionResult(
                    mHub.queryApps(contextHubId));
        }

        public long[] getPreloadedNanoappIds(int contextHubId) {
            return new long[0];
        }

        public void registerCallback(int contextHubId, ICallback callback) throws RemoteException {
            mHidlCallbackMap.put(contextHubId,
                        new ContextHubWrapperHidlCallback(contextHubId, callback));
            mHub.registerCallback(contextHubId, mHidlCallbackMap.get(contextHubId));
        }

        public void registerExistingCallback(int contextHubId) throws RemoteException {
            ContextHubWrapperHidlCallback callback = mHidlCallbackMap.get(contextHubId);
            if (callback == null) {
                Log.e(TAG, "Could not find existing callback for context hub with ID = "
                        + contextHubId);
                return;
            }

            mHub.registerCallback(contextHubId, callback);
        }

        public boolean setTestMode(boolean enable) {
            return false;
        }

        public boolean supportsBtSettingNotifications() {
            return false;
        }

        public void onWifiMainSettingChanged(boolean enabled) {}
        public void onWifiScanningSettingChanged(boolean enabled) {}
        public void onBtMainSettingChanged(boolean enabled) {}
        public void onBtScanningSettingChanged(boolean enabled) {}
    }

    private static class ContextHubWrapperV1_0 extends ContextHubWrapperHidl {
        private android.hardware.contexthub.V1_0.IContexthub mHub;

        ContextHubWrapperV1_0(android.hardware.contexthub.V1_0.IContexthub hub) {
            super(hub);
            mHub = hub;
        }

        public Pair<List<ContextHubInfo>, List<String>> getHubs() throws RemoteException {
            ArrayList<ContextHubInfo> hubInfoList = new ArrayList<>();
            for (ContextHub hub : mHub.getHubs()) {
                hubInfoList.add(new ContextHubInfo(hub));
            }
            return new Pair(hubInfoList, new ArrayList<String>());
        }

        public boolean supportsLocationSettingNotifications() {
            return false;
        }

        public boolean supportsWifiSettingNotifications() {
            return false;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return false;
        }

        public boolean supportsMicrophoneSettingNotifications() {
            return false;
        }

        public void onLocationSettingChanged(boolean enabled) {
        }

        public void onWifiSettingChanged(boolean enabled) {
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
        }

        public void onMicrophoneSettingChanged(boolean enabled) {
        }
    }

    private static class ContextHubWrapperV1_1 extends ContextHubWrapperHidl {
        private android.hardware.contexthub.V1_1.IContexthub mHub;

        ContextHubWrapperV1_1(android.hardware.contexthub.V1_1.IContexthub hub) {
            super(hub);
            mHub = hub;
        }

        public Pair<List<ContextHubInfo>, List<String>> getHubs() throws RemoteException {
            ArrayList<ContextHubInfo> hubInfoList = new ArrayList<>();
            for (ContextHub hub : mHub.getHubs()) {
                hubInfoList.add(new ContextHubInfo(hub));
            }
            return new Pair(hubInfoList, new ArrayList<String>());
        }

        public boolean supportsLocationSettingNotifications() {
            return true;
        }

        public boolean supportsWifiSettingNotifications() {
            return false;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return false;
        }

        public boolean supportsMicrophoneSettingNotifications() {
            return false;
        }

        public void onLocationSettingChanged(boolean enabled) {
            try {
                mHub.onSettingChanged(Setting.LOCATION,
                        enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send setting change to Contexthub", e);
            }
        }

        public void onWifiSettingChanged(boolean enabled) {
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
        }

        public void onMicrophoneSettingChanged(boolean enabled) {
        }
    }

    private static class ContextHubWrapperV1_2 extends ContextHubWrapperHidl
            implements android.hardware.contexthub.V1_2.IContexthub.getHubs_1_2Callback {
        private final android.hardware.contexthub.V1_2.IContexthub mHub;

        private Pair<List<ContextHubInfo>, List<String>> mHubInfo =
                new Pair<>(Collections.emptyList(), Collections.emptyList());

        ContextHubWrapperV1_2(android.hardware.contexthub.V1_2.IContexthub hub) {
            super(hub);
            mHub = hub;
        }

        @Override
        public void onValues(ArrayList<ContextHub> hubs, ArrayList<String> supportedPermissions) {
            ArrayList<ContextHubInfo> hubInfoList = new ArrayList<>();
            for (ContextHub hub : hubs) {
                hubInfoList.add(new ContextHubInfo(hub));
            }
            mHubInfo = new Pair(hubInfoList, supportedPermissions);
        }

        public Pair<List<ContextHubInfo>, List<String>> getHubs() throws RemoteException {
            mHub.getHubs_1_2(this);
            return mHubInfo;
        }

        public boolean supportsLocationSettingNotifications() {
            return true;
        }

        public boolean supportsWifiSettingNotifications() {
            return true;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return true;
        }

        public boolean supportsMicrophoneSettingNotifications() {
            return true;
        }

        public void onLocationSettingChanged(boolean enabled) {
            sendSettingChanged(Setting.LOCATION,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onWifiSettingChanged(boolean enabled) {
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.WIFI_AVAILABLE,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.AIRPLANE_MODE,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onMicrophoneSettingChanged(boolean enabled) {
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.MICROPHONE,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void registerCallback(int contextHubId, ICallback callback) throws RemoteException {
            mHidlCallbackMap.put(contextHubId,
                        new ContextHubWrapperHidlCallback(contextHubId, callback));
            mHub.registerCallback_1_2(contextHubId, mHidlCallbackMap.get(contextHubId));
        }

        private void sendSettingChanged(byte setting, byte newValue) {
            try {
                mHub.onSettingChanged_1_2(setting, newValue);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send setting change to Contexthub", e);
            }
        }
    }
}
