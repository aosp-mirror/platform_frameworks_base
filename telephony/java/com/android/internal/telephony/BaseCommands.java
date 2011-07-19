/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;


import android.content.Context;
import android.os.RegistrantList;
import android.os.Registrant;
import android.os.Handler;
import android.os.AsyncResult;
import android.os.SystemProperties;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@hide}
 */
public abstract class BaseCommands implements CommandsInterface {
    static final String LOG_TAG = "RILB";

    //***** Instance Variables
    protected Context mContext;
    protected RadioState mState = RadioState.RADIO_UNAVAILABLE;
    protected RadioState mSimState = RadioState.RADIO_UNAVAILABLE;
    protected RadioState mRuimState = RadioState.RADIO_UNAVAILABLE;
    protected RadioState mNvState = RadioState.RADIO_UNAVAILABLE;
    protected Object mStateMonitor = new Object();

    protected RegistrantList mRadioStateChangedRegistrants = new RegistrantList();
    protected RegistrantList mOnRegistrants = new RegistrantList();
    protected RegistrantList mAvailRegistrants = new RegistrantList();
    protected RegistrantList mOffOrNotAvailRegistrants = new RegistrantList();
    protected RegistrantList mNotAvailRegistrants = new RegistrantList();
    protected RegistrantList mSIMReadyRegistrants = new RegistrantList();
    protected RegistrantList mSIMLockedRegistrants = new RegistrantList();
    protected RegistrantList mRUIMReadyRegistrants = new RegistrantList();
    protected RegistrantList mRUIMLockedRegistrants = new RegistrantList();
    protected RegistrantList mNVReadyRegistrants = new RegistrantList();
    protected RegistrantList mCallStateRegistrants = new RegistrantList();
    protected RegistrantList mVoiceNetworkStateRegistrants = new RegistrantList();
    protected RegistrantList mDataNetworkStateRegistrants = new RegistrantList();
    protected RegistrantList mRadioTechnologyChangedRegistrants = new RegistrantList();
    protected RegistrantList mIccStatusChangedRegistrants = new RegistrantList();
    protected RegistrantList mVoicePrivacyOnRegistrants = new RegistrantList();
    protected RegistrantList mVoicePrivacyOffRegistrants = new RegistrantList();
    protected Registrant mUnsolOemHookRawRegistrant;
    protected RegistrantList mOtaProvisionRegistrants = new RegistrantList();
    protected RegistrantList mCallWaitingInfoRegistrants = new RegistrantList();
    protected RegistrantList mDisplayInfoRegistrants = new RegistrantList();
    protected RegistrantList mSignalInfoRegistrants = new RegistrantList();
    protected RegistrantList mNumberInfoRegistrants = new RegistrantList();
    protected RegistrantList mRedirNumInfoRegistrants = new RegistrantList();
    protected RegistrantList mLineControlInfoRegistrants = new RegistrantList();
    protected RegistrantList mT53ClirInfoRegistrants = new RegistrantList();
    protected RegistrantList mT53AudCntrlInfoRegistrants = new RegistrantList();
    protected RegistrantList mRingbackToneRegistrants = new RegistrantList();
    protected RegistrantList mResendIncallMuteRegistrants = new RegistrantList();
    protected RegistrantList mCdmaSubscriptionChangedRegistrants = new RegistrantList();
    protected RegistrantList mCdmaPrlChangedRegistrants = new RegistrantList();
    protected RegistrantList mExitEmergencyCallbackModeRegistrants = new RegistrantList();
    protected RegistrantList mRilConnectedRegistrants = new RegistrantList();
    protected RegistrantList mIccRefreshRegistrants = new RegistrantList();

    protected Registrant mSMSRegistrant;
    protected Registrant mNITZTimeRegistrant;
    protected Registrant mSignalStrengthRegistrant;
    protected Registrant mUSSDRegistrant;
    protected Registrant mSmsOnSimRegistrant;
    protected Registrant mSmsStatusRegistrant;
    protected Registrant mSsnRegistrant;
    protected Registrant mCatSessionEndRegistrant;
    protected Registrant mCatProCmdRegistrant;
    protected Registrant mCatEventRegistrant;
    protected Registrant mCatCallSetUpRegistrant;
    protected Registrant mIccSmsFullRegistrant;
    protected Registrant mEmergencyCallbackModeRegistrant;
    protected Registrant mRingRegistrant;
    protected Registrant mRestrictedStateRegistrant;
    protected Registrant mGsmBroadcastSmsRegistrant;

