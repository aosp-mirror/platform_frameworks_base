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

import static android.Manifest.permission.READ_PHONE_STATE;
import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Power;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.RegistrantList;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.CommandsInterface.RadioState;
import com.android.internal.telephony.gsm.SIMRecords;

import android.os.SystemProperties;

import com.android.internal.R;

/**
 * {@hide}
 */
public abstract class IccCard {
    protected String mLogTag;
    protected boolean mDbg;

    private IccCardStatus mIccCardStatus = null;
    protected State mState = null;
    protected PhoneBase mPhone;
    private RegistrantList mAbsentRegistrants = new RegistrantList();
    private RegistrantList mPinLockedRegistrants = new RegistrantList();
    private RegistrantList mNetworkLockedRegistrants = new RegistrantList();

    private boolean mDesiredPinLocked;
    private boolean mDesiredFdnEnabled;
    private boolean mIccPinLocked = true; // Default to locked
    private boolean mIccFdnEnabled = false; // Default to disabled.
                                            // Will be updated when SIM_READY.


    /* The extra data for broacasting intent INTENT_ICC_STATE_CHANGE */
    static public final String INTENT_KEY_ICC_STATE = "ss";
    /* NOT_READY means the ICC interface is not ready (eg, radio is off or powering on) */
    static public final String INTENT_VALUE_ICC_NOT_READY = "NOT_READY";
    /* ABSENT means ICC is missing */
    static public final String INTENT_VALUE_ICC_ABSENT = "ABSENT";
    /* LOCKED means ICC is locked by pin or by network */
    static public final String INTENT_VALUE_ICC_LOCKED = "LOCKED";
    /* READY means ICC is ready to access */
    static public final String INTENT_VALUE_ICC_READY = "READY";
    /* IMSI means ICC IMSI is ready in property */
    static public final String INTENT_VALUE_ICC_IMSI = "IMSI";
    /* LOADED means all ICC records, including IMSI, are loaded */
    static public final String INTENT_VALUE_ICC_LOADED = "LOADED";
    /* The extra data for broacasting intent INTENT_ICC_STATE_CHANGE */
    static public final String INTENT_KEY_LOCKED_REASON = "reason";
    /* PIN means ICC is locked on PIN1 */
    static public final String INTENT_VALUE_LOCKED_ON_PIN = "PIN";
    /* PUK means ICC is locked on PUK1 */
    static public final String INTENT_VALUE_LOCKED_ON_PUK = "PUK";
    /* NETWORK means ICC is locked on NETWORK PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_NETWORK = "NETWORK";
    /* PERM_DISABLED means ICC is permanently disabled due to puk fails */
    static public final String INTENT_VALUE_ABSENT_ON_PERM_DISABLED = "PERM_DISABLED";


    protected static final int EVENT_ICC_LOCKED_OR_ABSENT = 1;
    private static final int EVENT_GET_ICC_STATUS_DONE = 2;
    protected static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 3;
    private static final int EVENT_PINPUK_DONE = 4;
    private static final int EVENT_REPOLL_STATUS_DONE = 5;
    protected static final int EVENT_ICC_READY = 6;
    private static final int EVENT_QUERY_FACILITY_LOCK_DONE = 7;
    private static final int EVENT_CHANGE_FACILITY_LOCK_DONE = 8;
    private static final int EVENT_CHANGE_ICC_PASSWORD_DONE = 9;
    private static final int EVENT_QUERY_FACILITY_FDN_DONE = 10;
    private static final int EVENT_CHANGE_FACILITY_FDN_DONE = 11;
    private static final int EVENT_ICC_STATUS_CHANGED = 12;
    private static final int EVENT_CARD_REMOVED = 13;
    private static final int EVENT_CARD_ADDED = 14;

    /*
      UNKNOWN is a transient state, for example, after uesr inputs ICC pin under
      PIN_REQUIRED state, the query for ICC status returns UNKNOWN before it
      turns to READY
     */
    public enum State {
        UNKNOWN,
        ABSENT,
        PIN_REQUIRED,
        PUK_REQUIRED,
        NETWORK_LOCKED,
        READY,
        NOT_READY,
        PERM_DISABLED;

        public boolean isPinLocked() {
            return ((this == PIN_REQUIRED) || (this == PUK_REQUIRED));
        }

