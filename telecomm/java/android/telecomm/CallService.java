/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.telecomm;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.ICallService;
import com.android.internal.telecomm.ICallServiceAdapter;

import java.util.List;

/**
 * Base implementation of CallService which can be used to provide calls for the system
 * in-call UI. CallService is a one-way service from the framework's CallsManager to any app
 * that would like to provide calls managed by the default system in-call user interface.
 * When the service is bound by the framework, CallsManager will call setCallServiceAdapter
 * which will provide CallService with an instance of {@link CallServiceAdapter} to be used
 * for communicating back to CallsManager. Subsequently, more specific methods of the service
 * will be called to perform various call actions including making an outgoing call and
 * disconnected existing calls.
 * TODO(santoscordon): Needs more about AndroidManifest.xml service registrations before
 * we can unhide this API.
 *
 * Most public methods of this function are backed by a one-way AIDL interface which precludes
 * synchronous responses. As a result, most responses are handled by (or have TODOs to handle)
 * response objects instead of return values.
 * TODO(santoscordon): Improve paragraph above once the final design is in place.
 */
public abstract class CallService extends Service {

    private static final int MSG_SET_CALL_SERVICE_ADAPTER = 1;
    private static final int MSG_IS_COMPATIBLE_WITH = 2;
    private static final int MSG_CALL = 3;
    private static final int MSG_ABORT = 4;
    private static final int MSG_SET_INCOMING_CALL_ID = 5;
    private static final int MSG_ANSWER = 6;
    private static final int MSG_REJECT = 7;
    private static final int MSG_DISCONNECT = 8;
    private static final int MSG_HOLD = 9;
    private static final int MSG_UNHOLD = 10;
    private static final int MSG_ON_AUDIO_STATE_CHANGED = 11;
    private static final int MSG_PLAY_DTMF_TONE = 12;
    private static final int MSG_STOP_DTMF_TONE = 13;
    private static final int MSG_CONFERENCE = 14;
    private static final int MSG_SPLIT_FROM_CONFERENCE = 15;
    private static final int MSG_ON_POST_DIAL_CONTINUE = 16;

    /**
     * Default Handler used to consolidate binder method calls onto a single thread.
     */
    private final class CallServiceMessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_CALL_SERVICE_ADAPTER:
                    mAdapter = new CallServiceAdapter((ICallServiceAdapter) msg.obj);
                    onAdapterAttached(mAdapter);
                    break;
                case MSG_IS_COMPATIBLE_WITH:
                    isCompatibleWith((CallInfo) msg.obj);
                    break;
                case MSG_CALL:
                    call((CallInfo) msg.obj);
                    break;
                case MSG_ABORT:
                    abort((String) msg.obj);
                    break;
                case MSG_SET_INCOMING_CALL_ID: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        Bundle extras = (Bundle) args.arg2;
                        setIncomingCallId(callId, extras);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ANSWER:
                    answer((String) msg.obj);
                    break;
                case MSG_REJECT:
                    reject((String) msg.obj);
                    break;
                case MSG_DISCONNECT:
                    disconnect((String) msg.obj);
                    break;
                case MSG_HOLD:
                    hold((String) msg.obj);
                    break;
                case MSG_UNHOLD:
                    unhold((String) msg.obj);
                    break;
                case MSG_ON_AUDIO_STATE_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        CallAudioState audioState = (CallAudioState) args.arg2;
                        onAudioStateChanged(callId, audioState);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_PLAY_DTMF_TONE:
                    playDtmfTone((String) msg.obj, (char) msg.arg1);
                    break;
                case MSG_STOP_DTMF_TONE:
                    stopDtmfTone((String) msg.obj);
                    break;
                case MSG_CONFERENCE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String conferenceCallId = (String) args.arg1;
                        String callId = (String) args.arg2;
                        conference(conferenceCallId, callId);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ON_POST_DIAL_CONTINUE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        boolean proceed = (args.argi1 == 1);
                        onPostDialContinue(callId, proceed);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SPLIT_FROM_CONFERENCE:
                    splitFromConference((String) msg.obj);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Default ICallService implementation provided to CallsManager via {@link #onBind}.
     */
    private final class CallServiceBinder extends ICallService.Stub {
        @Override
        public void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter) {
            mMessageHandler.obtainMessage(MSG_SET_CALL_SERVICE_ADAPTER, callServiceAdapter)
                    .sendToTarget();
        }

        @Override
        public void isCompatibleWith(CallInfo callInfo) {
            mMessageHandler.obtainMessage(MSG_IS_COMPATIBLE_WITH, callInfo).sendToTarget();
        }

        @Override
        public void call(CallInfo callInfo) {
            mMessageHandler.obtainMessage(MSG_CALL, callInfo).sendToTarget();
        }

        @Override
        public void abort(String callId) {
            mMessageHandler.obtainMessage(MSG_ABORT, callId).sendToTarget();
        }

        @Override
        public void setIncomingCallId(String callId, Bundle extras) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = extras;
            mMessageHandler.obtainMessage(MSG_SET_INCOMING_CALL_ID, args).sendToTarget();
        }

