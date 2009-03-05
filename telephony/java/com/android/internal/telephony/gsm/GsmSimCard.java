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

import android.os.AsyncResult;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.util.Log;
import com.android.internal.telephony.SimCard;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import android.content.Intent;
import android.content.res.Configuration;
import android.app.ActivityManagerNative;

import static android.Manifest.permission.READ_PHONE_STATE;

/**
 * {@hide}
 */
public final class GsmSimCard extends Handler implements SimCard {
    static final String LOG_TAG="GSM";
    
    //***** Instance Variables
    private static final boolean DBG = true;

    private GSMPhone phone;
    private CommandsInterface.SimStatus status = null;
    private boolean mSimPinLocked = true; // Default to locked
    private boolean mSimFdnEnabled = false; // Default to disabled.
                                            // Will be updated when SIM_READY.
    private boolean mDesiredPinLocked;
    private boolean mDesiredFdnEnabled;

    //***** Constants

    // FIXME I hope this doesn't conflict with the Dialer's notifications
    static final int NOTIFICATION_ID_SIM_STATUS = 33456;

    //***** Event Constants

    static final int EVENT_SIM_LOCKED_OR_ABSENT = 1;
    static final int EVENT_GET_SIM_STATUS_DONE = 2;
    static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 3;
    static final int EVENT_PINPUK_DONE = 4;
    static final int EVENT_REPOLL_STATUS_DONE = 5;
    static final int EVENT_SIM_READY = 6;
    static final int EVENT_QUERY_FACILITY_LOCK_DONE = 7;
    static final int EVENT_CHANGE_FACILITY_LOCK_DONE = 8;
    static final int EVENT_CHANGE_SIM_PASSWORD_DONE = 9;
    static final int EVENT_QUERY_FACILITY_FDN_DONE = 10;
    static final int EVENT_CHANGE_FACILITY_FDN_DONE = 11;


    //***** Constructor

    GsmSimCard(GSMPhone phone)
    {
        this.phone = phone;

        phone.mCM.registerForSIMLockedOrAbsent(
                        this, EVENT_SIM_LOCKED_OR_ABSENT, null);

        phone.mCM.registerForOffOrNotAvailable(
                        this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);

        phone.mCM.registerForSIMReady(
                        this, EVENT_SIM_READY, null);

        updateStateProperty();
    }
    
    //***** SimCard implementation

    public State
    getState()
    {
        if (status == null) {
            switch(phone.mCM.getRadioState()) {
                /* This switch block must not return anything in
                 * State.isLocked() or State.ABSENT.
                 * If it does, handleSimStatus() may break
                 */
                case RADIO_OFF:
                case RADIO_UNAVAILABLE:
                case SIM_NOT_READY:
                    return State.UNKNOWN;
                case SIM_LOCKED_OR_ABSENT:
                    //this should be transient-only
                    return State.UNKNOWN;
                case SIM_READY:
                    return State.READY;
            }
        } else {
            switch (status) {
                case SIM_ABSENT:            return State.ABSENT;
                case SIM_NOT_READY:         return State.UNKNOWN;
                case SIM_READY:             return State.READY;
                case SIM_PIN:               return State.PIN_REQUIRED;
                case SIM_PUK:               return State.PUK_REQUIRED;
                case SIM_NETWORK_PERSONALIZATION: return State.NETWORK_LOCKED;
            }
        }

        Log.e(LOG_TAG, "GsmSimCard.getState(): case should never be reached");
        return State.UNKNOWN;
    }

    private RegistrantList absentRegistrants = new RegistrantList();
    private RegistrantList pinLockedRegistrants = new RegistrantList();
    private RegistrantList networkLockedRegistrants = new RegistrantList();


    public void registerForAbsent(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        absentRegistrants.add(r);

        if (getState() == State.ABSENT) {
            r.notifyRegistrant();
        }
    }
    
    public void unregisterForAbsent(Handler h) {
    	absentRegistrants.remove(h);
    }

    public void registerForNetworkLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        networkLockedRegistrants.add(r);

        if (getState() == State.NETWORK_LOCKED) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForNetworkLocked(Handler h) {
    	networkLockedRegistrants.remove(h);
    }
    
