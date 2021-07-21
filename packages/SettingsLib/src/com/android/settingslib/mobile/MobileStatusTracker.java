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
package com.android.settingslib.mobile;

import android.os.Handler;
import android.os.Looper;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Tracks the mobile signal status for the SysUI and Settings.
 *
 * This class is not threadsafe. All the mobile statuses monitored by this class is stored in
 * MobileStatus. Whoever uses this class should only rely on the MobileStatusTracker#Callback
 * to get the latest mobile statuses. Do not get mobile statues directly from
 * MobileStatusTracker#MobileStatus.
 */
public class MobileStatusTracker {
    private static final String TAG = "MobileStatusTracker";
    private final TelephonyManager mPhone;
    private final SubscriptionInfo mSubscriptionInfo;
    private final Callback mCallback;
    private final MobileStatus mMobileStatus;
    private final SubscriptionDefaults mDefaults;
    private final Handler mReceiverHandler;
    private final MobileTelephonyCallback mTelephonyCallback;

    /**
     * MobileStatusTracker constructors
     *
     * @param phone The TelephonyManager which corresponds to the subscription being monitored.
     * @param receiverLooper The Looper on which the callback will be invoked.
     * @param info The subscription being monitored.
     * @param defaults The wrapper of the SubscriptionManager.
     * @param callback The callback to notify any changes of the mobile status, users should only
     *                 use this callback to get the latest mobile status.
     */
    public MobileStatusTracker(TelephonyManager phone, Looper receiverLooper,
            SubscriptionInfo info, SubscriptionDefaults defaults, Callback callback) {
        mPhone = phone;
        mReceiverHandler = new Handler(receiverLooper);
        mTelephonyCallback = new MobileTelephonyCallback();
        mSubscriptionInfo = info;
        mDefaults = defaults;
        mCallback = callback;
        mMobileStatus = new MobileStatus();
        updateDataSim();
        mReceiverHandler.post(() -> mCallback.onMobileStatusChanged(
                /* updateTelephony= */false, new MobileStatus(mMobileStatus)));
    }

    public MobileTelephonyCallback getTelephonyCallback() {
        return mTelephonyCallback;
    }

    /**
     * Config the MobileStatusTracker to start or stop monitoring platform signals.
     */
    public void setListening(boolean listening) {
        if (listening) {
            mPhone.registerTelephonyCallback(mReceiverHandler::post, mTelephonyCallback);
        } else {
            mPhone.unregisterTelephonyCallback(mTelephonyCallback);
        }
    }

    private void updateDataSim() {
        int activeDataSubId = mDefaults.getActiveDataSubId();
        if (SubscriptionManager.isValidSubscriptionId(activeDataSubId)) {
            mMobileStatus.dataSim = activeDataSubId == mSubscriptionInfo.getSubscriptionId();
        } else {
            // There doesn't seem to be a data sim selected, however if
            // there isn't a MobileSignalController with dataSim set, then
            // QS won't get any callbacks and will be blank.  Instead
            // lets just assume we are the data sim (which will basically
            // show one at random) in QS until one is selected.  The user
            // should pick one soon after, so we shouldn't be in this state
            // for long.
            mMobileStatus.dataSim = true;
        }
    }

    private void setActivity(int activity) {
        mMobileStatus.activityIn = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_IN;
        mMobileStatus.activityOut = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_OUT;
    }

