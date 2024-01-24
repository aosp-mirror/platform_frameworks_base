/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.telecom;

import static android.telecom.TelecomManager.TELECOM_TRANSACTION_SUCCESS;

import android.os.Binder;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.ResultReceiver;
import android.telecom.CallAttributes;
import android.telecom.CallControl;
import android.telecom.CallControlCallback;
import android.telecom.CallEndpoint;
import android.telecom.CallEventCallback;
import android.telecom.CallException;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * wraps {@link CallControlCallback}, {@link CallEventCallback}, and {@link CallControl} on a
 * per-{@link  android.telecom.PhoneAccountHandle} basis to track ongoing calls.
 *
 * @hide
 */
public class ClientTransactionalServiceWrapper {

    private static final String TAG = ClientTransactionalServiceWrapper.class.getSimpleName();
    private final PhoneAccountHandle mPhoneAccountHandle;
    private final ClientTransactionalServiceRepository mRepository;
    private final ConcurrentHashMap<String, TransactionalCall> mCallIdToTransactionalCall =
            new ConcurrentHashMap<>();
    private static final String EXECUTOR_FAIL_MSG =
            "Telecom hit an exception while handling a CallEventCallback on an executor: ";

    public ClientTransactionalServiceWrapper(PhoneAccountHandle handle,
            ClientTransactionalServiceRepository repo) {
        mPhoneAccountHandle = handle;
        mRepository = repo;
    }

    /**
     * remove the given call from the class HashMap
     *
     * @param callId that is tied to TransactionalCall object
     */
    public void untrackCall(String callId) {
        Log.i(TAG, TextUtils.formatSimple("removeCall: with id=[%s]", callId));
        if (mCallIdToTransactionalCall.containsKey(callId)) {
            // remove the call from the hashmap
            TransactionalCall call = mCallIdToTransactionalCall.remove(callId);
            // null out interface to avoid memory leaks
            CallControl control = call.getCallControl();
            if (control != null) {
                call.setCallControl(null);
            }
        }
        // possibly cleanup service wrapper if there are no more calls
        if (mCallIdToTransactionalCall.size() == 0) {
            mRepository.removeServiceWrapper(mPhoneAccountHandle);
        }
    }

    /**
     * start tracking a newly created call for a particular package
     *
     * @param callAttributes of the new call
     * @param executor       to run callbacks on
     * @param pendingControl that allows telecom to call into the client
     * @param handshakes     that overrides the CallControlCallback
     * @param events         that overrides the CallStateCallback
     * @return the callId of the newly created call
     */
    public String trackCall(CallAttributes callAttributes, Executor executor,
            OutcomeReceiver<CallControl, CallException> pendingControl,
            CallControlCallback handshakes,
            CallEventCallback events) {
        // generate a new id for this new call
        String newCallId = UUID.randomUUID().toString();

        // couple the objects passed from the client side
        mCallIdToTransactionalCall.put(newCallId, new TransactionalCall(newCallId, callAttributes,
                executor, pendingControl, handshakes, events));

        return newCallId;
    }

    public ICallEventCallback getCallEventCallback() {
        return mCallEventCallback;
    }

    /**
     * Consumers that is to be completed by the client and the result relayed back to telecom server
     * side via a {@link ResultReceiver}. see com.android.server.telecom.TransactionalServiceWrapper
     * for how the response is handled.
     */
    private class ReceiverWrapper implements Consumer<Boolean> {
        private final ResultReceiver mRepeaterReceiver;

        ReceiverWrapper(ResultReceiver resultReceiver) {
            mRepeaterReceiver = resultReceiver;
        }

        @Override
        public void accept(Boolean clientCompletedCallbackSuccessfully) {
            if (clientCompletedCallbackSuccessfully) {
                mRepeaterReceiver.send(TELECOM_TRANSACTION_SUCCESS, null);
            } else {
                mRepeaterReceiver.send(CallException.CODE_ERROR_UNKNOWN, null);
            }
        }

        @Override
        public Consumer<Boolean> andThen(Consumer<? super Boolean> after) {
            return Consumer.super.andThen(after);
        }
    }

