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

package com.android.internal.telephony.gsm;

import android.content.Context;
import android.os.RegistrantList;
import android.os.Registrant;
import android.os.Handler;
import android.os.AsyncResult;
import android.os.SystemProperties;
import android.provider.Checkin;
import android.util.Config;
import android.util.Log;

/**
 * {@hide}
 */
public abstract class BaseCommands implements CommandsInterface
{
    static final String LOG_TAG = "GSM";

    //***** Instance Variables
    protected Context mContext;
    protected RadioState mState = RadioState.RADIO_UNAVAILABLE;
    protected Object mStateMonitor = new Object();

    protected RegistrantList mRadioStateChangedRegistrants = new RegistrantList();
    protected RegistrantList mOnRegistrants = new RegistrantList();
    protected RegistrantList mAvailRegistrants = new RegistrantList();
    protected RegistrantList mOffOrNotAvailRegistrants = new RegistrantList();
    protected RegistrantList mNotAvailRegistrants = new RegistrantList();
    protected RegistrantList mSIMReadyRegistrants = new RegistrantList();
    protected RegistrantList mSIMLockedRegistrants = new RegistrantList();
    protected RegistrantList mCallStateRegistrants = new RegistrantList();
    protected RegistrantList mNetworkStateRegistrants = new RegistrantList();
    protected RegistrantList mPDPRegistrants = new RegistrantList();
    protected Registrant mSMSRegistrant;
    protected Registrant mNITZTimeRegistrant;
    protected Registrant mSignalStrengthRegistrant;
    protected Registrant mUSSDRegistrant;
    protected Registrant mSmsOnSimRegistrant;
    /** Registrant for handling SMS Status Reports */
    protected Registrant mSmsStatusRegistrant;
    /** Registrant for handling Supplementary Service Notifications */
    protected Registrant mSsnRegistrant;
    protected Registrant mStkSessionEndRegistrant;
    protected Registrant mStkProCmdRegistrant;
    protected Registrant mStkEventRegistrant;
    protected Registrant mStkCallSetUpRegistrant;
    /** Registrant for handling SIM SMS storage full messages */
    protected Registrant mSimSmsFullRegistrant;
    /** Registrant for handling SIM Refresh notifications */
    protected Registrant mSimRefreshRegistrant;
    /** Registrant for handling RING notifications */
    protected Registrant mRingRegistrant;
    /** Registrant for handling RESTRICTED STATE changed notification */
    protected Registrant mRestrictedStateRegistrant;

    public BaseCommands(Context context) {
        mContext = context;  // May be null (if so we won't log statistics)
    }

    //***** CommandsInterface implementation

    public RadioState 
    getRadioState()
    {
        return mState;
    }


    public void 
    registerForRadioStateChanged(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mRadioStateChangedRegistrants.add(r);
            r.notifyRegistrant();
        }
    }

    public void 
    registerForOn(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mOnRegistrants.add(r);

            if (mState.isOn()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }
    

    public void 
    registerForAvailable(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mAvailRegistrants.add(r);

            if (mState.isAvailable()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void 
    registerForNotAvailable(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mNotAvailRegistrants.add(r);

            if (!mState.isAvailable()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void 
    registerForOffOrNotAvailable(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mOffOrNotAvailRegistrants.add(r);

            if (mState == RadioState.RADIO_OFF || !mState.isAvailable()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }


    /** Any transition into SIM_READY */
    public void 
    registerForSIMReady(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mSIMReadyRegistrants.add(r);

            if (mState.isSIMReady()) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void 
    registerForSIMLockedOrAbsent(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        synchronized (mStateMonitor) {
            mSIMLockedRegistrants.add(r);

            if (mState == RadioState.SIM_LOCKED_OR_ABSENT) {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    public void 
    registerForCallStateChanged(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        mCallStateRegistrants.add(r);
    }

    public void 
    registerForNetworkStateChanged(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        mNetworkStateRegistrants.add(r);
    }

    public void
    registerForPDPStateChanged(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        mPDPRegistrants.add(r);
    }

    public void 
    setOnNewSMS(Handler h, int what, Object obj)
    {
        mSMSRegistrant = new Registrant (h, what, obj);
    }

    public void
    setOnSmsOnSim(Handler h, int what, Object obj)
    {
        mSmsOnSimRegistrant = new Registrant (h, what, obj);
    }
    
    public void setOnSmsStatus(Handler h, int what, Object obj) {
        mSmsStatusRegistrant = new Registrant (h, what, obj);
    }

    public void
    setOnSignalStrengthUpdate(Handler h, int what, Object obj)
    {
        mSignalStrengthRegistrant = new Registrant (h, what, obj);
    }

    public void 
    setOnNITZTime(Handler h, int what, Object obj)
    {
        mNITZTimeRegistrant = new Registrant (h, what, obj);
    }
  
    public void 
    setOnUSSD(Handler h, int what, Object obj)
    {
        mUSSDRegistrant = new Registrant (h, what, obj);
    }

    public void
    setOnSuppServiceNotification(Handler h, int what, Object obj)
    {
        mSsnRegistrant = new Registrant (h, what, obj);
    }

    public void
    setOnStkSessionEnd(Handler h, int what, Object obj)
    {
        mStkSessionEndRegistrant = new Registrant (h, what, obj);
    }

    public void
    setOnStkProactiveCmd(Handler h, int what, Object obj)
    {
        mStkProCmdRegistrant = new Registrant (h, what, obj);
    }

    public void
    setOnStkEvent(Handler h, int what, Object obj)
    {
        mStkEventRegistrant = new Registrant (h, what, obj);
    }

    public void
    setOnStkCallSetUp(Handler h, int what, Object obj)
    {
        mStkCallSetUpRegistrant = new Registrant (h, what, obj);
    }

    public void setOnSimSmsFull(Handler h, int what, Object obj) {
        mSimSmsFullRegistrant = new Registrant (h, what, obj);
    }

    public void setOnSimRefresh(Handler h, int what, Object obj) {
        mSimRefreshRegistrant = new Registrant (h, what, obj);
    }
    
    public void setOnCallRing(Handler h, int what, Object obj) {
        mRingRegistrant = new Registrant (h, what, obj);
    }
    
    public void
    setOnRestrictedStateChanged(Handler h, int what, Object obj)
    {
        mRestrictedStateRegistrant = new Registrant (h, what, obj);
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
            if (Config.LOGV) {
                Log.v(LOG_TAG, "setRadioState old: " + mState
                    + " new " + newState);
            }
        
            oldState = mState;
            mState = newState;
            
            if (oldState == mState) {
                // no state transition
                return;
            }

            if (mContext != null &&
                newState == RadioState.RADIO_UNAVAILABLE &&
                oldState != RadioState.RADIO_OFF) {
                Checkin.updateStats(mContext.getContentResolver(),
                        Checkin.Stats.Tag.PHONE_RADIO_RESETS, 1, 0.0);
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
        }
    }
    
    protected void
    onRadioAvailable()
    {
    }
}