    // Preferred network type received from PhoneFactory.
    // This is used when establishing a connection to the
    // vendor ril so it starts up in the correct mode.
    protected int mPreferredNetworkType;
    // CDMA subscription received from PhoneFactory
    protected int mCdmaSubscription;
    // Type of Phone, GSM or CDMA. Set by CDMAPhone or GSMPhone.
    protected int mPhoneType;
    // RIL Version
    protected int mRilVersion = -1;

    public BaseCommands(Context context) {
        mContext = context;  // May be null (if so we won't log statistics)
    }

    //***** CommandsInterface implementation

    public RadioState getRadioState() {
        return mState;
    }

    public RadioState getSimState() {
        return mSimState;
    }

    public RadioState getRuimState() {
        return mRuimState;
    }

    public RadioState getNvState() {
        return mNvState;
    }


    public void registerForRadioStateChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mRadioStateChangedRegistrants.add(r);
            r.notifyRegistrant();
        }
    }

    public void unregisterForRadioStateChanged(Handler h) {
        synchronized (mStateMonitor) {
            mRadioStateChangedRegistrants.remove(h);
        }
    }

    public void registerForOn(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mOnRegistrants.add(r);

            if (mState.isOn()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }
    public void unregisterForOn(Handler h) {
        synchronized (mStateMonitor) {
            mOnRegistrants.remove(h);
        }
    }


    public void registerForAvailable(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mAvailRegistrants.add(r);

            if (mState.isAvailable()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void unregisterForAvailable(Handler h) {
        synchronized(mStateMonitor) {
            mAvailRegistrants.remove(h);
        }
    }

    public void registerForNotAvailable(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mNotAvailRegistrants.add(r);

            if (!mState.isAvailable()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void unregisterForNotAvailable(Handler h) {
        synchronized (mStateMonitor) {
            mNotAvailRegistrants.remove(h);
        }
    }

    public void registerForOffOrNotAvailable(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mOffOrNotAvailRegistrants.add(r);

            if (mState == RadioState.RADIO_OFF || !mState.isAvailable()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }
    public void unregisterForOffOrNotAvailable(Handler h) {
        synchronized(mStateMonitor) {
            mOffOrNotAvailRegistrants.remove(h);
        }
    }


    /** Any transition into SIM_READY */
    public void registerForSIMReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mSIMReadyRegistrants.add(r);

            if (mSimState.isSIMReady()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void unregisterForSIMReady(Handler h) {
        synchronized (mStateMonitor) {
            mSIMReadyRegistrants.remove(h);
        }
    }

    /** Any transition into RUIM_READY */
    public void registerForRUIMReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mRUIMReadyRegistrants.add(r);

            if (mRuimState.isRUIMReady()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void unregisterForRUIMReady(Handler h) {
        synchronized(mStateMonitor) {
            mRUIMReadyRegistrants.remove(h);
        }
    }

    /** Any transition into NV_READY */
    public void registerForNVReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mNVReadyRegistrants.add(r);

            if (mNvState.isNVReady()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void unregisterForNVReady(Handler h) {
        synchronized (mStateMonitor) {
            mNVReadyRegistrants.remove(h);
        }
    }

    public void registerForSIMLockedOrAbsent(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mSIMLockedRegistrants.add(r);

            if (mSimState == RadioState.SIM_LOCKED_OR_ABSENT) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void unregisterForSIMLockedOrAbsent(Handler h) {
        synchronized (mStateMonitor) {
            mSIMLockedRegistrants.remove(h);
        }
    }

    public void registerForRUIMLockedOrAbsent(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mRUIMLockedRegistrants.add(r);

            if (mRuimState == RadioState.RUIM_LOCKED_OR_ABSENT) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void unregisterForRUIMLockedOrAbsent(Handler h) {
        synchronized (mStateMonitor) {
            mRUIMLockedRegistrants.remove(h);
        }
    }

    public void registerForCallStateChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mCallStateRegistrants.add(r);
    }

    public void unregisterForCallStateChanged(Handler h) {
        mCallStateRegistrants.remove(h);
    }

    public void registerForVoiceNetworkStateChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mVoiceNetworkStateRegistrants.add(r);
    }

    public void unregisterForVoiceNetworkStateChanged(Handler h) {
        mVoiceNetworkStateRegistrants.remove(h);
    }

    public void registerForDataNetworkStateChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mDataNetworkStateRegistrants.add(r);
    }

    public void unregisterForDataNetworkStateChanged(Handler h) {
        mDataNetworkStateRegistrants.remove(h);
    }

    public void registerForRadioTechnologyChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mRadioTechnologyChangedRegistrants.add(r);
    }

    public void unregisterForRadioTechnologyChanged(Handler h) {
        mRadioTechnologyChangedRegistrants.remove(h);
    }

    public void registerForIccStatusChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mIccStatusChangedRegistrants.add(r);
    }

    public void unregisterForIccStatusChanged(Handler h) {
        mIccStatusChangedRegistrants.remove(h);
    }

    public void setOnNewSMS(Handler h, int what, Object obj) {
        mSMSRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnNewSMS(Handler h) {
        mSMSRegistrant.clear();
    }

    public void setOnNewGsmBroadcastSms(Handler h, int what, Object obj) {
        mGsmBroadcastSmsRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnNewGsmBroadcastSms(Handler h) {
        mGsmBroadcastSmsRegistrant.clear();
    }

    public void setOnSmsOnSim(Handler h, int what, Object obj) {
        mSmsOnSimRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnSmsOnSim(Handler h) {
        mSmsOnSimRegistrant.clear();
    }

    public void setOnSmsStatus(Handler h, int what, Object obj) {
        mSmsStatusRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnSmsStatus(Handler h) {
        mSmsStatusRegistrant.clear();
    }

    public void setOnSignalStrengthUpdate(Handler h, int what, Object obj) {
        mSignalStrengthRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnSignalStrengthUpdate(Handler h) {
        mSignalStrengthRegistrant.clear();
    }

    public void setOnNITZTime(Handler h, int what, Object obj) {
        mNITZTimeRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnNITZTime(Handler h) {
        mNITZTimeRegistrant.clear();
    }

    public void setOnUSSD(Handler h, int what, Object obj) {
        mUSSDRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnUSSD(Handler h) {
        mUSSDRegistrant.clear();
    }

    public void setOnSuppServiceNotification(Handler h, int what, Object obj) {
        mSsnRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnSuppServiceNotification(Handler h) {
        mSsnRegistrant.clear();
    }

    public void setOnCatSessionEnd(Handler h, int what, Object obj) {
        mCatSessionEndRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnCatSessionEnd(Handler h) {
        mCatSessionEndRegistrant.clear();
    }

    public void setOnCatProactiveCmd(Handler h, int what, Object obj) {
        mCatProCmdRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnCatProactiveCmd(Handler h) {
        mCatProCmdRegistrant.clear();
    }

    public void setOnCatEvent(Handler h, int what, Object obj) {
        mCatEventRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnCatEvent(Handler h) {
        mCatEventRegistrant.clear();
    }

    public void setOnCatCallSetUp(Handler h, int what, Object obj) {
        mCatCallSetUpRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnCatCallSetUp(Handler h) {
        mCatCallSetUpRegistrant.clear();
    }

    public void setOnIccSmsFull(Handler h, int what, Object obj) {
        mIccSmsFullRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnIccSmsFull(Handler h) {
        mIccSmsFullRegistrant.clear();
    }

    public void registerForIccRefresh(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mIccRefreshRegistrants.add(r);
    }
    public void setOnIccRefresh(Handler h, int what, Object obj) {
        registerForIccRefresh(h, what, obj);
    }

    public void setEmergencyCallbackMode(Handler h, int what, Object obj) {
        mEmergencyCallbackModeRegistrant = new Registrant (h, what, obj);
    }

    public void unregisterForIccRefresh(Handler h) {
        mIccRefreshRegistrants.remove(h);
    }
    public void unsetOnIccRefresh(Handler h) {
        unregisterForIccRefresh(h);
    }

    public void setOnCallRing(Handler h, int what, Object obj) {
        mRingRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnCallRing(Handler h) {
        mRingRegistrant.clear();
    }

    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mVoicePrivacyOnRegistrants.add(r);
    }

    public void unregisterForInCallVoicePrivacyOn(Handler h){
        mVoicePrivacyOnRegistrants.remove(h);
    }

    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mVoicePrivacyOffRegistrants.add(r);
    }

    public void unregisterForInCallVoicePrivacyOff(Handler h){
        mVoicePrivacyOffRegistrants.remove(h);
    }

    public void setOnRestrictedStateChanged(Handler h, int what, Object obj) {
        mRestrictedStateRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnRestrictedStateChanged(Handler h) {
        mRestrictedStateRegistrant.clear();
    }

    public void registerForDisplayInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mDisplayInfoRegistrants.add(r);
    }

    public void unregisterForDisplayInfo(Handler h) {
        mDisplayInfoRegistrants.remove(h);
    }

    public void registerForCallWaitingInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mCallWaitingInfoRegistrants.add(r);
    }

    public void unregisterForCallWaitingInfo(Handler h) {
        mCallWaitingInfoRegistrants.remove(h);
    }

    public void registerForSignalInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mSignalInfoRegistrants.add(r);
    }

    public void setOnUnsolOemHookRaw(Handler h, int what, Object obj) {
        mUnsolOemHookRawRegistrant = new Registrant (h, what, obj);
    }

    public void unSetOnUnsolOemHookRaw(Handler h) {
        mUnsolOemHookRawRegistrant.clear();
    }

    public void unregisterForSignalInfo(Handler h) {
        mSignalInfoRegistrants.remove(h);
    }

    public void registerForCdmaOtaProvision(Handler h,int what, Object obj){
        Registrant r = new Registrant (h, what, obj);
        mOtaProvisionRegistrants.add(r);
    }

    public void unregisterForCdmaOtaProvision(Handler h){
        mOtaProvisionRegistrants.remove(h);
    }

    public void registerForNumberInfo(Handler h,int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mNumberInfoRegistrants.add(r);
    }

    public void unregisterForNumberInfo(Handler h){
        mNumberInfoRegistrants.remove(h);
    }

     public void registerForRedirectedNumberInfo(Handler h,int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mRedirNumInfoRegistrants.add(r);
    }

    public void unregisterForRedirectedNumberInfo(Handler h) {
        mRedirNumInfoRegistrants.remove(h);
    }

    public void registerForLineControlInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mLineControlInfoRegistrants.add(r);
    }

    public void unregisterForLineControlInfo(Handler h) {
        mLineControlInfoRegistrants.remove(h);
    }

    public void registerFoT53ClirlInfo(Handler h,int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mT53ClirInfoRegistrants.add(r);
    }

    public void unregisterForT53ClirInfo(Handler h) {
        mT53ClirInfoRegistrants.remove(h);
    }

    public void registerForT53AudioControlInfo(Handler h,int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mT53AudCntrlInfoRegistrants.add(r);
    }

    public void unregisterForT53AudioControlInfo(Handler h) {
        mT53AudCntrlInfoRegistrants.remove(h);
    }

    public void registerForRingbackTone(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mRingbackToneRegistrants.add(r);
    }

    public void unregisterForRingbackTone(Handler h) {
        mRingbackToneRegistrants.remove(h);
    }

    public void registerForResendIncallMute(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mResendIncallMuteRegistrants.add(r);
    }

    public void unregisterForResendIncallMute(Handler h) {
        mResendIncallMuteRegistrants.remove(h);
    }

    @Override
    public void registerForCdmaSubscriptionChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mCdmaSubscriptionChangedRegistrants.add(r);
    }

    @Override
    public void unregisterForCdmaSubscriptionChanged(Handler h) {
        mCdmaSubscriptionChangedRegistrants.remove(h);
    }

    @Override
    public void registerForCdmaPrlChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mCdmaPrlChangedRegistrants.add(r);
    }

    @Override
    public void unregisterForCdmaPrlChanged(Handler h) {
        mCdmaPrlChangedRegistrants.remove(h);
    }

    @Override
    public void registerForExitEmergencyCallbackMode(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mExitEmergencyCallbackModeRegistrants.add(r);
    }

    @Override
    public void unregisterForExitEmergencyCallbackMode(Handler h) {
        mExitEmergencyCallbackModeRegistrants.remove(h);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerForRilConnected(Handler h, int what, Object obj) {
        Log.d(LOG_TAG, "registerForRilConnected h=" + h + " w=" + what);
        Registrant r = new Registrant (h, what, obj);
        mRilConnectedRegistrants.add(r);
        if (mRilVersion != -1) {
            Log.d(LOG_TAG, "Notifying: ril connected mRilVersion=" + mRilVersion);
            r.notifyRegistrant(new AsyncResult(null, new Integer(mRilVersion), null));
        }
    }

    @Override
    public void unregisterForRilConnected(Handler h) {
        mRilConnectedRegistrants.remove(h);
    }

    //***** Protected Methods
    /**
     * Store new RadioState and send notification based on the changes
     *
     * This function is called only by RIL.java when receiving unsolicited
     * RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED
     *
     * RadioState has 5 values : RADIO_OFF, RADIO_UNAVAILABLE, SIM_NOT_READY,
     * SIM_LOCKED_OR_ABSENT, and SIM_READY.
     *
     * @param newState new RadioState decoded from RIL_UNSOL_RADIO_STATE_CHANGED
     */
    protected void setRadioState(RadioState newState) {
        RadioState oldState;

        synchronized (mStateMonitor) {
            if (false) {
                Log.v(LOG_TAG, "setRadioState old: " + mState
                    + " new " + newState);
            }

            oldState = mState;
            mState = newState;

            if (oldState == mState) {
                // no state transition
                return;
            }

            // FIXME: Use Constants or Enums
            if(mState.getType() == 0) {
                mSimState = mState;
                mRuimState = mState;
                mNvState = mState;
            }
            else if (mState.getType() == 1) {
                mSimState = mState;
            }
            else if (mState.getType() == 2) {
                mRuimState = mState;
            }
            else if (mState.getType() == 3) {
                mNvState = mState;
            }

            mRadioStateChangedRegistrants.notifyRegistrants();

            if (mState.isAvailable() && !oldState.isAvailable()) {
                Log.d(LOG_TAG,"Notifying: radio available");
                mAvailRegistrants.notifyRegistrants();
                onRadioAvailable();
            }

            if (!mState.isAvailable() && oldState.isAvailable()) {
                Log.d(LOG_TAG,"Notifying: radio not available");
                mNotAvailRegistrants.notifyRegistrants();
            }

            if (mState.isSIMReady() && !oldState.isSIMReady()) {
                Log.d(LOG_TAG,"Notifying: SIM ready");
                mSIMReadyRegistrants.notifyRegistrants();
            }

            if (mState == RadioState.SIM_LOCKED_OR_ABSENT) {
                Log.d(LOG_TAG,"Notifying: SIM locked or absent");
                mSIMLockedRegistrants.notifyRegistrants();
            }

            if (mState.isRUIMReady() && !oldState.isRUIMReady()) {
                Log.d(LOG_TAG,"Notifying: RUIM ready");
                mRUIMReadyRegistrants.notifyRegistrants();
            }

            if (mState == RadioState.RUIM_LOCKED_OR_ABSENT) {
                Log.d(LOG_TAG,"Notifying: RUIM locked or absent");
                mRUIMLockedRegistrants.notifyRegistrants();
            }
            if (mState.isNVReady() && !oldState.isNVReady()) {
                Log.d(LOG_TAG,"Notifying: NV ready");
                mNVReadyRegistrants.notifyRegistrants();
            }

            if (mState.isOn() && !oldState.isOn()) {
                Log.d(LOG_TAG,"Notifying: Radio On");
                mOnRegistrants.notifyRegistrants();
            }

            if ((!mState.isOn() || !mState.isAvailable())
                && !((!oldState.isOn() || !oldState.isAvailable()))
            ) {
                Log.d(LOG_TAG,"Notifying: radio off or not available");
                mOffOrNotAvailRegistrants.notifyRegistrants();
            }

            /* Radio Technology Change events
             * NOTE: isGsm and isCdma have no common states in RADIO_OFF or RADIO_UNAVAILABLE; the
             *   current phone is determined by mPhoneType
             * NOTE: at startup no phone have been created and the RIL determines the mPhoneType
             *   looking based on the networkMode set by the PhoneFactory in the constructor
             */

            if (mState.isGsm() && oldState.isCdma()) {
                Log.d(LOG_TAG,"Notifying: radio technology change CDMA to GSM");
                mRadioTechnologyChangedRegistrants.notifyRegistrants();
            }

            if (mState.isGsm() && !oldState.isOn() && (mPhoneType == Phone.PHONE_TYPE_CDMA)) {
                Log.d(LOG_TAG,"Notifying: radio technology change CDMA OFF to GSM");
                mRadioTechnologyChangedRegistrants.notifyRegistrants();
            }

            if (mState.isCdma() && oldState.isGsm()) {
                Log.d(LOG_TAG,"Notifying: radio technology change GSM to CDMA");
                mRadioTechnologyChangedRegistrants.notifyRegistrants();
            }

            if (mState.isCdma() && !oldState.isOn() && (mPhoneType == Phone.PHONE_TYPE_GSM)) {
                Log.d(LOG_TAG,"Notifying: radio technology change GSM OFF to CDMA");
                mRadioTechnologyChangedRegistrants.notifyRegistrants();
            }
        }
    }

    protected void onRadioAvailable() {
    }

    /**
     * The contents of the /proc/cmdline file
     */
    private static String getProcCmdLine()
    {
        String cmdline = "";
        FileInputStream is = null;
        try {
            is = new FileInputStream("/proc/cmdline");
            byte [] buffer = new byte[2048];
            int count = is.read(buffer);
            if (count > 0) {
                cmdline = new String(buffer, 0, count);
            }
        } catch (IOException e) {
            Log.d(LOG_TAG, "No /proc/cmdline exception=" + e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                }
            }
        }
        Log.d(LOG_TAG, "/proc/cmdline=" + cmdline);
        return cmdline;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLteOnCdmaMode() {
        return getLteOnCdmaModeStatic();
    }

    /** Kernel command line */
    private static final String sKernelCmdLine = getProcCmdLine();

    /** Pattern for selecting the product type from the kernel command line */
    private static final Pattern sProductTypePattern =
        Pattern.compile("\\sproduct_type\\s*=\\s*(\\w+)");

    /** The ProductType used for LTE on CDMA devices */
    private static final String sLteOnCdmaProductType =
        SystemProperties.get(TelephonyProperties.PROPERTY_LTE_ON_CDMA_PRODUCT_TYPE, "");

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link Phone#LTE_ON_CDMA_TRUE}
     */
    public static int getLteOnCdmaModeStatic() {
        int retVal;
        int curVal;
        String productType = "";

        curVal = SystemProperties.getInt(TelephonyProperties.PROPERTY_LTE_ON_CDMA_DEVICE,
                    Phone.LTE_ON_CDMA_UNKNOWN);
        retVal = curVal;
        if (retVal == Phone.LTE_ON_CDMA_UNKNOWN) {
            Matcher matcher = sProductTypePattern.matcher(sKernelCmdLine);
            if (matcher.find()) {
                productType = matcher.group(1);
                if (sLteOnCdmaProductType.equals(productType)) {
                    retVal = Phone.LTE_ON_CDMA_TRUE;
                } else {
                    retVal = Phone.LTE_ON_CDMA_FALSE;
                }
            } else {
                retVal = Phone.LTE_ON_CDMA_FALSE;
            }
        }

        Log.d(LOG_TAG, "getLteOnCdmaMode=" + retVal + " curVal=" + curVal +
                " product_type='" + productType +
                "' lteOnCdmaProductType='" + sLteOnCdmaProductType + "'");
        return retVal;
    }
}
