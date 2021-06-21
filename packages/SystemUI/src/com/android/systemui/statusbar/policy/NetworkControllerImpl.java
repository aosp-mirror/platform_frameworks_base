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

package com.android.systemui.statusbar.policy;

import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN;
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_INOUT;
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_NONE;
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.CellSignalStrength;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.Utils;
import com.android.settingslib.mobile.MobileMappings.Config;
import com.android.settingslib.mobile.MobileStatusTracker.SubscriptionDefaults;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.util.CarrierConfigTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Platform implementation of the network controller. **/
@SysUISingleton
public class NetworkControllerImpl extends BroadcastReceiver
        implements NetworkController, DemoMode, DataUsageController.NetworkNameProvider, Dumpable {
    // debug
    static final String TAG = "NetworkController";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    // additional diagnostics, but not logspew
    static final boolean CHATTY =  Log.isLoggable(TAG + "Chat", Log.DEBUG);

    private static final int EMERGENCY_NO_CONTROLLERS = 0;
    private static final int EMERGENCY_FIRST_CONTROLLER = 100;
    private static final int EMERGENCY_VOICE_CONTROLLER = 200;
    private static final int EMERGENCY_NO_SUB = 300;
    private static final int EMERGENCY_ASSUMED_VOICE_CONTROLLER = 400;
    private static final int HISTORY_SIZE = 16;
    private static final SimpleDateFormat SSDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private final Context mContext;
    private final TelephonyManager mPhone;
    private final TelephonyListenerManager mTelephonyListenerManager;
    private final WifiManager mWifiManager;
    private final ConnectivityManager mConnectivityManager;
    private final SubscriptionManager mSubscriptionManager;
    private final boolean mHasMobileDataFeature;
    private final SubscriptionDefaults mSubDefaults;
    private final DataSaverController mDataSaverController;
    private final CurrentUserTracker mUserTracker;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final DemoModeController mDemoModeController;
    private final Object mLock = new Object();
    private final boolean mProviderModel;
    private Config mConfig;
    private final CarrierConfigTracker mCarrierConfigTracker;

    private TelephonyCallback.ActiveDataSubscriptionIdListener mPhoneStateListener;
    private int mActiveMobileDataSubscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    // Subcontrollers.
    @VisibleForTesting
    final WifiSignalController mWifiSignalController;

    @VisibleForTesting
    final EthernetSignalController mEthernetSignalController;

    @VisibleForTesting
    final SparseArray<MobileSignalController> mMobileSignalControllers = new SparseArray<>();
    // When no SIMs are around at setup, and one is added later, it seems to default to the first
    // SIM for most actions.  This may be null if there aren't any SIMs around.
    private MobileSignalController mDefaultSignalController;
    private final AccessPointControllerImpl mAccessPoints;
    private final DataUsageController mDataUsageController;

    private boolean mInetCondition; // Used for Logging and demo.

    // BitSets indicating which network transport types (e.g., TRANSPORT_WIFI, TRANSPORT_MOBILE) are
    // connected and validated, respectively.
    private final BitSet mConnectedTransports = new BitSet();
    private final BitSet mValidatedTransports = new BitSet();

    // States that don't belong to a subcontroller.
    private boolean mAirplaneMode = false;
    private boolean mHasNoSubs;
    private boolean mNoDefaultNetwork = false;
    private boolean mNoNetworksAvailable = true;
    private Locale mLocale = null;
    // This list holds our ordering.
    private List<SubscriptionInfo> mCurrentSubscriptions = new ArrayList<>();

    // Save the previous HISTORY_SIZE states for logging.
    private final String[] mHistory = new String[HISTORY_SIZE];
    // Where to copy the next state into.
    private int mHistoryIndex;

    @VisibleForTesting
    boolean mListening;

    // The current user ID.
    private int mCurrentUserId;

    private OnSubscriptionsChangedListener mSubscriptionListener;
    private NetworkCapabilities mLastDefaultNetworkCapabilities;
    // Handler that all broadcasts are received on.
    private final Handler mReceiverHandler;
    private final Looper mBgLooper;
    private final Executor mBgExecutor;
    // Handler that all callbacks are made on.
    private final CallbackHandler mCallbackHandler;

    private int mEmergencySource;
    private boolean mIsEmergency;

    @VisibleForTesting
    ServiceState mLastServiceState;
    private boolean mUserSetup;
    private boolean mSimDetected;
    private boolean mForceCellularValidated;

    private ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onConfigChanged(Configuration newConfig) {
                    mConfig = Config.readConfig(mContext);
                    mReceiverHandler.post(() -> handleConfigurationChanged());
                }
            };
    /**
     * Construct this controller object and register for updates.
     */
    @Inject
    public NetworkControllerImpl(
            Context context,
            @Background Looper bgLooper,
            @Background Executor bgExecutor,
            SubscriptionManager subscriptionManager,
            CallbackHandler callbackHandler,
            DeviceProvisionedController deviceProvisionedController,
            BroadcastDispatcher broadcastDispatcher,
            ConnectivityManager connectivityManager,
            TelephonyManager telephonyManager,
            TelephonyListenerManager telephonyListenerManager,
            @Nullable WifiManager wifiManager,
            NetworkScoreManager networkScoreManager,
            AccessPointControllerImpl accessPointController,
            DemoModeController demoModeController,
            CarrierConfigTracker carrierConfigTracker) {
        this(context, connectivityManager,
                telephonyManager,
                telephonyListenerManager,
                wifiManager,
                networkScoreManager,
                subscriptionManager,
                Config.readConfig(context),
                bgLooper,
                bgExecutor,
                callbackHandler,
                accessPointController,
                new DataUsageController(context),
                new SubscriptionDefaults(),
                deviceProvisionedController,
                broadcastDispatcher,
                demoModeController,
                carrierConfigTracker);
        mReceiverHandler.post(mRegisterListeners);
    }

    @VisibleForTesting
    NetworkControllerImpl(Context context, ConnectivityManager connectivityManager,
            TelephonyManager telephonyManager,
            TelephonyListenerManager telephonyListenerManager,
            WifiManager wifiManager,
            NetworkScoreManager networkScoreManager,
            SubscriptionManager subManager, Config config, Looper bgLooper,
            Executor bgExecutor,
            CallbackHandler callbackHandler,
            AccessPointControllerImpl accessPointController,
            DataUsageController dataUsageController,
            SubscriptionDefaults defaultsHandler,
            DeviceProvisionedController deviceProvisionedController,
            BroadcastDispatcher broadcastDispatcher,
            DemoModeController demoModeController,
            CarrierConfigTracker carrierConfigTracker) {
        mContext = context;
        mTelephonyListenerManager = telephonyListenerManager;
        mConfig = config;
        mReceiverHandler = new Handler(bgLooper);
        mBgLooper = bgLooper;
        mBgExecutor = bgExecutor;
        mCallbackHandler = callbackHandler;
        mDataSaverController = new DataSaverControllerImpl(context);
        mBroadcastDispatcher = broadcastDispatcher;

        mSubscriptionManager = subManager;
        mSubDefaults = defaultsHandler;
        mConnectivityManager = connectivityManager;
        mHasMobileDataFeature = telephonyManager.isDataCapable();
        mDemoModeController = demoModeController;
        mCarrierConfigTracker = carrierConfigTracker;

        // telephony
        mPhone = telephonyManager;

        // wifi
        mWifiManager = wifiManager;

        mLocale = mContext.getResources().getConfiguration().locale;
        mAccessPoints = accessPointController;
        mDataUsageController = dataUsageController;
        mDataUsageController.setNetworkController(this);
        // TODO: Find a way to move this into DataUsageController.
        mDataUsageController.setCallback(new DataUsageController.Callback() {
            @Override
            public void onMobileDataEnabled(boolean enabled) {
                mCallbackHandler.setMobileDataEnabled(enabled);
                notifyControllersMobileDataChanged();
            }
        });
        mWifiSignalController = new WifiSignalController(mContext, mHasMobileDataFeature,
                mCallbackHandler, this, mWifiManager, mConnectivityManager, networkScoreManager);

        mEthernetSignalController = new EthernetSignalController(mContext, mCallbackHandler, this);

        // AIRPLANE_MODE_CHANGED is sent at boot; we've probably already missed it
        updateAirplaneMode(true /* force callback */);
        mUserTracker = new CurrentUserTracker(broadcastDispatcher) {
            @Override
            public void onUserSwitched(int newUserId) {
                NetworkControllerImpl.this.onUserSwitched(newUserId);
            }
        };
        mUserTracker.startTracking();
        deviceProvisionedController.addCallback(new DeviceProvisionedListener() {
            @Override
            public void onUserSetupChanged() {
                setUserSetupComplete(deviceProvisionedController.isUserSetup(
                        deviceProvisionedController.getCurrentUser()));
            }
        });

        WifiManager.ScanResultsCallback scanResultsCallback =
                new WifiManager.ScanResultsCallback() {
            @Override
            public void onScanResultsAvailable() {
                mNoNetworksAvailable = true;
                for (ScanResult scanResult : mWifiManager.getScanResults()) {
                    if (!scanResult.SSID.equals(mWifiSignalController.getState().ssid)) {
                        mNoNetworksAvailable = false;
                        break;
                    }
                }
                // Only update the network availability if there is no default network.
                if (mNoDefaultNetwork) {
                    mCallbackHandler.setConnectivityStatus(mNoDefaultNetwork, !mInetCondition,
                            mNoNetworksAvailable);
                }
            }
        };

        if (mWifiManager != null) {
            mWifiManager.registerScanResultsCallback(mReceiverHandler::post, scanResultsCallback);
        }

        NetworkCallback callback =
                new NetworkCallback(NetworkCallback.FLAG_INCLUDE_LOCATION_INFO){
            private Network mLastNetwork;
            private NetworkCapabilities mLastNetworkCapabilities;

            @Override
            public void onLost(Network network) {
                mLastNetwork = null;
                mLastNetworkCapabilities = null;
                mLastDefaultNetworkCapabilities = null;
                String callback = new StringBuilder()
                        .append(SSDF.format(System.currentTimeMillis())).append(",")
                        .append("onLost: ")
                        .append("network=").append(network)
                        .toString();
                recordLastNetworkCallback(callback);
                updateConnectivity();
            }

            @Override
            public void onCapabilitiesChanged(
                Network network, NetworkCapabilities networkCapabilities) {
                boolean lastValidated = (mLastNetworkCapabilities != null) &&
                    mLastNetworkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED);
                boolean validated =
                    networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED);

                // This callback is invoked a lot (i.e. when RSSI changes), so avoid updating
                // icons when connectivity state has remained the same.
                if (network.equals(mLastNetwork) && validated == lastValidated) {
                    // Should not rely on getTransportTypes() returning the same order of transport
                    // types. So sort the array before comparing.
                    int[] newTypes = getProcessedTransportTypes(networkCapabilities);
                    Arrays.sort(newTypes);

                    int[] lastTypes = (mLastNetworkCapabilities != null)
                            ? getProcessedTransportTypes(mLastNetworkCapabilities) : null;
                    if (lastTypes != null) Arrays.sort(lastTypes);

                    if (Arrays.equals(newTypes, lastTypes)) {
                        return;
                    }
                }
                mLastNetwork = network;
                mLastNetworkCapabilities = networkCapabilities;
                mLastDefaultNetworkCapabilities = networkCapabilities;
                String callback = new StringBuilder()
                        .append(SSDF.format(System.currentTimeMillis())).append(",")
                        .append("onCapabilitiesChanged: ")
                        .append("network=").append(network).append(",")
                        .append("networkCapabilities=").append(networkCapabilities)
                        .toString();
                recordLastNetworkCallback(callback);
                updateConnectivity();
            }
        };
        // Even though this callback runs on the receiver handler thread which also processes the
        // CONNECTIVITY_ACTION broadcasts, the broadcast and callback might come in at different
        // times. This is safe since updateConnectivity() builds the list of transports from
        // scratch.
        // TODO: Move off of the deprecated CONNECTIVITY_ACTION broadcast and rely on callbacks
        // exclusively for status bar icons.
        mConnectivityManager.registerDefaultNetworkCallback(callback, mReceiverHandler);
        // Run the listener on our bg looper
        mPhoneStateListener = subId -> {
            mBgExecutor.execute(() -> {
                // For data switching from A to B, we assume B is validated for up to 2 seconds if:
                // 1) A and B are in the same subscription group e.g. CBRS data switch. And
                // 2) A was validated before the switch.
                // This is to provide smooth transition for UI without showing cross during data
                // switch.
                if (keepCellularValidationBitInSwitch(mActiveMobileDataSubscription, subId)) {
                    if (DEBUG) Log.d(TAG, ": mForceCellularValidated to true.");
                    mForceCellularValidated = true;
                    mReceiverHandler.removeCallbacks(mClearForceValidated);
                    mReceiverHandler.postDelayed(mClearForceValidated, 2000);
                }
                mActiveMobileDataSubscription = subId;
                doUpdateMobileControllers();
            });
        };

        mDemoModeController.addCallback(this);
        mProviderModel = FeatureFlagUtils.isEnabled(
                mContext, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
    }

    private final Runnable mClearForceValidated = () -> {
        if (DEBUG) Log.d(TAG, ": mClearForceValidated");
        mForceCellularValidated = false;
        updateConnectivity();
    };

    boolean isInGroupDataSwitch(int subId1, int subId2) {
        SubscriptionInfo info1 = mSubscriptionManager.getActiveSubscriptionInfo(subId1);
        SubscriptionInfo info2 = mSubscriptionManager.getActiveSubscriptionInfo(subId2);
        return (info1 != null && info2 != null && info1.getGroupUuid() != null
            && info1.getGroupUuid().equals(info2.getGroupUuid()));
    }

    boolean keepCellularValidationBitInSwitch(int sourceSubId, int destSubId) {
        return mValidatedTransports.get(TRANSPORT_CELLULAR)
                && isInGroupDataSwitch(sourceSubId, destSubId);
    }

    public DataSaverController getDataSaverController() {
        return mDataSaverController;
    }

    @VisibleForTesting
    void registerListeners() {
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.registerListener();
        }
        if (mSubscriptionListener == null) {
            mSubscriptionListener = new SubListener(mBgLooper);
        }
        mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionListener);
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(mPhoneStateListener);

        // broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        filter.addAction(TelephonyManager.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED);
        filter.addAction(Intent.ACTION_SERVICE_STATE);
        filter.addAction(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        mBroadcastDispatcher.registerReceiverWithHandler(this, filter, mReceiverHandler);
        mListening = true;

        // Initial setup of connectivity. Handled as if we had received a sticky broadcast of
        // ConnectivityManager.CONNECTIVITY_ACTION.
        mReceiverHandler.post(this::updateConnectivity);

        // Initial setup of WifiSignalController. Handled as if we had received a sticky broadcast
        // of WifiManager.WIFI_STATE_CHANGED_ACTION or WifiManager.NETWORK_STATE_CHANGED_ACTION
        mReceiverHandler.post(mWifiSignalController::fetchInitialState);

        // Initial setup of mLastServiceState. Only run if there is no service state yet.
        // Each MobileSignalController will also get their corresponding
        mReceiverHandler.post(() -> {
            if (mLastServiceState == null) {
                mLastServiceState = mPhone.getServiceState();
                if (mMobileSignalControllers.size() == 0) {
                    recalculateEmergency();
                }
            }
        });
        updateMobileControllers();

        // Initial setup of emergency information. Handled as if we had received a sticky broadcast
        // of TelephonyManager.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED.
        mReceiverHandler.post(this::recalculateEmergency);
    }

    private void unregisterListeners() {
        mListening = false;
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.unregisterListener();
        }
        mSubscriptionManager.removeOnSubscriptionsChangedListener(mSubscriptionListener);
        mBroadcastDispatcher.unregisterReceiver(this);
    }

    public int getConnectedWifiLevel() {
        return mWifiSignalController.getState().level;
    }

    @Override
    public AccessPointController getAccessPointController() {
        return mAccessPoints;
    }

    @Override
    public DataUsageController getMobileDataController() {
        return mDataUsageController;
    }

    public void addEmergencyListener(EmergencyListener listener) {
        mCallbackHandler.setListening(listener, true);
        mCallbackHandler.setEmergencyCallsOnly(isEmergencyOnly());
    }

    public void removeEmergencyListener(EmergencyListener listener) {
        mCallbackHandler.setListening(listener, false);
    }

    public boolean hasMobileDataFeature() {
        return mHasMobileDataFeature;
    }

    public boolean hasVoiceCallingFeature() {
        return mPhone.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE;
    }

    private int[] getProcessedTransportTypes(NetworkCapabilities networkCapabilities) {
        int[] transportTypes = networkCapabilities.getTransportTypes();
        for (int i = 0; i < transportTypes.length; i++) {
            // For VCN over WiFi, the transportType is set to be TRANSPORT_CELLULAR in the
            // NetworkCapabilities, but we need to convert it into TRANSPORT_WIFI in order to
            // distinguish it from VCN over Cellular.
            if (transportTypes[i] == NetworkCapabilities.TRANSPORT_CELLULAR
                    && Utils.tryGetWifiInfoForVcn(networkCapabilities) != null) {
                transportTypes[i] = NetworkCapabilities.TRANSPORT_WIFI;
                break;
            }
        }
        return transportTypes;
    }

    private MobileSignalController getDataController() {
        int dataSubId = mSubDefaults.getActiveDataSubId();
        return getControllerWithSubId(dataSubId);
    }

    private MobileSignalController getControllerWithSubId(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            if (DEBUG) Log.e(TAG, "No data sim selected");
            return mDefaultSignalController;
        }
        if (mMobileSignalControllers.indexOfKey(subId) >= 0) {
            return mMobileSignalControllers.get(subId);
        }
        if (DEBUG) Log.e(TAG, "Cannot find controller for data sub: " + subId);
        return mDefaultSignalController;
    }

    @Override
    public String getMobileDataNetworkName() {
        MobileSignalController controller = getDataController();
        return controller != null ? controller.getState().networkNameData : "";
    }

    @Override
    public boolean isMobileDataNetworkInService() {
        MobileSignalController controller = getDataController();
        return controller != null && controller.isInService();
    }

    @Override
    public int getNumberSubscriptions() {
        return mMobileSignalControllers.size();
    }

    boolean isDataControllerDisabled() {
        MobileSignalController dataController = getDataController();
        if (dataController == null) {
            return false;
        }

        return dataController.isDataDisabled();
    }

    boolean isCarrierMergedWifi(int subId) {
        return mWifiSignalController.isCarrierMergedWifi(subId);
    }

    boolean hasDefaultNetwork() {
        return !mNoDefaultNetwork;
    }

    boolean isNonCarrierWifiNetworkAvailable() {
        return !mNoNetworksAvailable;
    }

    boolean isEthernetDefault() {
        return mConnectedTransports.get(NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    String getNetworkNameForCarrierWiFi(int subId) {
        MobileSignalController controller = getControllerWithSubId(subId);
        return controller != null ? controller.getNetworkNameForCarrierWiFi() : "";
    }

    void notifyWifiLevelChange(int level) {
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.notifyWifiLevelChange(level);
        }
    }

    void notifyDefaultMobileLevelChange(int level) {
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.notifyDefaultMobileLevelChange(level);
        }
    }

    private void notifyControllersMobileDataChanged() {
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.onMobileDataChanged();
        }
    }

    public boolean isEmergencyOnly() {
        if (mMobileSignalControllers.size() == 0) {
            // When there are no active subscriptions, determine emengency state from last
            // broadcast.
            mEmergencySource = EMERGENCY_NO_CONTROLLERS;
            return mLastServiceState != null && mLastServiceState.isEmergencyOnly();
        }
        int voiceSubId = mSubDefaults.getDefaultVoiceSubId();
        if (!SubscriptionManager.isValidSubscriptionId(voiceSubId)) {
            for (int i = 0; i < mMobileSignalControllers.size(); i++) {
                MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
                if (!mobileSignalController.getState().isEmergency) {
                    mEmergencySource = EMERGENCY_FIRST_CONTROLLER
                            + mobileSignalController.mSubscriptionInfo.getSubscriptionId();
                    if (DEBUG) Log.d(TAG, "Found emergency " + mobileSignalController.mTag);
                    return false;
                }
            }
        }
        if (mMobileSignalControllers.indexOfKey(voiceSubId) >= 0) {
            mEmergencySource = EMERGENCY_VOICE_CONTROLLER + voiceSubId;
            if (DEBUG) Log.d(TAG, "Getting emergency from " + voiceSubId);
            return mMobileSignalControllers.get(voiceSubId).getState().isEmergency;
        }
        // If we have the wrong subId but there is only one sim anyway, assume it should be the
        // default.
        if (mMobileSignalControllers.size() == 1) {
            mEmergencySource = EMERGENCY_ASSUMED_VOICE_CONTROLLER
                    + mMobileSignalControllers.keyAt(0);
            if (DEBUG) Log.d(TAG, "Getting assumed emergency from "
                    + mMobileSignalControllers.keyAt(0));
            return mMobileSignalControllers.valueAt(0).getState().isEmergency;
        }
        if (DEBUG) Log.e(TAG, "Cannot find controller for voice sub: " + voiceSubId);
        mEmergencySource = EMERGENCY_NO_SUB + voiceSubId;
        // Something is wrong, better assume we can't make calls...
        return true;
    }

    /**
     * Emergency status may have changed (triggered by MobileSignalController),
     * so we should recheck and send out the state to listeners.
     */
    void recalculateEmergency() {
        mIsEmergency = isEmergencyOnly();
        mCallbackHandler.setEmergencyCallsOnly(mIsEmergency);
    }

    @Override
    public void addCallback(@NonNull SignalCallback cb) {
        cb.setSubs(mCurrentSubscriptions);
        cb.setIsAirplaneMode(new IconState(mAirplaneMode,
                TelephonyIcons.FLIGHT_MODE_ICON, R.string.accessibility_airplane_mode, mContext));
        cb.setNoSims(mHasNoSubs, mSimDetected);
        if (mProviderModel) {
            cb.setConnectivityStatus(mNoDefaultNetwork, !mInetCondition, mNoNetworksAvailable);
        }
        mWifiSignalController.notifyListeners(cb);
        mEthernetSignalController.notifyListeners(cb);
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.notifyListeners(cb);
            if (mProviderModel) {
                mobileSignalController.refreshCallIndicator(cb);
            }
        }
        mCallbackHandler.setListening(cb, true);
    }

    @Override
    public void removeCallback(@NonNull SignalCallback cb) {
        mCallbackHandler.setListening(cb, false);
    }

    @Override
    public void setWifiEnabled(final boolean enabled) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... args) {
                mWifiManager.setWifiEnabled(enabled);
                return null;
            }
        }.execute();
    }

    private void onUserSwitched(int newUserId) {
        mCurrentUserId = newUserId;
        mAccessPoints.onUserSwitched(newUserId);
        updateConnectivity();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (CHATTY) {
            Log.d(TAG, "onReceive: intent=" + intent);
        }
        final String action = intent.getAction();
        switch (action) {
            case ConnectivityManager.CONNECTIVITY_ACTION:
                updateConnectivity();
                break;
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                refreshLocale();
                updateAirplaneMode(false);
                break;
            case TelephonyManager.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED:
                // We are using different subs now, we might be able to make calls.
                recalculateEmergency();
                break;
            case TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED:
                // Notify every MobileSignalController so they can know whether they are the
                // data sim or not.
                for (int i = 0; i < mMobileSignalControllers.size(); i++) {
                    MobileSignalController controller = mMobileSignalControllers.valueAt(i);
                    controller.handleBroadcast(intent);
                }
                mConfig = Config.readConfig(mContext);
                mReceiverHandler.post(this::handleConfigurationChanged);
                break;
            case Intent.ACTION_SIM_STATE_CHANGED:
                // Avoid rebroadcast because SysUI is direct boot aware.
                if (intent.getBooleanExtra(Intent.EXTRA_REBROADCAST_ON_UNLOCK, false)) {
                    break;
                }
                // Might have different subscriptions now.
                updateMobileControllers();
                break;
            case Intent.ACTION_SERVICE_STATE:
                mLastServiceState = ServiceState.newFromBundle(intent.getExtras());
                if (mMobileSignalControllers.size() == 0) {
                    // If none of the subscriptions are active, we might need to recalculate
                    // emergency state.
                    recalculateEmergency();
                }
                break;
            case CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED:
                mConfig = Config.readConfig(mContext);
                mReceiverHandler.post(this::handleConfigurationChanged);
                break;
            default:
                int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    if (mMobileSignalControllers.indexOfKey(subId) >= 0) {
                        mMobileSignalControllers.get(subId).handleBroadcast(intent);
                    } else {
                        // Can't find this subscription...  We must be out of date.
                        updateMobileControllers();
                    }
                } else {
                    // No sub id, must be for the wifi.
                    mWifiSignalController.handleBroadcast(intent);
                }
                break;
        }
    }

    @VisibleForTesting
    void handleConfigurationChanged() {
        updateMobileControllers();
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController controller = mMobileSignalControllers.valueAt(i);
            controller.setConfiguration(mConfig);
            if (mProviderModel) {
                controller.refreshCallIndicator(mCallbackHandler);
            }
        }
        refreshLocale();
    }

    private void updateMobileControllers() {
        if (!mListening) {
            return;
        }
        doUpdateMobileControllers();
    }

    private void filterMobileSubscriptionInSameGroup(List<SubscriptionInfo> subscriptions) {
        if (subscriptions.size() == 2) {
            SubscriptionInfo info1 = subscriptions.get(0);
            SubscriptionInfo info2 = subscriptions.get(1);
            if (info1.getGroupUuid() != null && info1.getGroupUuid().equals(info2.getGroupUuid())) {
                // If both subscriptions are primary, show both.
                if (!info1.isOpportunistic() && !info2.isOpportunistic()) return;

                // If carrier required, always show signal bar of primary subscription.
                // Otherwise, show whichever subscription is currently active for Internet.
                boolean alwaysShowPrimary = CarrierConfigManager.getDefaultConfig()
                        .getBoolean(CarrierConfigManager
                        .KEY_ALWAYS_SHOW_PRIMARY_SIGNAL_BAR_IN_OPPORTUNISTIC_NETWORK_BOOLEAN);
                if (alwaysShowPrimary) {
                    subscriptions.remove(info1.isOpportunistic() ? info1 : info2);
                } else {
                    subscriptions.remove(info1.getSubscriptionId() == mActiveMobileDataSubscription
                            ? info2 : info1);
                }
            }
        }
    }

    @VisibleForTesting
    void doUpdateMobileControllers() {
        List<SubscriptionInfo> subscriptions = mSubscriptionManager
                .getCompleteActiveSubscriptionInfoList();
        if (subscriptions == null) {
            subscriptions = Collections.emptyList();
        }

        filterMobileSubscriptionInSameGroup(subscriptions);

        // If there have been no relevant changes to any of the subscriptions, we can leave as is.
        if (hasCorrectMobileControllers(subscriptions)) {
            // Even if the controllers are correct, make sure we have the right no sims state.
            // Such as on boot, don't need any controllers, because there are no sims,
            // but we still need to update the no sim state.
            updateNoSims();
            return;
        }
        synchronized (mLock) {
            setCurrentSubscriptionsLocked(subscriptions);
        }
        updateNoSims();
        recalculateEmergency();
    }

    @VisibleForTesting
    protected void updateNoSims() {
        boolean hasNoSubs = mHasMobileDataFeature && mMobileSignalControllers.size() == 0;
        boolean simDetected = hasAnySim();
        if (hasNoSubs != mHasNoSubs || simDetected != mSimDetected) {
            mHasNoSubs = hasNoSubs;
            mSimDetected = simDetected;
            mCallbackHandler.setNoSims(mHasNoSubs, mSimDetected);
        }
    }

    private boolean hasAnySim() {
        int simCount = mPhone.getActiveModemCount();
        for (int i = 0; i < simCount; i++) {
            int state = mPhone.getSimState(i);
            if (state != TelephonyManager.SIM_STATE_ABSENT
                    && state != TelephonyManager.SIM_STATE_UNKNOWN) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    public void setCurrentSubscriptionsLocked(List<SubscriptionInfo> subscriptions) {
        Collections.sort(subscriptions, new Comparator<SubscriptionInfo>() {
            @Override
            public int compare(SubscriptionInfo lhs, SubscriptionInfo rhs) {
                return lhs.getSimSlotIndex() == rhs.getSimSlotIndex()
                        ? lhs.getSubscriptionId() - rhs.getSubscriptionId()
                        : lhs.getSimSlotIndex() - rhs.getSimSlotIndex();
            }
        });
        mCurrentSubscriptions = subscriptions;

        SparseArray<MobileSignalController> cachedControllers =
                new SparseArray<MobileSignalController>();
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            cachedControllers.put(mMobileSignalControllers.keyAt(i),
                    mMobileSignalControllers.valueAt(i));
        }
        mMobileSignalControllers.clear();
        final int num = subscriptions.size();
        for (int i = 0; i < num; i++) {
            int subId = subscriptions.get(i).getSubscriptionId();
            // If we have a copy of this controller already reuse it, otherwise make a new one.
            if (cachedControllers.indexOfKey(subId) >= 0) {
                mMobileSignalControllers.put(subId, cachedControllers.get(subId));
                cachedControllers.remove(subId);
            } else {
                MobileSignalController controller = new MobileSignalController(mContext, mConfig,
                        mHasMobileDataFeature, mPhone.createForSubscriptionId(subId),
                        mCallbackHandler, this, subscriptions.get(i),
                        mSubDefaults, mReceiverHandler.getLooper(), mCarrierConfigTracker);
                controller.setUserSetupComplete(mUserSetup);
                mMobileSignalControllers.put(subId, controller);
                if (subscriptions.get(i).getSimSlotIndex() == 0) {
                    mDefaultSignalController = controller;
                }
                if (mListening) {
                    controller.registerListener();
                }
            }
        }
        if (mListening) {
            for (int i = 0; i < cachedControllers.size(); i++) {
                int key = cachedControllers.keyAt(i);
                if (cachedControllers.get(key) == mDefaultSignalController) {
                    mDefaultSignalController = null;
                }
                cachedControllers.get(key).unregisterListener();
            }
        }
        mCallbackHandler.setSubs(subscriptions);
        notifyAllListeners();

        // There may be new MobileSignalControllers around, make sure they get the current
        // inet condition and airplane mode.
        pushConnectivityToSignals();
        updateAirplaneMode(true /* force */);
    }

    private void setUserSetupComplete(final boolean userSetup) {
        mReceiverHandler.post(() -> handleSetUserSetupComplete(userSetup));
    }

    private void handleSetUserSetupComplete(boolean userSetup) {
        mUserSetup = userSetup;
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController controller = mMobileSignalControllers.valueAt(i);
            controller.setUserSetupComplete(mUserSetup);
        }
    }

    @VisibleForTesting
    boolean hasCorrectMobileControllers(List<SubscriptionInfo> allSubscriptions) {
        if (allSubscriptions.size() != mMobileSignalControllers.size()) {
            return false;
        }
        for (SubscriptionInfo info : allSubscriptions) {
            if (mMobileSignalControllers.indexOfKey(info.getSubscriptionId()) < 0) {
                return false;
            }
        }
        return true;
    }

    @VisibleForTesting
    void setNoNetworksAvailable(boolean noNetworksAvailable) {
        mNoNetworksAvailable = noNetworksAvailable;
    }

    private void updateAirplaneMode(boolean force) {
        boolean airplaneMode = (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
        if (airplaneMode != mAirplaneMode || force) {
            mAirplaneMode = airplaneMode;
            for (int i = 0; i < mMobileSignalControllers.size(); i++) {
                MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
                mobileSignalController.setAirplaneMode(mAirplaneMode);
            }
            notifyListeners();
        }
    }

    private void refreshLocale() {
        Locale current = mContext.getResources().getConfiguration().locale;
        if (!current.equals(mLocale)) {
            mLocale = current;
            mWifiSignalController.refreshLocale();
            notifyAllListeners();
        }
    }

    /**
     * Forces update of all callbacks on both SignalClusters and
     * NetworkSignalChangedCallbacks.
     */
    private void notifyAllListeners() {
        notifyListeners();
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.notifyListeners();
        }
        mWifiSignalController.notifyListeners();
        mEthernetSignalController.notifyListeners();
    }

    /**
     * Notifies listeners of changes in state of to the NetworkController, but
     * does not notify for any info on SignalControllers, for that call
     * notifyAllListeners.
     */
    private void notifyListeners() {
        mCallbackHandler.setIsAirplaneMode(new IconState(mAirplaneMode,
                TelephonyIcons.FLIGHT_MODE_ICON, R.string.accessibility_airplane_mode, mContext));
        mCallbackHandler.setNoSims(mHasNoSubs, mSimDetected);
    }

    /**
     * Update the Inet conditions and what network we are connected to.
     */
    private void updateConnectivity() {
        mConnectedTransports.clear();
        mValidatedTransports.clear();
        if (mLastDefaultNetworkCapabilities != null) {
            for (int transportType : mLastDefaultNetworkCapabilities.getTransportTypes()) {
                if (transportType != NetworkCapabilities.TRANSPORT_CELLULAR
                        && transportType != NetworkCapabilities.TRANSPORT_WIFI
                        && transportType != NetworkCapabilities.TRANSPORT_ETHERNET) {
                    continue;
                }
                if (transportType == NetworkCapabilities.TRANSPORT_CELLULAR
                        && Utils.tryGetWifiInfoForVcn(mLastDefaultNetworkCapabilities) != null) {
                    mConnectedTransports.set(NetworkCapabilities.TRANSPORT_WIFI);
                    if (mLastDefaultNetworkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)) {
                        mValidatedTransports.set(NetworkCapabilities.TRANSPORT_WIFI);
                    }
                } else {
                    mConnectedTransports.set(transportType);
                    if (mLastDefaultNetworkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)) {
                        mValidatedTransports.set(transportType);
                    }
                }
            }
        }

        if (mForceCellularValidated) mValidatedTransports.set(TRANSPORT_CELLULAR);

        if (CHATTY) {
            Log.d(TAG, "updateConnectivity: mConnectedTransports=" + mConnectedTransports);
            Log.d(TAG, "updateConnectivity: mValidatedTransports=" + mValidatedTransports);
        }

        mInetCondition = mValidatedTransports.get(NetworkCapabilities.TRANSPORT_CELLULAR)
                || mValidatedTransports.get(NetworkCapabilities.TRANSPORT_WIFI)
                || mValidatedTransports.get(NetworkCapabilities.TRANSPORT_ETHERNET);

        pushConnectivityToSignals();
        if (mProviderModel) {
            mNoDefaultNetwork = !mConnectedTransports.get(NetworkCapabilities.TRANSPORT_CELLULAR)
                && !mConnectedTransports.get(NetworkCapabilities.TRANSPORT_WIFI)
                && !mConnectedTransports.get(NetworkCapabilities.TRANSPORT_ETHERNET);
            mCallbackHandler.setConnectivityStatus(mNoDefaultNetwork, !mInetCondition,
                    mNoNetworksAvailable);
            for (int i = 0; i < mMobileSignalControllers.size(); i++) {
                MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
                mobileSignalController.updateNoCallingState();
            }
            notifyAllListeners();
        }
    }

    /**
     * Pushes the current connectivity state to all SignalControllers.
     */
    private void pushConnectivityToSignals() {
        // We want to update all the icons, all at once, for any condition change
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.updateConnectivity(mConnectedTransports, mValidatedTransports);
        }
        mWifiSignalController.updateConnectivity(mConnectedTransports, mValidatedTransports);
        mEthernetSignalController.updateConnectivity(mConnectedTransports, mValidatedTransports);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NetworkController state:");

        pw.println("  - telephony ------");
        pw.print("  hasVoiceCallingFeature()=");
        pw.println(hasVoiceCallingFeature());
        pw.println("  mListening=" + mListening);

        pw.println("  - connectivity ------");
        pw.print("  mConnectedTransports=");
        pw.println(mConnectedTransports);
        pw.print("  mValidatedTransports=");
        pw.println(mValidatedTransports);
        pw.print("  mInetCondition=");
        pw.println(mInetCondition);
        pw.print("  mAirplaneMode=");
        pw.println(mAirplaneMode);
        pw.print("  mLocale=");
        pw.println(mLocale);
        pw.print("  mLastServiceState=");
        pw.println(mLastServiceState);
        pw.print("  mIsEmergency=");
        pw.println(mIsEmergency);
        pw.print("  mEmergencySource=");
        pw.println(emergencyToString(mEmergencySource));

        pw.println("  - DefaultNetworkCallback -----");
        int size = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            if (mHistory[i] != null) {
                size++;
            }
        }
        for (int i = mHistoryIndex + HISTORY_SIZE - 1;
                i >= mHistoryIndex + HISTORY_SIZE - size; i--) {
            pw.println("  Previous NetworkCallback(" + (mHistoryIndex + HISTORY_SIZE - i) + "): "
                    + mHistory[i & (HISTORY_SIZE - 1)]);
        }

        pw.println("  - config ------");
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.dump(pw);
        }
        mWifiSignalController.dump(pw);

        mEthernetSignalController.dump(pw);

        mAccessPoints.dump(pw);

        mCallbackHandler.dump(pw);
    }

    private static final String emergencyToString(int emergencySource) {
        if (emergencySource > EMERGENCY_NO_SUB) {
            return "ASSUMED_VOICE_CONTROLLER(" + (emergencySource - EMERGENCY_VOICE_CONTROLLER)
                    + ")";
        } else if (emergencySource > EMERGENCY_NO_SUB) {
            return "NO_SUB(" + (emergencySource - EMERGENCY_NO_SUB) + ")";
        } else if (emergencySource > EMERGENCY_VOICE_CONTROLLER) {
            return "VOICE_CONTROLLER(" + (emergencySource - EMERGENCY_VOICE_CONTROLLER) + ")";
        } else if (emergencySource > EMERGENCY_FIRST_CONTROLLER) {
            return "FIRST_CONTROLLER(" + (emergencySource - EMERGENCY_FIRST_CONTROLLER) + ")";
        } else if (emergencySource == EMERGENCY_NO_CONTROLLERS) {
            return "NO_CONTROLLERS";
        }
        return "UNKNOWN_SOURCE";
    }

    private boolean mDemoInetCondition;
    private WifiSignalController.WifiState mDemoWifiState;

    @Override
    public void onDemoModeStarted() {
        if (DEBUG) Log.d(TAG, "Entering demo mode");
        unregisterListeners();
        mDemoInetCondition = mInetCondition;
        mDemoWifiState = mWifiSignalController.getState();
        mDemoWifiState.ssid = "DemoMode";
    }

    @Override
    public void onDemoModeFinished() {
        if (DEBUG) Log.d(TAG, "Exiting demo mode");
        // Update what MobileSignalControllers, because they may change
        // to set the number of sim slots.
        updateMobileControllers();
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController controller = mMobileSignalControllers.valueAt(i);
            controller.resetLastState();
        }
        mWifiSignalController.resetLastState();
        mReceiverHandler.post(mRegisterListeners);
        notifyAllListeners();
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoModeController.isInDemoMode()) {
            return;
        }

        String airplane = args.getString("airplane");
        if (airplane != null) {
            boolean show = airplane.equals("show");
            mCallbackHandler.setIsAirplaneMode(new IconState(show,
                    TelephonyIcons.FLIGHT_MODE_ICON, R.string.accessibility_airplane_mode,
                    mContext));
        }
        String fully = args.getString("fully");
        if (fully != null) {
            mDemoInetCondition = Boolean.parseBoolean(fully);
            BitSet connected = new BitSet();

            if (mDemoInetCondition) {
                connected.set(mWifiSignalController.mTransportType);
            }
            mWifiSignalController.updateConnectivity(connected, connected);
            for (int i = 0; i < mMobileSignalControllers.size(); i++) {
                MobileSignalController controller = mMobileSignalControllers.valueAt(i);
                if (mDemoInetCondition) {
                    connected.set(controller.mTransportType);
                }
                controller.updateConnectivity(connected, connected);
            }
        }
        String wifi = args.getString("wifi");
        if (wifi != null) {
            boolean show = wifi.equals("show");
            String level = args.getString("level");
            if (level != null) {
                mDemoWifiState.level = level.equals("null") ? -1
                        : Math.min(Integer.parseInt(level), WifiIcons.WIFI_LEVEL_COUNT - 1);
                mDemoWifiState.connected = mDemoWifiState.level >= 0;
            }
            String activity = args.getString("activity");
            if (activity != null) {
                switch (activity) {
                    case "inout":
                        mWifiSignalController.setActivity(DATA_ACTIVITY_INOUT);
                        break;
                    case "in":
                        mWifiSignalController.setActivity(DATA_ACTIVITY_IN);
                        break;
                    case "out":
                        mWifiSignalController.setActivity(DATA_ACTIVITY_OUT);
                        break;
                    default:
                        mWifiSignalController.setActivity(DATA_ACTIVITY_NONE);
                        break;
                }
            } else {
                mWifiSignalController.setActivity(DATA_ACTIVITY_NONE);
            }
            String ssid = args.getString("ssid");
            if (ssid != null) {
                mDemoWifiState.ssid = ssid;
            }
            mDemoWifiState.enabled = show;
            mWifiSignalController.notifyListeners();
        }
        String sims = args.getString("sims");
        if (sims != null) {
            int num = MathUtils.constrain(Integer.parseInt(sims), 1, 8);
            List<SubscriptionInfo> subs = new ArrayList<>();
            if (num != mMobileSignalControllers.size()) {
                mMobileSignalControllers.clear();
                int start = mSubscriptionManager.getActiveSubscriptionInfoCountMax();
                for (int i = start /* get out of normal index range */; i < start + num; i++) {
                    subs.add(addSignalController(i, i));
                }
                mCallbackHandler.setSubs(subs);
                for (int i = 0; i < mMobileSignalControllers.size(); i++) {
                    int key = mMobileSignalControllers.keyAt(i);
                    MobileSignalController controller = mMobileSignalControllers.get(key);
                    controller.notifyListeners();
                }
            }
        }
        String nosim = args.getString("nosim");
        if (nosim != null) {
            mHasNoSubs = nosim.equals("show");
            mCallbackHandler.setNoSims(mHasNoSubs, mSimDetected);
        }
        String mobile = args.getString("mobile");
        if (mobile != null) {
            boolean show = mobile.equals("show");
            String datatype = args.getString("datatype");
            String slotString = args.getString("slot");
            int slot = TextUtils.isEmpty(slotString) ? 0 : Integer.parseInt(slotString);
            slot = MathUtils.constrain(slot, 0, 8);
            // Ensure we have enough sim slots
            List<SubscriptionInfo> subs = new ArrayList<>();
            while (mMobileSignalControllers.size() <= slot) {
                int nextSlot = mMobileSignalControllers.size();
                subs.add(addSignalController(nextSlot, nextSlot));
            }
            if (!subs.isEmpty()) {
                mCallbackHandler.setSubs(subs);
            }
            // Hack to index linearly for easy use.
            MobileSignalController controller = mMobileSignalControllers.valueAt(slot);
            controller.getState().dataSim = datatype != null;
            controller.getState().isDefault = datatype != null;
            controller.getState().dataConnected = datatype != null;
            if (datatype != null) {
                controller.getState().iconGroup =
                        datatype.equals("1x") ? TelephonyIcons.ONE_X :
                        datatype.equals("3g") ? TelephonyIcons.THREE_G :
                        datatype.equals("4g") ? TelephonyIcons.FOUR_G :
                        datatype.equals("4g+") ? TelephonyIcons.FOUR_G_PLUS :
                        datatype.equals("5g") ? TelephonyIcons.NR_5G :
                        datatype.equals("5ge") ? TelephonyIcons.LTE_CA_5G_E :
                        datatype.equals("5g+") ? TelephonyIcons.NR_5G_PLUS :
                        datatype.equals("e") ? TelephonyIcons.E :
                        datatype.equals("g") ? TelephonyIcons.G :
                        datatype.equals("h") ? TelephonyIcons.H :
                        datatype.equals("h+") ? TelephonyIcons.H_PLUS :
                        datatype.equals("lte") ? TelephonyIcons.LTE :
                        datatype.equals("lte+") ? TelephonyIcons.LTE_PLUS :
                        datatype.equals("dis") ? TelephonyIcons.DATA_DISABLED :
                        datatype.equals("not") ? TelephonyIcons.NOT_DEFAULT_DATA :
                        TelephonyIcons.UNKNOWN;
            }
            if (args.containsKey("roam")) {
                controller.getState().roaming = "show".equals(args.getString("roam"));
            }
            String level = args.getString("level");
            if (level != null) {
                controller.getState().level = level.equals("null") ? -1
                        : Math.min(Integer.parseInt(level),
                                CellSignalStrength.getNumSignalStrengthLevels());
                controller.getState().connected = controller.getState().level >= 0;
            }
            if (args.containsKey("inflate")) {
                for (int i = 0; i < mMobileSignalControllers.size(); i++) {
                    mMobileSignalControllers.valueAt(i).mInflateSignalStrengths =
                            "true".equals(args.getString("inflate"));
                }
            }
            String activity = args.getString("activity");
            if (activity != null) {
                controller.getState().dataConnected = true;
                switch (activity) {
                    case "inout":
                        controller.setActivity(TelephonyManager.DATA_ACTIVITY_INOUT);
                        break;
                    case "in":
                        controller.setActivity(TelephonyManager.DATA_ACTIVITY_IN);
                        break;
                    case "out":
                        controller.setActivity(TelephonyManager.DATA_ACTIVITY_OUT);
                        break;
                    default:
                        controller.setActivity(TelephonyManager.DATA_ACTIVITY_NONE);
                        break;
                }
            } else {
                controller.setActivity(TelephonyManager.DATA_ACTIVITY_NONE);
            }
            controller.getState().enabled = show;
            controller.notifyListeners();
        }
        String carrierNetworkChange = args.getString("carriernetworkchange");
        if (carrierNetworkChange != null) {
            boolean show = carrierNetworkChange.equals("show");
            for (int i = 0; i < mMobileSignalControllers.size(); i++) {
                MobileSignalController controller = mMobileSignalControllers.valueAt(i);
                controller.setCarrierNetworkChangeMode(show);
            }
        }
    }

    @Override
    public List<String> demoCommands() {
        List<String> s = new ArrayList<>();
        s.add(DemoMode.COMMAND_NETWORK);
        return s;
    }

    private void recordLastNetworkCallback(String callback) {
        mHistory[mHistoryIndex] = callback;
        mHistoryIndex = (mHistoryIndex + 1) % HISTORY_SIZE;
    }

    private SubscriptionInfo addSignalController(int id, int simSlotIndex) {
        SubscriptionInfo info = new SubscriptionInfo(id, "", simSlotIndex, "", "", 0, 0, "", 0,
                null, null, null, "", false, null, null);
        MobileSignalController controller = new MobileSignalController(mContext,
                mConfig, mHasMobileDataFeature,
                mPhone.createForSubscriptionId(info.getSubscriptionId()), mCallbackHandler, this,
                info, mSubDefaults, mReceiverHandler.getLooper(), mCarrierConfigTracker);
        mMobileSignalControllers.put(id, controller);
        controller.getState().userSetup = true;
        return info;
    }

    public boolean hasEmergencyCryptKeeperText() {
        return EncryptionHelper.IS_DATA_ENCRYPTED;
    }

    public boolean isRadioOn() {
        return !mAirplaneMode;
    }

    private class SubListener extends OnSubscriptionsChangedListener {
        SubListener(Looper looper) {
            super(looper);
        }

        @Override
        public void onSubscriptionsChanged() {
            updateMobileControllers();
        }
    }

    /**
     * Used to register listeners from the BG Looper, this way the PhoneStateListeners that
     * get created will also run on the BG Looper.
     */
    private final Runnable mRegisterListeners = () -> registerListeners();
}