    private final ICallEventCallback mCallEventCallback = new ICallEventCallback.Stub() {

        private static final String ON_SET_ACTIVE = "onSetActive";
        private static final String ON_SET_INACTIVE = "onSetInactive";
        private static final String ON_ANSWER = "onAnswer";
        private static final String ON_DISCONNECT = "onDisconnect";
        private static final String ON_STREAMING_STARTED = "onStreamingStarted";
        private static final String ON_REQ_ENDPOINT_CHANGE = "onRequestEndpointChange";
        private static final String ON_AVAILABLE_CALL_ENDPOINTS = "onAvailableCallEndpointsChanged";
        private static final String ON_MUTE_STATE_CHANGED = "onMuteStateChanged";
        private static final String ON_CALL_STREAMING_FAILED = "onCallStreamingFailed";
        private static final String ON_EVENT = "onEvent";

        private void handleCallEventCallback(String action, String callId,
                ResultReceiver ackResultReceiver, Object... args) {
            Log.i(TAG, TextUtils.formatSimple("hCEC: id=[%s], action=[%s]", callId, action));
            // lookup the callEventCallback associated with the particular call
            TransactionalCall call = mCallIdToTransactionalCall.get(callId);

            if (call != null) {
                // Get the CallEventCallback interface
                CallControlCallback callback = call.getCallControlCallback();
                // Get Receiver to wait on client ack
                ReceiverWrapper outcomeReceiverWrapper = new ReceiverWrapper(ackResultReceiver);

                // wait for the client to complete the CallEventCallback
                final long identity = Binder.clearCallingIdentity();
                try {
                    call.getExecutor().execute(() -> {
                        switch (action) {
                            case ON_SET_ACTIVE:
                                callback.onSetActive(outcomeReceiverWrapper);
                                break;
                            case ON_SET_INACTIVE:
                                callback.onSetInactive(outcomeReceiverWrapper);
                                break;
                            case ON_DISCONNECT:
                                callback.onDisconnect((DisconnectCause) args[0],
                                        outcomeReceiverWrapper);
                                untrackCall(callId);
                                break;
                            case ON_ANSWER:
                                callback.onAnswer((int) args[0], outcomeReceiverWrapper);
                                break;
                            case ON_STREAMING_STARTED:
                                callback.onCallStreamingStarted(outcomeReceiverWrapper);
                                break;
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, EXECUTOR_FAIL_MSG + e);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void onAddCallControl(String callId, int resultCode, ICallControl callControl,
                CallException transactionalException) {
            Log.i(TAG, TextUtils.formatSimple("oACC: id=[%s], code=[%d]", callId, resultCode));
            TransactionalCall call = mCallIdToTransactionalCall.get(callId);

            if (call != null) {
                OutcomeReceiver<CallControl, CallException> pendingControl =
                        call.getPendingControl();

                if (resultCode == TELECOM_TRANSACTION_SUCCESS) {

                    // create the interface object that the client will interact with
                    CallControl control = new CallControl(callId, callControl);
                    // give the client the object via the OR that was passed into addCall
                    pendingControl.onResult(control);

                    // store for later reference
                    call.setCallControl(control);
                } else {
                    pendingControl.onError(transactionalException);
                    mCallIdToTransactionalCall.remove(callId);
                }

            } else {
                untrackCall(callId);
                Log.e(TAG, "oACC: TransactionalCall object not found for call w/ id=" + callId);
            }
        }

        @Override
        public void onSetActive(String callId, ResultReceiver resultReceiver) {
            handleCallEventCallback(ON_SET_ACTIVE, callId, resultReceiver);
        }

        @Override
        public void onSetInactive(String callId, ResultReceiver resultReceiver) {
            handleCallEventCallback(ON_SET_INACTIVE, callId, resultReceiver);
        }

        @Override
        public void onAnswer(String callId, int videoState, ResultReceiver resultReceiver) {
            handleCallEventCallback(ON_ANSWER, callId, resultReceiver, videoState);
        }

        @Override
        public void onDisconnect(String callId, DisconnectCause cause,
                ResultReceiver resultReceiver) {
            handleCallEventCallback(ON_DISCONNECT, callId, resultReceiver, cause);
        }

        @Override
        public void onCallEndpointChanged(String callId, CallEndpoint endpoint) {
            handleEventCallback(callId, ON_REQ_ENDPOINT_CHANGE, endpoint);
        }

        @Override
        public void onAvailableCallEndpointsChanged(String callId, List<CallEndpoint> endpoints) {
            handleEventCallback(callId, ON_AVAILABLE_CALL_ENDPOINTS, endpoints);
        }

        @Override
        public void onMuteStateChanged(String callId, boolean isMuted) {
            handleEventCallback(callId, ON_MUTE_STATE_CHANGED, isMuted);
        }

        public void handleEventCallback(String callId, String action, Object arg) {
            Log.d(TAG, TextUtils.formatSimple("hEC: [%s], callId=[%s]", action, callId));
            // lookup the callEventCallback associated with the particular call
            TransactionalCall call = mCallIdToTransactionalCall.get(callId);
            if (call != null) {
                CallEventCallback callback = call.getCallStateCallback();
                Executor executor = call.getExecutor();
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> {
                        switch (action) {
                            case ON_REQ_ENDPOINT_CHANGE:
                                callback.onCallEndpointChanged((CallEndpoint) arg);
                                break;
                            case ON_AVAILABLE_CALL_ENDPOINTS:
                                callback.onAvailableCallEndpointsChanged((List<CallEndpoint>) arg);
                                break;
                            case ON_MUTE_STATE_CHANGED:
                                callback.onMuteStateChanged((boolean) arg);
                                break;
                            case ON_CALL_STREAMING_FAILED:
                                callback.onCallStreamingFailed((int) arg /* reason */);
                                break;
                        }
                    });
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void removeCallFromTransactionalServiceWrapper(String callId) {
            untrackCall(callId);
        }

        @Override
        public void onCallStreamingStarted(String callId, ResultReceiver resultReceiver) {
            handleCallEventCallback(ON_STREAMING_STARTED, callId, resultReceiver);
        }

        @Override
        public void onCallStreamingFailed(String callId, int reason) {
            Log.i(TAG, TextUtils.formatSimple("oCSF: id=[%s], reason=[%s]", callId, reason));
            handleEventCallback(callId, ON_CALL_STREAMING_FAILED, reason);
        }

        @Override
        public void onEvent(String callId, String event, Bundle extras) {
            // lookup the callEventCallback associated with the particular call
            TransactionalCall call = mCallIdToTransactionalCall.get(callId);
            if (call != null) {
                CallEventCallback callback = call.getCallStateCallback();
                Executor executor = call.getExecutor();
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> {
                        callback.onEvent(event, extras);
                    });
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    };
}