    public void registerForLocked(Handler h, int what, Object obj)
    {
        Registrant r = new Registrant (h, what, obj);

        pinLockedRegistrants.add(r);

        if (getState().isPinLocked()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForLocked(Handler h)
    {
        pinLockedRegistrants.remove(h);
    }


    public void supplyPin (String pin, Message onComplete)
    {
        phone.mCM.supplySimPin(pin, 
                            obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }

    public void supplyPuk (String puk, String newPin, Message onComplete)
    {
        phone.mCM.supplySimPuk(puk, newPin,
                        obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }
    public void supplyPin2 (String pin2, Message onComplete)
    {
        phone.mCM.supplySimPin2(pin2, 
                        obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }
    public void supplyPuk2 (String puk2, String newPin2, Message onComplete)
    {
        phone.mCM.supplySimPuk2(puk2, newPin2,
                obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }

    public void supplyNetworkDepersonalization (String pin, Message onComplete)
    {
        if(DBG) log("Network Despersonalization: " + pin);
        phone.mCM.supplyNetworkDepersonalization(pin,
                        obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }

    public boolean getSimLockEnabled() {
       return mSimPinLocked;
    }

    public boolean getSimFdnEnabled() {
       return mSimFdnEnabled;
    }

    public void setSimLockEnabled (boolean enabled,
            String password, Message onComplete) {
        int serviceClassX;
        serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                CommandsInterface.SERVICE_CLASS_DATA +
                CommandsInterface.SERVICE_CLASS_FAX;

        mDesiredPinLocked = enabled;

        phone.mCM.setFacilityLock(CommandsInterface.CB_FACILITY_BA_SIM,
                enabled, password, serviceClassX,
                obtainMessage(EVENT_CHANGE_FACILITY_LOCK_DONE, onComplete));
    }

    public void setSimFdnEnabled (boolean enabled,
            String password, Message onComplete) {
        int serviceClassX;
        serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                CommandsInterface.SERVICE_CLASS_DATA +
                CommandsInterface.SERVICE_CLASS_FAX +
                CommandsInterface.SERVICE_CLASS_SMS;

        mDesiredFdnEnabled = enabled;

        phone.mCM.setFacilityLock(CommandsInterface.CB_FACILITY_BA_FD,
                enabled, password, serviceClassX,
                obtainMessage(EVENT_CHANGE_FACILITY_FDN_DONE, onComplete));
    }

    public void changeSimLockPassword(String oldPassword, String newPassword,
            Message onComplete) {
        if(DBG) log("Change Pin1 old: " + oldPassword + " new: " + newPassword);
        phone.mCM.changeSimPin(oldPassword, newPassword,
                obtainMessage(EVENT_CHANGE_SIM_PASSWORD_DONE, onComplete));

    }

    public void changeSimFdnPassword(String oldPassword, String newPassword,
            Message onComplete) {
        if(DBG) log("Change Pin2 old: " + oldPassword + " new: " + newPassword);
        phone.mCM.changeSimPin2(oldPassword, newPassword,
                obtainMessage(EVENT_CHANGE_SIM_PASSWORD_DONE, onComplete));

    }

    public String getServiceProviderName () {
        return phone.mSIMRecords.getServiceProviderName();
    }

    //***** Handler implementation
    @Override
    public void handleMessage(Message msg){
        AsyncResult ar;
        int serviceClassX;

        serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                        CommandsInterface.SERVICE_CLASS_DATA +
                        CommandsInterface.SERVICE_CLASS_FAX;

        switch (msg.what) {
            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                status = null;
                updateStateProperty();
                broadcastSimStateChangedIntent(SimCard.INTENT_VALUE_SIM_NOT_READY, null);
                break;
            case EVENT_SIM_READY:
                //TODO: put facility read in SIM_READY now, maybe in REG_NW
                phone.mCM.getSimStatus(obtainMessage(EVENT_GET_SIM_STATUS_DONE));
                phone.mCM.queryFacilityLock (
                        CommandsInterface.CB_FACILITY_BA_SIM, "", serviceClassX,
                        obtainMessage(EVENT_QUERY_FACILITY_LOCK_DONE));
                phone.mCM.queryFacilityLock (
                        CommandsInterface.CB_FACILITY_BA_FD, "", serviceClassX,
                        obtainMessage(EVENT_QUERY_FACILITY_FDN_DONE));
                break;
            case EVENT_SIM_LOCKED_OR_ABSENT:
                phone.mCM.getSimStatus(obtainMessage(EVENT_GET_SIM_STATUS_DONE));
                phone.mCM.queryFacilityLock (
                        CommandsInterface.CB_FACILITY_BA_SIM, "", serviceClassX,
                        obtainMessage(EVENT_QUERY_FACILITY_LOCK_DONE));
                break;
            case EVENT_GET_SIM_STATUS_DONE:
                ar = (AsyncResult)msg.obj;

                getSimStatusDone(ar);
                break;
            case EVENT_PINPUK_DONE:
                // a PIN/PUK/PIN2/PUK2/Network Personalization
                // request has completed. ar.userObj is the response Message
                // Repoll before returning
                ar = (AsyncResult)msg.obj;
                // TODO should abstract these exceptions
                AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                    = ar.exception;
                phone.mCM.getSimStatus(
                    obtainMessage(EVENT_REPOLL_STATUS_DONE, ar.userObj));
                break;
            case EVENT_REPOLL_STATUS_DONE:
                // Finished repolling status after PIN operation
                // ar.userObj is the response messaeg
                // ar.userObj.obj is already an AsyncResult with an
                // appropriate exception filled in if applicable

                ar = (AsyncResult)msg.obj;
                getSimStatusDone(ar);
                ((Message)ar.userObj).sendToTarget();
                break;
            case EVENT_QUERY_FACILITY_LOCK_DONE:
                ar = (AsyncResult)msg.obj;
                onQueryFacilityLock(ar);
                break;
            case EVENT_QUERY_FACILITY_FDN_DONE:
                ar = (AsyncResult)msg.obj;
                onQueryFdnEnabled(ar);
                break;
            case EVENT_CHANGE_FACILITY_LOCK_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    mSimPinLocked = mDesiredPinLocked;
                    if (DBG) log( "EVENT_CHANGE_FACILITY_LOCK_DONE: " +
                            "mSimPinLocked= " + mSimPinLocked);
                } else {
                    Log.e(LOG_TAG, "Error change facility lock with exception "
                        + ar.exception);
                }
                AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                    = ar.exception;
                ((Message)ar.userObj).sendToTarget();
                break;
            case EVENT_CHANGE_FACILITY_FDN_DONE:
                ar = (AsyncResult)msg.obj;

                if (ar.exception == null) {
                    mSimFdnEnabled = mDesiredFdnEnabled;
                    if (DBG) log("EVENT_CHANGE_FACILITY_FDN_DONE: " +
                            "mSimFdnEnabled=" + mSimFdnEnabled);
                } else {
                    Log.e(LOG_TAG, "Error change facility fdn with exception "
                            + ar.exception);
                }
                AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                    = ar.exception;
                ((Message)ar.userObj).sendToTarget();
                break;
            case EVENT_CHANGE_SIM_PASSWORD_DONE:
                ar = (AsyncResult)msg.obj;
                if(ar.exception != null) {
                    Log.e(LOG_TAG, "Error in change sim password with exception"
                        + ar.exception);
                }
                AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                    = ar.exception;
                ((Message)ar.userObj).sendToTarget();
                break;
            default:
                Log.e(LOG_TAG, "[GsmSimCard] Unknown Event " + msg.what);
        }
    }


    //***** Private methods

    /**
     * Interperate EVENT_QUERY_FACILITY_LOCK_DONE
     * @param ar is asyncResult of Query_Facility_Locked
     */
    private void onQueryFacilityLock(AsyncResult ar) {
        if(ar.exception != null) {
            if (DBG) log("Error in querying facility lock:" + ar.exception);
            return;
        }

        int[] ints = (int[])ar.result;
        if(ints.length != 0) {
            mSimPinLocked = (0!=ints[0]);
            if(DBG) log("Query facility lock : "  + mSimPinLocked);
        } else {
            Log.e(LOG_TAG, "[GsmSimCard] Bogus facility lock response");
        }
    }

    /**
     * Interperate EVENT_QUERY_FACILITY_LOCK_DONE
     * @param ar is asyncResult of Query_Facility_Locked
     */
    private void onQueryFdnEnabled(AsyncResult ar) {
        if(ar.exception != null) {
            if(DBG) log("Error in querying facility lock:" + ar.exception);
            return;
        }

        int[] ints = (int[])ar.result;
        if(ints.length != 0) {
            mSimFdnEnabled = (0!=ints[0]);
            if(DBG) log("Query facility lock : "  + mSimFdnEnabled);
        } else {
            Log.e(LOG_TAG, "[GsmSimCard] Bogus facility lock response");
        }
    }

    private void
    getSimStatusDone(AsyncResult ar) {
        if (ar.exception != null) {
            Log.e(LOG_TAG,"Error getting SIM status. "
                    + "RIL_REQUEST_GET_SIM_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }

        CommandsInterface.SimStatus newStatus 
            = (CommandsInterface.SimStatus)  ar.result;

        handleSimStatus(newStatus);
    }

    private void
    handleSimStatus(CommandsInterface.SimStatus newStatus) {
        boolean transitionedIntoPinLocked;
        boolean transitionedIntoAbsent;
        boolean transitionedIntoNetworkLocked;
        
        SimCard.State oldState, newState;

        oldState = getState();
        status = newStatus;
        newState = getState();

        updateStateProperty();

        transitionedIntoPinLocked = (
                 (oldState != State.PIN_REQUIRED && newState == State.PIN_REQUIRED)
              || (oldState != State.PUK_REQUIRED && newState == State.PUK_REQUIRED));
        transitionedIntoAbsent = (oldState != State.ABSENT && newState == State.ABSENT);
        transitionedIntoNetworkLocked = (oldState != State.NETWORK_LOCKED
                && newState == State.NETWORK_LOCKED);

        if (transitionedIntoPinLocked) {
            if(DBG) log("Notify SIM pin or puk locked.");
            pinLockedRegistrants.notifyRegistrants();
            broadcastSimStateChangedIntent(SimCard.INTENT_VALUE_SIM_LOCKED, 
                    (newState == State.PIN_REQUIRED) ?
                       INTENT_VALUE_LOCKED_ON_PIN : INTENT_VALUE_LOCKED_ON_PUK);
        } else if (transitionedIntoAbsent) {
            if(DBG) log("Notify SIM missing.");
            absentRegistrants.notifyRegistrants();
            broadcastSimStateChangedIntent(SimCard.INTENT_VALUE_SIM_ABSENT, null);
        } else if (transitionedIntoNetworkLocked) {
            if(DBG) log("Notify SIM network locked.");
            networkLockedRegistrants.notifyRegistrants();
            broadcastSimStateChangedIntent(SimCard.INTENT_VALUE_SIM_LOCKED,
                  INTENT_VALUE_LOCKED_NETWORK);
        }
    }

    public void broadcastSimStateChangedIntent(String value, String reason) {
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Phone.PHONE_NAME_KEY, phone.getPhoneName());
        intent.putExtra(SimCard.INTENT_KEY_SIM_STATE, value);
        intent.putExtra(SimCard.INTENT_KEY_LOCKED_REASON, reason);
        if(DBG) log("Broadcasting intent SIM_STATE_CHANGED_ACTION " +  value
                + " reason " + reason);
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE);
    }

    public void updateImsiConfiguration(String imsi) {
        if (imsi.length() >= 6) {
            Configuration config = new Configuration();
            config.mcc = ((imsi.charAt(0)-'0')*100)
                    + ((imsi.charAt(1)-'0')*10)
                    + (imsi.charAt(2)-'0');
            config.mnc = ((imsi.charAt(3)-'0')*100)
                    + ((imsi.charAt(4)-'0')*10)
                    + (imsi.charAt(5)-'0');
            try {
                ActivityManagerNative.getDefault().updateConfiguration(config);
            } catch (RemoteException e) {
            }
        }
    }

    private void
    updateStateProperty() {
        phone.setSystemProperty(
            TelephonyProperties.PROPERTY_SIM_STATE,
            getState().toString());
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[GsmSimCard] " + msg);
    }
}

