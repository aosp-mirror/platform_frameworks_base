/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static com.android.settingslib.mobile.MobileMappings.getDefaultIcons;
import static com.android.settingslib.mobile.MobileMappings.getIconKey;
import static com.android.settingslib.mobile.MobileMappings.mapIconSets;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Global;
import android.telephony.AccessNetworkConstants;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.RegistrationManager.RegistrationCallback;
import android.text.Html;
import android.text.TextUtils;
import android.util.FeatureFlagUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.AccessibilityContentDescriptions;
import com.android.settingslib.SignalIcon.MobileIconGroup;
import com.android.settingslib.SignalIcon.MobileState;
import com.android.settingslib.Utils;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.mobile.MobileMappings.Config;
import com.android.settingslib.mobile.MobileStatusTracker;
import com.android.settingslib.mobile.MobileStatusTracker.MobileStatus;
import com.android.settingslib.mobile.MobileStatusTracker.SubscriptionDefaults;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.net.SignalStrengthUtil;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.MobileDataIndicators;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * Monitors the mobile signal changes and update the SysUI icons.
 */
public class MobileSignalController extends SignalController<MobileState, MobileIconGroup> {
    private static final SimpleDateFormat SSDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    private static final int STATUS_HISTORY_SIZE = 64;
    private static final int IMS_TYPE_WWAN = 1;
    private static final int IMS_TYPE_WLAN = 2;
    private static final int IMS_TYPE_WLAN_CROSS_SIM = 3;
    private final TelephonyManager mPhone;
    private final ImsMmTelManager mImsMmTelManager;
    private final SubscriptionDefaults mDefaults;
    private final String mNetworkNameDefault;
    private final String mNetworkNameSeparator;
    private final ContentObserver mObserver;
    private final boolean mProviderModel;
    private final Handler mReceiverHandler;
    private int mImsType = IMS_TYPE_WWAN;
    // Save entire info for logging, we only use the id.
    final SubscriptionInfo mSubscriptionInfo;
    // @VisibleForDemoMode
    Map<String, MobileIconGroup> mNetworkToIconLookup;

