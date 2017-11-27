/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.location;

import android.content.Context;
import android.hardware.contexthub.V1_0.AsyncEventType;
import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.HubAppInfo;
import android.hardware.contexthub.V1_0.IContexthub;
import android.hardware.contexthub.V1_0.IContexthubCallback;
import android.hardware.contexthub.V1_0.Result;
import android.hardware.contexthub.V1_0.TransactionResult;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubMessage;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubCallback;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.IContextHubService;
import android.hardware.location.IContextHubTransactionCallback;
import android.hardware.location.NanoApp;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppFilter;
import android.hardware.location.NanoAppInstanceInfo;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public class ContextHubService extends IContextHubService.Stub {
    private static final String TAG = "ContextHubService";

    /*
     * Constants for the type of transaction that is defined by ContextHubService.
     * This is used to report the transaction callback to clients, and is different from
     * ContextHubTransaction.Type.
     */
    public static final int MSG_ENABLE_NANO_APP = 1;
    public static final int MSG_DISABLE_NANO_APP = 2;
    public static final int MSG_LOAD_NANO_APP = 3;
    public static final int MSG_UNLOAD_NANO_APP = 4;
    public static final int MSG_QUERY_NANO_APPS = 5;
    public static final int MSG_QUERY_MEMORY = 6;
    public static final int MSG_HUB_RESET = 7;

    private static final String PRE_LOADED_GENERIC_UNKNOWN = "Preloaded app, unknown";
    private static final String PRE_LOADED_APP_NAME = PRE_LOADED_GENERIC_UNKNOWN;
    private static final String PRE_LOADED_APP_PUBLISHER = PRE_LOADED_GENERIC_UNKNOWN;
    private static final int PRE_LOADED_APP_MEM_REQ = 0;

    private static final int OS_APP_INSTANCE = -1;

    private final Context mContext;

    // TODO(b/69270990): Remove once old ContextHubManager API is deprecated
    // Service cache maintaining of instance ID to nanoapp infos
    private final ConcurrentHashMap<Integer, NanoAppInstanceInfo> mNanoAppHash =
            new ConcurrentHashMap<>();
    // The next available instance ID (managed by the service) to assign to a nanoapp
    private int mNextAvailableInstanceId = 0;
    // A map of the long nanoapp ID to instance ID managed by the service
    private final ConcurrentHashMap<Long, Integer> mNanoAppIdToInstanceMap =
            new ConcurrentHashMap<>();

    private final ContextHubInfo[] mContextHubInfo;
    private final RemoteCallbackList<IContextHubCallback> mCallbacksList =
            new RemoteCallbackList<>();

    // Proxy object to communicate with the Context Hub HAL
    private final IContexthub mContextHubProxy;

    // The manager for transaction queue
    private final ContextHubTransactionManager mTransactionManager;

    // The manager for sending messages to/from clients
    private final ContextHubClientManager mClientManager;

    // The default client for old API clients
    private final Map<Integer, IContextHubClient> mDefaultClientMap;

    /**
     * Class extending the callback to register with a Context Hub.
     */
    private class ContextHubServiceCallback extends IContexthubCallback.Stub {
        private final int mContextHubId;

        ContextHubServiceCallback(int contextHubId) {
            mContextHubId = contextHubId;
        }

        @Override
        public void handleClientMsg(ContextHubMsg message) {
            handleClientMessageCallback(mContextHubId, message);
        }

        @Override
        public void handleTxnResult(int transactionId, int result) {
            handleTransactionResultCallback(mContextHubId, transactionId, result);
        }

        @Override
        public void handleHubEvent(int eventType) {
            handleHubEventCallback(mContextHubId, eventType);
        }

        @Override
        public void handleAppAbort(long nanoAppId, int abortCode) {
            handleAppAbortCallback(mContextHubId, nanoAppId, abortCode);
        }

        @Override
        public void handleAppsInfo(ArrayList<HubAppInfo> nanoAppInfoList) {
            handleQueryAppsCallback(mContextHubId, nanoAppInfoList);
        }
    }

    public ContextHubService(Context context) {
        mContext = context;

        mContextHubProxy = getContextHubProxy();
        if (mContextHubProxy == null) {
            mTransactionManager = null;
            mClientManager = null;
            mDefaultClientMap = Collections.EMPTY_MAP;
            mContextHubInfo = new ContextHubInfo[0];
            return;
        }

        mClientManager = new ContextHubClientManager(mContext, mContextHubProxy);
        mTransactionManager = new ContextHubTransactionManager(mContextHubProxy, mClientManager);

        List<ContextHub> hubList;
        try {
            hubList = mContextHubProxy.getHubs();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting Context Hub info", e);
            hubList = Collections.emptyList();
        }
        mContextHubInfo = ContextHubServiceUtil.createContextHubInfoArray(hubList);

        HashMap<Integer, IContextHubClient> defaultClientMap = new HashMap<>();
        for (ContextHubInfo contextHubInfo : mContextHubInfo) {
            int contextHubId = contextHubInfo.getId();

            IContextHubClient client = mClientManager.registerClient(
                    createDefaultClientCallback(contextHubId), contextHubId);
            defaultClientMap.put(contextHubId, client);
        }
        mDefaultClientMap = Collections.unmodifiableMap(defaultClientMap);

        for (ContextHubInfo contextHubInfo : mContextHubInfo) {
            int contextHubId = contextHubInfo.getId();
            try {
                mContextHubProxy.registerCallback(
                        contextHubId, new ContextHubServiceCallback(contextHubId));
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while registering service callback for hub (ID = "
                        + contextHubId + ")", e);
            }
        }

        // Do a query to initialize the service cache list of nanoapps
        // TODO(b/69270990): Remove this when old API is deprecated
        for (ContextHubInfo contextHubInfo : mContextHubInfo) {
            queryNanoAppsInternal(contextHubInfo.getId());
        }

        for (int i = 0; i < mContextHubInfo.length; i++) {
            Log.d(TAG, "ContextHub[" + i + "] id: " + mContextHubInfo[i].getId()
                    + ", name:  " + mContextHubInfo[i].getName());
        }
    }

    /**
     * Creates a default client callback for old API clients.
     *
     * @param contextHubId the ID of the hub to attach this client to
     * @return the internal callback interface
     */
    private IContextHubClientCallback createDefaultClientCallback(int contextHubId) {
        return new IContextHubClientCallback.Stub() {
            @Override
            public void onMessageFromNanoApp(NanoAppMessage message) {
                int nanoAppInstanceId =
                        mNanoAppIdToInstanceMap.containsKey(message.getNanoAppId()) ?
                        mNanoAppIdToInstanceMap.get(message.getNanoAppId()) : -1;

                onMessageReceiptOldApi(
                        message.getMessageType(), contextHubId, nanoAppInstanceId,
                        message.getMessageBody());
            }

            @Override
            public void onHubReset() {
                byte[] data = {TransactionResult.SUCCESS};
                onMessageReceiptOldApi(MSG_HUB_RESET, contextHubId, OS_APP_INSTANCE, data);
            }

            @Override
            public void onNanoAppAborted(long nanoAppId, int abortCode) {
            }

            @Override
            public void onNanoAppLoaded(long nanoAppId) {
            }

            @Override
            public void onNanoAppUnloaded(long nanoAppId) {
            }

            @Override
            public void onNanoAppEnabled(long nanoAppId) {
            }

            @Override
            public void onNanoAppDisabled(long nanoAppId) {
            }
        };
    }

    /**
     * @return the IContexthub proxy interface
     */
    private IContexthub getContextHubProxy() {
        IContexthub proxy = null;
        try {
            proxy = IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return proxy;
    }

    @Override
    public int registerCallback(IContextHubCallback callback) throws RemoteException {
        checkPermissions();
        mCallbacksList.register(callback);

        Log.d(TAG, "Added callback, total callbacks " +
                mCallbacksList.getRegisteredCallbackCount());
        return 0;
    }

    @Override
    public int[] getContextHubHandles() throws RemoteException {
        checkPermissions();
        int[] returnArray = new int[mContextHubInfo.length];

        Log.d(TAG, "System supports " + returnArray.length + " hubs");
        for (int i = 0; i < returnArray.length; ++i) {
            returnArray[i] = i;
            Log.d(TAG, String.format("Hub %s is mapped to %d",
                    mContextHubInfo[i].getName(), returnArray[i]));
        }

        return returnArray;
    }

    @Override
    public ContextHubInfo getContextHubInfo(int contextHubId) throws RemoteException {
        checkPermissions();
        if (!(contextHubId >= 0 && contextHubId < mContextHubInfo.length)) {
            Log.e(TAG, "Invalid context hub handle " + contextHubId);
            return null; // null means fail
        }

        return mContextHubInfo[contextHubId];
    }

    /**
     * Creates an internal load transaction callback to be used for old API clients
     *
     * @param contextHubId  the ID of the hub to load the binary
     * @param nanoAppBinary the binary to load
     * @return the callback interface
     */
    private IContextHubTransactionCallback createLoadTransactionCallback(
            int contextHubId, NanoAppBinary nanoAppBinary) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onTransactionComplete(int result) {
                handleLoadResponseOldApi(contextHubId, result, nanoAppBinary);
            }

            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
            }
        };
    }

    /**
     * Creates an internal unload transaction callback to be used for old API clients
     *
     * @param contextHubId the ID of the hub to unload the nanoapp
     * @param nanoAppId    the ID of the nanoapp to unload
     * @return the callback interface
     */
    private IContextHubTransactionCallback createUnloadTransactionCallback(
            int contextHubId, long nanoAppId) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onTransactionComplete(int result) {
                handleUnloadResponseOldApi(contextHubId, result, nanoAppId);
            }

            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
            }
        };
    }

    /**
     * Creates an internal query transaction callback to be used for old API clients
     *
     * @param contextHubId the ID of the hub to query
     * @return the callback interface
     */
    private IContextHubTransactionCallback createQueryTransactionCallback(int contextHubId) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onTransactionComplete(int result) {
            }

            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
                byte[] data = {(byte) result};
                onMessageReceiptOldApi(MSG_QUERY_NANO_APPS, contextHubId, OS_APP_INSTANCE, data);
            }
        };
    }

    /**
     * Adds a new transaction to the transaction manager queue
     *
     * @param transaction the transaction to add
     * @return the result of adding the transaction
     */
    private int addTransaction(ContextHubServiceTransaction transaction) {
        int result = Result.OK;
        try {
            mTransactionManager.addTransaction(transaction);
        } catch (IllegalStateException e) {
            Log.e(TAG, e.getMessage());
            result = Result.TRANSACTION_PENDING; /* failed */
        }

        return result;
    }

    @Override
    public int loadNanoApp(int contextHubId, NanoApp app) throws RemoteException {
        checkPermissions();
        if (mContextHubProxy == null) {
            return -1;
        }

        if (!(contextHubId >= 0 && contextHubId < mContextHubInfo.length)) {
            Log.e(TAG, "Invalid contextHubhandle " + contextHubId);
            return -1;
        }
        if (app == null) {
            Log.e(TAG, "Invalid null app");
            return -1;
        }

        // Create an internal IContextHubTransactionCallback for the old API clients
        NanoAppBinary nanoAppBinary = new NanoAppBinary(app.getAppBinary());
        IContextHubTransactionCallback onCompleteCallback =
                createLoadTransactionCallback(contextHubId, nanoAppBinary);

        ContextHubServiceTransaction transaction = mTransactionManager.createLoadTransaction(
                contextHubId, nanoAppBinary, onCompleteCallback);

        int result = addTransaction(transaction);
        if (result != Result.OK) {
            Log.e(TAG, "Failed to load nanoapp with error code " + result);
            return -1;
        }

        // Do not add an entry to mNanoAppInstance Hash yet. The HAL may reject the app
        return 0;
    }

    @Override
    public int unloadNanoApp(int nanoAppInstanceHandle) throws RemoteException {
        checkPermissions();
        if (mContextHubProxy == null) {
            return -1;
        }

        NanoAppInstanceInfo info = mNanoAppHash.get(nanoAppInstanceHandle);
        if (info == null) {
            Log.e(TAG, "Cannot find app with handle " + nanoAppInstanceHandle);
            return -1; //means failed
        }

        int contextHubId = info.getContexthubId();
        long nanoAppId = info.getAppId();
        IContextHubTransactionCallback onCompleteCallback =
                createUnloadTransactionCallback(contextHubId, nanoAppId);
        ContextHubServiceTransaction transaction = mTransactionManager.createUnloadTransaction(
                contextHubId, nanoAppId, onCompleteCallback);

        int result = addTransaction(transaction);
        if (result != Result.OK) {
            Log.e(TAG, "Failed to unload nanoapp with error code " + result);
            return -1;
        }

        // Do not add an entry to mNanoAppInstance Hash yet. The HAL may reject the app
        return 0;
    }

    @Override
    public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppInstanceHandle)
            throws RemoteException {
        checkPermissions();
        // This assumes that all the nanoAppInfo is current. This is reasonable
        // for the use cases for tightly controlled nanoApps.
        if (mNanoAppHash.containsKey(nanoAppInstanceHandle)) {
            return mNanoAppHash.get(nanoAppInstanceHandle);
        } else {
            Log.e(TAG, "Could not find nanoApp with handle " + nanoAppInstanceHandle);
            return null;
        }
    }

    @Override
    public int[] findNanoAppOnHub(int hubHandle, NanoAppFilter filter) throws RemoteException {
        checkPermissions();
        ArrayList<Integer> foundInstances = new ArrayList<Integer>();

        for (Integer nanoAppInstance : mNanoAppHash.keySet()) {
            NanoAppInstanceInfo info = mNanoAppHash.get(nanoAppInstance);

            if (filter.testMatch(info)) {
                foundInstances.add(nanoAppInstance);
            }
        }

        int[] retArray = new int[foundInstances.size()];
        for (int i = 0; i < foundInstances.size(); i++) {
            retArray[i] = foundInstances.get(i).intValue();
        }

        Log.w(TAG, "Found " + retArray.length + " apps on hub handle " + hubHandle);
        return retArray;
    }

    /**
     * Performs a query at the specified hub.
     *
     * This method should only be invoked internally by the service, either to update the service
     * cache or as a result of an explicit query requested by a client through the sendMessage API.
     *
     * @param contextHubId the ID of the hub to do the query
     * @return the result of the query
     */
    private int queryNanoAppsInternal(int contextHubId) {
        if (mContextHubProxy == null) {
            return Result.UNKNOWN_FAILURE;
        }

        IContextHubTransactionCallback onCompleteCallback =
                createQueryTransactionCallback(contextHubId);
        ContextHubServiceTransaction transaction = mTransactionManager.createQueryTransaction(
                contextHubId, onCompleteCallback);

        return addTransaction(transaction);
    }

    @Override
    public int sendMessage(
            int hubHandle, int nanoAppHandle, ContextHubMessage msg) throws RemoteException {
        checkPermissions();
        if (mContextHubProxy == null) {
            return -1;
        }
        if (msg == null) {
            Log.e(TAG, "ContextHubMessage cannot be null");
            return -1;
        }
        if (msg.getData() == null) {
            Log.e(TAG, "ContextHubMessage message body cannot be null");
            return -1;
        }
        if (!mDefaultClientMap.containsKey(hubHandle)) {
            Log.e(TAG, "Hub with ID " + hubHandle + " does not exist");
            return -1;
        }

        boolean success = false;
        if (nanoAppHandle == OS_APP_INSTANCE) {
            if (msg.getMsgType() == MSG_QUERY_NANO_APPS) {
                success = (queryNanoAppsInternal(hubHandle) == Result.OK);
            } else {
                Log.e(TAG, "Invalid OS message params of type " + msg.getMsgType());
            }
        } else {
            NanoAppInstanceInfo info = getNanoAppInstanceInfo(nanoAppHandle);
            if (info != null) {
                NanoAppMessage message = NanoAppMessage.createMessageToNanoApp(
                        info.getAppId(), msg.getMsgType(), msg.getData());

                IContextHubClient client = mDefaultClientMap.get(hubHandle);
                success = (client.sendMessageToNanoApp(message) ==
                        ContextHubTransaction.TRANSACTION_SUCCESS);
            } else {
                Log.e(TAG, "Failed to send nanoapp message - nanoapp with instance ID "
                        + nanoAppHandle + " does not exist.");
            }
        }

        return success ? 0 : -1;
    }

    /**
     * Handles a unicast or broadcast message from a nanoapp.
     *
     * @param contextHubId the ID of the hub the message came from
     * @param message      the message contents
     */
    private void handleClientMessageCallback(int contextHubId, ContextHubMsg message) {
        mClientManager.onMessageFromNanoApp(contextHubId, message);
    }

    /**
     * A helper function to handle a load response from the Context Hub for the old API.
     *
     * TODO(b/69270990): Remove this once the old APIs are obsolete.
     */
    private void handleLoadResponseOldApi(
            int contextHubId, int result, NanoAppBinary nanoAppBinary) {
        if (nanoAppBinary == null) {
            Log.e(TAG, "Nanoapp binary field was null for a load transaction");
            return;
        }

        // NOTE: The legacy JNI code used to do a query right after a load success
        // to synchronize the service cache. Instead store the binary that was requested to
        // load to update the cache later without doing a query.
        int instanceId = 0;
        long nanoAppId = nanoAppBinary.getNanoAppId();
        int nanoAppVersion = nanoAppBinary.getNanoAppVersion();
        if (result == TransactionResult.SUCCESS) {
            if (mNanoAppIdToInstanceMap.containsKey(nanoAppId)) {
                instanceId = mNanoAppIdToInstanceMap.get(nanoAppId);
            } else {
                instanceId = mNextAvailableInstanceId++;
                mNanoAppIdToInstanceMap.put(nanoAppId, instanceId);
            }

            addAppInstance(contextHubId, instanceId, nanoAppId, nanoAppVersion);
        }

        byte[] data = new byte[5];
        data[0] = (byte) result;
        ByteBuffer.wrap(data, 1, 4).order(ByteOrder.nativeOrder()).putInt(instanceId);

        onMessageReceiptOldApi(MSG_LOAD_NANO_APP, contextHubId, OS_APP_INSTANCE, data);
    }

    /**
     * A helper function to handle an unload response from the Context Hub for the old API.
     *
     * TODO(b/69270990): Remove this once the old APIs are obsolete.
     */
    private void handleUnloadResponseOldApi(
            int contextHubId, int result, long nanoAppId) {
        if (result == TransactionResult.SUCCESS) {
            int instanceId = mNanoAppIdToInstanceMap.get(nanoAppId);
            deleteAppInstance(instanceId);
            mNanoAppIdToInstanceMap.remove(nanoAppId);
        }

        byte[] data = new byte[1];
        data[0] = (byte) result;
        onMessageReceiptOldApi(MSG_UNLOAD_NANO_APP, contextHubId, OS_APP_INSTANCE, data);
    }

    /**
     * Handles a transaction response from a Context Hub.
     *
     * @param contextHubId  the ID of the hub the response came from
     * @param transactionId the ID of the transaction
     * @param result        the result of the transaction reported by the hub
     */
    private void handleTransactionResultCallback(int contextHubId, int transactionId, int result) {
        mTransactionManager.onTransactionResponse(transactionId, result);
    }

    /**
     * Handles an asynchronous event from a Context Hub.
     *
     * @param contextHubId the ID of the hub the response came from
     * @param eventType    the type of the event as defined in Context Hub HAL AsyncEventType
     */
    private void handleHubEventCallback(int contextHubId, int eventType) {
        if (eventType == AsyncEventType.RESTARTED) {
            mTransactionManager.onHubReset();
            queryNanoAppsInternal(contextHubId);

            mClientManager.onHubReset(contextHubId);
        } else {
            Log.i(TAG, "Received unknown hub event (hub ID = " + contextHubId + ", type = "
                    + eventType + ")");
        }
    }

    /**
     * Handles an asynchronous abort event of a nanoapp.
     *
     * @param contextHubId the ID of the hub that the nanoapp aborted in
     * @param nanoAppId    the ID of the aborted nanoapp
     * @param abortCode    the nanoapp-specific abort code
     */
    private void handleAppAbortCallback(int contextHubId, long nanoAppId, int abortCode) {
        // TODO(b/31049861): Implement this
    }

    /**
     * Handles a query response from a Context Hub.
     *
     * @param contextHubId    the ID of the hub of the response
     * @param nanoAppInfoList the list of loaded nanoapps
     */
    private void handleQueryAppsCallback(int contextHubId, List<HubAppInfo> nanoAppInfoList) {
        List<NanoAppState> nanoAppStateList =
                ContextHubServiceUtil.createNanoAppStateList(nanoAppInfoList);

        updateServiceCache(contextHubId, nanoAppInfoList);
        mTransactionManager.onQueryResponse(nanoAppStateList);
    }

    /**
     * Updates the service's cache of the list of loaded nanoapps using a nanoapp list response.
     *
     * TODO(b/69270990): Remove this when the old API functionality is removed.
     *
     * @param contextHubId    the ID of the hub the response came from
     * @param nanoAppInfoList the list of loaded nanoapps
     */
    private void updateServiceCache(int contextHubId, List<HubAppInfo> nanoAppInfoList) {
        synchronized (mNanoAppHash) {
            for (int instanceId : mNanoAppHash.keySet()) {
                if (mNanoAppHash.get(instanceId).getContexthubId() == contextHubId) {
                    deleteAppInstance(instanceId);
                }
            }

            for (HubAppInfo appInfo : nanoAppInfoList) {
                int instanceId;
                long nanoAppId = appInfo.appId;
                if (mNanoAppIdToInstanceMap.containsKey(nanoAppId)) {
                    instanceId = mNanoAppIdToInstanceMap.get(nanoAppId);
                } else {
                    instanceId = mNextAvailableInstanceId++;
                    mNanoAppIdToInstanceMap.put(nanoAppId, instanceId);
                }

                addAppInstance(contextHubId, instanceId, nanoAppId, appInfo.version);
            }
        }
    }

    /**
     * @param contextHubId the hub ID to validate
     * @return {@code true} if the ID represents that of an available hub, {@code false} otherwise
     */
    private boolean isValidContextHubId(int contextHubId) {
        for (ContextHubInfo hubInfo : mContextHubInfo) {
            if (hubInfo.getId() == contextHubId) {
                return true;
            }
        }

        return false;
    }

    /**
     * Creates and registers a client at the service for the specified Context Hub.
     *
     * @param clientCallback the client interface to register with the service
     * @param contextHubId   the ID of the hub this client is attached to
     * @return the generated client interface, null if registration was unsuccessful
     *
     * @throws IllegalArgumentException if contextHubId is not a valid ID
     * @throws IllegalStateException if max number of clients have already registered
     * @throws NullPointerException if clientCallback is null
     */
    @Override
    public IContextHubClient createClient(
            IContextHubClientCallback clientCallback, int contextHubId) throws RemoteException {
        checkPermissions();
        if (!isValidContextHubId(contextHubId)) {
            throw new IllegalArgumentException("Invalid context hub ID " + contextHubId);
        }
        if (clientCallback == null) {
            throw new NullPointerException("Cannot register client with null callback");
        }

        return mClientManager.registerClient(clientCallback, contextHubId);
    }

    /**
     * Loads a nanoapp binary at the specified Context hub.
     *
     * @param contextHubId the ID of the hub to load the binary
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppBinary the binary to load
     *
     * @throws RemoteException
     */
    @Override
    public void loadNanoAppOnHub(
            int contextHubId, IContextHubTransactionCallback transactionCallback,
            NanoAppBinary nanoAppBinary) throws RemoteException {
        checkPermissions();
        if (mContextHubProxy == null) {
            transactionCallback.onTransactionComplete(
                    ContextHubTransaction.TRANSACTION_FAILED_HAL_UNAVAILABLE);
            return;
        }
        if (!isValidContextHubId(contextHubId)) {
            Log.e(TAG, "Cannot load nanoapp for invalid hub ID " + contextHubId);
            transactionCallback.onTransactionComplete(
                    ContextHubTransaction.TRANSACTION_FAILED_BAD_PARAMS);
            return;
        }
        if (nanoAppBinary == null) {
            Log.e(TAG, "NanoAppBinary cannot be null in loadNanoAppOnHub");
            transactionCallback.onTransactionComplete(
                    ContextHubTransaction.TRANSACTION_FAILED_BAD_PARAMS);
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createLoadTransaction(
                contextHubId, nanoAppBinary, transactionCallback);

        addTransaction(transaction);
    }

    /**
     * Unloads a nanoapp from the specified Context Hub.
     *
     * @param contextHubId the ID of the hub to unload the nanoapp
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppId the ID of the nanoapp to unload
     *
     * @throws RemoteException
     */
    @Override
    public void unloadNanoAppFromHub(
            int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId)
            throws RemoteException {
        checkPermissions();
        if (mContextHubProxy == null) {
            transactionCallback.onTransactionComplete(
                    ContextHubTransaction.TRANSACTION_FAILED_HAL_UNAVAILABLE);
            return;
        }
        if (!isValidContextHubId(contextHubId)) {
            Log.e(TAG, "Cannot unload nanoapp for invalid hub ID " + contextHubId);
            transactionCallback.onTransactionComplete(
                    ContextHubTransaction.TRANSACTION_FAILED_BAD_PARAMS);
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createUnloadTransaction(
                contextHubId, nanoAppId, transactionCallback);

        addTransaction(transaction);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.println("Dumping ContextHub Service");

        pw.println("");
        // dump ContextHubInfo
        pw.println("=================== CONTEXT HUBS ====================");
        for (int i = 0; i < mContextHubInfo.length; i++) {
            pw.println("Handle " + i + " : " + mContextHubInfo[i].toString());
        }
        pw.println("");
        pw.println("=================== NANOAPPS ====================");
        // Dump nanoAppHash
        for (Integer nanoAppInstance : mNanoAppHash.keySet()) {
            pw.println(nanoAppInstance + " : " + mNanoAppHash.get(nanoAppInstance).toString());
        }

        // dump eventLog
    }

    private void checkPermissions() {
        ContextHubServiceUtil.checkPermissions(mContext);
    }

    private int onMessageReceiptOldApi(int msgType, int hubHandle, int appInstance, byte[] data) {
        if (data == null) {
            return -1;
        }

        int msgVersion = 0;
        int callbacksCount = mCallbacksList.beginBroadcast();
        Log.d(TAG, "Sending message " + msgType + " version " + msgVersion + " from hubHandle " +
                hubHandle + ", appInstance " + appInstance + ", callBackCount " + callbacksCount);

        if (callbacksCount < 1) {
            Log.v(TAG, "No message callbacks registered.");
            return 0;
        }

        ContextHubMessage msg = new ContextHubMessage(msgType, msgVersion, data);
        for (int i = 0; i < callbacksCount; ++i) {
            IContextHubCallback callback = mCallbacksList.getBroadcastItem(i);
            try {
                callback.onMessageReceipt(hubHandle, appInstance, msg);
            } catch (RemoteException e) {
                Log.i(TAG, "Exception (" + e + ") calling remote callback (" + callback + ").");
                continue;
            }
        }
        mCallbacksList.finishBroadcast();
        return 0;
    }

    private int addAppInstance(int hubHandle, int appInstanceHandle, long appId, int appVersion) {
        // App Id encodes vendor & version
        NanoAppInstanceInfo appInfo = new NanoAppInstanceInfo();

        appInfo.setAppId(appId);
        appInfo.setAppVersion(appVersion);
        appInfo.setName(PRE_LOADED_APP_NAME);
        appInfo.setContexthubId(hubHandle);
        appInfo.setHandle(appInstanceHandle);
        appInfo.setPublisher(PRE_LOADED_APP_PUBLISHER);
        appInfo.setNeededExecMemBytes(PRE_LOADED_APP_MEM_REQ);
        appInfo.setNeededReadMemBytes(PRE_LOADED_APP_MEM_REQ);
        appInfo.setNeededWriteMemBytes(PRE_LOADED_APP_MEM_REQ);

        String action;
        if (mNanoAppHash.containsKey(appInstanceHandle)) {
            action = "Updated";
        } else {
            action = "Added";
        }

        mNanoAppHash.put(appInstanceHandle, appInfo);
        Log.d(TAG, action + " app instance " + appInstanceHandle + " with id 0x"
                + Long.toHexString(appId) + " version 0x" + Integer.toHexString(appVersion));

        return 0;
    }

    private int deleteAppInstance(int appInstanceHandle) {
        if (mNanoAppHash.remove(appInstanceHandle) == null) {
            return -1;
        }

        return 0;
    }
}
