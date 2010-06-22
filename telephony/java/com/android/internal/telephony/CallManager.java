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

package com.android.internal.telephony;



import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RegistrantList;
import android.telephony.PhoneStateListener;


import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 *
 * CallManager class provides an abstract layer for PhoneApp to access
 * and control calls. It implements Phone interface.
 *
 * CallManager provides call and connection control as well as
 * channel capability.
 *
 * There are three categories of APIs CallManager provided
 *
 *  1. Call control and operation, such as dial() and hangup()
 *  2. Channel capabilities, such as CanConference()
 *  3. Register notification
 *
 *
 */
public final class CallManager {

    private static final int EVENT_DISCONNECT = 100;
    private static final int EVENT_CALL_STATE_CHANGED = 101;


    // Singleton instance
    private static final CallManager INSTANCE = new CallManager();

    // list of registered phones
    private final ArrayList<Phone> mPhones;

    // list of supported ringing calls
    private final ArrayList<Call> mRingingCalls;

    // list of supported background calls
    private final ArrayList<Call> mBackgroundCalls;

    // list of supported foreground calls
    private final ArrayList<Call> mForegroundCalls;

    // state registrants
    protected final RegistrantList mPreciseCallStateRegistrants
    = new RegistrantList();

    protected final RegistrantList mNewRingingConnectionRegistrants
    = new RegistrantList();

    protected final RegistrantList mIncomingRingRegistrants
    = new RegistrantList();

    protected final RegistrantList mDisconnectRegistrants
    = new RegistrantList();

    protected final RegistrantList mServiceStateRegistrants
    = new RegistrantList();

    protected final RegistrantList mMmiCompleteRegistrants
    = new RegistrantList();

    protected final RegistrantList mMmiRegistrants
    = new RegistrantList();

    protected final RegistrantList mUnknownConnectionRegistrants
    = new RegistrantList();

    protected final RegistrantList mSuppServiceFailedRegistrants
    = new RegistrantList();

    private CallManager() {
        mPhones = new ArrayList<Phone>();
        mRingingCalls = new ArrayList<Call>();
        mBackgroundCalls = new ArrayList<Call>();
        mForegroundCalls = new ArrayList<Call>();
    }

    /**
     * get singleton instance of CallManager
     * @return CallManager
     */
    public static CallManager getInstance() {
        return INSTANCE;
    }

    /**
     * Register phone to CallManager
     * @param phone
     * @return
     */
    public boolean registerPhone(Phone phone) {
        if (phone != null && !mPhones.contains(phone)) {
            mPhones.add(phone);
            mRingingCalls.add(phone.getRingingCall());
            mBackgroundCalls.add(phone.getBackgroundCall());
            mForegroundCalls.add(phone.getForegroundCall());
            registerForPhoneStates(phone);
            return true;
        }
        return false;
    }

    /**
     * unregister phone from CallManager
     * @param phone
     */
    public void unregisterPhone(Phone phone) {
        if (phone != null && !mPhones.contains(phone)) {
            mPhones.remove(phone);
            mRingingCalls.remove(phone.getRingingCall());
            mBackgroundCalls.remove(phone.getBackgroundCall());
            mForegroundCalls.remove(phone.getForegroundCall());
            unregisterForPhoneStates(phone);
        }
    }

    private void registerForPhoneStates(Phone phone) {
        phone.registerForPreciseCallStateChanged(mHandler, EVENT_CALL_STATE_CHANGED, null);
        phone.registerForDisconnect(mHandler, EVENT_DISCONNECT, null);
    }

    private void unregisterForPhoneStates(Phone phone) {
        phone.unregisterForPreciseCallStateChanged(mHandler);
        phone.unregisterForDisconnect(mHandler);
    }

    /**
     * Answers a ringing or waiting call.
     *
     * Active call, if any, go on hold.
     * If active call can't be held, i.e., a background call of the same channel exists,
     * the active call will be hang up.
     *
     * Answering occurs asynchronously, and final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException when call is not ringing or waiting
     */
    public void acceptCall(Call ringingCall) throws CallStateException {
        Phone ringingPhone = ringingCall.getPhone();

        if ( hasActiveFgCall() ) {
            Phone activePhone = getActiveFgCall().getPhone();
            boolean hasBgCall = activePhone.getBackgroundCall().isIdle();
            boolean sameChannel = (activePhone == ringingPhone);

            if (sameChannel && hasBgCall) {
                getActiveFgCall().hangup();
            } else if (!sameChannel && !hasBgCall) {
                activePhone.switchHoldingAndActive();
            } else if (!sameChannel && hasBgCall) {
                getActiveFgCall().hangup();
            }
        }

        ringingPhone.acceptCall();
    }