    // Since some pieces of the phone state are interdependent we store it locally,
    // this could potentially become part of MobileState for simplification/complication
    // of code.
    private int mDataState = TelephonyManager.DATA_DISCONNECTED;
    private TelephonyDisplayInfo mTelephonyDisplayInfo =
            new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE);
    private ServiceState mServiceState;
    private SignalStrength mSignalStrength;
    private int mLastLevel;
    private MobileIconGroup mDefaultIcons;
    private Config mConfig;
    @VisibleForTesting
    boolean mInflateSignalStrengths = false;
    private MobileStatusTracker.Callback mCallback;
    private RegistrationCallback mRegistrationCallback;
    private int mLastWwanLevel;
    private int mLastWlanLevel;
    private int mLastWlanCrossSimLevel;
    @VisibleForTesting
    MobileStatusTracker mMobileStatusTracker;

    // Save the previous STATUS_HISTORY_SIZE states for logging.
    private final String[] mMobileStatusHistory = new String[STATUS_HISTORY_SIZE];
    // Where to copy the next state into.
    private int mMobileStatusHistoryIndex;

    // TODO: Reduce number of vars passed in, if we have the NetworkController, probably don't
    // need listener lists anymore.
    public MobileSignalController(Context context, Config config, boolean hasMobileData,
            TelephonyManager phone, CallbackHandler callbackHandler,
            NetworkControllerImpl networkController, SubscriptionInfo info,
            SubscriptionDefaults defaults, Looper receiverLooper) {
        super("MobileSignalController(" + info.getSubscriptionId() + ")", context,
                NetworkCapabilities.TRANSPORT_CELLULAR, callbackHandler,
                networkController);
        mConfig = config;
        mPhone = phone;
        mDefaults = defaults;
        mSubscriptionInfo = info;
        mNetworkNameSeparator = getTextIfExists(R.string.status_bar_network_name_separator)
                .toString();
        mNetworkNameDefault = getTextIfExists(
                com.android.internal.R.string.lockscreen_carrier_default).toString();
        mReceiverHandler = new Handler(receiverLooper);

        mNetworkToIconLookup = mapIconSets(mConfig);
        mDefaultIcons = getDefaultIcons(mConfig);

        String networkName = info.getCarrierName() != null ? info.getCarrierName().toString()
                : mNetworkNameDefault;
        mLastState.networkName = mCurrentState.networkName = networkName;
        mLastState.networkNameData = mCurrentState.networkNameData = networkName;
        mLastState.enabled = mCurrentState.enabled = hasMobileData;
        mLastState.iconGroup = mCurrentState.iconGroup = mDefaultIcons;
        mObserver = new ContentObserver(new Handler(receiverLooper)) {
            @Override
            public void onChange(boolean selfChange) {
                updateTelephony();
            }
        };
        mCallback = new MobileStatusTracker.Callback() {
            private String mLastStatus;

            @Override
            public void onMobileStatusChanged(boolean updateTelephony,
                    MobileStatus mobileStatus) {
                if (Log.isLoggable(mTag, Log.DEBUG)) {
                    Log.d(mTag, "onMobileStatusChanged="
                            + " updateTelephony=" + updateTelephony
                            + " mobileStatus=" + mobileStatus.toString());
                }
                String currentStatus = mobileStatus.toString();
                if (!currentStatus.equals(mLastStatus)) {
                    mLastStatus = currentStatus;
                    String status = new StringBuilder()
                            .append(SSDF.format(System.currentTimeMillis())).append(",")
                            .append(currentStatus)
                            .toString();
                    recordLastMobileStatus(status);
                }
                updateMobileStatus(mobileStatus);
                if (updateTelephony) {
                    updateTelephony();
                } else {
                    notifyListenersIfNecessary();
                }
            }
        };

        mRegistrationCallback = new RegistrationCallback() {
            @Override
            public void onRegistered(ImsRegistrationAttributes attributes) {
                Log.d(mTag, "onRegistered: " + "attributes=" + attributes);
                int imsTransportType = attributes.getTransportType();
                int registrationAttributes = attributes.getAttributeFlags();
                if (imsTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                    mImsType = IMS_TYPE_WWAN;
                    IconState statusIcon = new IconState(
                            true,
                            getCallStrengthIcon(mLastWwanLevel, /* isWifi= */false),
                            getCallStrengthDescription(mLastWwanLevel, /* isWifi= */false));
                    notifyCallStateChange(statusIcon, mSubscriptionInfo.getSubscriptionId());
                } else if (imsTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
                    if (registrationAttributes == 0) {
                        mImsType = IMS_TYPE_WLAN;
                        IconState statusIcon = new IconState(
                                true,
                                getCallStrengthIcon(mLastWlanLevel, /* isWifi= */true),
                                getCallStrengthDescription(mLastWlanLevel, /* isWifi= */true));
                        notifyCallStateChange(statusIcon, mSubscriptionInfo.getSubscriptionId());
                    } else if (registrationAttributes
                            == ImsRegistrationAttributes.ATTR_EPDG_OVER_CELL_INTERNET) {
                        mImsType = IMS_TYPE_WLAN_CROSS_SIM;
                        IconState statusIcon = new IconState(
                                true,
                                getCallStrengthIcon(mLastWlanCrossSimLevel, /* isWifi= */false),
                                getCallStrengthDescription(
                                        mLastWlanCrossSimLevel, /* isWifi= */false));
                        notifyCallStateChange(statusIcon, mSubscriptionInfo.getSubscriptionId());
                    }
                }
            }

            @Override
            public void onUnregistered(ImsReasonInfo info) {
                Log.d(mTag, "onDeregistered: " + "info=" + info);
                mImsType = IMS_TYPE_WWAN;
                IconState statusIcon = new IconState(
                        true,
                        getCallStrengthIcon(mLastWwanLevel, /* isWifi= */false),
                        getCallStrengthDescription(mLastWwanLevel, /* isWifi= */false));
                notifyCallStateChange(statusIcon, mSubscriptionInfo.getSubscriptionId());
            }
        };
        mImsMmTelManager = ImsMmTelManager.createForSubscriptionId(info.getSubscriptionId());
        mMobileStatusTracker = new MobileStatusTracker(mPhone, receiverLooper,
                info, mDefaults, mCallback);
        mProviderModel = FeatureFlagUtils.isEnabled(
                mContext, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
    }

    public void setConfiguration(Config config) {
        mConfig = config;
        updateInflateSignalStrength();
        mNetworkToIconLookup = mapIconSets(mConfig);
        mDefaultIcons = getDefaultIcons(mConfig);
        updateTelephony();
    }

    public void setAirplaneMode(boolean airplaneMode) {
        mCurrentState.airplaneMode = airplaneMode;
        notifyListenersIfNecessary();
    }

    public void setUserSetupComplete(boolean userSetup) {
        mCurrentState.userSetup = userSetup;
        notifyListenersIfNecessary();
    }

    @Override
    public void updateConnectivity(BitSet connectedTransports, BitSet validatedTransports) {
        boolean isValidated = validatedTransports.get(mTransportType);
        mCurrentState.isDefault = connectedTransports.get(mTransportType);
        // Only show this as not having connectivity if we are default.
        mCurrentState.inetCondition = (isValidated || !mCurrentState.isDefault) ? 1 : 0;
        notifyListenersIfNecessary();
    }

    public void setCarrierNetworkChangeMode(boolean carrierNetworkChangeMode) {
        mCurrentState.carrierNetworkChangeMode = carrierNetworkChangeMode;
        updateTelephony();
    }

    /**
     * Start listening for phone state changes.
     */
    public void registerListener() {
        mMobileStatusTracker.setListening(true);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(Global.MOBILE_DATA),
                true, mObserver);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(
                Global.MOBILE_DATA + mSubscriptionInfo.getSubscriptionId()),
                true, mObserver);
        if (mProviderModel) {
            mReceiverHandler.post(mTryRegisterIms);
        }
    }

    // There is no listener to monitor whether the IMS service is ready, so we have to retry the
    // IMS registration.
    private final Runnable mTryRegisterIms = new Runnable() {
        private static final int MAX_RETRY = 12;
        private int mRetryCount;

        @Override
        public void run() {
            try {
                mRetryCount++;
                mImsMmTelManager.registerImsRegistrationCallback(
                        mReceiverHandler::post, mRegistrationCallback);
                Log.d(mTag, "registerImsRegistrationCallback succeeded");
            } catch (RuntimeException | ImsException e) {
                if (mRetryCount < MAX_RETRY) {
                    Log.e(mTag, mRetryCount + " registerImsRegistrationCallback failed", e);
                    // Wait for 5 seconds to retry
                    mReceiverHandler.postDelayed(mTryRegisterIms, 5000);
                }
            }
        }
    };

    /**
     * Stop listening for phone state changes.
     */
    public void unregisterListener() {
        mMobileStatusTracker.setListening(false);
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        mImsMmTelManager.unregisterImsRegistrationCallback(mRegistrationCallback);
    }

    private void updateInflateSignalStrength() {
        mInflateSignalStrengths = SignalStrengthUtil.shouldInflateSignalStrength(mContext,
                mSubscriptionInfo.getSubscriptionId());
    }

    private int getNumLevels() {
        if (mInflateSignalStrengths) {
            return CellSignalStrength.getNumSignalStrengthLevels() + 1;
        }
        return CellSignalStrength.getNumSignalStrengthLevels();
    }

    @Override
    public int getCurrentIconId() {
        if (mCurrentState.iconGroup == TelephonyIcons.CARRIER_NETWORK_CHANGE) {
            return SignalDrawable.getCarrierChangeState(getNumLevels());
        } else if (mCurrentState.connected) {
            int level = mCurrentState.level;
            if (mInflateSignalStrengths) {
                level++;
            }
            boolean dataDisabled = mCurrentState.userSetup
                    && (mCurrentState.iconGroup == TelephonyIcons.DATA_DISABLED
                    || (mCurrentState.iconGroup == TelephonyIcons.NOT_DEFAULT_DATA
                            && mCurrentState.defaultDataOff));
            boolean noInternet = mCurrentState.inetCondition == 0;
            boolean cutOut = dataDisabled || noInternet;
            return SignalDrawable.getState(level, getNumLevels(), cutOut);
        } else if (mCurrentState.enabled) {
            return SignalDrawable.getEmptyState(getNumLevels());
        } else {
            return 0;
        }
    }

    @Override
    public int getQsCurrentIconId() {
        return getCurrentIconId();
    }

    @Override
    public void notifyListeners(SignalCallback callback) {
        // If the device is on carrier merged WiFi, we should let WifiSignalController to control
        // the SysUI states.
        if (mNetworkController.isCarrierMergedWifi(mSubscriptionInfo.getSubscriptionId())) {
            return;
        }
        MobileIconGroup icons = getIcons();

        String contentDescription = getTextIfExists(getContentDescription()).toString();
        CharSequence dataContentDescriptionHtml = getTextIfExists(icons.dataContentDescription);

        //TODO: Hacky
        // The data content description can sometimes be shown in a text view and might come to us
        // as HTML. Strip any styling here so that listeners don't have to care
        CharSequence dataContentDescription = Html.fromHtml(
                dataContentDescriptionHtml.toString(), 0).toString();
        if (mCurrentState.inetCondition == 0) {
            dataContentDescription = mContext.getString(R.string.data_connection_no_internet);
        }
        final boolean dataDisabled = (mCurrentState.iconGroup == TelephonyIcons.DATA_DISABLED
                || (mCurrentState.iconGroup == TelephonyIcons.NOT_DEFAULT_DATA))
                && mCurrentState.userSetup;

        if (mProviderModel) {
            // Show icon in QS when we are connected or data is disabled.
            boolean showDataIcon = mCurrentState.dataConnected || dataDisabled;

            int qsTypeIcon = 0;
            IconState qsIcon = null;
            CharSequence description = null;
            // Mobile icon will only be shown in the statusbar in 2 scenarios
            // 1. Mobile is the default network, and it is validated
            // 2. Mobile is the default network, it is not validated and there is no other
            // non-Carrier WiFi networks available.
            boolean maybeShowIcons = (mCurrentState.inetCondition == 1)
                    || (mCurrentState.inetCondition == 0
                            && !mNetworkController.isNonCarrierWifiNetworkAvailable());
            // Only send data sim callbacks to QS.
            if (mCurrentState.dataSim && mCurrentState.isDefault && maybeShowIcons) {
                qsTypeIcon =
                        (showDataIcon || mConfig.alwaysShowDataRatIcon) ? icons.qsDataType : 0;
                qsIcon = new IconState(mCurrentState.enabled
                        && !mCurrentState.isEmergency, getQsCurrentIconId(), contentDescription);
                description = mCurrentState.isEmergency ? null : mCurrentState.networkName;
            }
            boolean activityIn = mCurrentState.dataConnected
                    && !mCurrentState.carrierNetworkChangeMode
                    && mCurrentState.activityIn;
            boolean activityOut = mCurrentState.dataConnected
                    && !mCurrentState.carrierNetworkChangeMode
                    && mCurrentState.activityOut;
            showDataIcon &= mCurrentState.dataSim && mCurrentState.isDefault && maybeShowIcons;
            boolean showTriangle = showDataIcon && !mCurrentState.airplaneMode;
            int typeIcon = (showDataIcon || mConfig.alwaysShowDataRatIcon) ? icons.dataType : 0;
            showDataIcon |= mCurrentState.roaming;
            IconState statusIcon = new IconState(showDataIcon && !mCurrentState.airplaneMode,
                    getCurrentIconId(), contentDescription);
            MobileDataIndicators mobileDataIndicators = new MobileDataIndicators(
                    statusIcon, qsIcon, typeIcon, qsTypeIcon,
                    activityIn, activityOut, dataContentDescription, dataContentDescriptionHtml,
                    description, icons.isWide, mSubscriptionInfo.getSubscriptionId(),
                    mCurrentState.roaming, showTriangle);
            callback.setMobileDataIndicators(mobileDataIndicators);
        } else {
            boolean showDataIcon = mCurrentState.dataConnected || dataDisabled;
            IconState statusIcon = new IconState(
                    mCurrentState.enabled && !mCurrentState.airplaneMode,
                    getCurrentIconId(), contentDescription);

            int qsTypeIcon = 0;
            IconState qsIcon = null;
            CharSequence description = null;
            // Only send data sim callbacks to QS.
            if (mCurrentState.dataSim) {
                qsTypeIcon =
                        (showDataIcon || mConfig.alwaysShowDataRatIcon) ? icons.qsDataType : 0;
                qsIcon = new IconState(mCurrentState.enabled
                        && !mCurrentState.isEmergency, getQsCurrentIconId(), contentDescription);
                description = mCurrentState.isEmergency ? null : mCurrentState.networkName;
            }
            boolean activityIn = mCurrentState.dataConnected
                    && !mCurrentState.carrierNetworkChangeMode
                    && mCurrentState.activityIn;
            boolean activityOut = mCurrentState.dataConnected
                    && !mCurrentState.carrierNetworkChangeMode
                    && mCurrentState.activityOut;
            showDataIcon &= mCurrentState.isDefault || dataDisabled;
            int typeIcon = (showDataIcon || mConfig.alwaysShowDataRatIcon) ? icons.dataType : 0;
            boolean showTriangle = mCurrentState.enabled && !mCurrentState.airplaneMode;
            MobileDataIndicators mobileDataIndicators = new MobileDataIndicators(
                    statusIcon, qsIcon, typeIcon, qsTypeIcon,
                    activityIn, activityOut, dataContentDescription, dataContentDescriptionHtml,
                    description, icons.isWide, mSubscriptionInfo.getSubscriptionId(),
                    mCurrentState.roaming, showTriangle);
            callback.setMobileDataIndicators(mobileDataIndicators);
        }
    }

    @Override
    protected MobileState cleanState() {
        return new MobileState();
    }

    private boolean isCdma() {
        return (mSignalStrength != null) && !mSignalStrength.isGsm();
    }

    public boolean isEmergencyOnly() {
        return (mServiceState != null && mServiceState.isEmergencyOnly());
    }

    public boolean isInService() {
        return Utils.isInService(mServiceState);
    }

    String getNetworkNameForCarrierWiFi() {
        return mPhone.getSimOperatorName();
    }

    private boolean isRoaming() {
        // During a carrier change, roaming indications need to be supressed.
        if (isCarrierNetworkChangeActive()) {
            return false;
        }
        if (isCdma()) {
            return mPhone.getCdmaEnhancedRoamingIndicatorDisplayNumber()
                    != TelephonyManager.ERI_OFF;
        } else {
            return mServiceState != null && mServiceState.getRoaming();
        }
    }

    private boolean isCarrierNetworkChangeActive() {
        return mCurrentState.carrierNetworkChangeMode;
    }

    public void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action.equals(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED)) {
            updateNetworkName(intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_SPN, false),
                    intent.getStringExtra(TelephonyManager.EXTRA_SPN),
                    intent.getStringExtra(TelephonyManager.EXTRA_DATA_SPN),
                    intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_PLMN, false),
                    intent.getStringExtra(TelephonyManager.EXTRA_PLMN));
            notifyListenersIfNecessary();
        } else if (action.equals(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)) {
            updateDataSim();
            notifyListenersIfNecessary();
        }
    }

    private void updateDataSim() {
        int activeDataSubId = mDefaults.getActiveDataSubId();
        if (SubscriptionManager.isValidSubscriptionId(activeDataSubId)) {
            mCurrentState.dataSim = activeDataSubId == mSubscriptionInfo.getSubscriptionId();
        } else {
            // There doesn't seem to be a data sim selected, however if
            // there isn't a MobileSignalController with dataSim set, then
            // QS won't get any callbacks and will be blank.  Instead
            // lets just assume we are the data sim (which will basically
            // show one at random) in QS until one is selected.  The user
            // should pick one soon after, so we shouldn't be in this state
            // for long.
            mCurrentState.dataSim = true;
        }
    }

    /**
     * Updates the network's name based on incoming spn and plmn.
     */
    void updateNetworkName(boolean showSpn, String spn, String dataSpn,
            boolean showPlmn, String plmn) {
        if (CHATTY) {
            Log.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn
                    + " spn=" + spn + " dataSpn=" + dataSpn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        StringBuilder str = new StringBuilder();
        StringBuilder strData = new StringBuilder();
        if (showPlmn && plmn != null) {
            str.append(plmn);
            strData.append(plmn);
        }
        if (showSpn && spn != null) {
            if (str.length() != 0) {
                str.append(mNetworkNameSeparator);
            }
            str.append(spn);
        }
        if (str.length() != 0) {
            mCurrentState.networkName = str.toString();
        } else {
            mCurrentState.networkName = mNetworkNameDefault;
        }
        if (showSpn && dataSpn != null) {
            if (strData.length() != 0) {
                strData.append(mNetworkNameSeparator);
            }
            strData.append(dataSpn);
        }
        if (strData.length() != 0) {
            mCurrentState.networkNameData = strData.toString();
        } else {
            mCurrentState.networkNameData = mNetworkNameDefault;
        }
    }

    /**
     * Extracts the CellSignalStrengthCdma from SignalStrength then returns the level
     */
    private int getCdmaLevel(SignalStrength signalStrength) {
        List<CellSignalStrengthCdma> signalStrengthCdma =
                signalStrength.getCellSignalStrengths(CellSignalStrengthCdma.class);
        if (!signalStrengthCdma.isEmpty()) {
            return signalStrengthCdma.get(0).getLevel();
        }
        return CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }

    private void updateMobileStatus(MobileStatus mobileStatus) {
        mCurrentState.activityIn = mobileStatus.activityIn;
        mCurrentState.activityOut = mobileStatus.activityOut;
        mCurrentState.dataSim = mobileStatus.dataSim;
        mCurrentState.carrierNetworkChangeMode = mobileStatus.carrierNetworkChangeMode;
        mDataState = mobileStatus.dataState;
        notifyMobileLevelChangeIfNecessary(mobileStatus.signalStrength);
        mSignalStrength = mobileStatus.signalStrength;
        mTelephonyDisplayInfo = mobileStatus.telephonyDisplayInfo;
        int lastVoiceState = mServiceState != null ? mServiceState.getState() : -1;
        mServiceState = mobileStatus.serviceState;
        int currentVoiceState =  mServiceState != null ? mServiceState.getState() : -1;
        // Only update the no calling Status in the below scenarios
        // 1. The first valid voice state has been received
        // 2. The voice state has been changed and either the last or current state is
        //    ServiceState.STATE_IN_SERVICE
        if (mProviderModel
                && lastVoiceState != currentVoiceState
                && (lastVoiceState == -1
                        || (lastVoiceState == ServiceState.STATE_IN_SERVICE
                                || currentVoiceState == ServiceState.STATE_IN_SERVICE))) {
            boolean isNoCalling = currentVoiceState != ServiceState.STATE_IN_SERVICE;
            IconState statusIcon = new IconState(isNoCalling, R.drawable.ic_qs_no_calling_sms,
                    getTextIfExists(AccessibilityContentDescriptions.NO_CALLING).toString());
            notifyCallStateChange(statusIcon, mSubscriptionInfo.getSubscriptionId());
        }
    }

    private int getCallStrengthIcon(int level, boolean isWifi) {
        return isWifi ? TelephonyIcons.WIFI_CALL_STRENGTH_ICONS[level]
                : TelephonyIcons.MOBILE_CALL_STRENGTH_ICONS[level];
    }

    private String getCallStrengthDescription(int level, boolean isWifi) {
        return isWifi
                ? getTextIfExists(AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH[level])
                        .toString()
                : getTextIfExists(AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[level])
                        .toString();
    }

    void refreshCallIndicator(SignalCallback callback) {
        boolean isNoCalling = mServiceState != null
                && mServiceState.getState() != ServiceState.STATE_IN_SERVICE;
        IconState statusIcon = new IconState(isNoCalling, R.drawable.ic_qs_no_calling_sms,
                getTextIfExists(AccessibilityContentDescriptions.NO_CALLING).toString());
        callback.setCallIndicator(statusIcon, mSubscriptionInfo.getSubscriptionId());

        switch (mImsType) {
            case IMS_TYPE_WWAN:
                statusIcon = new IconState(
                        true,
                        getCallStrengthIcon(mLastWwanLevel, /* isWifi= */false),
                        getCallStrengthDescription(mLastWwanLevel, /* isWifi= */false));
                break;
            case IMS_TYPE_WLAN:
                statusIcon = new IconState(
                        true,
                        getCallStrengthIcon(mLastWlanLevel, /* isWifi= */true),
                        getCallStrengthDescription(mLastWlanLevel, /* isWifi= */true));
                break;
            case IMS_TYPE_WLAN_CROSS_SIM:
                statusIcon = new IconState(
                        true,
                        getCallStrengthIcon(mLastWlanCrossSimLevel, /* isWifi= */false),
                        getCallStrengthDescription(mLastWlanCrossSimLevel, /* isWifi= */false));
        }
        callback.setCallIndicator(statusIcon, mSubscriptionInfo.getSubscriptionId());
    }

    void notifyWifiLevelChange(int level) {
        if (!mProviderModel) {
            return;
        }
        Log.d("mTag", "notifyWifiLevelChange " + mImsType);
        mLastWlanLevel = level;
        if (mImsType != IMS_TYPE_WLAN) {
            return;
        }
        IconState statusIcon = new IconState(
                true,
                getCallStrengthIcon(level, /* isWifi= */true),
                getCallStrengthDescription(level, /* isWifi= */true));
        notifyCallStateChange(statusIcon, mSubscriptionInfo.getSubscriptionId());
    }

    void notifyDefaultMobileLevelChange(int level) {
        if (!mProviderModel) {
            return;
        }
        Log.d("mTag", "notifyDefaultMobileLevelChange " + mImsType);
        mLastWlanCrossSimLevel = level;
        if (mImsType != IMS_TYPE_WLAN_CROSS_SIM) {
            return;
        }
        IconState statusIcon = new IconState(
                true,
                getCallStrengthIcon(level, /* isWifi= */false),
                getCallStrengthDescription(level, /* isWifi= */false));
        notifyCallStateChange(statusIcon, mSubscriptionInfo.getSubscriptionId());
    }

    void notifyMobileLevelChangeIfNecessary(SignalStrength signalStrength) {
        if (!mProviderModel) {
            return;
        }
        int newLevel = getSignalLevel(signalStrength);
        if (newLevel != mLastLevel) {
            mLastLevel = newLevel;
            Log.d("mTag", "notifyMobileLevelChangeIfNecessary " + mImsType);
            mLastWwanLevel = newLevel;
            if (mImsType == IMS_TYPE_WWAN) {
                IconState statusIcon = new IconState(
                        true,
                        getCallStrengthIcon(newLevel, /* isWifi= */false),
                        getCallStrengthDescription(newLevel, /* isWifi= */false));
                notifyCallStateChange(statusIcon, mSubscriptionInfo.getSubscriptionId());
            }
            if (mCurrentState.dataSim) {
                mNetworkController.notifyDefaultMobileLevelChange(newLevel);
            }
        }
    }

    int getSignalLevel(SignalStrength signalStrength) {
        if (signalStrength == null) {
            return 0;
        }
        if (!signalStrength.isGsm() && mConfig.alwaysShowCdmaRssi) {
            return getCdmaLevel(signalStrength);
        } else {
            return signalStrength.getLevel();
        }
    }

    /**
     * Updates the current state based on mServiceState, mSignalStrength, mDataState,
     * mTelephonyDisplayInfo, and mSimState.  It should be called any time one of these is updated.
     * This will call listeners if necessary.
     */
    private final void updateTelephony() {
        if (Log.isLoggable(mTag, Log.DEBUG)) {
            Log.d(mTag, "updateTelephonySignalStrength: hasService=" +
                    Utils.isInService(mServiceState) + " ss=" + mSignalStrength
                    + " displayInfo=" + mTelephonyDisplayInfo);
        }
        checkDefaultData();
        mCurrentState.connected = Utils.isInService(mServiceState) && mSignalStrength != null;
        if (mCurrentState.connected) {
            mCurrentState.level = getSignalLevel(mSignalStrength);
        }

        String iconKey = getIconKey(mTelephonyDisplayInfo);
        if (mNetworkToIconLookup.get(iconKey) != null) {
            mCurrentState.iconGroup = mNetworkToIconLookup.get(iconKey);
        } else {
            mCurrentState.iconGroup = mDefaultIcons;
        }
        mCurrentState.dataConnected = mCurrentState.connected
                && mDataState == TelephonyManager.DATA_CONNECTED;

        mCurrentState.roaming = isRoaming();
        if (isCarrierNetworkChangeActive()) {
            mCurrentState.iconGroup = TelephonyIcons.CARRIER_NETWORK_CHANGE;
        } else if (isDataDisabled() && !mConfig.alwaysShowDataRatIcon) {
            if (mSubscriptionInfo.getSubscriptionId() != mDefaults.getDefaultDataSubId()) {
                mCurrentState.iconGroup = TelephonyIcons.NOT_DEFAULT_DATA;
            } else {
                mCurrentState.iconGroup = TelephonyIcons.DATA_DISABLED;
            }
        }
        if (isEmergencyOnly() != mCurrentState.isEmergency) {
            mCurrentState.isEmergency = isEmergencyOnly();
            mNetworkController.recalculateEmergency();
        }
        // Fill in the network name if we think we have it.
        if (mCurrentState.networkName.equals(mNetworkNameDefault) && mServiceState != null
                && !TextUtils.isEmpty(mServiceState.getOperatorAlphaShort())) {
            mCurrentState.networkName = mServiceState.getOperatorAlphaShort();
        }
        // If this is the data subscription, update the currentState data name
        if (mCurrentState.networkNameData.equals(mNetworkNameDefault) && mServiceState != null
                && mCurrentState.dataSim
                && !TextUtils.isEmpty(mServiceState.getOperatorAlphaShort())) {
            mCurrentState.networkNameData = mServiceState.getOperatorAlphaShort();
        }

        notifyListenersIfNecessary();
    }

    /**
     * If we are controlling the NOT_DEFAULT_DATA icon, check the status of the other one
     */
    private void checkDefaultData() {
        if (mCurrentState.iconGroup != TelephonyIcons.NOT_DEFAULT_DATA) {
            mCurrentState.defaultDataOff = false;
            return;
        }

        mCurrentState.defaultDataOff = mNetworkController.isDataControllerDisabled();
    }

    void onMobileDataChanged() {
        checkDefaultData();
        notifyListenersIfNecessary();
    }

    boolean isDataDisabled() {
        return !mPhone.isDataConnectionAllowed();
    }

    @VisibleForTesting
    void setActivity(int activity) {
        mCurrentState.activityIn = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_IN;
        mCurrentState.activityOut = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_OUT;
        notifyListenersIfNecessary();
    }

    private void recordLastMobileStatus(String mobileStatus) {
        mMobileStatusHistory[mMobileStatusHistoryIndex] = mobileStatus;
        mMobileStatusHistoryIndex = (mMobileStatusHistoryIndex + 1) % STATUS_HISTORY_SIZE;
    }

    @VisibleForTesting
    void setImsType(int imsType) {
        mImsType = imsType;
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        pw.println("  mSubscription=" + mSubscriptionInfo + ",");
        pw.println("  mServiceState=" + mServiceState + ",");
        pw.println("  mSignalStrength=" + mSignalStrength + ",");
        pw.println("  mTelephonyDisplayInfo=" + mTelephonyDisplayInfo + ",");
        pw.println("  mDataState=" + mDataState + ",");
        pw.println("  mInflateSignalStrengths=" + mInflateSignalStrengths + ",");
        pw.println("  isDataDisabled=" + isDataDisabled() + ",");
        pw.println("  MobileStatusHistory");
        int size = 0;
        for (int i = 0; i < STATUS_HISTORY_SIZE; i++) {
            if (mMobileStatusHistory[i] != null) {
                size++;
            }
        }
        // Print out the previous states in ordered number.
        for (int i = mMobileStatusHistoryIndex + STATUS_HISTORY_SIZE - 1;
                i >= mMobileStatusHistoryIndex + STATUS_HISTORY_SIZE - size; i--) {
            pw.println("  Previous MobileStatus("
                    + (mMobileStatusHistoryIndex + STATUS_HISTORY_SIZE - i) + "): "
                    + mMobileStatusHistory[i & (STATUS_HISTORY_SIZE - 1)]);
        }
    }
}