        public boolean iccCardExist() {
            return ((this == PIN_REQUIRED) || (this == PUK_REQUIRED)
                    || (this == NETWORK_LOCKED) || (this == READY)
                    || (this == PERM_DISABLED));
        }
    }

    public State getState() {
        if (mState == null) {
            switch(mPhone.mCM.getRadioState()) {
                /* This switch block must not return anything in
                 * State.isLocked() or State.ABSENT.
                 * If it does, handleSimStatus() may break
                 */
                case RADIO_OFF:
                case RADIO_UNAVAILABLE:
                case SIM_NOT_READY:
                case RUIM_NOT_READY:
                    return State.UNKNOWN;
                case SIM_LOCKED_OR_ABSENT:
                case RUIM_LOCKED_OR_ABSENT:
                    //this should be transient-only
                    return State.UNKNOWN;
                case SIM_READY:
                case RUIM_READY:
                case NV_READY:
                    return State.READY;
                case NV_NOT_READY:
                    return State.ABSENT;
            }
        } else {
            return mState;
        }

        Log.e(mLogTag, "IccCard.getState(): case should never be reached");
        return State.UNKNOWN;
    }

    public IccCard(PhoneBase phone, String logTag, Boolean dbg) {
        mPhone = phone;
        mPhone.mCM.registerForIccStatusChanged(mHandler, EVENT_ICC_STATUS_CHANGED, null);
        mLogTag = logTag;
        mDbg = dbg;
    }

    public void dispose() {
        mPhone.mCM.unregisterForIccStatusChanged(mHandler);
    }

    protected void finalize() {
        if(mDbg) Log.d(mLogTag, "IccCard finalized");
    }