    /**
     * Reject (ignore) a ringing call. In GSM, this means UDUB
     * (User Determined User Busy). Reject occurs asynchronously,
     * and final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException when no call is ringing or waiting
     */
    public void rejectCall(Call ringingCall) throws CallStateException {
        Phone ringingPhone = ringingCall.getPhone();

        ringingPhone.rejectCall();
    }

    /**
     * Places any active calls on hold, and makes any held calls
     *  active. Switch occurs asynchronously and may fail.
     * Final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException if active call is ringing, waiting, or
     * dialing/alerting, or heldCall can�t be active.
     * In these cases, this operation may not be performed.
     */
    public void switchHoldingAndActive(Call heldCall) throws CallStateException {
        Phone activePhone = null;
        Phone heldPhone = null;

        if (hasActiveFgCall()) {
            activePhone = getActiveFgCall().getPhone();
        }

        if (heldCall != null) {
            heldPhone = heldCall.getPhone();
        }

        if (activePhone != heldPhone) {
            activePhone.switchHoldingAndActive();
        }

        heldPhone.switchHoldingAndActive();
    }

    /**
     * Whether or not the phone can conference in the current phone
     * state--that is, one call holding and one call active.
     * @return true if the phone can conference; false otherwise.
     */
    public boolean canConference(Call heldCall) {
        Phone activePhone = null;
        Phone heldPhone = null;

        if (hasActiveFgCall()) {
            activePhone = getActiveFgCall().getPhone();
        }

        if (heldCall != null) {
            heldPhone = heldCall.getPhone();
        }

        return (heldPhone == activePhone);
    }

    /**
     * Conferences holding and active. Conference occurs asynchronously
     * and may fail. Final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException if canConference() would return false.
     * In these cases, this operation may not be performed.
     */
    public void conference(Call heldCall) throws CallStateException {
        if (canConference(heldCall))
            throw(new CallStateException("Can't conference foreground and selected background call"));

        heldCall.getPhone().conference();
    }

    /**
     * Initiate a new voice connection. This happens asynchronously, so you
     * cannot assume the audio path is connected (or a call index has been
     * assigned) until PhoneStateChanged notification has occurred.
     *
     * @exception CallStateException if a new outgoing call is not currently
     * possible because no more call slots exist or a call exists that is
     * dialing, alerting, ringing, or waiting.  Other errors are
     * handled asynchronously.
     */
    public Connection dial(Phone phone, String dialString) throws CallStateException {
        return phone.dial(dialString);
    }

    /**
     * Initiate a new voice connection. This happens asynchronously, so you
     * cannot assume the audio path is connected (or a call index has been
     * assigned) until PhoneStateChanged notification has occurred.
     *
     * @exception CallStateException if a new outgoing call is not currently
     * possible because no more call slots exist or a call exists that is
     * dialing, alerting, ringing, or waiting.  Other errors are
     * handled asynchronously.
     */
    public Connection dial(Phone phone, String dialString, UUSInfo uusInfo) throws CallStateException {
        return phone.dial(dialString, uusInfo);
    }

    /**
     * clear disconnect connection for each phone
     */
    public void clearDisconnected() {
        for(Phone phone : mPhones) {
            phone.clearDisconnected();
        }
    }

    /**
     * Whether or not the phone can do explicit call transfer in the current
     * phone state--that is, one call holding and one call active.
     * @return true if the phone can do explicit call transfer; false otherwise.
     */
    public boolean canTransfer(Call heldCall) {
        Phone activePhone = null;
        Phone heldPhone = null;

        if (hasActiveFgCall()) {
            activePhone = getActiveFgCall().getPhone();
        }

        if (heldCall != null) {
            heldPhone = heldCall.getPhone();
        }

        return (heldPhone == activePhone && activePhone.canTransfer());
    }

