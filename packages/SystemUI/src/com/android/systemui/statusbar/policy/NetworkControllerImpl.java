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
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN;
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_INOUT;
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_NONE;
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT;
import static android.telephony.PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE;

import static com.android.internal.telephony.PhoneConstants.MAX_PHONE_COUNT_DUAL_SIM;
import static com.android.systemui.Dependency.BG_LOOPER_NAME;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.ConfigurationChangedReceiver;
import com.android.systemui.DemoMode;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.MobileSignalController.MobileIconGroup;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/** Platform implementation of the network controller. **/
@Singleton
public class NetworkControllerImpl extends BroadcastReceiver
        implements NetworkController, DemoMode, DataUsageController.NetworkNameProvider,
        ConfigurationChangedReceiver, Dumpable {
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

    private final Context mContext;
    private final TelephonyManager mPhone;
    private final WifiManager mWifiManager;
    private final ConnectivityManager mConnectivityManager;
    private final SubscriptionManager mSubscriptionManager;
    private final boolean mHasMobileDataFeature;
    private final SubscriptionDefaults mSubDefaults;
    private final DataSaverController mDataSaverController;
    private final CurrentUserTracker mUserTracker;
    private final Object mLock = new Object();
    private Config mConfig;

    private PhoneStateListener mPhoneStateListener;
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
    private Locale mLocale = null;
    // This list holds our ordering.
    private List<SubscriptionInfo> mCurrentSubscriptions = new ArrayList<>();

    @VisibleForTesting
    boolean mListening;

    // The current user ID.
    private int mCurrentUserId;

    private OnSubscriptionsChangedListener mSubscriptionListener;

    // Handler that all broadcasts are received on.
    private final Handler mReceiverHandler;
    // Handler that all callbacks are made on.
    private final CallbackHandler mCallbackHandler;

    private int mEmergencySource;
    private boolean mIsEmergency;

    @VisibleForTesting
    ServiceState mLastServiceState;
    private boolean mUserSetup;
    private boolean mSimDetected;

    /**
     * Construct this controller object and register for updates.
     */
    @Inject
    public NetworkControllerImpl(Context context, @Named(BG_LOOPER_NAME) Looper bgLooper,
            DeviceProvisionedController deviceProvisionedController) {
        this(context, (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE),
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE),
                (WifiManager) context.getSystemService(Context.WIFI_SERVICE),
                SubscriptionManager.from(context), Config.readConfig(context), bgLooper,
                new CallbackHandler(),
                new AccessPointControllerImpl(context),
                new DataUsageController(context),
                new SubscriptionDefaults(),
                deviceProvisionedController);
        mReceiverHandler.post(mRegisterListeners);
    }

    @VisibleForTesting
    NetworkControllerImpl(Context context, ConnectivityManager connectivityManager,
            TelephonyManager telephonyManager, WifiManager wifiManager,
            SubscriptionManager subManager, Config config, Looper bgLooper,
            CallbackHandler callbackHandler,
            AccessPointControllerImpl accessPointController,
            DataUsageController dataUsageController,
            SubscriptionDefaults defaultsHandler,
            DeviceProvisionedController deviceProvisionedController) {
        mContext = context;
        mConfig = config;
        mReceiverHandler = new Handler(bgLooper);
        mCallbackHandler = callbackHandler;
        mDataSaverController = new DataSaverControllerImpl(context);

        mSubscriptionManager = subManager;
        mSubDefaults = defaultsHandler;
        mConnectivityManager = connectivityManager;
        mHasMobileDataFeature =
                mConnectivityManager.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

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
                mCallbackHandler, this, mWifiManager);

        mEthernetSignalController = new EthernetSignalController(mContext, mCallbackHandler, this);

        // AIRPLANE_MODE_CHANGED is sent at boot; we've probably already missed it
        updateAirplaneMode(true /* force callback */);
        mUserTracker = new CurrentUserTracker(mContext) {
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

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback(){
            private Network mLastNetwork;
            private NetworkCapabilities mLastNetworkCapabilities;

            @Override
            public void onCapabilitiesChanged(
                Network network, NetworkCapabilities networkCapabilities) {
                boolean lastValidated = (mLastNetworkCapabilities != null) &&
                    mLastNetworkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED);
                boolean validated =
                    networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED);

                // This callback is invoked a lot (i.e. when RSSI changes), so avoid updating
                // icons when connectivity state has remained the same.
                if (network.equals(mLastNetwork) &&
                    networkCapabilities.equalsTransportTypes(mLastNetworkCapabilities) &&
                    validated == lastValidated) {
                    return;
                }
                mLastNetwork = network;
                mLastNetworkCapabilities = networkCapabilities;
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
        // Register the listener on our bg looper
        mPhoneStateListener = new PhoneStateListener(bgLooper) {
            @Override
            public void onActiveDataSubscriptionIdChanged(int subId) {
                mActiveMobileDataSubscription = subId;
                doUpdateMobileControllers();
            }
        };
    }

    public DataSaverController getDataSaverController() {
        return mDataSaverController;
    }

    private void registerListeners() {
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.registerListener();
        }
        if (mSubscriptionListener == null) {
            mSubscriptionListener = new SubListener();
        }
        mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionListener);
        mPhone.listen(mPhoneStateListener, LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE);

        // broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        filter.addAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ConnectivityManager.INET_CONDITION_ACTION);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        mContext.registerReceiver(this, filter, null, mReceiverHandler);
        mListening = true;

        updateMobileControllers();
    }

    private void unregisterListeners() {
        mListening = false;
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.unregisterListener();
        }
        mSubscriptionManager.removeOnSubscriptionsChangedListener(mSubscriptionListener);
        mContext.unregisterReceiver(this);
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

    private MobileSignalController getDataController() {
        int dataSubId = mSubDefaults.getActiveDataSubId();
        if (!SubscriptionManager.isValidSubscriptionId(dataSubId)) {
            if (DEBUG) Log.e(TAG, "No data sim selected");
            return mDefaultSignalController;
        }
        if (mMobileSignalControllers.indexOfKey(dataSubId) >= 0) {
            return mMobileSignalControllers.get(dataSubId);
        }
        if (DEBUG) Log.e(TAG, "Cannot find controller for data sub: " + dataSubId);
        return mDefaultSignalController;
    }

    @Override
    public String getMobileDataNetworkName() {
        MobileSignalController controller = getDataController();
        return controller != null ? controller.getState().networkNameData : "";
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

    public void addCallback(SignalCallback cb) {
        cb.setSubs(mCurrentSubscriptions);
        cb.setIsAirplaneMode(new IconState(mAirplaneMode,
                TelephonyIcons.FLIGHT_MODE_ICON, R.string.accessibility_airplane_mode, mContext));
        cb.setNoSims(mHasNoSubs, mSimDetected);
        mWifiSignalController.notifyListeners(cb);
        mEthernetSignalController.notifyListeners(cb);
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.notifyListeners(cb);
        }
        mCallbackHandler.setListening(cb, true);
    }

    @Override
    public void removeCallback(SignalCallback cb) {
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
            case ConnectivityManager.INET_CONDITION_ACTION:
                updateConnectivity();
                break;
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                refreshLocale();
                updateAirplaneMode(false);
                break;
            case TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED:
                // We are using different subs now, we might be able to make calls.
                recalculateEmergency();
                break;
            case TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED:
                // Notify every MobileSignalController so they can know whether they are the
                // data sim or not.
                for (int i = 0; i < mMobileSignalControllers.size(); i++) {
                    MobileSignalController controller = mMobileSignalControllers.valueAt(i);
                    controller.handleBroadcast(intent);
                }
                mConfig = Config.readConfig(mContext);
                mReceiverHandler.post(this::handleConfigurationChanged);
                break;
            case TelephonyIntents.ACTION_SIM_STATE_CHANGED:
                // Avoid rebroadcast because SysUI is direct boot aware.
                if (intent.getBooleanExtra(TelephonyIntents.EXTRA_REBROADCAST_ON_UNLOCK, false)) {
                    break;
                }
                // Might have different subscriptions now.
                updateMobileControllers();
                break;
            case TelephonyIntents.ACTION_SERVICE_STATE_CHANGED:
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
                int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
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

    public void onConfigurationChanged(Configuration newConfig) {
        mConfig = Config.readConfig(mContext);
        mReceiverHandler.post(new Runnable() {
            @Override
            public void run() {
                handleConfigurationChanged();
            }
        });
    }

    @VisibleForTesting
    void handleConfigurationChanged() {
        updateMobileControllers();
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController controller = mMobileSignalControllers.valueAt(i);
            controller.setConfiguration(mConfig);
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
        if (subscriptions.size() == MAX_PHONE_COUNT_DUAL_SIM) {
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
                .getActiveSubscriptionInfoList(false);
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
        int simCount = mPhone.getSimCount();
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
                        mSubDefaults, mReceiverHandler.getLooper());
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
        for (NetworkCapabilities nc :
                mConnectivityManager.getDefaultNetworkCapabilitiesForUser(mCurrentUserId)) {
            for (int transportType : nc.getTransportTypes()) {
                mConnectedTransports.set(transportType);
                if (nc.hasCapability(NET_CAPABILITY_VALIDATED)) {
                    mValidatedTransports.set(transportType);
                }
            }
        }

        if (CHATTY) {
            Log.d(TAG, "updateConnectivity: mConnectedTransports=" + mConnectedTransports);
            Log.d(TAG, "updateConnectivity: mValidatedTransports=" + mValidatedTransports);
        }

        mInetCondition = !mValidatedTransports.isEmpty();

        pushConnectivityToSignals();
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

        pw.println("  - config ------");
        pw.print("  patternOfCarrierSpecificDataIcon=");
        pw.println(mConfig.patternOfCarrierSpecificDataIcon);
        pw.print("  nr5GIconMap=");
        pw.println(mConfig.nr5GIconMap.toString());
        pw.print("  nrIconDisplayGracePeriodMs=");
        pw.println(mConfig.nrIconDisplayGracePeriodMs);
        for (int i = 0; i < mMobileSignalControllers.size(); i++) {
            MobileSignalController mobileSignalController = mMobileSignalControllers.valueAt(i);
            mobileSignalController.dump(pw);
        }
        mWifiSignalController.dump(pw);

        mEthernetSignalController.dump(pw);

        mAccessPoints.dump(pw);
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

    private boolean mDemoMode;
    private boolean mDemoInetCondition;
    private WifiSignalController.WifiState mDemoWifiState;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            if (DEBUG) Log.d(TAG, "Entering demo mode");
            unregisterListeners();
            mDemoMode = true;
            mDemoInetCondition = mInetCondition;
            mDemoWifiState = mWifiSignalController.getState();
            mDemoWifiState.ssid = "DemoMode";
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            if (DEBUG) Log.d(TAG, "Exiting demo mode");
            mDemoMode = false;
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
        } else if (mDemoMode && command.equals(COMMAND_NETWORK)) {
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
                                    SignalStrength.NUM_SIGNAL_STRENGTH_BINS);
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
    }

    private SubscriptionInfo addSignalController(int id, int simSlotIndex) {
        SubscriptionInfo info = new SubscriptionInfo(id, "", simSlotIndex, "", "", 0, 0, "", 0,
                null, null, null, "", false, null, null);
        MobileSignalController controller = new MobileSignalController(mContext,
                mConfig, mHasMobileDataFeature,
                mPhone.createForSubscriptionId(info.getSubscriptionId()), mCallbackHandler, this, info,
                mSubDefaults, mReceiverHandler.getLooper());
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
        @Override
        public void onSubscriptionsChanged() {
            updateMobileControllers();
        }
    }

    /**
     * Used to register listeners from the BG Looper, this way the PhoneStateListeners that
     * get created will also run on the BG Looper.
     */
    private final Runnable mRegisterListeners = new Runnable() {
        @Override
        public void run() {
            registerListeners();
        }
    };

    public static class SubscriptionDefaults {
        public int getDefaultVoiceSubId() {
            return SubscriptionManager.getDefaultVoiceSubscriptionId();
        }

        public int getDefaultDataSubId() {
            return SubscriptionManager.getDefaultDataSubscriptionId();
        }

        public int getActiveDataSubId() {
            return SubscriptionManager.getActiveDataSubscriptionId();
        }
    }

    @VisibleForTesting
    static class Config {
        static final int NR_CONNECTED_MMWAVE = 1;
        static final int NR_CONNECTED = 2;
        static final int NR_NOT_RESTRICTED_RRC_IDLE = 3;
        static final int NR_NOT_RESTRICTED_RRC_CON = 4;
        static final int NR_RESTRICTED = 5;

        Map<Integer, MobileIconGroup> nr5GIconMap = new HashMap<>();

        boolean showAtLeast3G = false;
        boolean show4gFor3g = false;
        boolean alwaysShowCdmaRssi = false;
        boolean show4gForLte = false;
        boolean hideLtePlus = false;
        boolean hspaDataDistinguishable;
        boolean inflateSignalStrengths = false;
        boolean alwaysShowDataRatIcon = false;
        boolean showVolteIcon;
        public String patternOfCarrierSpecificDataIcon = "";
        public long nrIconDisplayGracePeriodMs;

        /**
         * Mapping from NR 5G status string to an integer. The NR 5G status string should match
         * those in carrier config.
         */
        private static final Map<String, Integer> NR_STATUS_STRING_TO_INDEX;
        static {
            NR_STATUS_STRING_TO_INDEX = new HashMap<>(5);
            NR_STATUS_STRING_TO_INDEX.put("connected_mmwave", NR_CONNECTED_MMWAVE);
            NR_STATUS_STRING_TO_INDEX.put("connected", NR_CONNECTED);
            NR_STATUS_STRING_TO_INDEX.put("not_restricted_rrc_idle", NR_NOT_RESTRICTED_RRC_IDLE);
            NR_STATUS_STRING_TO_INDEX.put("not_restricted_rrc_con", NR_NOT_RESTRICTED_RRC_CON);
            NR_STATUS_STRING_TO_INDEX.put("restricted", NR_RESTRICTED);
        }

        static Config readConfig(Context context) {
            Config config = new Config();
            Resources res = context.getResources();

            config.showAtLeast3G = res.getBoolean(R.bool.config_showMin3G);
            config.alwaysShowCdmaRssi =
                    res.getBoolean(com.android.internal.R.bool.config_alwaysUseCdmaRssi);
            config.hspaDataDistinguishable =
                    res.getBoolean(R.bool.config_hspa_data_distinguishable);
            config.inflateSignalStrengths = res.getBoolean(
                    com.android.internal.R.bool.config_inflateSignalStrength);

            CarrierConfigManager configMgr = (CarrierConfigManager)
                    context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
            // Handle specific carrier config values for the default data SIM
            int defaultDataSubId = SubscriptionManager.from(context)
                    .getDefaultDataSubscriptionId();
            PersistableBundle b = configMgr.getConfigForSubId(defaultDataSubId);
            if (b != null) {
                config.alwaysShowDataRatIcon = b.getBoolean(
                        CarrierConfigManager.KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL);
                config.show4gForLte = b.getBoolean(
                        CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL);
                config.show4gFor3g = b.getBoolean(
                        CarrierConfigManager.KEY_SHOW_4G_FOR_3G_DATA_ICON_BOOL);
                /*config.hideLtePlus = b.getBoolean(
                        CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL);*/
                config.patternOfCarrierSpecificDataIcon = b.getString(
                        CarrierConfigManager.KEY_SHOW_CARRIER_DATA_ICON_PATTERN_STRING);
                String nr5GIconConfiguration =
                        b.getString(CarrierConfigManager.KEY_5G_ICON_CONFIGURATION_STRING);
                if (!TextUtils.isEmpty(nr5GIconConfiguration)) {
                    String[] nr5GIconConfigPairs = nr5GIconConfiguration.trim().split(",");
                    for (String pair : nr5GIconConfigPairs) {
                        add5GIconMapping(pair, config);
                    }
                }
                setDisplayGraceTime(
                        b.getInt(CarrierConfigManager.KEY_5G_ICON_DISPLAY_GRACE_PERIOD_SEC_INT),
                        config);
            }

            config.showVolteIcon = res.getBoolean(R.bool.config_display_volte);
            return config;
        }

        /**
         * Add a mapping from NR 5G status to the 5G icon. All the icon resources come from
         * {@link TelephonyIcons}.
         *
         * @param keyValuePair the NR 5G status and icon name separated by a colon.
         * @param config container that used to store the parsed configs.
         */
        @VisibleForTesting
        static void add5GIconMapping(String keyValuePair, Config config) {
            String[] kv = (keyValuePair.trim().toLowerCase()).split(":");

            if (kv.length != 2) {
                if (DEBUG) Log.e(TAG, "Invalid 5G icon configuration, config = " + keyValuePair);
                return;
            }

            String key = kv[0], value = kv[1];

            // There is no icon config for the specific 5G status.
            if (value.equals("none")) return;

            if (NR_STATUS_STRING_TO_INDEX.containsKey(key)
                    && TelephonyIcons.ICON_NAME_TO_ICON.containsKey(value)) {
                config.nr5GIconMap.put(
                        NR_STATUS_STRING_TO_INDEX.get(key),
                        TelephonyIcons.ICON_NAME_TO_ICON.get(value));
            }
        }

        /**
         * Set display gracefully period time(MS) depend on carrierConfig KEY
         * KEY_5G_ICON_DISPLAY_GRACE_PERIOD_SEC_INT, and this function will convert to ms.
         * {@link CarrierConfigManager}.
         *
         * @param time   showing 5G icon gracefully in the period of the time(SECOND)
         * @param config container that used to store the parsed configs.
         */
        @VisibleForTesting
        static void setDisplayGraceTime(int time, Config config) {
            config.nrIconDisplayGracePeriodMs = time * DateUtils.SECOND_IN_MILLIS;
        }
    }
}
