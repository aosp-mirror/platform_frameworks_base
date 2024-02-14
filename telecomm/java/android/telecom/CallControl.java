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

package android.telecom;

import static android.telecom.CallException.TRANSACTION_EXCEPTION_KEY;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.TextUtils;

import com.android.internal.telecom.ICallControl;
import com.android.server.telecom.flags.Flags;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * CallControl provides client side control of a call.  Each Call will get an individual CallControl
 * instance in which the client can alter the state of the associated call.
 *
 * <p>
 * Each method is Transactional meaning that it can succeed or fail. If a transaction succeeds,
 * the {@link OutcomeReceiver#onResult} will be called by Telecom.  Otherwise, the
 * {@link OutcomeReceiver#onError} is called and provides a {@link CallException} that details why
 * the operation failed.
 */
@SuppressLint("NotCloseable")
public final class CallControl {
    private static final String TAG = CallControl.class.getSimpleName();
    private final String mCallId;
    private final ICallControl mServerInterface;

    /** @hide */
    public CallControl(@NonNull String callId, @NonNull ICallControl serverInterface) {
        mCallId = callId;
        mServerInterface = serverInterface;
    }

    /**
     * @return the callId Telecom assigned to this CallControl object which should be attached to
     * an individual call.
     */
    @NonNull
    public ParcelUuid getCallId() {
        return ParcelUuid.fromString(mCallId);
    }

    /**
     * Request Telecom set the call state to active. This method should be called when either an
     * outgoing call is ready to go active or a held call is ready to go active again. For incoming
     * calls that are ready to be answered, use
     * {@link CallControl#answer(int, Executor, OutcomeReceiver)}.
     *
     * @param executor The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                 will be called on.
     * @param callback that will be completed on the Telecom side that details success or failure
     *                 of the requested operation.
     *
     *                 {@link OutcomeReceiver#onResult} will be called if Telecom has successfully
     *                 switched the call state to active
     *
     *                 {@link OutcomeReceiver#onError} will be called if Telecom has failed to set
     *                 the call state to active.  A {@link CallException} will be passed
     *                 that details why the operation failed.
     */
    public void setActive(@CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mServerInterface.setActive(mCallId,
                    new CallControlResultReceiver("setActive", executor, callback));

        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Request Telecom answer an incoming call.  For outgoing calls and calls that have been placed
     * on hold, use {@link CallControl#setActive(Executor, OutcomeReceiver)}.
     *
     * @param videoState to report to Telecom. Telecom will store VideoState in the event another
     *                   service/device requests it in order to continue the call on another screen.
     * @param executor   The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                   will be called on.
     * @param callback   that will be completed on the Telecom side that details success or failure
     *                   of the requested operation.
     *
     *                   {@link OutcomeReceiver#onResult} will be called if Telecom has successfully
     *                   switched the call state to active
     *
     *                   {@link OutcomeReceiver#onError} will be called if Telecom has failed to set
     *                   the call state to active.  A {@link CallException} will be passed
     *                   that details why the operation failed.
     */
    public void answer(@android.telecom.CallAttributes.CallType int videoState,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        validateVideoState(videoState);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mServerInterface.answer(videoState, mCallId,
                    new CallControlResultReceiver("answer", executor, callback));

        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Request Telecom set the call state to inactive. This the same as hold for two call endpoints
     * but can be extended to setting a meeting to inactive.
     *
     * @param executor The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                 will be called on.
     * @param callback that will be completed on the Telecom side that details success or failure
     *                 of the requested operation.
     *
     *                 {@link OutcomeReceiver#onResult} will be called if Telecom has successfully
     *                 switched the call state to inactive
     *
     *                 {@link OutcomeReceiver#onError} will be called if Telecom has failed to set
     *                 the call state to inactive.  A {@link CallException} will be passed
     *                 that details why the operation failed.
     */
    public void setInactive(@CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mServerInterface.setInactive(mCallId,
                    new CallControlResultReceiver("setInactive", executor, callback));

        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Request Telecom disconnect the call and remove the call from telecom tracking.
     *
     * @param disconnectCause represents the cause for disconnecting the call.  The only valid
     *                        codes for the {@link  android.telecom.DisconnectCause} passed in are:
     *                        <ul>
     *                        <li>{@link DisconnectCause#LOCAL}</li>
     *                        <li>{@link DisconnectCause#REMOTE}</li>
     *                        <li>{@link DisconnectCause#REJECTED}</li>
     *                        <li>{@link DisconnectCause#MISSED}</li>
     *                        </ul>
     * @param executor        The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                        will be called on.
     * @param callback        That will be completed on the Telecom side that details success or
     *                        failure of the requested operation.
     *
     *                        {@link OutcomeReceiver#onResult} will be called if Telecom has
     *                        successfully disconnected the call.
     *
     *                        {@link OutcomeReceiver#onError} will be called if Telecom has failed
     *                        to disconnect the call.  A {@link CallException} will be passed
     *                        that details why the operation failed.
     *
     * <p>
     * Note: After the call has been successfully disconnected, calling any CallControl API will
     * result in the {@link OutcomeReceiver#onError} with
     * {@link CallException#CODE_CALL_IS_NOT_BEING_TRACKED}.
     */
    public void disconnect(@NonNull DisconnectCause disconnectCause,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        Objects.requireNonNull(disconnectCause);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        validateDisconnectCause(disconnectCause);
        try {
            mServerInterface.disconnect(mCallId, disconnectCause,
                    new CallControlResultReceiver("disconnect", executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Request start a call streaming session. On receiving valid request, telecom will bind to
     * the {@code CallStreamingService} implemented by a general call streaming sender. So that the
     * call streaming sender can perform streaming local device audio to another remote device and
     * control the call during streaming.
     *
     * @param executor The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                 will be called on.
     * @param callback that will be completed on the Telecom side that details success or failure
     *                 of the requested operation.
     *
     *                 {@link OutcomeReceiver#onResult} will be called if Telecom has successfully
     *                 started the call streaming.
     *
     *                 {@link OutcomeReceiver#onError} will be called if Telecom has failed to
     *                 start the call streaming. A {@link CallException} will be passed that
     *                 details why the operation failed.
     */
    public void startCallStreaming(@CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mServerInterface.startCallStreaming(mCallId,
                    new CallControlResultReceiver("startCallStreaming", executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Request a CallEndpoint change. Clients should not define their own CallEndpoint when
     * requesting a change. Instead, the new endpoint should be one of the valid endpoints provided
     * by {@link CallEventCallback#onAvailableCallEndpointsChanged(List)}.
     *
     * @param callEndpoint The {@link CallEndpoint} to change to.
     * @param executor     The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                     will be called on.
     * @param callback     The {@link OutcomeReceiver} that will be completed on the Telecom side
     *                     that details success or failure of the requested operation.
     *
     *                     {@link OutcomeReceiver#onResult} will be called if Telecom has
     *                     successfully changed the CallEndpoint that was requested.
     *
     *                     {@link OutcomeReceiver#onError} will be called if Telecom has failed to
     *                     switch to the requested CallEndpoint.  A {@link CallException} will be
     *                     passed that details why the operation failed.
     */
    public void requestCallEndpointChange(@NonNull CallEndpoint callEndpoint,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        Objects.requireNonNull(callEndpoint);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mServerInterface.requestCallEndpointChange(callEndpoint,
                    new CallControlResultReceiver("requestCallEndpointChange", executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Request a new mute state.  Note: {@link CallEventCallback#onMuteStateChanged(boolean)}
     * will be called every time the mute state is changed and can be used to track the current
     * mute state.
     *
     * @param isMuted  The new mute state.  Passing in a {@link Boolean#TRUE} for the isMuted
     *                 parameter will mute the call.  {@link Boolean#FALSE} will unmute the call.
     * @param executor The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                 will be called on.
     * @param callback The {@link OutcomeReceiver} that will be completed on the Telecom side
     *                 that details success or failure of the requested operation.
     *
     *                 {@link OutcomeReceiver#onResult} will be called if Telecom has
     *                 successfully changed the mute state.
     *
     *                 {@link OutcomeReceiver#onError} will be called if Telecom has failed to
     *                 switch to the mute state.  A {@link CallException} will be
     *                 passed that details why the operation failed.
     */
    @FlaggedApi(Flags.FLAG_SET_MUTE_STATE)
    public void requestMuteState(boolean isMuted, @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mServerInterface.setMuteState(isMuted,
                    new CallControlResultReceiver("requestMuteState", executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

     /**
     * Request a new video state for the ongoing call. This can only be changed if the application
     * has registered a {@link PhoneAccount} with the
     * {@link PhoneAccount#CAPABILITY_SUPPORTS_VIDEO_CALLING} and set the
     * {@link CallAttributes#SUPPORTS_VIDEO_CALLING} when adding the call via
     * {@link TelecomManager#addCall(CallAttributes, Executor, OutcomeReceiver,
     *                                                      CallControlCallback, CallEventCallback)}
     *
     * @param videoState to report to Telecom. To see the valid argument to pass,
      *                   see {@link CallAttributes.CallType}.
     * @param executor   The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                   will be called on.
     * @param callback   that will be completed on the Telecom side that details success or failure
     *                   of the requested operation.
     *
     *                   {@link OutcomeReceiver#onResult} will be called if Telecom has successfully
     *                   switched the video state.
     *
     *                   {@link OutcomeReceiver#onError} will be called if Telecom has failed to set
     *                   the new video state.  A {@link CallException} will be passed
     *                   that details why the operation failed.
     * @throws IllegalArgumentException if the argument passed for videoState is invalid.  To see a
     * list of valid states, see {@link CallAttributes.CallType}.
     */
     @FlaggedApi(Flags.FLAG_TRANSACTIONAL_VIDEO_STATE)
     public void requestVideoState(@CallAttributes.CallType int videoState,
             @CallbackExecutor @NonNull Executor executor,
             @NonNull OutcomeReceiver<Void, CallException> callback) {
         validateVideoState(videoState);
         Objects.requireNonNull(executor);
         Objects.requireNonNull(callback);
         try {
             mServerInterface.requestVideoState(videoState, mCallId,
                     new CallControlResultReceiver("requestVideoState", executor, callback));
         } catch (RemoteException e) {
             throw e.rethrowAsRuntimeException();
         }
     }

    /**
     * Raises an event to the {@link android.telecom.InCallService} implementations tracking this
     * call via {@link android.telecom.Call.Callback#onConnectionEvent(Call, String, Bundle)}.
     * These events and the associated extra keys for the {@code Bundle} parameter are mutually
     * defined by a VoIP application and {@link android.telecom.InCallService}. This API is used to
     * relay additional information about a call other than what is specified in the
     * {@link android.telecom.CallAttributes} to {@link android.telecom.InCallService}s. This might
     * include, for example, a change to the list of participants in a meeting, or the name of the
     * speakers who have their hand raised. Where appropriate, the {@link InCallService}s tracking
     * this call may choose to render this additional information about the call. An automotive
     * calling UX, for example may have enough screen real estate to indicate the number of
     * participants in a meeting, but to prevent distractions could suppress the list of
     * participants.
     *
     * @param event a string event identifier agreed upon between a VoIP application and an
     *              {@link android.telecom.InCallService}
     * @param extras a {@link android.os.Bundle} containing information about the event, as agreed
     *              upon between a VoIP application and {@link android.telecom.InCallService}.
     */
    public void sendEvent(@NonNull String event, @NonNull Bundle extras) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(extras);
        try {
            mServerInterface.sendEvent(mCallId, event, extras);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Since {@link OutcomeReceiver}s cannot be passed via AIDL, a ResultReceiver (which can) must
     * wrap the Clients {@link OutcomeReceiver} passed in and await for the Telecom Server side
     * response in {@link ResultReceiver#onReceiveResult(int, Bundle)}.
     *
     * @hide
     */
    private class CallControlResultReceiver extends ResultReceiver {
        private final String mCallingMethod;
        private final Executor mExecutor;
        private final OutcomeReceiver<Void, CallException> mClientCallback;

        CallControlResultReceiver(String method, Executor executor,
                OutcomeReceiver<Void, CallException> clientCallback) {
            super(null);
            mCallingMethod = method;
            mExecutor = executor;
            mClientCallback = clientCallback;
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            Log.d(CallControl.TAG, "%s: oRR: resultCode=[%s]", mCallingMethod, resultCode);
            super.onReceiveResult(resultCode, resultData);
            final long identity = Binder.clearCallingIdentity();
            try {
                if (resultCode == TelecomManager.TELECOM_TRANSACTION_SUCCESS) {
                    mExecutor.execute(() -> mClientCallback.onResult(null));
                } else {
                    mExecutor.execute(() ->
                            mClientCallback.onError(getTransactionException(resultData)));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

    }

    /** @hide */
    private CallException getTransactionException(Bundle resultData) {
        String message = "unknown error";
        if (resultData != null && resultData.containsKey(TRANSACTION_EXCEPTION_KEY)) {
            return resultData.getParcelable(TRANSACTION_EXCEPTION_KEY,
                    CallException.class);
        }
        return new CallException(message, CallException.CODE_ERROR_UNKNOWN);
    }

    /** @hide */
    private void validateDisconnectCause(DisconnectCause disconnectCause) {
        final int code = disconnectCause.getCode();
        if (code != DisconnectCause.LOCAL && code != DisconnectCause.REMOTE
                && code != DisconnectCause.MISSED && code != DisconnectCause.REJECTED) {
            throw new IllegalArgumentException(TextUtils.formatSimple(
                    "The DisconnectCause code provided, %d , is not a valid Disconnect code. Valid "
                            + "DisconnectCause codes are limited to [DisconnectCause.LOCAL, "
                            + "DisconnectCause.REMOTE, DisconnectCause.MISSED, or "
                            + "DisconnectCause.REJECTED]", disconnectCause.getCode()));
        }
    }

    /** @hide */
    private void validateVideoState(@android.telecom.CallAttributes.CallType int videoState) {
        if (videoState != CallAttributes.AUDIO_CALL && videoState != CallAttributes.VIDEO_CALL) {
            throw new IllegalArgumentException(TextUtils.formatSimple(
                    "The VideoState argument passed in, %d , is not a valid VideoState. The "
                            + "VideoState choices are limited to CallAttributes.AUDIO_CALL or"
                            + "CallAttributes.VIDEO_CALL", videoState));
        }
    }
}