    public class MobileTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DataConnectionStateListener,
            TelephonyCallback.DataActivityListener,
            TelephonyCallback.CarrierNetworkListener,
            TelephonyCallback.ActiveDataSubscriptionIdListener,
            TelephonyCallback.DisplayInfoListener{

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onSignalStrengthsChanged signalStrength=" + signalStrength
                        + ((signalStrength == null) ? ""
                                : (" level=" + signalStrength.getLevel())));
            }
            mMobileStatus.signalStrength = signalStrength;
            mCallback.onMobileStatusChanged(
                    /* updateTelephony= */true, new MobileStatus(mMobileStatus));
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onServiceStateChanged voiceState="
                        + (state == null ? "" : state.getState())
                        + " dataState=" + (state == null ? "" : state.getDataRegistrationState()));
            }
            mMobileStatus.serviceState = state;
            mCallback.onMobileStatusChanged(
                    /* updateTelephony= */true, new MobileStatus(mMobileStatus));
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onDataConnectionStateChanged: state=" + state
                        + " type=" + networkType);
            }
            mMobileStatus.dataState = state;
            mCallback.onMobileStatusChanged(
                    /* updateTelephony= */true, new MobileStatus(mMobileStatus));
        }

        @Override
        public void onDataActivity(int direction) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onDataActivity: direction=" + direction);
            }
            setActivity(direction);
            mCallback.onMobileStatusChanged(
                    /* updateTelephony= */false, new MobileStatus(mMobileStatus));
        }

        @Override
        public void onCarrierNetworkChange(boolean active) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCarrierNetworkChange: active=" + active);
            }
            mMobileStatus.carrierNetworkChangeMode = active;
            mCallback.onMobileStatusChanged(
                    /* updateTelephony= */true, new MobileStatus(mMobileStatus));
        }

        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onActiveDataSubscriptionIdChanged: subId=" + subId);
            }
            updateDataSim();
            mCallback.onMobileStatusChanged(
                    /* updateTelephony= */true, new MobileStatus(mMobileStatus));
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onDisplayInfoChanged: telephonyDisplayInfo=" + telephonyDisplayInfo);
            }
            mMobileStatus.telephonyDisplayInfo = telephonyDisplayInfo;
            mCallback.onMobileStatusChanged(
                    /* updateTelephony= */ true, new MobileStatus(mMobileStatus));
        }
    }

    /**
     * Wrapper class of the SubscriptionManager, for mock testing purpose
     */
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

    /**
     * Wrapper class which contains all the mobile status tracked by MobileStatusTracker.
     */
    public static class MobileStatus {
        public boolean activityIn;
        public boolean activityOut;
        public boolean dataSim;
        public boolean carrierNetworkChangeMode;
        public int dataState = TelephonyManager.DATA_DISCONNECTED;
        public ServiceState serviceState;
        public SignalStrength signalStrength;
        public TelephonyDisplayInfo telephonyDisplayInfo =
                new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE);

        /**
         * Empty constructor
         */
        public MobileStatus() { }

        /**
         * Copy constructors
         *
         * @param status Source MobileStatus
         */
        public MobileStatus(MobileStatus status) {
            copyFrom(status);
        }

        protected void copyFrom(MobileStatus status) {
            activityIn = status.activityIn;
            activityOut = status.activityOut;
            dataSim = status.dataSim;
            carrierNetworkChangeMode = status.carrierNetworkChangeMode;
            dataState = status.dataState;
            // We don't do deep copy for the below members since they may be Mockito instances.
            serviceState = status.serviceState;
            signalStrength = status.signalStrength;
            telephonyDisplayInfo = status.telephonyDisplayInfo;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            return builder.append("[activityIn=").append(activityIn).append(',')
                .append("activityOut=").append(activityOut).append(',')
                .append("dataSim=").append(dataSim).append(',')
                .append("carrierNetworkChangeMode=").append(carrierNetworkChangeMode).append(',')
                .append("dataState=").append(dataState).append(',')
                .append("serviceState=").append(serviceState == null ? ""
                        : "mVoiceRegState=" + serviceState.getState() + "("
                                + ServiceState.rilServiceStateToString(serviceState.getState())
                                + ")" + ", mDataRegState=" + serviceState.getDataRegState() + "("
                                + ServiceState.rilServiceStateToString(
                                        serviceState.getDataRegState()) + ")")
                                        .append(',')
                .append("signalStrength=").append(signalStrength == null ? ""
                        : signalStrength.getLevel()).append(',')
                .append("telephonyDisplayInfo=").append(telephonyDisplayInfo == null ? ""
                        : telephonyDisplayInfo.toString()).append(']').toString();
        }
    }

    /**
     * Callback for notifying any changes of the mobile status.
     *
     * This callback will always be invoked on the receiverLooper which must be specified when
     * MobileStatusTracker is constructed.
     */
    public interface Callback {
        /**
         * Notify the mobile status has been updated.
         *
         * @param updateTelephony Whether needs to update other Telephony related parameters, this
         *                        is only used by SysUI.
         * @param mobileStatus Holds the latest mobile statuses
         */
        void onMobileStatusChanged(boolean updateTelephony, MobileStatus mobileStatus);
    }
}
