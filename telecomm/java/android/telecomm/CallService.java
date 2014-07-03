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

/**
 * Base implementation of CallService which can be used to provide calls for the system
 * in-call UI. CallService is a one-way service from the framework's CallsManager to any app
 * that would like to provide calls managed by the default system in-call user interface.
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
    private static final int MSG_CALL = 2;
    private static final int MSG_ABORT = 3;
    private static final int MSG_SET_INCOMING_CALL_ID = 4;
    private static final int MSG_ANSWER = 5;
    private static final int MSG_REJECT = 6;
    private static final int MSG_DISCONNECT = 7;
    private static final int MSG_HOLD = 8;
    private static final int MSG_UNHOLD = 9;
    private static final int MSG_ON_AUDIO_STATE_CHANGED = 10;
    private static final int MSG_PLAY_DTMF_TONE = 11;
    private static final int MSG_STOP_DTMF_TONE = 12;
    private static final int MSG_CONFERENCE = 13;
    private static final int MSG_SPLIT_FROM_CONFERENCE = 14;
    private static final int MSG_ON_POST_DIAL_CONTINUE = 15;
    private static final int MSG_ON_PHONE_ACCOUNT_CLICKED = 16;

    /**
     * Default Handler used to consolidate binder method calls onto a single thread.
     */
    private final class CallServiceMessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_CALL_SERVICE_ADAPTER:
                    mAdapter.addAdapter((ICallServiceAdapter) msg.obj);
                    onAdapterAttached(mAdapter);
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
                case MSG_ON_PHONE_ACCOUNT_CLICKED:
                    onPhoneAccountClicked((String) msg.obj);
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

        @Override
        public void onPhoneAccountClicked(String callId) {
            mMessageHandler.obtainMessage(MSG_ON_PHONE_ACCOUNT_CLICKED, callId).sendToTarget();
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

    private CallServiceAdapter mAdapter = new CallServiceAdapter();

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

    /** @hide */
    protected final CallServiceAdapter getAdapter() {
        return mAdapter;
    }

    /** @hide */
    protected abstract void onAdapterAttached(CallServiceAdapter adapter);

    /** @hide */
    protected abstract void call(CallInfo callInfo);

    /** @hide */
    protected abstract void abort(String callId);

    /** @hide */
    protected abstract void setIncomingCallId(String callId, Bundle extras);

    /** @hide */
    protected abstract void answer(String callId);

    /** @hide */
    protected abstract void reject(String callId);

    /** @hide */
    protected abstract void disconnect(String callId);

    /** @hide */
    protected abstract void hold(String callId);

    /** @hide */
    protected abstract void unhold(String callId);

    /** @hide */
    protected abstract void playDtmfTone(String callId, char digit);

    /** @hide */
    protected abstract void stopDtmfTone(String callId);

    /** @hide */
    protected abstract void onAudioStateChanged(String activeCallId, CallAudioState audioState);

    /** @hide */
    protected abstract void conference(String conferenceCallId, String callId);

    /** @hide */
    protected abstract void splitFromConference(String callId);

    /** @hide */
    protected abstract void onPostDialContinue(String callId, boolean proceed);

    /** @hide */
    protected abstract void onFeaturesChanged(String callId, int features);

    /** @hide */
    protected abstract void onPhoneAccountClicked(String callId);
}