    /**
     * Connects the held call and active call
     * Disconnects the subscriber from both calls
     *
     * Explicit Call Transfer occurs asynchronously
     * and may fail. Final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException if canTransfer() would return false.
     * In these cases, this operation may not be performed.
     */
    public void explicitCallTransfer(Call heldCall) throws CallStateException {
        if (canTransfer(heldCall)) {
            heldCall.getPhone().explicitCallTransfer();
        }
    }


    /**
     * @return list of ringing calls
     */
    public ArrayList<Call> getRingingCalls() {
        return mBackgroundCalls;
    }

    /**
     * @return list of background calls
     */
    public ArrayList<Call> getBackgroundCalls() {
        return mBackgroundCalls;
    }

    /**
     * Return the non idle foreground call,
     * note: there is difference between isAlive and non idle
     */
    public Call getActiveFgCall() {
        for (Call call : mForegroundCalls) {
            if (call.getState() != Call.State.IDLE) {
                return call;
            }
        }
        return null;
    }

    /**
     * return the first active call from a call list
     */
    private  Call getFirstActiveCall(ArrayList<Call> calls) {
        for (Call call : calls) {
            if (!call.isIdle()) {
                return call;
            }
        }
        return null;
    }

    /**
     * Return true if there is at least one active foreground call
     */
    public boolean hasActiveFgCall() {
        return (getFirstActiveCall(mForegroundCalls) != null);
    }

    /**
     * Return true if there is at least one active background call
     */
    public boolean hasActiveBgCall() {
        return (getFirstActiveCall(mBackgroundCalls) != null);
    }

    /**
     * Return true if there is at least one active ringing call
     */
    public boolean hasActiveRingingCall() {
        return (getFirstActiveCall(mRingingCalls) != null);
    }

    /**
     * Returns a list of MMI codes that are pending for a phone. (They have initiated
     * but have not yet completed).
     * Presently there is only ever one.
     *
     * Use <code>registerForMmiInitiate</code>
     * and <code>registerForMmiComplete</code> for change notification.
     * @return null if phone doesn't have or support mmi code
     */
    public List<? extends MmiCode> getPendingMmiCodes(Phone phone) {
        return null;
    }

    /**
     * Sends user response to a USSD REQUEST message.  An MmiCode instance
     * representing this response is sent to handlers registered with
     * registerForMmiInitiate.
     *
     * @param ussdMessge    Message to send in the response.
     * @return false if phone doesn't support ussd service
     */
    public boolean sendUssdResponse(Phone phone, String ussdMessge) {
        return false;
    }

    /**
     * Mutes or unmutes the microphone for the active call. The microphone
     * is automatically unmuted if a call is answered, dialed, or resumed
     * from a holding state.
     *
     * @param muted true to mute the microphone,
     * false to activate the microphone.
     */

    public void setMute(boolean muted) {
        if (hasActiveFgCall()) {
            getActiveFgCall().getPhone().setMute(muted);
        }
    }

    /**
     * Gets current mute status. Use
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}
     * as a change notifcation, although presently phone state changed is not
     * fired when setMute() is called.
     *
     * @return true is muting, false is unmuting
     */
    public boolean getMute() {
        if (hasActiveFgCall()) {
            return getActiveFgCall().getPhone().getMute();
        }
        return false;
    }

    /**
     * Play a DTMF tone on the active call.
     *
     * @param c should be one of 0-9, '*' or '#'. Other values will be
     * silently ignored.
     * @return false if no active call or the active call doesn't support
     *         dtmf tone
     */
    public boolean sendDtmf(char c) {
        if (hasActiveFgCall()) {
            getActiveFgCall().getPhone().sendDtmf(c);
            return true;
        }
        return false;
    }

    /**
     * Start to paly a DTMF tone on the active call.
     * or there is a playing DTMF tone.
     * @param c should be one of 0-9, '*' or '#'. Other values will be
     * silently ignored.
     *
     * @return false if no active call or the active call doesn't support
     *         dtmf tone
     */
    public boolean startDtmf(char c) {
        if (hasActiveFgCall()) {
            getActiveFgCall().getPhone().sendDtmf(c);
            return true;
        }
        return false;
    }

