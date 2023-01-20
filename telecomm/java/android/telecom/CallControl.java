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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ResultReceiver;

import com.android.internal.telecom.ClientTransactionalServiceRepository;
import com.android.internal.telecom.ICallControl;

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
    private static final String INTERFACE_ERROR_MSG = "Call Control is not available";
    private final String mCallId;
    private final ICallControl mServerInterface;
    private final PhoneAccountHandle mPhoneAccountHandle;
    private final ClientTransactionalServiceRepository mRepository;

    /** @hide */
    public CallControl(@NonNull String callId, @Nullable ICallControl serverInterface,
            @NonNull ClientTransactionalServiceRepository repository,
            @NonNull PhoneAccountHandle pah) {
        mCallId = callId;
        mServerInterface = serverInterface;
        mRepository = repository;
        mPhoneAccountHandle = pah;
    }

    /**
     * @return the callId Telecom assigned to this CallControl object which should be attached to
     *  an individual call.
     */
    @NonNull
    public ParcelUuid getCallId() {
        return ParcelUuid.fromString(mCallId);
    }

    /**
     * Request Telecom set the call state to active.
     *
     * @param executor The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                will be called on.
     * @param callback that will be completed on the Telecom side that details success or failure
     *                of the requested operation.
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
        if (mServerInterface != null) {
            try {
                mServerInterface.setActive(mCallId,
                        new CallControlResultReceiver("setActive", executor, callback));

            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        } else {
            throw new IllegalStateException(INTERFACE_ERROR_MSG);
        }
    }

    /**
     * Request Telecom set the call state to inactive. This the same as hold for two call endpoints
     * but can be extended to setting a meeting to inactive.
     *
     * @param executor The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                will be called on.
     * @param callback that will be completed on the Telecom side that details success or failure
     *                of the requested operation.
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
        if (mServerInterface != null) {
            try {
                mServerInterface.setInactive(mCallId,
                        new CallControlResultReceiver("setInactive", executor, callback));

            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        } else {
            throw new IllegalStateException(INTERFACE_ERROR_MSG);
        }
    }

    /**
     * Request Telecom set the call state to disconnect.
     *
     * @param executor The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                will be called on.
     * @param callback that will be completed on the Telecom side that details success or failure
     *                of the requested operation.
     *
     *                 {@link OutcomeReceiver#onResult} will be called if Telecom has successfully
     *                 disconnected the call.
     *
     *                 {@link OutcomeReceiver#onError} will be called if Telecom has failed to
     *                 disconnect the call.  A {@link CallException} will be passed
     *                 that details why the operation failed.
     */
    public void disconnect(@NonNull DisconnectCause disconnectCause,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        if (mServerInterface != null) {
            try {
                mServerInterface.disconnect(mCallId, disconnectCause,
                        new CallControlResultReceiver("disconnect", executor, callback));
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        } else {
            throw new IllegalStateException(INTERFACE_ERROR_MSG);
        }
    }

    /**
     * Request Telecom reject the incoming call.
     *
     * @param executor The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                will be called on.
     * @param callback that will be completed on the Telecom side that details success or failure
     *                of the requested operation.
     *
     *                 {@link OutcomeReceiver#onResult} will be called if Telecom has successfully
     *                 rejected the incoming call.
     *
     *                 {@link OutcomeReceiver#onError} will be called if Telecom has failed to
     *                 reject the incoming call.  A {@link CallException} will be passed
     *                 that details why the operation failed.
     */
    public void rejectCall(@CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        if (mServerInterface != null) {
            try {
                mServerInterface.rejectCall(mCallId,
                        new CallControlResultReceiver("rejectCall", executor, callback));
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        } else {
            throw new IllegalStateException(INTERFACE_ERROR_MSG);
        }
    }

    /**
     * Request start a call streaming session. On receiving valid request, telecom will bind to
     * the {@link CallStreamingService} implemented by a general call streaming sender. So that the
     * call streaming sender can perform streaming local device audio to another remote device and
     * control the call during streaming.
     *
     * @param executor The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                 will be called on.
     * @param callback that will be completed on the Telecom side that details success or failure
     *                 of the requested operation.
     *
     *                 {@link OutcomeReceiver#onResult} will be called if Telecom has successfully
     *                 rejected the incoming call.
     *
     *                 {@link OutcomeReceiver#onError} will be called if Telecom has failed to
     *                 reject the incoming call.  A {@link CallException} will be passed that
     *                 details why the operation failed.
     */
    public void startCallStreaming(@CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, CallException> callback) {
        if (mServerInterface != null) {
            try {
                mServerInterface.startCallStreaming(mCallId,
                        new CallControlResultReceiver("startCallStreaming", executor, callback));
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        } else {
            throw new IllegalStateException(INTERFACE_ERROR_MSG);
        }
    }

    /**
     * Request a CallEndpoint change. Clients should not define their own CallEndpoint when
     * requesting a change. Instead, the new endpoint should be one of the valid endpoints provided
     * by {@link CallEventCallback#onAvailableCallEndpointsChanged(List)}.
     *
     * @param callEndpoint ; The {@link CallEndpoint} to change to.
     * @param executor     ; The {@link Executor} on which the {@link OutcomeReceiver} callback
     *                     will be called on.
     * @param callback     ; The {@link OutcomeReceiver} that will be completed on the Telecom side
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
        if (mServerInterface != null) {
            try {
                mServerInterface.requestCallEndpointChange(callEndpoint,
                        new CallControlResultReceiver("endpointChange", executor, callback));
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        } else {
            throw new IllegalStateException(INTERFACE_ERROR_MSG);
        }
    }

    /**
     * Since {@link OutcomeReceiver}s cannot be passed via AIDL, a ResultReceiver (which can) must
     * wrap the Clients {@link OutcomeReceiver} passed in and await for the Telecom Server side
     * response in {@link ResultReceiver#onReceiveResult(int, Bundle)}.
     * @hide */
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
}