        @Override
        public void answer(String callId) {
            mMessageHandler.obtainMessage(MSG_ANSWER, callId).sendToTarget();
        }

        @Override
        public void reject(String callId) {
            mMessageHandler.obtainMessage(MSG_REJECT, callId).sendToTarget();
        }

        @Override
        public void disconnect(String callId) {
            mMessageHandler.obtainMessage(MSG_DISCONNECT, callId).sendToTarget();
        }

        @Override
        public void hold(String callId) {
            mMessageHandler.obtainMessage(MSG_HOLD, callId).sendToTarget();
        }

        @Override
        public void unhold(String callId) {
            mMessageHandler.obtainMessage(MSG_UNHOLD, callId).sendToTarget();
        }

        @Override
        public void playDtmfTone(String callId, char digit) {
            mMessageHandler.obtainMessage(MSG_PLAY_DTMF_TONE, digit, 0, callId).sendToTarget();
        }

        @Override
        public void stopDtmfTone(String callId) {
            mMessageHandler.obtainMessage(MSG_STOP_DTMF_TONE, callId).sendToTarget();
        }

        @Override
        public void onAudioStateChanged(String callId, CallAudioState audioState) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = audioState;
            mMessageHandler.obtainMessage(MSG_ON_AUDIO_STATE_CHANGED, args).sendToTarget();
        }

        @Override
        public void conference(String conferenceCallId, String callId) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = conferenceCallId;
            args.arg2 = callId;
            mMessageHandler.obtainMessage(MSG_CONFERENCE, args).sendToTarget();
        }

        @Override
        public void splitFromConference(String callId) {
            mMessageHandler.obtainMessage(MSG_SPLIT_FROM_CONFERENCE, callId).sendToTarget();
        }

        @Override
        public void onPostDialContinue(String callId, boolean proceed) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.argi1 = proceed ? 1 : 0;
            mMessageHandler.obtainMessage(MSG_ON_POST_DIAL_CONTINUE, args).sendToTarget();
        }
    }

    /**
     * Message handler for consolidating binder callbacks onto a single thread.
     * See {@link CallServiceMessageHandler}.
     */
    private final CallServiceMessageHandler mMessageHandler = new CallServiceMessageHandler();

    /**
     * Default binder implementation of {@link ICallService} interface.
     */
    private final CallServiceBinder mBinder = new CallServiceBinder();

    private CallServiceAdapter mAdapter = null;

    /** {@inheritDoc} */
    @Override
    public final IBinder onBind(Intent intent) {
        return getBinder();
    }

    /**
     * Returns binder object which can be used across IPC methods.
     */
    public final IBinder getBinder() {
        return mBinder;
    }

    /**
     * @return The attached {@link CallServiceAdapter} if the service is bound, null otherwise.
     */
    protected final CallServiceAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Lifecycle callback which is called when this {@link CallService} has been attached to a
     * {@link CallServiceAdapter}, indicating {@link #getAdapter()} is now safe to use.
     *
     * @param adapter The adapter now attached to this call service.
     */
    protected void onAdapterAttached(CallServiceAdapter adapter) {
    }

    /**
     * Determines if the CallService can place the specified call. Response is sent via
     * {@link CallServiceAdapter#setIsCompatibleWith}. When responding, the correct call ID must be
     * specified.  Only used in the context of outgoing calls and call switching (handoff).
     *
     * @param callInfo The details of the relevant call.
     */
    public abstract void isCompatibleWith(CallInfo callInfo);

    /**
     * Attempts to call the relevant party using the specified call's handle, be it a phone number,
     * SIP address, or some other kind of user ID.  Note that the set of handle types is
     * dynamically extensible since call providers should be able to implement arbitrary
     * handle-calling systems.  See {@link #isCompatibleWith}. It is expected that the
     * call service respond via {@link CallServiceAdapter#handleSuccessfulOutgoingCall(String)}
     * if it can successfully make the call.  Only used in the context of outgoing calls.
     *
     * @param callInfo The details of the relevant call.
     */
    public abstract void call(CallInfo callInfo);

    /**
     * Aborts the outgoing call attempt. Invoked in the unlikely event that Telecomm decides to
     * abort an attempt to place a call.  Only ever be invoked after {@link #call} invocations.
     * After this is invoked, Telecomm does not expect any more updates about the call and will
     * actively ignore any such update. This is different from {@link #disconnect} where Telecomm
     * expects confirmation via CallServiceAdapter.markCallAsDisconnected.
     *
     * @param callId The identifier of the call to abort.
     */
    public abstract void abort(String callId);

    /**
     * Receives a new call ID to use with an incoming call. Invoked by Telecomm after it is notified
     * that this call service has a pending incoming call, see
     * {@link TelecommConstants#ACTION_INCOMING_CALL}. The call service must first give Telecomm
     * additional information about the call through {@link CallServiceAdapter#notifyIncomingCall}.
     * Following that, the call service can update the call at will using the specified call ID.
     *
     * If a {@link Bundle} was passed (via {@link TelecommConstants#EXTRA_INCOMING_CALL_EXTRAS}) in
     * with the {@link TelecommConstants#ACTION_INCOMING_CALL} intent, <code>extras</code> will be
     * populated with this {@link Bundle}. Otherwise, an empty Bundle will be returned.
     *
     * @param callId The ID of the call.
     * @param extras The optional extras which were passed in with the intent, or an empty Bundle.
     */
    public abstract void setIncomingCallId(String callId, Bundle extras);

    /**
     * Answers a ringing call identified by callId. Telecomm invokes this method as a result of the
     * user hitting the "answer" button in the incoming call screen.
     *
     * @param callId The ID of the call.
     */
    public abstract void answer(String callId);

    /**
     * Rejects a ringing call identified by callId. Telecomm invokes this method as a result of the
     * user hitting the "reject" button in the incoming call screen.
     *
     * @param callId The ID of the call.
     */
    public abstract void reject(String callId);

    /**
     * Disconnects the specified call.
     *
     * @param callId The ID of the call to disconnect.
     */
    public abstract void disconnect(String callId);

    /**
     * Puts the specified call on hold.
     *
     * @param callId The ID of the call to put on hold.
     */
    public abstract void hold(String callId);

    /**
     * Removes the specified call from hold.
     *
     * @param callId The ID of the call to unhold.
     */
    public abstract void unhold(String callId);

    /**
     * Plays a dual-tone multi-frequency signaling (DTMF) tone in a call.
     *
     * @param callId The unique ID of the call in which the tone will be played.
     * @param digit A character representing the DTMF digit for which to play the tone. This
     *         value must be one of {@code '0'} through {@code '9'}, {@code '*'} or {@code '#'}.
     */
    public abstract void playDtmfTone(String callId, char digit);

    /**
     * Stops any dual-tone multi-frequency sinaling (DTMF) tone currently playing.
     *
     * DTMF tones are played by calling {@link #playDtmfTone(String,char)}. If no DTMF tone is
     * currently playing, this method will do nothing.
     *
     * @param callId The unique ID of the call in which any currently playing tone will be stopped.
     */
    public abstract void stopDtmfTone(String callId);

    /**
     * Called when the audio state changes.
     *
     * @param activeCallId The identifier of the call that was active during the state change.
     * @param audioState The new {@link CallAudioState}.
     */
    public abstract void onAudioStateChanged(String activeCallId, CallAudioState audioState);

    /**
     * Conferences the specified call.
     *
     * @param conferenceCallId The unique ID of the conference call onto which the specified calls
     *         should be added.
     * @param callId The call to conference.
     * @hide
     */
    public abstract void conference(String conferenceCallId, String callId);

    /**
     * Removes the specified call from a conference call.
     *
     * @param callId The call to remove from the conference call
     * @hide
     */
    public abstract void splitFromConference(String callId);

    public void onPostDialContinue(String callId, boolean proceed) {}
    public void onPostDialWait(Connection conn, String remaining) {}
}
