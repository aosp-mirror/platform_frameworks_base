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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.chre.flags.Flags;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManagerInternal;
import android.hardware.contexthub.ErrorCode;
import android.hardware.contexthub.MessageDeliveryStatus;
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
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.location.ContextHubServiceProto;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final int OS_APP_INSTANCE = -1;

    /**
     * Constants describing an async event from the Context Hub.
     * {@hide}
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CONTEXT_HUB_EVENT_"}, value = {
            CONTEXT_HUB_EVENT_UNKNOWN,
            CONTEXT_HUB_EVENT_RESTARTED,
    })
    public @interface Type {
    }

    public static final int CONTEXT_HUB_EVENT_UNKNOWN = 0;
    public static final int CONTEXT_HUB_EVENT_RESTARTED = 1;

    /*
     * Local flag to enable debug logging.
     */
    private static final boolean DEBUG_LOG_ENABLED = false;

    private final Context mContext;

    private Map<Integer, ContextHubInfo> mContextHubIdToInfoMap;
    private List<String> mSupportedContextHubPerms;
    private List<ContextHubInfo> mContextHubInfoList;
    private final RemoteCallbackList<IContextHubCallback> mCallbacksList =
            new RemoteCallbackList<>();

    // Proxy object to communicate with the Context Hub HAL
    private final IContextHubWrapper mContextHubWrapper;

    // The manager for transaction queue
    private ContextHubTransactionManager mTransactionManager;

    // The manager for sending messages to/from clients
    private ContextHubClientManager mClientManager;

    // The default client for old API clients
    private Map<Integer, IContextHubClient> mDefaultClientMap;

    // The manager for the internal nanoapp state cache
    private final NanoAppStateManager mNanoAppStateManager = new NanoAppStateManager();

    // An executor and the future object for scheduling timeout timers
    private final ScheduledThreadPoolExecutor mDailyMetricTimer =
            new ScheduledThreadPoolExecutor(1);

    // A queue of reliable message records for duplicate detection
    private final PriorityQueue<ReliableMessageRecord> mReliableMessageRecordQueue =
            new PriorityQueue<ReliableMessageRecord>(
                    (ReliableMessageRecord left, ReliableMessageRecord right) -> {
                        return Long.compare(left.getTimestamp(), right.getTimestamp());
                    });

    // The test mode manager that manages behaviors during test mode.
    private final TestModeManager mTestModeManager = new TestModeManager();

    // The period of the recurring time
    private static final int PERIOD_METRIC_QUERY_DAYS = 1;

    // True if WiFi is available for the Context Hub
    private boolean mIsWifiAvailable = false;
    private boolean mIsWifiScanningEnabled = false;
    private boolean mIsWifiMainEnabled = false;

    // True if BT is available for the Context Hub
    private boolean mIsBtScanningEnabled = false;
    private boolean mIsBtMainEnabled = false;

    // True if test mode is enabled for the Context Hub
    private AtomicBoolean mIsTestModeEnabled = new AtomicBoolean(false);

    // A hashmap used to record if a contexthub is waiting for daily query
    private Set<Integer> mMetricQueryPendingContextHubIds =
            Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    // Lock object for sendWifiSettingUpdate()
    private final Object mSendWifiSettingUpdateLock = new Object();

    private SensorPrivacyManagerInternal mSensorPrivacyManagerInternal;

    private UserManager mUserManager = null;

    private final Map<Integer, AtomicLong> mLastRestartTimestampMap = new HashMap<>();

    /**
     * Class extending the callback to register with a Context Hub.
     */
    private class ContextHubServiceCallback implements IContextHubWrapper.ICallback {
        private final int mContextHubId;

        ContextHubServiceCallback(int contextHubId) {
            mContextHubId = contextHubId;
        }

        @Override
        public void handleTransactionResult(int transactionId, boolean success) {
            handleTransactionResultCallback(mContextHubId, transactionId, success);
        }

        @Override
        public void handleContextHubEvent(int eventType) {
            handleHubEventCallback(mContextHubId, eventType);
        }

        @Override
        public void handleNanoappAbort(long nanoappId, int abortCode) {
            handleAppAbortCallback(mContextHubId, nanoappId, abortCode);
        }

        @Override
        public void handleNanoappInfo(List<NanoAppState> nanoappStateList) {
            handleQueryAppsCallback(mContextHubId, nanoappStateList);
        }

        @Override
        public void handleNanoappMessage(short hostEndpointId, NanoAppMessage message,
                List<String> nanoappPermissions, List<String> messagePermissions) {
            if (Flags.reliableMessageImplementation()
                    && Flags.reliableMessageTestModeBehavior()
                    && mIsTestModeEnabled.get()
                    && mTestModeManager.handleNanoappMessage(mContextHubId, hostEndpointId,
                            message, nanoappPermissions, messagePermissions)) {
                // The TestModeManager handled the nanoapp message, so return here.
                return;
            }

            handleClientMessageCallback(mContextHubId, hostEndpointId, message,
                    nanoappPermissions, messagePermissions);
        }

        @Override
        public void handleServiceRestart() {
            Log.i(TAG, "Starting Context Hub Service restart");
            initExistingCallbacks();
            resetSettings();
            Log.i(TAG, "Finished Context Hub Service restart");
        }

        @Override
        public void handleMessageDeliveryStatus(MessageDeliveryStatus messageDeliveryStatus) {
            handleMessageDeliveryStatusCallback(messageDeliveryStatus);
        }
    }

    /**
     * Records a reliable message from a nanoapp for duplicate detection.
     */
    private static class ReliableMessageRecord {
        public static final int TIMEOUT_NS = 1000000000;

        public int mContextHubId;
        public long mTimestamp;
        public int mMessageSequenceNumber;
        byte mErrorCode;

        ReliableMessageRecord(int contextHubId, long timestamp,
                int messageSequenceNumber, byte errorCode) {
            mContextHubId = contextHubId;
            mTimestamp = timestamp;
            mMessageSequenceNumber = messageSequenceNumber;
            mErrorCode = errorCode;
        }

        public int getContextHubId() {
            return mContextHubId;
        }

        public long getTimestamp() {
            return mTimestamp;
        }

        public int getMessageSequenceNumber() {
            return mMessageSequenceNumber;
        }

        public byte getErrorCode() {
            return mErrorCode;
        }

        public void setErrorCode(byte errorCode) {
            mErrorCode = errorCode;
        }

        public boolean isExpired() {
            return mTimestamp + TIMEOUT_NS < SystemClock.elapsedRealtimeNanos();
        }
    }

    /**
     * A class to manage behaviors during test mode. This is used for testing.
     */
    private class TestModeManager {
        /**
         * Probability (in percent) of duplicating a message.
         */
        private static final int MESSAGE_DUPLICATION_PROBABILITY_PERCENT = 50;

        /**
         * The number of total messages to send when the duplicate event happens.
         */
        private static final int NUM_MESSAGES_TO_DUPLICATE = 3;

        /**
         * A probability percent for a certain event.
         */
        private static final int MAX_PROBABILITY_PERCENT = 100;

        private Random mRandom = new Random();

        /**
         * @see ContextHubServiceCallback.handleNanoappMessage
         * @return whether the message was handled
         */
        public boolean handleNanoappMessage(int contextHubId,
                short hostEndpointId, NanoAppMessage message,
                List<String> nanoappPermissions, List<String> messagePermissions) {
            if (!message.isReliable()) {
                return false;
            }

            if (Flags.reliableMessageDuplicateDetectionService()
                && didEventHappen(MESSAGE_DUPLICATION_PROBABILITY_PERCENT)) {
                for (int i = 0; i < NUM_MESSAGES_TO_DUPLICATE; ++i) {
                    handleClientMessageCallback(contextHubId, hostEndpointId,
                            message, nanoappPermissions, messagePermissions);
                }
                return true;
            }
            return false;
        }

        /**
         * Returns true if the event with percentPercent did happen.
         *
         * @param probabilityPercent the percent probability of the event.
         * @return true if the event happened, false otherwise.
         */
        private boolean didEventHappen(int probabilityPercent) {
            return mRandom.nextInt(MAX_PROBABILITY_PERCENT) < probabilityPercent;
        }
    }

    public ContextHubService(Context context, IContextHubWrapper contextHubWrapper) {
        Log.i(TAG, "Starting Context Hub Service init");
        mContext = context;
        long startTimeNs = SystemClock.elapsedRealtimeNanos();
        mContextHubWrapper = contextHubWrapper;
        if (!initContextHubServiceState(startTimeNs)) {
            Log.e(TAG, "Failed to initialize the Context Hub Service");
            return;
        }
        initDefaultClientMap();

        initLocationSettingNotifications();
        initWifiSettingNotifications();
        initAirplaneModeSettingNotifications();
        initMicrophoneSettingNotifications();
        initBtSettingNotifications();

        scheduleDailyMetricSnapshot();
        Log.i(TAG, "Finished Context Hub Service init");
    }

    /**
     * Creates a default client callback for old API clients.
     *
     * @param contextHubId the ID of the hub to attach this client to
     * @return the internal callback interface
     */
    private IContextHubClientCallback createDefaultClientCallback(int contextHubId) {
        return new IContextHubClientCallback.Stub() {
            private void finishCallback() {
                try {
                    IContextHubClient client = mDefaultClientMap.get(contextHubId);
                    client.callbackFinished();
                } catch (RemoteException e) {
                    Log.e(
                            TAG,
                            "RemoteException while finishing callback for hub (ID = "
                                    + contextHubId
                                    + ")",
                            e);
                }
            }

            @Override
            public void onMessageFromNanoApp(NanoAppMessage message) {
                int nanoAppHandle =
                        mNanoAppStateManager.getNanoAppHandle(contextHubId, message.getNanoAppId());

                onMessageReceiptOldApi(
                        message.getMessageType(),
                        contextHubId,
                        nanoAppHandle,
                        message.getMessageBody());

                finishCallback();
            }

            @Override
            public void onHubReset() {
                byte[] data = {android.hardware.contexthub.V1_0.TransactionResult.SUCCESS};
                onMessageReceiptOldApi(MSG_HUB_RESET, contextHubId, OS_APP_INSTANCE, data);
                finishCallback();
            }

            @Override
            public void onNanoAppAborted(long nanoAppId, int abortCode) {
                finishCallback();
            }

            @Override
            public void onNanoAppLoaded(long nanoAppId) {
                finishCallback();
            }

            @Override
            public void onNanoAppUnloaded(long nanoAppId) {
                finishCallback();
            }

            @Override
            public void onNanoAppEnabled(long nanoAppId) {
                finishCallback();
            }

            @Override
            public void onNanoAppDisabled(long nanoAppId) {
                finishCallback();
            }

            @Override
            public void onClientAuthorizationChanged(long nanoAppId, int authorization) {
                finishCallback();
            }
        };
    }

    /**
     * Initializes the private state of the ContextHubService
     *
     * @param startTimeNs               the start time when init was called
     *
     * @return      if mContextHubWrapper is not null and a full state init was done
     */
    private boolean initContextHubServiceState(long startTimeNs) {
        if (mContextHubWrapper == null) {
            mTransactionManager = null;
            mClientManager = null;
            mSensorPrivacyManagerInternal = null;
            mDefaultClientMap = Collections.emptyMap();
            mContextHubIdToInfoMap = Collections.emptyMap();
            mSupportedContextHubPerms = Collections.emptyList();
            mContextHubInfoList = Collections.emptyList();
            return false;
        }

        Pair<List<ContextHubInfo>, List<String>> hubInfo;
        try {
            hubInfo = mContextHubWrapper.getHubs();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting Context Hub info", e);
            hubInfo = new Pair(Collections.emptyList(), Collections.emptyList());
        }

        long bootTimeNs = SystemClock.elapsedRealtimeNanos() - startTimeNs;
        int numContextHubs = hubInfo.first.size();
        ContextHubStatsLog.write(ContextHubStatsLog.CONTEXT_HUB_BOOTED, bootTimeNs,
                numContextHubs);

        mContextHubIdToInfoMap = Collections.unmodifiableMap(
                ContextHubServiceUtil.createContextHubInfoMap(hubInfo.first));
        mSupportedContextHubPerms = hubInfo.second;
        mContextHubInfoList = new ArrayList<>(mContextHubIdToInfoMap.values());
        mClientManager = new ContextHubClientManager(mContext, mContextHubWrapper);
        mTransactionManager = new ContextHubTransactionManager(
                mContextHubWrapper, mClientManager, mNanoAppStateManager);
        mSensorPrivacyManagerInternal =
                LocalServices.getService(SensorPrivacyManagerInternal.class);
        return true;
    }

    /**
     * Creates the default client map that maps context hub IDs to the associated
     * ClientManager. The client map is unmodifiable
     */
    private void initDefaultClientMap() {
        HashMap<Integer, IContextHubClient> defaultClientMap = new HashMap<>();
        for (Map.Entry<Integer, ContextHubInfo> entry: mContextHubIdToInfoMap.entrySet()) {
            int contextHubId = entry.getKey();
            ContextHubInfo contextHubInfo = entry.getValue();

            mLastRestartTimestampMap.put(contextHubId,
                    new AtomicLong(SystemClock.elapsedRealtimeNanos()));

            try {
                mContextHubWrapper.registerCallback(contextHubId,
                        new ContextHubServiceCallback(contextHubId));
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while registering service callback for hub (ID = "
                        + contextHubId + ")", e);
            }

            IContextHubClient client = mClientManager.registerClient(
                    contextHubInfo, createDefaultClientCallback(contextHubId),
                    /* attributionTag= */ null, mTransactionManager, mContext.getPackageName());
            defaultClientMap.put(contextHubId, client);

            // Do a query to initialize the service cache list of nanoapps
            // TODO(b/194289715): Remove this when old API is deprecated
            queryNanoAppsInternal(contextHubId);
        }
        mDefaultClientMap = Collections.unmodifiableMap(defaultClientMap);
    }

    /**
     * Initializes existing callbacks with the mContextHubWrapper for every context hub
     */
    private void initExistingCallbacks() {
        for (int contextHubId : mContextHubIdToInfoMap.keySet()) {
            try {
                mContextHubWrapper.registerExistingCallback(contextHubId);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while registering existing service callback for hub "
                        + "(ID = " + contextHubId + ")", e);
            }
        }
    }

    /**
     * Handles the initialization of location settings notifications
     */
    private void initLocationSettingNotifications() {
        if (mContextHubWrapper == null
                || !mContextHubWrapper.supportsLocationSettingNotifications()) {
            return;
        }

        sendLocationSettingUpdate();
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE),
                /* notifyForDescendants= */ true,
                new ContentObserver(/* handler= */ null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        sendLocationSettingUpdate();
                    }
                }, UserHandle.USER_ALL);
    }

    /**
     * Handles the initialization of wifi settings notifications
     */
    private void initWifiSettingNotifications() {
        if (mContextHubWrapper == null || !mContextHubWrapper.supportsWifiSettingNotifications()) {
            return;
        }

        sendWifiSettingUpdate(/* forceUpdate= */ true);

        BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())
                        || WifiManager.ACTION_WIFI_SCAN_AVAILABILITY_CHANGED.equals(
                        intent.getAction())) {
                    sendWifiSettingUpdate(/* forceUpdate= */ false);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.ACTION_WIFI_SCAN_AVAILABILITY_CHANGED);
        mContext.registerReceiver(wifiReceiver, filter);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE),
                /* notifyForDescendants= */ true,
                new ContentObserver(/* handler= */ null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        sendWifiSettingUpdate(/* forceUpdate= */ false);
                    }
                }, UserHandle.USER_ALL);
    }

    /**
     * Handles the initialization of airplane mode settings notifications
     */
    private void initAirplaneModeSettingNotifications() {
        if (mContextHubWrapper == null
                || !mContextHubWrapper.supportsAirplaneModeSettingNotifications()) {
            return;
        }

        sendAirplaneModeSettingUpdate();
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON),
                /* notifyForDescendants= */ true,
                new ContentObserver(/* handler= */ null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        sendAirplaneModeSettingUpdate();
                    }
                }, UserHandle.USER_ALL);
    }

    /**
     * Handles the initialization of microphone settings notifications
     */
    private void initMicrophoneSettingNotifications() {
        if (mContextHubWrapper == null
                || !mContextHubWrapper.supportsMicrophoneSettingNotifications()) {
            return;
        }

        if (mUserManager == null) {
            mUserManager = mContext.getSystemService(UserManager.class);
            if (mUserManager == null) {
                Log.e(TAG, "Unable to get the UserManager service");
                return;
            }
        }

        sendMicrophoneDisableSettingUpdateForCurrentUser();
        if (mSensorPrivacyManagerInternal == null) {
            Log.e(TAG, "Unable to add a sensor privacy listener for all users");
            return;
        }

        mSensorPrivacyManagerInternal.addSensorPrivacyListenerForAllUsers(
                SensorPrivacyManager.Sensors.MICROPHONE, (userId, enabled) -> {
                    // If we are in HSUM mode, any user can change the microphone setting
                    if (mUserManager.isHeadlessSystemUserMode() || userId == getCurrentUserId()) {
                        Log.d(TAG, "User: " + userId + " mic privacy: " + enabled);
                        sendMicrophoneDisableSettingUpdate(enabled);
                    }
                });
    }

    /**
     * Handles the initialization of bluetooth settings notifications
     */
    private void initBtSettingNotifications() {
        if (mContextHubWrapper == null || !mContextHubWrapper.supportsBtSettingNotifications()) {
            return;
        }

        sendBtSettingUpdate(/* forceUpdate= */ true);

        BroadcastReceiver btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    sendBtSettingUpdate(/* forceUpdate= */ false);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(btReceiver, filter);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE),
                /* notifyForDescendants= */ false,
                new ContentObserver(/* handler= */ null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        sendBtSettingUpdate(/* forceUpdate= */ false);
                    }
                }, UserHandle.USER_ALL);
    }

    /**
     * Resets the settings. Called when a context hub restarts or the AIDL HAL dies
     */
    private void resetSettings() {
        mIsTestModeEnabled.set(false);

        sendLocationSettingUpdate();
        sendWifiSettingUpdate(/* forceUpdate= */ true);
        sendAirplaneModeSettingUpdate();
        sendMicrophoneDisableSettingUpdateForCurrentUser();
        sendBtSettingUpdate(/* forceUpdate= */ true);
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver result) {
        new ContextHubShellCommand(mContext, this).exec(this, in, out, err, args, callback, result);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Override
    public int registerCallback(IContextHubCallback callback) throws RemoteException {
        super.registerCallback_enforcePermission();

        mCallbacksList.register(callback);

        Log.d(TAG, "Added callback, total callbacks " +
                mCallbacksList.getRegisteredCallbackCount());
        return 0;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Override
    public int[] getContextHubHandles() throws RemoteException {
        super.getContextHubHandles_enforcePermission();

        return ContextHubServiceUtil.createPrimitiveIntArray(mContextHubIdToInfoMap.keySet());
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Override
    public ContextHubInfo getContextHubInfo(int contextHubHandle) throws RemoteException {
        super.getContextHubInfo_enforcePermission();

        if (!mContextHubIdToInfoMap.containsKey(contextHubHandle)) {
            Log.e(TAG, "Invalid Context Hub handle " + contextHubHandle + " in getContextHubInfo");
            return null;
        }

        return mContextHubIdToInfoMap.get(contextHubHandle);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    /**
     * Returns a List of ContextHubInfo object describing the available hubs.
     *
     * @return the List of ContextHubInfo objects
     */
    @Override
    public List<ContextHubInfo> getContextHubs() throws RemoteException {
        super.getContextHubs_enforcePermission();

        return mContextHubInfoList;
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
     * @return the callback interface
     */
    private IContextHubTransactionCallback createUnloadTransactionCallback(int contextHubId) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onTransactionComplete(int result) {
                handleUnloadResponseOldApi(contextHubId, result);
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

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Override
    public int loadNanoApp(int contextHubHandle, NanoApp nanoApp) throws RemoteException {
        super.loadNanoApp_enforcePermission();

        if (mContextHubWrapper == null) {
            return -1;
        }
        if (!isValidContextHubId(contextHubHandle)) {
            Log.e(TAG, "Invalid Context Hub handle " + contextHubHandle + " in loadNanoApp");
            return -1;
        }
        if (nanoApp == null) {
            Log.e(TAG, "NanoApp cannot be null in loadNanoApp");
            return -1;
        }

        // Create an internal IContextHubTransactionCallback for the old API clients
        NanoAppBinary nanoAppBinary = new NanoAppBinary(nanoApp.getAppBinary());
        IContextHubTransactionCallback onCompleteCallback =
                createLoadTransactionCallback(contextHubHandle, nanoAppBinary);

        ContextHubServiceTransaction transaction = mTransactionManager.createLoadTransaction(
                contextHubHandle, nanoAppBinary, onCompleteCallback, getCallingPackageName());

        mTransactionManager.addTransaction(transaction);
        return 0;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Override
    public int unloadNanoApp(int nanoAppHandle) throws RemoteException {
        super.unloadNanoApp_enforcePermission();

        if (mContextHubWrapper == null) {
            return -1;
        }

        NanoAppInstanceInfo info =
                mNanoAppStateManager.getNanoAppInstanceInfo(nanoAppHandle);
        if (info == null) {
            Log.e(TAG, "Invalid nanoapp handle " + nanoAppHandle + " in unloadNanoApp");
            return -1;
        }

        int contextHubId = info.getContexthubId();
        long nanoAppId = info.getAppId();
        IContextHubTransactionCallback onCompleteCallback =
                createUnloadTransactionCallback(contextHubId);
        ContextHubServiceTransaction transaction = mTransactionManager.createUnloadTransaction(
                contextHubId, nanoAppId, onCompleteCallback, getCallingPackageName());

        mTransactionManager.addTransaction(transaction);
        return 0;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Override
    public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppHandle) throws RemoteException {

        super.getNanoAppInstanceInfo_enforcePermission();

        return mNanoAppStateManager.getNanoAppInstanceInfo(nanoAppHandle);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Override
    public int[] findNanoAppOnHub(
            int contextHubHandle, NanoAppFilter filter) throws RemoteException {

        super.findNanoAppOnHub_enforcePermission();

        ArrayList<Integer> foundInstances = new ArrayList<>();
        if (filter != null) {
            mNanoAppStateManager.foreachNanoAppInstanceInfo((info) -> {
                if (filter.testMatch(info)) {
                    foundInstances.add(info.getHandle());
                }
            });
        }

        int[] retArray = new int[foundInstances.size()];
        for (int i = 0; i < foundInstances.size(); i++) {
            retArray[i] = foundInstances.get(i).intValue();
        }
        return retArray;
    }

    /**
     * Performs a query at the specified hub.
     * <p>
     * This method should only be invoked internally by the service, either to update the service
     * cache or as a result of an explicit query requested by a client through the sendMessage API.
     *
     * @param contextHubId the ID of the hub to do the query
     * @return true if the query succeeded
     * @throws IllegalStateException if the transaction queue is full
     */
    private boolean queryNanoAppsInternal(int contextHubId) {
        if (mContextHubWrapper == null) {
            return false;
        }

        IContextHubTransactionCallback onCompleteCallback =
                createQueryTransactionCallback(contextHubId);
        ContextHubServiceTransaction transaction = mTransactionManager.createQueryTransaction(
                contextHubId, onCompleteCallback, getCallingPackageName());

        mTransactionManager.addTransaction(transaction);
        return true;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Override
    public int sendMessage(int contextHubHandle, int nanoAppHandle, ContextHubMessage msg)
            throws RemoteException {
        super.sendMessage_enforcePermission();

        if (mContextHubWrapper == null) {
            return -1;
        }
        if (msg == null) {
            Log.e(TAG, "ContextHubMessage cannot be null in sendMessage");
            return -1;
        }
        if (msg.getData() == null) {
            Log.e(TAG, "ContextHubMessage message body cannot be null in sendMessage");
            return -1;
        }
        if (!isValidContextHubId(contextHubHandle)) {
            Log.e(TAG, "Invalid Context Hub handle " + contextHubHandle + " in sendMessage");
            return -1;
        }

        boolean success = false;
        if (nanoAppHandle == OS_APP_INSTANCE) {
            if (msg.getMsgType() == MSG_QUERY_NANO_APPS) {
                success = queryNanoAppsInternal(contextHubHandle);
            } else {
                Log.e(TAG, "Invalid OS message params of type " + msg.getMsgType());
            }
        } else {
            NanoAppInstanceInfo info = getNanoAppInstanceInfo(nanoAppHandle);
            if (info != null) {
                NanoAppMessage message = NanoAppMessage.createMessageToNanoApp(
                        info.getAppId(), msg.getMsgType(), msg.getData());

                IContextHubClient client = mDefaultClientMap.get(contextHubHandle);
                success = client.sendMessageToNanoApp(message)
                        == ContextHubTransaction.RESULT_SUCCESS;
            } else {
                Log.e(TAG, "Failed to send nanoapp message - nanoapp with handle "
                        + nanoAppHandle + " does not exist.");
            }
        }

        return success ? 0 : -1;
    }

    /**
     * Handles a unicast or broadcast message from a nanoapp.
     *
     * @param contextHubId the ID of the hub the message came from
     * @param hostEndpointId the host endpoint ID of the client receiving this message
     * @param message the message contents
     * @param nanoappPermissions the set of permissions the nanoapp holds
     * @param messagePermissions the set of permissions that should be used for attributing
     *        permissions when this message is consumed by a client
     */
    private void handleClientMessageCallback(int contextHubId, short hostEndpointId,
            NanoAppMessage message, List<String> nanoappPermissions,
            List<String> messagePermissions) {
        if (!Flags.reliableMessageImplementation()
                || !Flags.reliableMessageDuplicateDetectionService()) {
            byte errorCode = mClientManager.onMessageFromNanoApp(contextHubId, hostEndpointId,
                    message, nanoappPermissions, messagePermissions);
            if (message.isReliable() && errorCode != ErrorCode.OK) {
                sendMessageDeliveryStatusToContextHub(contextHubId,
                        message.getMessageSequenceNumber(), errorCode);
            }
            return;
        }

        if (!message.isReliable()) {
            mClientManager.onMessageFromNanoApp(
                    contextHubId, hostEndpointId, message,
                    nanoappPermissions, messagePermissions);
            cleanupReliableMessageRecordQueue();
            return;
        }

        byte errorCode = ErrorCode.OK;
        synchronized (mReliableMessageRecordQueue) {
            Optional<ReliableMessageRecord> record =
                    findReliableMessageRecord(contextHubId,
                            message.getMessageSequenceNumber());

            if (record.isPresent()) {
                errorCode = record.get().getErrorCode();
                if (errorCode == ErrorCode.TRANSIENT_ERROR) {
                    Log.w(TAG, "Found duplicate reliable message with message sequence number: "
                            + record.get().getMessageSequenceNumber() + ": retrying");
                    errorCode = mClientManager.onMessageFromNanoApp(
                            contextHubId, hostEndpointId, message,
                            nanoappPermissions, messagePermissions);
                    record.get().setErrorCode(errorCode);
                } else {
                    Log.w(TAG, "Found duplicate reliable message with message sequence number: "
                            + record.get().getMessageSequenceNumber());
                }
            } else {
                errorCode = mClientManager.onMessageFromNanoApp(
                        contextHubId, hostEndpointId, message,
                        nanoappPermissions, messagePermissions);
                mReliableMessageRecordQueue.add(
                        new ReliableMessageRecord(contextHubId,
                                SystemClock.elapsedRealtimeNanos(),
                                message.getMessageSequenceNumber(),
                                errorCode));
            }
        }

        sendMessageDeliveryStatusToContextHub(contextHubId,
                message.getMessageSequenceNumber(), errorCode);
        cleanupReliableMessageRecordQueue();
    }

    /**
     * Finds a reliable message record in the queue that matches the given
     * context hub ID and message sequence number. This function assumes
     * the caller is synchronized on mReliableMessageRecordQueue.
     *
     * @param contextHubId the ID of the hub
     * @param messageSequenceNumber the message sequence number
     *
     * @return the record if found, or empty if not found
     */
    private Optional<ReliableMessageRecord> findReliableMessageRecord(
            int contextHubId, int messageSequenceNumber) {
        for (ReliableMessageRecord record: mReliableMessageRecordQueue) {
            if (record.getContextHubId() == contextHubId
                && record.getMessageSequenceNumber() == messageSequenceNumber) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /**
     * Removes old entries from the reliable message record queue.
     */
    private void cleanupReliableMessageRecordQueue() {
        synchronized (mReliableMessageRecordQueue) {
            while (mReliableMessageRecordQueue.peek() != null
                   && mReliableMessageRecordQueue.peek().isExpired()) {
                mReliableMessageRecordQueue.poll();
            }
        }
    }

    /**
     * Sends the message delivery status to the Context Hub.
     *
     * @param contextHubId the ID of the hub
     * @param messageSequenceNumber the message sequence number
     * @param errorCode the error code, one of the enum ErrorCode
     */
    private void sendMessageDeliveryStatusToContextHub(int contextHubId,
            int messageSequenceNumber, byte errorCode) {
        if (!Flags.reliableMessageImplementation()) {
            return;
        }

        MessageDeliveryStatus status = new MessageDeliveryStatus();
        status.messageSequenceNumber = messageSequenceNumber;
        status.errorCode = errorCode;
        if (mContextHubWrapper.sendMessageDeliveryStatusToContextHub(contextHubId, status)
                != ContextHubTransaction.RESULT_SUCCESS) {
            Log.e(TAG, "Failed to send the reliable message status for message sequence number: "
                    + messageSequenceNumber + " with error code: " + errorCode);
        }
    }

    /**
     * A helper function to handle a load response from the Context Hub for the old API.
     * TODO(b/194289715): Remove this once the old APIs are obsolete.
     */
    private void handleLoadResponseOldApi(
            int contextHubId, int result, NanoAppBinary nanoAppBinary) {
        if (nanoAppBinary == null) {
            Log.e(TAG, "Nanoapp binary field was null for a load transaction");
            return;
        }

        byte[] data = new byte[5];
        data[0] = (byte) result;
        int nanoAppHandle = mNanoAppStateManager.getNanoAppHandle(
                contextHubId, nanoAppBinary.getNanoAppId());
        ByteBuffer.wrap(data, 1, 4).order(ByteOrder.nativeOrder()).putInt(nanoAppHandle);

        onMessageReceiptOldApi(MSG_LOAD_NANO_APP, contextHubId, OS_APP_INSTANCE, data);
    }

    /**
     * A helper function to handle an unload response from the Context Hub for the old API.
     * <p>
     * TODO(b/194289715): Remove this once the old APIs are obsolete.
     */
    private void handleUnloadResponseOldApi(int contextHubId, int result) {
        byte[] data = new byte[1];
        data[0] = (byte) result;
        onMessageReceiptOldApi(MSG_UNLOAD_NANO_APP, contextHubId, OS_APP_INSTANCE, data);
    }

    /**
     * Handles a transaction response from a Context Hub.
     *
     * @param contextHubId  the ID of the hub the response came from
     * @param transactionId the ID of the transaction
     * @param success       true if the transaction succeeded
     */
    private void handleTransactionResultCallback(int contextHubId, int transactionId,
            boolean success) {
        mTransactionManager.onTransactionResponse(transactionId, success);
    }

    /**
     * Handles a message delivery status from a Context Hub.
     *
     * @param messageDeliveryStatus     The message delivery status to deliver.
     */
    private void handleMessageDeliveryStatusCallback(MessageDeliveryStatus messageDeliveryStatus) {
        mTransactionManager.onMessageDeliveryResponse(messageDeliveryStatus.messageSequenceNumber,
                messageDeliveryStatus.errorCode == ErrorCode.OK);
    }

    /**
     * Handles an asynchronous event from a Context Hub.
     *
     * @param contextHubId the ID of the hub the response came from
     * @param eventType    the type of the event as in CONTEXT_HUB_EVENT_*
     */
    private void handleHubEventCallback(int contextHubId, int eventType) {
        if (eventType == CONTEXT_HUB_EVENT_RESTARTED) {
            long now = SystemClock.elapsedRealtimeNanos();
            long lastRestartTimeNs = mLastRestartTimestampMap.get(contextHubId).getAndSet(now);
            ContextHubStatsLog.write(
                    ContextHubStatsLog.CONTEXT_HUB_RESTARTED,
                    TimeUnit.NANOSECONDS.toMillis(now - lastRestartTimeNs),
                    contextHubId);

            ContextHubEventLogger.getInstance().logContextHubRestart(contextHubId);

            resetSettings();

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
        mClientManager.onNanoAppAborted(contextHubId, nanoAppId, abortCode);
    }

    /**
     * Handles a query response from a Context Hub.
     *
     * @param contextHubId     the ID of the hub of the response
     * @param nanoappStateList the list of loaded nanoapps
     */
    private void handleQueryAppsCallback(int contextHubId, List<NanoAppState> nanoappStateList) {
        if (mMetricQueryPendingContextHubIds.contains(contextHubId)) {
            for (NanoAppState nanoappState : nanoappStateList) {
                ContextHubStatsLog.write(
                        ContextHubStatsLog.CONTEXT_HUB_LOADED_NANOAPP_SNAPSHOT_REPORTED,
                        contextHubId, nanoappState.getNanoAppId(),
                        (int) nanoappState.getNanoAppVersion());
            }
            mMetricQueryPendingContextHubIds.remove(contextHubId);
            if (mMetricQueryPendingContextHubIds.isEmpty()) {
                scheduleDailyMetricSnapshot();
            }
        }
        mNanoAppStateManager.updateCache(contextHubId, nanoappStateList);
        mTransactionManager.onQueryResponse(nanoappStateList);
    }

    /**
     * @param contextHubId the hub ID to validate
     * @return {@code true} if the ID represents that of an available hub, {@code false} otherwise
     */
    private boolean isValidContextHubId(int contextHubId) {
        return mContextHubIdToInfoMap.containsKey(contextHubId);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    /**
     * Creates and registers a client at the service for the specified Context Hub.
     *
     * @param contextHubId   the ID of the hub this client is attached to
     * @param clientCallback the client interface to register with the service
     * @param attributionTag an optional attribution tag within the given package
     * @param packageName    the name of the package creating this client
     * @return the generated client interface, null if registration was unsuccessful
     * @throws IllegalArgumentException if contextHubId is not a valid ID
     * @throws IllegalStateException    if max number of clients have already registered
     * @throws NullPointerException     if clientCallback is null
     */
    @Override
    public IContextHubClient createClient(
            int contextHubId, IContextHubClientCallback clientCallback,
            @Nullable String attributionTag, String packageName) throws RemoteException {
        super.createClient_enforcePermission();

        if (!isValidContextHubId(contextHubId)) {
            throw new IllegalArgumentException("Invalid context hub ID " + contextHubId);
        }
        if (clientCallback == null) {
            throw new NullPointerException("Cannot register client with null callback");
        }

        ContextHubInfo contextHubInfo = mContextHubIdToInfoMap.get(contextHubId);
        return mClientManager.registerClient(
                contextHubInfo, clientCallback, attributionTag, mTransactionManager, packageName);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    /**
     * Creates and registers a PendingIntent client at the service for the specified Context Hub.
     *
     * @param contextHubId   the ID of the hub this client is attached to
     * @param pendingIntent  the PendingIntent associated with this client
     * @param nanoAppId      the ID of the nanoapp PendingIntent events will be sent for
     * @param attributionTag an optional attribution tag within the given package
     * @return the generated client interface
     * @throws IllegalArgumentException if hubInfo does not represent a valid hub
     * @throws IllegalStateException    if there were too many registered clients at the service
     */
    @Override
    public IContextHubClient createPendingIntentClient(
            int contextHubId, PendingIntent pendingIntent, long nanoAppId,
            @Nullable String attributionTag) throws RemoteException {
        super.createPendingIntentClient_enforcePermission();

        if (!isValidContextHubId(contextHubId)) {
            throw new IllegalArgumentException("Invalid context hub ID " + contextHubId);
        }

        ContextHubInfo contextHubInfo = mContextHubIdToInfoMap.get(contextHubId);
        return mClientManager.registerClient(
                contextHubInfo, pendingIntent, nanoAppId, attributionTag, mTransactionManager);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    /**
     * Loads a nanoapp binary at the specified Context hub.
     *
     * @param contextHubId        the ID of the hub to load the binary
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppBinary       the binary to load
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void loadNanoAppOnHub(
            int contextHubId, IContextHubTransactionCallback transactionCallback,
            NanoAppBinary nanoAppBinary) throws RemoteException {
        super.loadNanoAppOnHub_enforcePermission();

        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_LOAD_NANOAPP)) {
            return;
        }
        if (nanoAppBinary == null) {
            Log.e(TAG, "NanoAppBinary cannot be null in loadNanoAppOnHub");
            transactionCallback.onTransactionComplete(
                    ContextHubTransaction.RESULT_FAILED_BAD_PARAMS);
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createLoadTransaction(
                contextHubId, nanoAppBinary, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    /**
     * Unloads a nanoapp from the specified Context Hub.
     *
     * @param contextHubId        the ID of the hub to unload the nanoapp
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppId           the ID of the nanoapp to unload
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void unloadNanoAppFromHub(
            int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId)
            throws RemoteException {
        super.unloadNanoAppFromHub_enforcePermission();

        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_UNLOAD_NANOAPP)) {
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createUnloadTransaction(
                contextHubId, nanoAppId, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    /**
     * Enables a nanoapp at the specified Context Hub.
     *
     * @param contextHubId        the ID of the hub to enable the nanoapp
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppId           the ID of the nanoapp to enable
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void enableNanoApp(
            int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId)
            throws RemoteException {
        super.enableNanoApp_enforcePermission();

        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_ENABLE_NANOAPP)) {
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createEnableTransaction(
                contextHubId, nanoAppId, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    /**
     * Disables a nanoapp at the specified Context Hub.
     *
     * @param contextHubId        the ID of the hub to disable the nanoapp
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppId           the ID of the nanoapp to disable
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void disableNanoApp(
            int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId)
            throws RemoteException {
        super.disableNanoApp_enforcePermission();

        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_DISABLE_NANOAPP)) {
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createDisableTransaction(
                contextHubId, nanoAppId, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    /**
     * Queries for a list of nanoapps from the specified Context hub.
     *
     * @param contextHubId        the ID of the hub to query
     * @param transactionCallback the client-facing transaction callback interface
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void queryNanoApps(int contextHubId, IContextHubTransactionCallback transactionCallback)
            throws RemoteException {
        super.queryNanoApps_enforcePermission();

        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_QUERY_NANOAPPS)) {
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createQueryTransaction(
                contextHubId, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    /**
     * Queries for a list of preloaded nanoapp IDs from the specified Context Hub.
     *
     * @param hubInfo The Context Hub to query a list of nanoapps from.
     * @return The list of 64-bit IDs of the preloaded nanoapps.
     * @throws NullPointerException if hubInfo is null
     */
    @Override
    public long[] getPreloadedNanoAppIds(ContextHubInfo hubInfo) throws RemoteException {
        super.getPreloadedNanoAppIds_enforcePermission();
        Objects.requireNonNull(hubInfo, "hubInfo cannot be null");

        long[] nanoappIds = mContextHubWrapper.getPreloadedNanoappIds(hubInfo.getId());
        if (nanoappIds == null) {
            return new long[0];
        }
        return nanoappIds;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
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
    @Override
    public boolean setTestMode(boolean enable) {
        super.setTestMode_enforcePermission();
        boolean status = mContextHubWrapper.setTestMode(enable);
        if (status) {
            mIsTestModeEnabled.set(enable);
        }

        // Query nanoapps to update service state after test mode state change.
        for (int contextHubId: mDefaultClientMap.keySet()) {
            queryNanoAppsInternal(contextHubId);
        }
        return status;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        for (String arg : args) {
            if ("--proto".equals(arg)) {
                dump(new ProtoOutputStream(fd));
                return;
            }
        }

        pw.println("Dumping ContextHub Service");

        pw.println("");
        // dump ContextHubInfo
        pw.println("=================== CONTEXT HUBS ====================");
        for (ContextHubInfo hubInfo : mContextHubIdToInfoMap.values()) {
            pw.println(hubInfo);
        }
        pw.println("Supported permissions: "
                + Arrays.toString(mSupportedContextHubPerms.toArray()));
        pw.println("");
        pw.println("=================== NANOAPPS ====================");
        // Dump nanoAppHash
        mNanoAppStateManager.foreachNanoAppInstanceInfo(pw::println);

        pw.println("");
        pw.println("=================== PRELOADED NANOAPPS ====================");
        dumpPreloadedNanoapps(pw);

        pw.println("");
        pw.println("=================== CLIENTS ====================");
        pw.println(mClientManager);

        pw.println("");
        pw.println("=================== TRANSACTIONS ====================");
        pw.println(mTransactionManager);

        pw.println("");
        pw.println("=================== EVENTS ====================");
        pw.println(ContextHubEventLogger.getInstance());

        // dump eventLog
    }

    /* package */ void denyClientAuthState(int contextHubId, String packageName, long nanoAppId) {
        Log.i(TAG, "Denying " + packageName + " access to " + Long.toHexString(nanoAppId)
                + " on context hub # " + contextHubId);

        mClientManager.forEachClientOfHub(contextHubId, client -> {
            if (client.getPackageName().equals(packageName)) {
                client.updateNanoAppAuthState(
                        nanoAppId, /* nanoappPermissions= */ Collections.emptyList(),
                        /* gracePeriodExpired= */ false, /* forceDenied= */ true);
            }
        });
    }

    private void dump(ProtoOutputStream proto) {
        mContextHubIdToInfoMap.values().forEach(hubInfo -> {
            long token = proto.start(ContextHubServiceProto.CONTEXT_HUB_INFO);
            hubInfo.dump(proto);
            proto.end(token);
        });

        long token = proto.start(ContextHubServiceProto.CLIENT_MANAGER);
        mClientManager.dump(proto);
        proto.end(token);

        proto.flush();
    }

    /** Dumps preloaded nanoapps to the console */
    private void dumpPreloadedNanoapps(PrintWriter pw) {
        if (mContextHubWrapper == null) {
            return;
        }

        for (int contextHubId: mContextHubIdToInfoMap.keySet()) {
            long[] preloadedNanoappIds = mContextHubWrapper.getPreloadedNanoappIds(contextHubId);
            if (preloadedNanoappIds == null) {
                return;
            }

            pw.print("Context Hub (id=");
            pw.print(contextHubId);
            pw.println("):");
            for (long preloadedNanoappId : preloadedNanoappIds) {
                pw.print("  ID: 0x");
                pw.println(Long.toHexString(preloadedNanoappId));
            }
        }
    }

    private void checkPermissions() {
        ContextHubServiceUtil.checkPermissions(mContext);
    }

    private int onMessageReceiptOldApi(
            int msgType, int contextHubHandle, int appInstance, byte[] data) {
        if (data == null) {
            return -1;
        }

        int msgVersion = 0;
        // Synchronize access to mCallbacksList to prevent more than one outstanding broadcast as
        // that will cause a crash.
        synchronized (mCallbacksList) {
            int callbacksCount = mCallbacksList.beginBroadcast();
            if (DEBUG_LOG_ENABLED) {
                Log.v(TAG, "Sending message " + msgType + " version " + msgVersion
                        + " from hubHandle " + contextHubHandle + ", appInstance " + appInstance
                        + ", callBackCount " + callbacksCount);
            }

            if (callbacksCount < 1) {
                if (DEBUG_LOG_ENABLED) {
                    Log.v(TAG, "No message callbacks registered.");
                }
                return 0;
            }

            ContextHubMessage msg = new ContextHubMessage(msgType, msgVersion, data);
            for (int i = 0; i < callbacksCount; ++i) {
                IContextHubCallback callback = mCallbacksList.getBroadcastItem(i);
                try {
                    callback.onMessageReceipt(contextHubHandle, appInstance, msg);
                } catch (RemoteException e) {
                    Log.i(TAG, "Exception (" + e + ") calling remote callback (" + callback + ").");
                    continue;
                }
            }
            mCallbacksList.finishBroadcast();
        }
        return 0;
    }

    /**
     * Validates the HAL proxy state and context hub ID to see if we can start the transaction.
     *
     * @param contextHubId    the ID of the hub to start the transaction
     * @param callback        the client transaction callback interface
     * @param transactionType the type of the transaction
     * @return {@code true} if mContextHubWrapper and contextHubId is valid, {@code false} otherwise
     */
    private boolean checkHalProxyAndContextHubId(
            int contextHubId, IContextHubTransactionCallback callback,
            @ContextHubTransaction.Type int transactionType) {
        if (mContextHubWrapper == null) {
            try {
                callback.onTransactionComplete(
                        ContextHubTransaction.RESULT_FAILED_HAL_UNAVAILABLE);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onTransactionComplete", e);
            }
            return false;
        }
        if (!isValidContextHubId(contextHubId)) {
            Log.e(TAG, "Cannot start "
                    + ContextHubTransaction.typeToString(transactionType, /* upperCase= */ false)
                    + " transaction for invalid hub ID " + contextHubId);
            try {
                callback.onTransactionComplete(ContextHubTransaction.RESULT_FAILED_BAD_PARAMS);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onTransactionComplete", e);
            }
            return false;
        }

        return true;
    }

    /**
     * Obtains the latest location setting value and notifies the Context Hub.
     */
    private void sendLocationSettingUpdate() {
        boolean enabled = mContext.getSystemService(LocationManager.class)
                .isLocationEnabledForUser(UserHandle.CURRENT);
        mContextHubWrapper.onLocationSettingChanged(enabled);
    }

    /**
     * Obtains the latest WiFi availability setting value and notifies the Context Hub.
     *
     * @param forceUpdate True to force send update to the Context Hub, otherwise only send the
     *                    update when the WiFi availability changes.
     */
    private void sendWifiSettingUpdate(boolean forceUpdate) {
        synchronized (mSendWifiSettingUpdateLock) {
            WifiManager wifiManager = mContext.getSystemService(WifiManager.class);
            boolean wifiEnabled = wifiManager.isWifiEnabled();
            boolean wifiScanEnabled = wifiManager.isScanAlwaysAvailable();
            boolean wifiAvailable = wifiEnabled || wifiScanEnabled;
            if (forceUpdate || mIsWifiAvailable != wifiAvailable) {
                mIsWifiAvailable = wifiAvailable;
                mContextHubWrapper.onWifiSettingChanged(wifiAvailable);
            }
            if (forceUpdate || mIsWifiScanningEnabled != wifiScanEnabled) {
                mIsWifiScanningEnabled = wifiScanEnabled;
                mContextHubWrapper.onWifiScanningSettingChanged(wifiScanEnabled);
            }
            if (forceUpdate || mIsWifiMainEnabled != wifiEnabled) {
                mIsWifiMainEnabled = wifiEnabled;
                mContextHubWrapper.onWifiMainSettingChanged(wifiEnabled);
            }
        }
    }

    /**
     * Obtains the latest BT availability setting value and notifies the Context Hub.
     *
     * @param forceUpdate True to force send update to the Context Hub, otherwise only send the
     *                    update when the BT availability changes.
     */
    private void sendBtSettingUpdate(boolean forceUpdate) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        // Adapter may be null if BT is not supported.
        if (adapter != null) {
            boolean btEnabled = adapter.isEnabled();
            boolean btScanEnabled = adapter.isBleScanAlwaysAvailable();
            if (forceUpdate || mIsBtScanningEnabled != btScanEnabled) {
                mIsBtScanningEnabled = btScanEnabled;
                mContextHubWrapper.onBtScanningSettingChanged(btScanEnabled);
            }
            if (forceUpdate || mIsBtMainEnabled != btEnabled) {
                mIsBtMainEnabled = btEnabled;
                mContextHubWrapper.onBtMainSettingChanged(btEnabled);
            }
        } else {
            Log.d(TAG, "BT adapter not available. Getting permissions from user settings");
            boolean btEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.BLUETOOTH_ON, 0) == 1;
            boolean btScanEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE, 0) == 1;
            if (forceUpdate || mIsBtMainEnabled != btEnabled) {
                mIsBtMainEnabled = btEnabled;
                mContextHubWrapper.onBtMainSettingChanged(mIsBtMainEnabled);
            }
            if (forceUpdate || mIsBtScanningEnabled != btScanEnabled) {
                mIsBtScanningEnabled = btScanEnabled;
                mContextHubWrapper.onBtScanningSettingChanged(mIsBtScanningEnabled);
            }
        }
    }

    /**
     * Obtains the latest airplane mode setting value and notifies the Context Hub.
     */
    private void sendAirplaneModeSettingUpdate() {
        boolean enabled =
                (Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0)
                        == 1);
        mContextHubWrapper.onAirplaneModeSettingChanged(enabled);
    }

    /**
     * Notifies a microphone disable settings change to the Context Hub.
     */
    private void sendMicrophoneDisableSettingUpdate(boolean enabled) {
        Log.d(TAG, "Mic Disabled Setting: " + enabled);
        // The SensorPrivacyManager reports if microphone privacy was enabled,
        // which translates to microphone access being disabled (and vice-versa).
        // With this in mind, we flip the argument before piping it to CHRE.
        mContextHubWrapper.onMicrophoneSettingChanged(!enabled);
    }

    /**
     * Obtains the latest microphone disabled setting for the current user and notifies the Context
     * Hub.
     */
    private void sendMicrophoneDisableSettingUpdateForCurrentUser() {
        boolean isEnabled = mSensorPrivacyManagerInternal == null ? false :
                mSensorPrivacyManagerInternal.isSensorPrivacyEnabled(
                getCurrentUserId(), SensorPrivacyManager.Sensors.MICROPHONE);
        sendMicrophoneDisableSettingUpdate(isEnabled);
    }

    /**
     * Invokes a daily timer to query all context hubs
     */
    private void scheduleDailyMetricSnapshot() {
        Runnable queryAllContextHub = () -> {
            for (int contextHubId : mContextHubIdToInfoMap.keySet()) {
                mMetricQueryPendingContextHubIds.add(contextHubId);
                queryNanoAppsInternal(contextHubId);
            }
        };
        try {
            mDailyMetricTimer.schedule(queryAllContextHub, PERIOD_METRIC_QUERY_DAYS,
                    TimeUnit.DAYS);
        } catch (Exception e) {
            Log.e(TAG, "Error when schedule a timer", e);
        }
    }

    private String getCallingPackageName() {
        return mContext.getPackageManager().getNameForUid(Binder.getCallingUid());
    }

    private int getCurrentUserId() {
        final long id = Binder.clearCallingIdentity();
        try {
            UserInfo currentUser = ActivityManager.getService().getCurrentUser();
            return currentUser.id;
        } catch (RemoteException e) {
            // Activity manager not running, nothing we can do - assume user 0.
        } finally {
            Binder.restoreCallingIdentity(id);
        }
        return UserHandle.USER_SYSTEM;
    }

    /**
     * Send a microphone disable settings update whenever the foreground user changes. We always
     * send a settings update regardless of the previous state for the same user since the CHRE
     * framework is expected to handle repeated identical setting update.
     */
    public void onUserChanged() {
        Log.d(TAG, "User changed to id: " + getCurrentUserId());
        sendLocationSettingUpdate();
        sendMicrophoneDisableSettingUpdateForCurrentUser();
    }
}
