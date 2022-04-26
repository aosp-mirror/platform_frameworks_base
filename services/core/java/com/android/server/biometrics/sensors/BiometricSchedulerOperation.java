/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.BiometricConstants;
import android.os.Handler;
import android.os.IBinder;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contains all the necessary information for a HAL operation.
 */
public class BiometricSchedulerOperation {
    protected static final String TAG = "BiometricSchedulerOperation";

    /**
     * The operation is added to the list of pending operations and waiting for its turn.
     */
    protected static final int STATE_WAITING_IN_QUEUE = 0;

    /**
     * The operation is added to the list of pending operations, but a subsequent operation
     * has been added. This state only applies to {@link Interruptable} operations. When this
     * operation reaches the head of the queue, it will send ERROR_CANCELED and finish.
     */
    protected static final int STATE_WAITING_IN_QUEUE_CANCELING = 1;

    /**
     * The operation has reached the front of the queue and has started.
     */
    protected static final int STATE_STARTED = 2;

    /**
     * The operation was started, but is now canceling. Operations should wait for the HAL to
     * acknowledge that the operation was canceled, at which point it finishes.
     */
    protected static final int STATE_STARTED_CANCELING = 3;

    /**
     * The operation has reached the head of the queue but is waiting for BiometricService
     * to acknowledge and start the operation.
     */
    protected static final int STATE_WAITING_FOR_COOKIE = 4;

    /**
     * The {@link BaseClientMonitor.Callback} has been invoked and the client is finished.
     */
    protected static final int STATE_FINISHED = 5;

    @IntDef({STATE_WAITING_IN_QUEUE,
            STATE_WAITING_IN_QUEUE_CANCELING,
            STATE_STARTED,
            STATE_STARTED_CANCELING,
            STATE_WAITING_FOR_COOKIE,
            STATE_FINISHED})
    @Retention(RetentionPolicy.SOURCE)
    protected @interface OperationState {}

    private static final int CANCEL_WATCHDOG_DELAY_MS = 3000;

    @NonNull
    private final BaseClientMonitor mClientMonitor;
    @Nullable
    private final BaseClientMonitor.Callback mClientCallback;
    @OperationState
    private int mState;
    @VisibleForTesting
    @NonNull
    final Runnable mCancelWatchdog;

    BiometricSchedulerOperation(
            @NonNull BaseClientMonitor clientMonitor,
            @Nullable BaseClientMonitor.Callback callback
    ) {
        this(clientMonitor, callback, STATE_WAITING_IN_QUEUE);
    }

    protected BiometricSchedulerOperation(
            @NonNull BaseClientMonitor clientMonitor,
            @Nullable BaseClientMonitor.Callback callback,
            @OperationState int state
    ) {
        mClientMonitor = clientMonitor;
        mClientCallback = callback;
        mState = state;
        mCancelWatchdog = () -> {
            if (!isFinished()) {
                Slog.e(TAG, "[Watchdog Triggered]: " + this);
                getWrappedCallback().onClientFinished(mClientMonitor, false /* success */);
            }
        };
    }

    /**
     * Zero if this operation is ready to start or has already started. A non-zero cookie
     * is returned if the operation has not started and is waiting on
     * {@link android.hardware.biometrics.IBiometricService#onReadyForAuthentication(int)}.
     *
     * @return cookie or 0 if ready/started
     */
    public int isReadyToStart() {
        if (mState == STATE_WAITING_FOR_COOKIE || mState == STATE_WAITING_IN_QUEUE) {
            final int cookie = mClientMonitor.getCookie();
            if (cookie != 0) {
                mState = STATE_WAITING_FOR_COOKIE;
            }
            return cookie;
        }

        return 0;
    }

    /**
     * Start this operation without waiting for a cookie
     * (i.e. {@link #isReadyToStart() returns zero}
     *
     * @param callback lifecycle callback
     * @return if this operation started
     */
    public boolean start(@NonNull BaseClientMonitor.Callback callback) {
        checkInState("start",
                STATE_WAITING_IN_QUEUE,
                STATE_WAITING_FOR_COOKIE,
                STATE_WAITING_IN_QUEUE_CANCELING);

        if (mClientMonitor.getCookie() != 0) {
            throw new IllegalStateException("operation requires cookie");
        }

        return doStart(callback);
    }