    /**
     * Notifies handler of any transition into State.ABSENT
     */
    public void registerForAbsent(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mAbsentRegistrants.add(r);

        if (getState() == State.ABSENT) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForAbsent(Handler h) {
        mAbsentRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.NETWORK_LOCKED
     */
    public void registerForNetworkLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mNetworkLockedRegistrants.add(r);

        if (getState() == State.NETWORK_LOCKED) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForNetworkLocked(Handler h) {
        mNetworkLockedRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.isPinLocked()
     */
    public void registerForLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mPinLockedRegistrants.add(r);

        if (getState().isPinLocked()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForLocked(Handler h) {
        mPinLockedRegistrants.remove(h);
    }


    /**
     * Supply the ICC PIN to the ICC
     *
     * When the operation is complete, onComplete will be sent to its
     * Handler.
     *
     * onComplete.obj will be an AsyncResult
     *
     * ((AsyncResult)onComplete.obj).exception == null on success
     * ((AsyncResult)onComplete.obj).exception != null on fail
     *
     * If the supplied PIN is incorrect:
     * ((AsyncResult)onComplete.obj).exception != null
     * && ((AsyncResult)onComplete.obj).exception
     *       instanceof com.android.internal.telephony.gsm.CommandException)
     * && ((CommandException)(((AsyncResult)onComplete.obj).exception))
     *          .getCommandError() == CommandException.Error.PASSWORD_INCORRECT
     *
     *
     */

    public void supplyPin (String pin, Message onComplete) {
        mPhone.mCM.supplyIccPin(pin, mHandler.obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }

    public void supplyPuk (String puk, String newPin, Message onComplete) {
        mPhone.mCM.supplyIccPuk(puk, newPin,
                mHandler.obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }

    public void supplyPin2 (String pin2, Message onComplete) {
        mPhone.mCM.supplyIccPin2(pin2,
                mHandler.obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }

    public void supplyPuk2 (String puk2, String newPin2, Message onComplete) {
        mPhone.mCM.supplyIccPuk2(puk2, newPin2,
                mHandler.obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }

    public void supplyNetworkDepersonalization (String pin, Message onComplete) {
        mPhone.mCM.supplyNetworkDepersonalization(pin,
                mHandler.obtainMessage(EVENT_PINPUK_DONE, onComplete));
    }

    /**
     * Check whether ICC pin lock is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC locked enabled
     *         false for ICC locked disabled
     */
    public boolean getIccLockEnabled() {
        return mIccPinLocked;
     }

    /**
     * Check whether ICC fdn (fixed dialing number) is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC fdn enabled
     *         false for ICC fdn disabled
     */
     public boolean getIccFdnEnabled() {
        return mIccFdnEnabled;
     }

     /**
      * Set the ICC pin lock enabled or disabled
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param enabled "true" for locked "false" for unlocked.
      * @param password needed to change the ICC pin state, aka. Pin1
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void setIccLockEnabled (boolean enabled,
             String password, Message onComplete) {
         int serviceClassX;
         serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                 CommandsInterface.SERVICE_CLASS_DATA +
                 CommandsInterface.SERVICE_CLASS_FAX;

         mDesiredPinLocked = enabled;

         mPhone.mCM.setFacilityLock(CommandsInterface.CB_FACILITY_BA_SIM,
                 enabled, password, serviceClassX,
                 mHandler.obtainMessage(EVENT_CHANGE_FACILITY_LOCK_DONE, onComplete));
     }

     /**
      * Set the ICC fdn enabled or disabled
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param enabled "true" for locked "false" for unlocked.
      * @param password needed to change the ICC fdn enable, aka Pin2
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void setIccFdnEnabled (boolean enabled,
             String password, Message onComplete) {
         int serviceClassX;
         serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                 CommandsInterface.SERVICE_CLASS_DATA +
                 CommandsInterface.SERVICE_CLASS_FAX +
                 CommandsInterface.SERVICE_CLASS_SMS;

         mDesiredFdnEnabled = enabled;

         mPhone.mCM.setFacilityLock(CommandsInterface.CB_FACILITY_BA_FD,
                 enabled, password, serviceClassX,
                 mHandler.obtainMessage(EVENT_CHANGE_FACILITY_FDN_DONE, onComplete));
     }

     /**
      * Change the ICC password used in ICC pin lock
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param oldPassword is the old password
      * @param newPassword is the new password
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void changeIccLockPassword(String oldPassword, String newPassword,
             Message onComplete) {
         mPhone.mCM.changeIccPin(oldPassword, newPassword,
                 mHandler.obtainMessage(EVENT_CHANGE_ICC_PASSWORD_DONE, onComplete));

     }

     /**
      * Change the ICC password used in ICC fdn enable
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param oldPassword is the old password
      * @param newPassword is the new password
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void changeIccFdnPassword(String oldPassword, String newPassword,
             Message onComplete) {
         mPhone.mCM.changeIccPin2(oldPassword, newPassword,
                 mHandler.obtainMessage(EVENT_CHANGE_ICC_PASSWORD_DONE, onComplete));

     }


    /**
     * Returns service provider name stored in ICC card.
     * If there is no service provider name associated or the record is not
     * yet available, null will be returned <p>
     *
     * Please use this value when display Service Provider Name in idle mode <p>
     *
     * Usage of this provider name in the UI is a common carrier requirement.
     *
     * Also available via Android property "gsm.sim.operator.alpha"
     *
     * @return Service Provider Name stored in ICC card
     *         null if no service provider name associated or the record is not
     *         yet available
     *
     */
    public abstract String getServiceProviderName();

    protected void updateStateProperty() {
        mPhone.setSystemProperty(TelephonyProperties.PROPERTY_SIM_STATE, getState().toString());
    }

    private void getIccCardStatusDone(AsyncResult ar) {
        if (ar.exception != null) {
            Log.e(mLogTag,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }
        handleIccCardStatus((IccCardStatus) ar.result);
    }

    private void handleIccCardStatus(IccCardStatus newCardStatus) {
        boolean transitionedIntoPinLocked;
        boolean transitionedIntoAbsent;
        boolean transitionedIntoNetworkLocked;
        boolean transitionedIntoPermBlocked;
        boolean isIccCardRemoved;
        boolean isIccCardAdded;

        State oldState, newState;

        oldState = mState;
        mIccCardStatus = newCardStatus;
        newState = getIccCardState();
        mState = newState;

        updateStateProperty();

        transitionedIntoPinLocked = (
                 (oldState != State.PIN_REQUIRED && newState == State.PIN_REQUIRED)
              || (oldState != State.PUK_REQUIRED && newState == State.PUK_REQUIRED));
        transitionedIntoAbsent = (oldState != State.ABSENT && newState == State.ABSENT);
        transitionedIntoNetworkLocked = (oldState != State.NETWORK_LOCKED
                && newState == State.NETWORK_LOCKED);
        transitionedIntoPermBlocked = (oldState != State.PERM_DISABLED
                && newState == State.PERM_DISABLED);
        isIccCardRemoved = (oldState != null &&
                        oldState.iccCardExist() && newState == State.ABSENT);
        isIccCardAdded = (oldState == State.ABSENT &&
                        newState != null && newState.iccCardExist());

        if (transitionedIntoPinLocked) {
            if (mDbg) log("Notify SIM pin or puk locked.");
            mPinLockedRegistrants.notifyRegistrants();
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                    (newState == State.PIN_REQUIRED) ?
                       INTENT_VALUE_LOCKED_ON_PIN : INTENT_VALUE_LOCKED_ON_PUK);
        } else if (transitionedIntoAbsent) {
            if (mDbg) log("Notify SIM missing.");
            mAbsentRegistrants.notifyRegistrants();
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_ABSENT, null);
        } else if (transitionedIntoNetworkLocked) {
            if (mDbg) log("Notify SIM network locked.");
            mNetworkLockedRegistrants.notifyRegistrants();
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_NETWORK);
        } else if (transitionedIntoPermBlocked) {
            if (mDbg) log("Notify SIM permanently disabled.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_ABSENT,
                    INTENT_VALUE_ABSENT_ON_PERM_DISABLED);
        }

        if (isIccCardRemoved) {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_CARD_REMOVED, null));
        } else if (isIccCardAdded) {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_CARD_ADDED, null));
        }
    }

    private void onIccSwap(boolean isAdded) {
        // TODO: Here we assume the device can't handle SIM hot-swap
        //      and has to reboot. We may want to add a property,
        //      e.g. REBOOT_ON_SIM_SWAP, to indicate if modem support
        //      hot-swap.
        DialogInterface.OnClickListener listener = null;


        // TODO: SimRecords is not reset while SIM ABSENT (only reset while
        //       Radio_off_or_not_available). Have to reset in both both
        //       added or removed situation.
        listener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    if (mDbg) log("Reboot due to SIM swap");
                    PowerManager pm = (PowerManager) mPhone.getContext()
                    .getSystemService(Context.POWER_SERVICE);
                    pm.reboot("SIM is added.");
                }
            }

        };

