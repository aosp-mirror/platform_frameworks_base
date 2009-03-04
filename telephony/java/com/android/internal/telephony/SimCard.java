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

import android.os.Message;
import android.os.Handler;

/**
 * {@hide}
 */
public interface SimCard
{
    /* The extra data for broacasting intent INTENT_SIM_STATE_CHANGE */
    static public final String INTENT_KEY_SIM_STATE = "ss";
    /* NOT_READY means the SIM interface is not ready (eg, radio is off or powering on) */
    static public final String INTENT_VALUE_SIM_NOT_READY = "NOT_READY";
    /* ABSENT means SIM is missing */
    static public final String INTENT_VALUE_SIM_ABSENT = "ABSENT";
    /* LOCKED means SIM is locked by pin or by network */
    static public final String INTENT_VALUE_SIM_LOCKED = "LOCKED";
    /* READY means SIM is ready to access */
    static public final String INTENT_VALUE_SIM_READY = "READY";
    /* IMSI means SIM IMSI is ready in property */
    static public final String INTENT_VALUE_SIM_IMSI = "IMSI";
    /* LOADED means all SIM records, including IMSI, are loaded */
    static public final String INTENT_VALUE_SIM_LOADED = "LOADED";
    /* The extra data for broacasting intent INTENT_SIM_STATE_CHANGE */
    static public final String INTENT_KEY_LOCKED_REASON = "reason";
    /* PIN means SIM is locked on PIN1 */
    static public final String INTENT_VALUE_LOCKED_ON_PIN = "PIN";
    /* PUK means SIM is locked on PUK1 */
    static public final String INTENT_VALUE_LOCKED_ON_PUK = "PUK";
    /* NETWORK means SIM is locked on NETWORK PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_NETWORK = "NETWORK";


    /*
      UNKNOWN is a transient state, for example, after uesr inputs sim pin under
      PIN_REQUIRED state, the query for sim status returns UNKNOWN before it
      turns to READY
     */
    public enum State {
        UNKNOWN,
        ABSENT,
        PIN_REQUIRED,
        PUK_REQUIRED,
        NETWORK_LOCKED,
        READY;

        public boolean isPinLocked() {
            return ((this == PIN_REQUIRED) || (this == PUK_REQUIRED));
        }
    }

    State getState();


    /**
     * Notifies handler of any transition into State.ABSENT
     */
    void registerForAbsent(Handler h, int what, Object obj);
    void unregisterForAbsent(Handler h);    

    /**
     * Notifies handler of any transition into State.isPinLocked()
     */
    void registerForLocked(Handler h, int what, Object obj);
    void unregisterForLocked(Handler h);

    /**
     * Notifies handler of any transition into State.NETWORK_LOCKED
     */
    void registerForNetworkLocked(Handler h, int what, Object obj);
    void unregisterForNetworkLocked(Handler h);

    /**
     * Supply the SIM PIN to the SIM
     *
     * When the operation is complete, onComplete will be sent to it's
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

    void supplyPin (String pin, Message onComplete);
    void supplyPuk (String puk, String newPin, Message onComplete);
    void supplyPin2 (String pin2, Message onComplete);
    void supplyPuk2 (String puk2, String newPin2, Message onComplete);

    /**
     * Check whether sim pin lock is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for sim locked enabled
     *         false for sim locked disabled
     */
    boolean getSimLockEnabled ();

    /**
     * Set the sim pin lock enabled or disabled
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param enabled "true" for locked "false" for unlocked.
     * @param password needed to change the sim pin state, aka. Pin1
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    void setSimLockEnabled(boolean enabled, String password, Message onComplete);


    /**
     * Change the sim password used in sim pin lock
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param oldPassword is the old password
     * @param newPassword is the new password
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    void changeSimLockPassword(String oldPassword, String newPassword,
                           Message onComplete);

    /**
     * Check whether sim fdn (fixed dialing number) is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for sim fdn enabled
     *         false for sim fdn disabled
     */
    boolean getSimFdnEnabled ();

    /**
     * Set the sim fdn enabled or disabled
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param enabled "true" for locked "false" for unlocked.
     * @param password needed to change the sim fdn enable, aka Pin2
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    void setSimFdnEnabled(boolean enabled, String password, Message onComplete);

    /**
     * Change the sim password used in sim fdn enable
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param oldPassword is the old password
     * @param newPassword is the new password
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    void changeSimFdnPassword(String oldPassword, String newPassword,
                           Message onComplete);

    void supplyNetworkDepersonalization (String pin, Message onComplete);

    /**
     * Returns service provider name stored in SIM card.
     * If there is no service provider name associated or the record is not
     * yet available, null will be returned <p>
     *
     * Please use this value when display Service Provider Name in idle mode <p>
     *
     * Usage of this provider name in the UI is a common carrier requirement.
     *
     * Also available via Android property "gsm.sim.operator.alpha"
     *
     * @return Service Provider Name stored in SIM card
     *         null if no service provider name associated or the record is not
     *         yet available
     *
     */
    String getServiceProviderName();
}