    /**
     * Start this operation after receiving the given cookie.
     *
     * @param callback lifecycle callback
     * @param cookie   cookie indicting the operation should begin
     * @return if this operation started
     */
    public boolean startWithCookie(@NonNull BaseClientMonitor.Callback callback, int cookie) {
        checkInState("start",
                STATE_WAITING_IN_QUEUE,
                STATE_WAITING_FOR_COOKIE,
                STATE_WAITING_IN_QUEUE_CANCELING);

        if (mClientMonitor.getCookie() != cookie) {
            Slog.e(TAG, "Mismatched cookie for operation: " + this + ", received: " + cookie);
            return false;
        }

        return doStart(callback);
    }

    private boolean doStart(@NonNull BaseClientMonitor.Callback callback) {
        final BaseClientMonitor.Callback cb = getWrappedCallback(callback);

        if (mState == STATE_WAITING_IN_QUEUE_CANCELING) {
            Slog.d(TAG, "Operation marked for cancellation, cancelling now: " + this);

            cb.onClientFinished(mClientMonitor, true /* success */);
            if (mClientMonitor instanceof ErrorConsumer) {
                final ErrorConsumer errorConsumer = (ErrorConsumer) mClientMonitor;
                errorConsumer.onError(BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                        0 /* vendorCode */);
            } else {
                Slog.w(TAG, "monitor cancelled but does not implement ErrorConsumer");
            }

            return false;
        }

        if (isUnstartableHalOperation()) {
            Slog.v(TAG, "unable to start: " + this);
            ((HalClientMonitor<?>) mClientMonitor).unableToStart();
            cb.onClientFinished(mClientMonitor, false /* success */);
            return false;
        }

        mState = STATE_STARTED;
        mClientMonitor.start(cb);

        Slog.v(TAG, "started: " + this);
        return true;
    }

    /**
     * Abort a pending operation.
     *
     * This is similar to cancel but the operation must not have been started. It will
     * immediately abort the operation and notify the client that it has finished unsuccessfully.
     */
    public void abort() {
        checkInState("cannot abort a non-pending operation",
                STATE_WAITING_IN_QUEUE,
                STATE_WAITING_FOR_COOKIE,
                STATE_WAITING_IN_QUEUE_CANCELING);

        if (isHalOperation()) {
            ((HalClientMonitor<?>) mClientMonitor).unableToStart();
        }
        getWrappedCallback().onClientFinished(mClientMonitor, false /* success */);

        Slog.v(TAG, "Aborted: " + this);
    }

    /** Flags this operation as canceled, if possible, but does not cancel it until started. */
    public boolean markCanceling() {
        if (mState == STATE_WAITING_IN_QUEUE && isInterruptable()) {
            mState = STATE_WAITING_IN_QUEUE_CANCELING;
            return true;
        }
        return false;
    }

    /**
     * Cancel the operation now.
     *
     * @param handler handler to use for the cancellation watchdog
     * @param callback lifecycle callback (only used if this operation hasn't started, otherwise
     *                 the callback used from {@link #start(BaseClientMonitor.Callback)} is used)
     */
    public void cancel(@NonNull Handler handler, @NonNull BaseClientMonitor.Callback callback) {
        checkNotInState("cancel", STATE_FINISHED);

        final int currentState = mState;
        if (!isInterruptable()) {
            Slog.w(TAG, "Cannot cancel - operation not interruptable: " + this);
            return;
        }
        if (currentState == STATE_STARTED_CANCELING) {
            Slog.w(TAG, "Cannot cancel - already invoked for operation: " + this);
            return;
        }

        mState = STATE_STARTED_CANCELING;
        if (currentState == STATE_WAITING_IN_QUEUE
                || currentState == STATE_WAITING_IN_QUEUE_CANCELING
                || currentState == STATE_WAITING_FOR_COOKIE) {
            Slog.d(TAG, "[Cancelling] Current client (without start): " + mClientMonitor);
            ((Interruptable) mClientMonitor).cancelWithoutStarting(getWrappedCallback(callback));
        } else {
            Slog.d(TAG, "[Cancelling] Current client: " + mClientMonitor);
            ((Interruptable) mClientMonitor).cancel();
        }

        // forcibly finish this client if the HAL does not acknowledge within the timeout
        handler.postDelayed(mCancelWatchdog, CANCEL_WATCHDOG_DELAY_MS);
    }

    @NonNull
    private BaseClientMonitor.Callback getWrappedCallback() {
        return getWrappedCallback(null);
    }