    /**
     * Stop the playing DTMF tone. Ignored if there is no playing DTMF
     * tone or no active call.
     */
    public void stopDtmf(Phone phone) {
        phone.stopDtmf();
    }

    /**
     * send burst DTMF tone, it can send the string as single character or multiple character
     * ignore if there is no active call or not valid digits string.
     * Valid digit means only includes characters ISO-LATIN characters 0-9, *, #
     * The difference between sendDtmf and sendBurstDtmf is sendDtmf only sends one character,
     * this api can send single character and multiple character, also, this api has response
     * back to caller.
     *
     * @param dtmfString is string representing the dialing digit(s) in the active call
     * @param on the DTMF ON length in milliseconds, or 0 for default
     * @param off the DTMF OFF length in milliseconds, or 0 for default
     * @param onComplete is the callback message when the action is processed by BP
     *
     */
    public boolean sendBurstDtmf(Phone phone, String dtmfString, int on, int off, Message onComplete) {
        if (hasActiveFgCall()) {
            getActiveFgCall().getPhone().sendBurstDtmf(dtmfString, on, off, onComplete);
            return true;
        }
        return false;
    }

    /**
     * Notifies when a voice connection has disconnected, either due to local
     * or remote hangup or error.
     *
     *  Messages received from this will have the following members:<p>
     *  <ul><li>Message.obj will be an AsyncResult</li>
     *  <li>AsyncResult.userObj = obj</li>
     *  <li>AsyncResult.result = a Connection object that is
     *  no longer connected.</li></ul>
     */
    public void registerForDisconnect(Handler h, int what, Object obj) {
        mDisconnectRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for voice disconnection notification.
     * Extraneous calls are tolerated silently
     */
    public void unregisterForDisconnect(Handler h){
        mDisconnectRegistrants.remove(h);
    }

    /**
     * Register for getting notifications for change in the Call State {@link Call.State}
     * This is called PreciseCallState because the call state is more precise than the
     * {@link Phone.State} which can be obtained using the {@link PhoneStateListener}
     *
     * Resulting events will have an AsyncResult in <code>Message.obj</code>.
     * AsyncResult.userData will be set to the obj argument here.
     * The <em>h</em> parameter is held only by a weak reference.
     */
    public void registerForPreciseCallStateChanged(Handler h, int what, Object obj){
        mPreciseCallStateRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for voice call state change notifications.
     * Extraneous calls are tolerated silently.
     */
    public void unregisterForPreciseCallStateChanged(Handler h){
        mPreciseCallStateRegistrants.remove(h);
    }

    /**
     * Notifies when a previously untracked non-ringing/waiting connection has appeared.
     * This is likely due to some other entity (eg, SIM card application) initiating a call.
     */
    public void registerForUnknownConnection(Handler h, int what, Object obj){}

    /**
     * Unregisters for unknown connection notifications.
     */
    public void unregisterForUnknownConnection(Handler h){}


    /**
     * Notifies when a new ringing or waiting connection has appeared.<p>
     *
     *  Messages received from this:
     *  Message.obj will be an AsyncResult
     *  AsyncResult.userObj = obj
     *  AsyncResult.result = a Connection. <p>
     *  Please check Connection.isRinging() to make sure the Connection
     *  has not dropped since this message was posted.
     *  If Connection.isRinging() is true, then
     *   Connection.getCall() == Phone.getRingingCall()
     */
    public void registerForNewRingingConnection(Handler h, int what, Object obj){}

    /**
     * Unregisters for new ringing connection notification.
     * Extraneous calls are tolerated silently
     */

    public void unregisterForNewRingingConnection(Handler h){}

    /**
     * Notifies when an incoming call rings.<p>
     *
     *  Messages received from this:
     *  Message.obj will be an AsyncResult
     *  AsyncResult.userObj = obj
     *  AsyncResult.result = a Connection. <p>
     */
    public void registerForIncomingRing(Handler h, int what, Object obj){}

    /**
     * Unregisters for ring notification.
     * Extraneous calls are tolerated silently
     */

    public void unregisterForIncomingRing(Handler h){}

    /**
     * Notifies when out-band ringback tone is needed.<p>
     *
     *  Messages received from this:
     *  Message.obj will be an AsyncResult
     *  AsyncResult.userObj = obj
     *  AsyncResult.result = boolean, true to start play ringback tone
     *                       and false to stop. <p>
     */
    public void registerForRingbackTone(Handler h, int what, Object obj){}

    /**
     * Unregisters for ringback tone notification.
     */

    public void unregisterForRingbackTone(Handler h){}

    /**
     * Registers the handler to reset the uplink mute state to get
     * uplink audio.
     */
    public void registerForResendIncallMute(Handler h, int what, Object obj){}

    /**
     * Unregisters for resend incall mute notifications.
     */
    public void unregisterForResendIncallMute(Handler h){}



    /**
     * Register for notifications of initiation of a new MMI code request.
     * MMI codes for GSM are discussed in 3GPP TS 22.030.<p>
     *
     * Example: If Phone.dial is called with "*#31#", then the app will
     * be notified here.<p>
     *
     * The returned <code>Message.obj</code> will contain an AsyncResult.
     *
     * <code>obj.result</code> will be an "MmiCode" object.
     */
    public void registerForMmiInitiate(Handler h, int what, Object obj){}

    /**
     * Unregisters for new MMI initiate notification.
     * Extraneous calls are tolerated silently
     */
    public void unregisterForMmiInitiate(Handler h){}

    /**
     * Register for notifications that an MMI request has completed
     * its network activity and is in its final state. This may mean a state
     * of COMPLETE, FAILED, or CANCELLED.
     *
     * <code>Message.obj</code> will contain an AsyncResult.
     * <code>obj.result</code> will be an "MmiCode" object
     */
    public void registerForMmiComplete(Handler h, int what, Object obj){}

    /**
     * Unregisters for MMI complete notification.
     * Extraneous calls are tolerated silently
     */
    public void unregisterForMmiComplete(Handler h){}

    /**
     * Registration point for Ecm timer reset
     * @param h handler to notify
     * @param what user-defined message code
     * @param obj placed in Message.obj
     */
    public void registerForEcmTimerReset(Handler h, int what, Object obj){}

    /**
     * Unregister for notification for Ecm timer reset
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForEcmTimerReset(Handler h){}



    /**
     * Register for ServiceState changed.
     * Message.obj will contain an AsyncResult.
     * AsyncResult.result will be a ServiceState instance
     */
    public void registerForServiceStateChanged(Handler h, int what, Object obj){}

    /**
     * Unregisters for ServiceStateChange notification.
     * Extraneous calls are tolerated silently
     */
    public void unregisterForServiceStateChanged(Handler h){}

    /**
     * Register for Supplementary Service notifications from the network.
     * Message.obj will contain an AsyncResult.
     * AsyncResult.result will be a SuppServiceNotification instance.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSuppServiceNotification(Handler h, int what, Object obj){}

    /**
     * Unregisters for Supplementary Service notifications.
     * Extraneous calls are tolerated silently
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSuppServiceNotification(Handler h){}

    /**
     * Register for notifications when a supplementary service attempt fails.
     * Message.obj will contain an AsyncResult.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSuppServiceFailed(Handler h, int what, Object obj){}

    /**
     * Unregister for notifications when a supplementary service attempt fails.
     * Extraneous calls are tolerated silently
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSuppServiceFailed(Handler h){}

    /**
     * Register for notifications when a sInCall VoicePrivacy is enabled
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj){}

    /**
     * Unegister for notifications when a sInCall VoicePrivacy is enabled
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForInCallVoicePrivacyOn(Handler h){}

    /**
     * Register for notifications when a sInCall VoicePrivacy is disabled
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj){}

    /**
     * Unegister for notifications when a sInCall VoicePrivacy is disabled
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForInCallVoicePrivacyOff(Handler h){}

    /**
     * Register for notifications when CDMA OTA Provision status change
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForCdmaOtaStatusChange(Handler h, int what, Object obj){}

    /**
     * Unegister for notifications when CDMA OTA Provision status change
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForCdmaOtaStatusChange(Handler h){}

    /**
     * Registration point for subscription info ready
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj){}

    /**
     * Unregister for notifications for subscription info
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSubscriptionInfoReady(Handler h){}

    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_DISCONNECT:
                    mDisconnectRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_CALL_STATE_CHANGED:
                    mPreciseCallStateRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
            }
        }
    };
}