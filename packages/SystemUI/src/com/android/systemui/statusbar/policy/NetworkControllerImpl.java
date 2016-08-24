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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.MathUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.DemoMode;
import com.android.systemui.R;

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

import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;

/** Platform implementation of the network controller. **/
public class NetworkControllerImpl extends BroadcastReceiver
        implements NetworkController, DemoMode, DataUsageController.NetworkNameProvider {
    // debug
    static final String TAG = "NetworkController";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    // additional diagnostics, but not logspew
    static final boolean CHATTY =  Log.isLoggable(TAG + "Chat", Log.DEBUG);

    private static final int EMERGENCY_NO_CONTROLLERS = 0;
    private static final int EMERGENCY_FIRST_CONTROLLER = 100;
    private static final int EMERGENCY_VOICE_CONTROLLER = 200;
    private static final int EMERGENCY_NO_SUB = 300;

    private final Context mContext;
    private final TelephonyManager mPhone;
    private final WifiManager mWifiManager;
    private final ConnectivityManager mConnectivityManager;
    private final SubscriptionManager mSubscriptionManager;
    private final boolean mHasMobileDataFeature;
    private final SubscriptionDefaults mSubDefaults;
    private final DataSaverController mDataSaverController;
    private Config mConfig;

    // Subcontrollers.
    @VisibleForTesting
    final WifiSignalController mWifiSignalController;

    @VisibleForTesting
    final EthernetSignalController mEthernetSignalController;

    @VisibleForTesting
    final Map<Integer, MobileSignalController> mMobileSignalControllers =
            new HashMap<Integer, MobileSignalController>();
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
    private boolean mHasNoSims;
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

    /**
     * Construct this controller object and register for updates.
     */
    public NetworkControllerImpl(Context context, Looper bgLooper) {
        this(context, (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE),
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE),
                (WifiManager) context.getSystemService(Context.WIFI_SERVICE),
                SubscriptionManager.from(context), Config.readConfig(context), bgLooper,
                new CallbackHandler(),
                new AccessPointControllerImpl(context, bgLooper),
                new DataUsageController(context),
                new SubscriptionDefaults());
        mReceiverHandler.post(mRegisterListeners);
    }

    @VisibleForTesting
    NetworkControllerImpl(Context context, ConnectivityManager connectivityManager,
            TelephonyManager telephonyManager, WifiManager wifiManager,
            SubscriptionManager subManager, Config config, Looper bgLooper,
            CallbackHandler callbackHandler,
            AccessPointControllerImpl accessPointController,
            DataUsageController dataUsageController,
            SubscriptionDefaults defaultsHandler) {
        mContext = context;
        mConfig = config;
        mReceiverHandler = new Handler(bgLooper);
        mCallbackHandler = callbackHandler;
        mDataSaverController = new DataSaverController(context);

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
            }
        });
        mWifiSignalController = new WifiSignalController(mContext, mHasMobileDataFeature,
                mCallbackHandler, this);

        mEthernetSignalController = new EthernetSignalController(mContext, mCallbackHandler, this);

        // AIRPLANE_MODE_CHANGED is sent at boot; we've probably already missed it
        updateAirplaneMode(true /* force callback */);
    }

    public DataSaverController getDataSaverController() {
        return mDataSaverController;
    }

    private void registerListeners() {
        for (MobileSignalController mobileSignalController : mMobileSignalControllers.values()) {
            mobileSignalController.registerListener();
        }
        if (mSubscriptionListener == null) {
            mSubscriptionListener = new SubListener();
        }
        mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionListener);

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
        mContext.registerReceiver(this, filter, null, mReceiverHandler);
        mListening = true;

        updateMobileControllers();
    }

    private void unregisterListeners() {
        mListening = false;
        for (MobileSignalController mobileSignalController : mMobileSignalControllers.values()) {
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
        int dataSubId = mSubDefaults.getDefaultDataSubId();
        if (!SubscriptionManager.isValidSubscriptionId(dataSubId)) {
            if (DEBUG) Log.e(TAG, "No data sim selected");
            return mDefaultSignalController;
        }
        if (mMobileSignalControllers.containsKey(dataSubId)) {
            return mMobileSignalControllers.get(dataSubId);
        }
        if (DEBUG) Log.e(TAG, "Cannot find controller for data sub: " + dataSubId);
        return mDefaultSignalController;
    }

    public String getMobileDataNetworkName() {
        MobileSignalController controller = getDataController();
        return controller != null ? controller.getState().networkNameData : "";
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
            for (MobileSignalController mobileSignalController :
                                            mMobileSignalControllers.values()) {
                if (!mobileSignalController.getState().isEmergency) {
                    mEmergencySource = EMERGENCY_FIRST_CONTROLLER
                            + mobileSignalController.mSubscriptionInfo.getSubscriptionId();
                    if (DEBUG) Log.d(TAG, "Found emergency " + mobileSignalController.mTag);
                    return false;
                }
            }
        }
        if (mMobileSignalControllers.containsKey(voiceSubId)) {
            mEmergencySource = EMERGENCY_VOICE_CONTROLLER + voiceSubId;
            if (DEBUG) Log.d(TAG, "Getting emergency from " + voiceSubId);
            return mMobileSignalControllers.get(voiceSubId).getState().isEmergency;
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

    public void addSignalCallback(SignalCallback cb) {
        cb.setSubs(mCurrentSubscriptions);
        cb.setIsAirplaneMode(new IconState(mAirplaneMode,
                TelephonyIcons.FLIGHT_MODE_ICON, R.string.accessibility_airplane_mode, mContext));
        cb.setNoSims(mHasNoSims);
        mWifiSignalController.notifyListeners(cb);
        mEthernetSignalController.notifyListeners(cb);
        for (MobileSignalController mobileSignalController : mMobileSignalControllers.values()) {
            mobileSignalController.notifyListeners(cb);
        }
        mCallbackHandler.setListening(cb, true);
    }

    @Override
    public void removeSignalCallback(SignalCallback cb) {
        mCallbackHandler.setListening(cb, false);
    }

    @Override
    public void setWifiEnabled(final boolean enabled) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... args) {
                // Disable tethering if enabling Wifi
                final int wifiApState = mWifiManager.getWifiApState();
                if (enabled && ((wifiApState == WifiManager.WIFI_AP_STATE_ENABLING) ||
                        (wifiApState == WifiManager.WIFI_AP_STATE_ENABLED))) {
                    mWifiManager.setWifiApEnabled(null, false);
                }

                mWifiManager.setWifiEnabled(enabled);
                return null;
            }
        }.execute();
    }

    @Override
    public void onUserSwitched(int newUserId) {
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
        if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ||
                action.equals(ConnectivityManager.INET_CONDITION_ACTION)) {
            updateConnectivity();
        } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
            refreshLocale();
            updateAirplaneMode(false);
        } else if (action.equals(TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED)) {
            // We are using different subs now, we might be able to make calls.
            recalculateEmergency();
        } else if (action.equals(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)) {
            // Notify every MobileSignalController so they can know whether they are the
            // data sim or not.
            for (MobileSignalController controller : mMobileSignalControllers.values()) {
                controller.handleBroadcast(intent);
            }
        } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
            // Might have different subscriptions now.
            updateMobileControllers();
        } else if (action.equals(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED)) {
            mLastServiceState = ServiceState.newFromBundle(intent.getExtras());
            if (mMobileSignalControllers.size() == 0) {
                // If none of the subscriptions are active, we might need to recalculate
                // emergency state.
                recalculateEmergency();
            }
        } else {
            int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                if (mMobileSignalControllers.containsKey(subId)) {
                    mMobileSignalControllers.get(subId).handleBroadcast(intent);
                } else {
                    // Can't find this subscription...  We must be out of date.
                    updateMobileControllers();
                }
            } else {
                // No sub id, must be for the wifi.
                mWifiSignalController.handleBroadcast(intent);
            }
        }
    }

    public void onConfigurationChanged() {
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
        for (MobileSignalController mobileSignalController : mMobileSignalControllers.values()) {
            mobileSignalController.setConfiguration(mConfig);
        }
        refreshLocale();
    }

    private void updateMobileControllers() {
        if (!mListening) {
            return;
        }
        doUpdateMobileControllers();
    }

    @VisibleForTesting
    void doUpdateMobileControllers() {
        List<SubscriptionInfo> subscriptions = mSubscriptionManager.getActiveSubscriptionInfoList();
        if (subscriptions == null) {
            subscriptions = Collections.emptyList();
        }
        // If there have been no relevant changes to any of the subscriptions, we can leave as is.
        if (hasCorrectMobileControllers(subscriptions)) {
            // Even if the controllers are correct, make sure we have the right no sims state.
            // Such as on boot, don't need any controllers, because there are no sims,
            // but we still need to update the no sim state.
            updateNoSims();
            return;
        }
        setCurrentSubscriptions(subscriptions);
        updateNoSims();
        recalculateEmergency();
    }

    @VisibleForTesting
    protected void updateNoSims() {
        boolean hasNoSims = mHasMobileDataFeature && mMobileSignalControllers.size() == 0;
        if (hasNoSims != mHasNoSims) {
            mHasNoSims = hasNoSims;
            mCallbackHandler.setNoSims(mHasNoSims);
        }
    }

    @VisibleForTesting
    void setCurrentSubscriptions(List<SubscriptionInfo> subscriptions) {
        Collections.sort(subscriptions, new Comparator<SubscriptionInfo>() {
            @Override
            public int compare(SubscriptionInfo lhs, SubscriptionInfo rhs) {
                return lhs.getSimSlotIndex() == rhs.getSimSlotIndex()
                        ? lhs.getSubscriptionId() - rhs.getSubscriptionId()
                        : lhs.getSimSlotIndex() - rhs.getSimSlotIndex();
            }
        });
        mCurrentSubscriptions = subscriptions;

        HashMap<Integer, MobileSignalController> cachedControllers =
                new HashMap<Integer, MobileSignalController>(mMobileSignalControllers);
        mMobileSignalControllers.clear();
        final int num = subscriptions.size();
        for (int i = 0; i < num; i++) {
            int subId = subscriptions.get(i).getSubscriptionId();
            // If we have a copy of this controller already reuse it, otherwise make a new one.
            if (cachedControllers.containsKey(subId)) {
                mMobileSignalControllers.put(subId, cachedControllers.remove(subId));
            } else {
                MobileSignalController controller = new MobileSignalController(mContext, mConfig,
                        mHasMobileDataFeature, mPhone, mCallbackHandler,
                        this, subscriptions.get(i), mSubDefaults, mReceiverHandler.getLooper());
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
            for (Integer key : cachedControllers.keySet()) {
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

    public void setUserSetupComplete(final boolean userSetup) {
        mReceiverHandler.post(new Runnable() {
            @Override
            public void run() {
                handleSetUserSetupComplete(userSetup);
            }
        });
    }

    @VisibleForTesting
    void handleSetUserSetupComplete(boolean userSetup) {
        mUserSetup = userSetup;
        for (MobileSignalController controller : mMobileSignalControllers.values()) {
            controller.setUserSetupComplete(mUserSetup);
        }
    }

    @VisibleForTesting
    boolean hasCorrectMobileControllers(List<SubscriptionInfo> allSubscriptions) {
        if (allSubscriptions.size() != mMobileSignalControllers.size()) {
            return false;
        }
        for (SubscriptionInfo info : allSubscriptions) {
            if (!mMobileSignalControllers.containsKey(info.getSubscriptionId())) {
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
            for (MobileSignalController mobileSignalController : mMobileSignalControllers.values()) {
                mobileSignalController.setAirplaneMode(mAirplaneMode);
            }
            notifyListeners();
        }
    }

    private void refreshLocale() {
        Locale current = mContext.getResources().getConfiguration().locale;
        if (!current.equals(mLocale)) {
            mLocale = current;
            notifyAllListeners();
        }
    }

    /**
     * Forces update of all callbacks on both SignalClusters and
     * NetworkSignalChangedCallbacks.
     */
    private void notifyAllListeners() {
        notifyListeners();
        for (MobileSignalController mobileSignalController : mMobileSignalControllers.values()) {
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
        mCallbackHandler.setNoSims(mHasNoSims);
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
        for (MobileSignalController mobileSignalController : mMobileSignalControllers.values()) {
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

        for (MobileSignalController mobileSignalController : mMobileSignalControllers.values()) {
            mobileSignalController.dump(pw);
        }
        mWifiSignalController.dump(pw);

        mEthernetSignalController.dump(pw);

        mAccessPoints.dump(pw);
    }

    private static final String emergencyToString(int emergencySource) {
        if (emergencySource > EMERGENCY_NO_SUB) {
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
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            if (DEBUG) Log.d(TAG, "Exiting demo mode");
            mDemoMode = false;
            // Update what MobileSignalControllers, because they may change
            // to set the number of sim slots.
            updateMobileControllers();
            for (MobileSignalController controller : mMobileSignalControllers.values()) {
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
                for (MobileSignalController controller : mMobileSignalControllers.values()) {
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
                }
            }
            String nosim = args.getString("nosim");
            if (nosim != null) {
                mHasNoSims = nosim.equals("show");
                mCallbackHandler.setNoSims(mHasNoSims);
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
                MobileSignalController controller = mMobileSignalControllers
                        .values().toArray(new MobileSignalController[0])[slot];
                controller.getState().dataSim = datatype != null;
                if (datatype != null) {
                    controller.getState().iconGroup =
                            datatype.equals("1x") ? TelephonyIcons.ONE_X :
                            datatype.equals("3g") ? TelephonyIcons.THREE_G :
                            datatype.equals("4g") ? TelephonyIcons.FOUR_G :
                            datatype.equals("e") ? TelephonyIcons.E :
                            datatype.equals("g") ? TelephonyIcons.G :
                            datatype.equals("h") ? TelephonyIcons.H :
                            datatype.equals("lte") ? TelephonyIcons.LTE :
                            datatype.equals("roam") ? TelephonyIcons.ROAMING :
                            TelephonyIcons.UNKNOWN;
                }
                int[][] icons = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH;
                String level = args.getString("level");
                if (level != null) {
                    controller.getState().level = level.equals("null") ? -1
                            : Math.min(Integer.parseInt(level), icons[0].length - 1);
                    controller.getState().connected = controller.getState().level >= 0;
                }
                controller.getState().enabled = show;
                controller.notifyListeners();
            }
            String carrierNetworkChange = args.getString("carriernetworkchange");
            if (carrierNetworkChange != null) {
                boolean show = carrierNetworkChange.equals("show");
                for (MobileSignalController controller : mMobileSignalControllers.values()) {
                    controller.setCarrierNetworkChangeMode(show);
                }
            }
        }
    }

    private SubscriptionInfo addSignalController(int id, int simSlotIndex) {
        SubscriptionInfo info = new SubscriptionInfo(id, "", simSlotIndex, "", "", 0, 0, "", 0,
                null, 0, 0, "", SubscriptionManager.SIM_PROVISIONED);
        mMobileSignalControllers.put(id, new MobileSignalController(mContext,
                mConfig, mHasMobileDataFeature, mPhone, mCallbackHandler, this, info,
                mSubDefaults, mReceiverHandler.getLooper()));
        return info;
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
    }

    @VisibleForTesting
    static class Config {
        boolean showAtLeast3G = false;
        boolean alwaysShowCdmaRssi = false;
        boolean show4gForLte = false;
        boolean hspaDataDistinguishable;

        static Config readConfig(Context context) {
            Config config = new Config();
            Resources res = context.getResources();

            config.showAtLeast3G = res.getBoolean(R.bool.config_showMin3G);
            config.alwaysShowCdmaRssi =
                    res.getBoolean(com.android.internal.R.bool.config_alwaysUseCdmaRssi);
            config.show4gForLte = res.getBoolean(R.bool.config_show4GForLTE);
            config.hspaDataDistinguishable =
                    res.getBoolean(R.bool.config_hspa_data_distinguishable);
            return config;
        }
    }
}