        Resources r = Resources.getSystem();

        String title = (isAdded) ? r.getString(R.string.sim_added_title) :
            r.getString(R.string.sim_removed_title);
        String message = (isAdded) ? r.getString(R.string.sim_added_message) :
            r.getString(R.string.sim_removed_message);
        String buttonTxt = r.getString(R.string.sim_restart_button);

        AlertDialog dialog = new AlertDialog.Builder(mPhone.getContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(buttonTxt, listener)
            .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    /**
     * Interperate EVENT_QUERY_FACILITY_LOCK_DONE
     * @param ar is asyncResult of Query_Facility_Locked
     */
    private void onQueryFdnEnabled(AsyncResult ar) {
        if(ar.exception != null) {
            if(mDbg) log("Error in querying facility lock:" + ar.exception);
            return;
        }

        int[] ints = (int[])ar.result;
        if(ints.length != 0) {
            mIccFdnEnabled = (0!=ints[0]);
            if(mDbg) log("Query facility lock : "  + mIccFdnEnabled);
        } else {
            Log.e(mLogTag, "[IccCard] Bogus facility lock response");
        }
    }

    /**
     * Interperate EVENT_QUERY_FACILITY_LOCK_DONE
     * @param ar is asyncResult of Query_Facility_Locked
     */
    private void onQueryFacilityLock(AsyncResult ar) {
        if(ar.exception != null) {
            if (mDbg) log("Error in querying facility lock:" + ar.exception);
            return;
        }

        int[] ints = (int[])ar.result;
        if(ints.length != 0) {
            mIccPinLocked = (0!=ints[0]);
            if(mDbg) log("Query facility lock : "  + mIccPinLocked);
        } else {
            Log.e(mLogTag, "[IccCard] Bogus facility lock response");
        }
    }

    public void broadcastIccStateChangedIntent(String value, String reason) {
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(Phone.PHONE_NAME_KEY, mPhone.getPhoneName());
        intent.putExtra(INTENT_KEY_ICC_STATE, value);
        intent.putExtra(INTENT_KEY_LOCKED_REASON, reason);
        if(mDbg) log("Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
                + " reason " + reason);
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE);
    }

    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg){
            AsyncResult ar;
            int serviceClassX;

            serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                            CommandsInterface.SERVICE_CLASS_DATA +
                            CommandsInterface.SERVICE_CLASS_FAX;

            if (!mPhone.mIsTheCurrentActivePhone) {
                Log.e(mLogTag, "Received message " + msg + "[" + msg.what
                        + "] while being destroyed. Ignoring.");
                return;
            }

            switch (msg.what) {
                case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                    mState = null;
                    updateStateProperty();
                    broadcastIccStateChangedIntent(INTENT_VALUE_ICC_NOT_READY, null);
                    break;
                case EVENT_ICC_READY:
                    //TODO: put facility read in SIM_READY now, maybe in REG_NW
                    mPhone.mCM.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                    mPhone.mCM.queryFacilityLock (
                            CommandsInterface.CB_FACILITY_BA_SIM, "", serviceClassX,
                            obtainMessage(EVENT_QUERY_FACILITY_LOCK_DONE));
                    mPhone.mCM.queryFacilityLock (
                            CommandsInterface.CB_FACILITY_BA_FD, "", serviceClassX,
                            obtainMessage(EVENT_QUERY_FACILITY_FDN_DONE));
                    break;
                case EVENT_ICC_LOCKED_OR_ABSENT:
                    mPhone.mCM.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                    mPhone.mCM.queryFacilityLock (
                            CommandsInterface.CB_FACILITY_BA_SIM, "", serviceClassX,
                            obtainMessage(EVENT_QUERY_FACILITY_LOCK_DONE));
                    break;
                case EVENT_GET_ICC_STATUS_DONE:
                    ar = (AsyncResult)msg.obj;

                    getIccCardStatusDone(ar);
                    break;
                case EVENT_PINPUK_DONE:
                    // a PIN/PUK/PIN2/PUK2/Network Personalization
                    // request has completed. ar.userObj is the response Message
                    // Repoll before returning
                    ar = (AsyncResult)msg.obj;
                    // TODO should abstract these exceptions
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    mPhone.mCM.getIccCardStatus(
                        obtainMessage(EVENT_REPOLL_STATUS_DONE, ar.userObj));
                    break;
                case EVENT_REPOLL_STATUS_DONE:
                    // Finished repolling status after PIN operation
                    // ar.userObj is the response messaeg
                    // ar.userObj.obj is already an AsyncResult with an
                    // appropriate exception filled in if applicable

                    ar = (AsyncResult)msg.obj;
                    getIccCardStatusDone(ar);
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
                        mIccPinLocked = mDesiredPinLocked;
                        if (mDbg) log( "EVENT_CHANGE_FACILITY_LOCK_DONE: " +
                                "mIccPinLocked= " + mIccPinLocked);
                    } else {
                        Log.e(mLogTag, "Error change facility lock with exception "
                            + ar.exception);
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_CHANGE_FACILITY_FDN_DONE:
                    ar = (AsyncResult)msg.obj;

                    if (ar.exception == null) {
                        mIccFdnEnabled = mDesiredFdnEnabled;
                        if (mDbg) log("EVENT_CHANGE_FACILITY_FDN_DONE: " +
                                "mIccFdnEnabled=" + mIccFdnEnabled);
                    } else {
                        Log.e(mLogTag, "Error change facility fdn with exception "
                                + ar.exception);
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_CHANGE_ICC_PASSWORD_DONE:
                    ar = (AsyncResult)msg.obj;
                    if(ar.exception != null) {
                        Log.e(mLogTag, "Error in change sim password with exception"
                            + ar.exception);
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_ICC_STATUS_CHANGED:
                    Log.d(mLogTag, "Received Event EVENT_ICC_STATUS_CHANGED");
                    mPhone.mCM.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                    break;
                case EVENT_CARD_REMOVED:
                    onIccSwap(false);
                    break;
                case EVENT_CARD_ADDED:
                    onIccSwap(true);
                    break;
                default:
                    Log.e(mLogTag, "[IccCard] Unknown Event " + msg.what);
            }
        }
    };

    public State getIccCardState() {
        if (mIccCardStatus == null) {
            Log.e(mLogTag, "[IccCard] IccCardStatus is null");
            return IccCard.State.ABSENT;
        }

        // this is common for all radio technologies
        if (!mIccCardStatus.getCardState().isCardPresent()) {
            return IccCard.State.ABSENT;
        }

        RadioState currentRadioState = mPhone.mCM.getRadioState();
        // check radio technology
        if( currentRadioState == RadioState.RADIO_OFF         ||
            currentRadioState == RadioState.RADIO_UNAVAILABLE ||
            currentRadioState == RadioState.SIM_NOT_READY     ||
            currentRadioState == RadioState.RUIM_NOT_READY    ||
            currentRadioState == RadioState.NV_NOT_READY      ||
            currentRadioState == RadioState.NV_READY) {
            return IccCard.State.NOT_READY;
        }

        if( currentRadioState == RadioState.SIM_LOCKED_OR_ABSENT  ||
            currentRadioState == RadioState.SIM_READY             ||
            currentRadioState == RadioState.RUIM_LOCKED_OR_ABSENT ||
            currentRadioState == RadioState.RUIM_READY) {

            State csimState =
                getAppState(mIccCardStatus.getCdmaSubscriptionAppIndex());
            State usimState =
                getAppState(mIccCardStatus.getGsmUmtsSubscriptionAppIndex());

            if(mDbg) log("USIM=" + usimState + " CSIM=" + csimState);

            if (mPhone.getLteOnCdmaMode() == Phone.LTE_ON_CDMA_TRUE) {
                // UICC card contains both USIM and CSIM
                // Return consolidated status
                return getConsolidatedState(csimState, usimState, csimState);
            }

            // check for CDMA radio technology
            if (currentRadioState == RadioState.RUIM_LOCKED_OR_ABSENT ||
                currentRadioState == RadioState.RUIM_READY) {
                return csimState;
            }
            return usimState;
        }

        return IccCard.State.ABSENT;
    }

    private State getAppState(int appIndex) {
        IccCardApplication app;
        if (appIndex >= 0 && appIndex < IccCardStatus.CARD_MAX_APPS) {
            app = mIccCardStatus.getApplication(appIndex);
        } else {
            Log.e(mLogTag, "[IccCard] Invalid Subscription Application index:" + appIndex);
            return IccCard.State.ABSENT;
        }

        if (app == null) {
            Log.e(mLogTag, "[IccCard] Subscription Application in not present");
            return IccCard.State.ABSENT;
        }

        // check if PIN required
        if (app.pin1.isPermBlocked()) {
            return IccCard.State.PERM_DISABLED;
        }
        if (app.app_state.isPinRequired()) {
            return IccCard.State.PIN_REQUIRED;
        }
        if (app.app_state.isPukRequired()) {
            return IccCard.State.PUK_REQUIRED;
        }
        if (app.app_state.isSubscriptionPersoEnabled()) {
            return IccCard.State.NETWORK_LOCKED;
        }
        if (app.app_state.isAppReady()) {
            return IccCard.State.READY;
        }
        if (app.app_state.isAppNotReady()) {
            return IccCard.State.NOT_READY;
        }
        return IccCard.State.NOT_READY;
    }

    private State getConsolidatedState(State left, State right, State preferredState) {
        // Check if either is absent.
        if (right == IccCard.State.ABSENT) return left;
        if (left == IccCard.State.ABSENT) return right;

        // Only if both are ready, return ready
        if ((left == IccCard.State.READY) && (right == IccCard.State.READY)) {
            return State.READY;
        }

        // Case one is ready, but the other is not.
        if (((right == IccCard.State.NOT_READY) && (left == IccCard.State.READY)) ||
            ((left == IccCard.State.NOT_READY) && (right == IccCard.State.READY))) {
            return IccCard.State.NOT_READY;
        }

        // At this point, the other state is assumed to be one of locked state
        if (right == IccCard.State.NOT_READY) return left;
        if (left == IccCard.State.NOT_READY) return right;

        // At this point, FW currently just assumes the status will be
        // consistent across the applications...
        return preferredState;
    }

    public boolean isApplicationOnIcc(IccCardApplication.AppType type) {
        if (mIccCardStatus == null) return false;

        for (int i = 0 ; i < mIccCardStatus.getNumApplications(); i++) {
            IccCardApplication app = mIccCardStatus.getApplication(i);
            if (app != null && app.app_type == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        if (mIccCardStatus == null) {
            return false;
        } else {
            // Returns ICC card status for both GSM and CDMA mode
            return mIccCardStatus.getCardState().isCardPresent();
        }
    }

    private void log(String msg) {
        Log.d(mLogTag, "[IccCard] " + msg);
    }
}