    @NonNull
    private BaseClientMonitor.Callback getWrappedCallback(
            @Nullable BaseClientMonitor.Callback callback) {
        final BaseClientMonitor.Callback destroyCallback = new BaseClientMonitor.Callback() {
            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                Slog.d(TAG, "[Finished / destroy]: " + clientMonitor);
                mClientMonitor.destroy();
                mState = STATE_FINISHED;
            }
        };
        return new BaseClientMonitor.CompositeCallback(destroyCallback, callback, mClientCallback);
    }

    /** {@link BaseClientMonitor#getSensorId()}. */
    public int getSensorId() {
        return mClientMonitor.getSensorId();
    }

    /** {@link BaseClientMonitor#getProtoEnum()}. */
    public int getProtoEnum() {
        return mClientMonitor.getProtoEnum();
    }

    /** {@link BaseClientMonitor#getTargetUserId()}. */
    public int getTargetUserId() {
        return mClientMonitor.getTargetUserId();
    }

    /** If the given clientMonitor is the same as the one in the constructor. */
    public boolean isFor(@NonNull BaseClientMonitor clientMonitor) {
        return mClientMonitor == clientMonitor;
    }

    /** If this operation is {@link Interruptable}. */
    public boolean isInterruptable() {
        return mClientMonitor instanceof Interruptable;
    }

    private boolean isHalOperation() {
        return mClientMonitor instanceof HalClientMonitor<?>;
    }

    private boolean isUnstartableHalOperation() {
        if (isHalOperation()) {
            final HalClientMonitor<?> client = (HalClientMonitor<?>) mClientMonitor;
            if (client.getFreshDaemon() == null) {
                return true;
            }
        }
        return false;
    }

    /** If this operation is an enrollment. */
    public boolean isEnrollOperation() {
        return mClientMonitor instanceof EnrollClient;
    }

    /** If this operation is authentication. */
    public boolean isAuthenticateOperation() {
        return mClientMonitor instanceof AuthenticationClient;
    }

    /** If this operation is authentication or detection. */
    public boolean isAuthenticationOrDetectionOperation() {
        final boolean isAuthentication = mClientMonitor instanceof AuthenticationConsumer;
        final boolean isDetection = mClientMonitor instanceof DetectionConsumer;
        return isAuthentication || isDetection;
    }

    /** If this operation performs acquisition {@link AcquisitionClient}. */
    public boolean isAcquisitionOperation() {
        return mClientMonitor instanceof AcquisitionClient;
    }

    /**
     * If this operation matches the original requestId.
     *
     * By default, monitors are not associated with a request id to retain the original
     * behavior (i.e. if no requestId is explicitly set then assume it matches)
     *
     * @param requestId a unique id {@link BaseClientMonitor#setRequestId(long)}.
     */
    public boolean isMatchingRequestId(long requestId) {
        return !mClientMonitor.hasRequestId()
                || mClientMonitor.getRequestId() == requestId;
    }

    /** If the token matches */
    public boolean isMatchingToken(@Nullable IBinder token) {
        return mClientMonitor.getToken() == token;
    }

    /** If this operation has started. */
    public boolean isStarted() {
        return mState == STATE_STARTED;
    }

    /** If this operation is cancelling but has not yet completed. */
    public boolean isCanceling() {
        return mState == STATE_STARTED_CANCELING;
    }

    /** If this operation has finished and completed its lifecycle. */
    public boolean isFinished() {
        return mState == STATE_FINISHED;
    }

    /** If {@link #markCanceling()} was called but the operation hasn't been canceled. */
    public boolean isMarkedCanceling() {
        return mState == STATE_WAITING_IN_QUEUE_CANCELING;
    }

    /**
     * The monitor passed to the constructor.
     * @deprecated avoid using and move to encapsulate within the operation
     */
    @Deprecated
    public BaseClientMonitor getClientMonitor() {
        return mClientMonitor;
    }

    private void checkNotInState(String message, @OperationState int... states) {
        for (int state : states) {
            if (mState == state) {
                throw new IllegalStateException(message + ": illegal state= " + state);
            }
        }
    }

    private void checkInState(String message, @OperationState int... states) {
        for (int state : states) {
            if (mState == state) {
                return;
            }
        }
        throw new IllegalStateException(message + ": illegal state= " + mState);
    }

    @Override
    public String toString() {
        return mClientMonitor + ", State: " + mState;
    }
}
