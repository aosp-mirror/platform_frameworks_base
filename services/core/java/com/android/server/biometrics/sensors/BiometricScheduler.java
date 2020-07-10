/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityTracker;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

/**
 * A scheduler for biometric HAL operations. Maintains a queue of {@link ClientMonitor} operations,
 * without caring about its implementation details. Operations may perform one or more
 * interactions with the HAL before finishing.
 */
public class BiometricScheduler {

    private static final String BASE_TAG = "BiometricScheduler";

    /**
     * Contains all the necessary information for a HAL operation.
     */
    private static final class Operation {

        /**
         * The operation is added to the list of pending operations and waiting for its turn.
         */
        static final int STATE_WAITING_IN_QUEUE = 0;

        /**
         * The operation is added to the list of pending operations, but a subsequent operation
         * has been added. This state only applies to {@link Interruptable} operations. When this
         * operation reaches the head of the queue, it will send ERROR_CANCELED and finish.
         */
        static final int STATE_WAITING_IN_QUEUE_CANCELING = 1;

        /**
         * The operation has reached the front of the queue and has started.
         */
        static final int STATE_STARTED = 2;

        /**
         * The operation was started, but is now canceling. Operations should wait for the HAL to
         * acknowledge that the operation was canceled, at which point it finishes.
         */
        static final int STATE_STARTED_CANCELING = 3;

        /**
         * The operation has reached the head of the queue but is waiting for BiometricService
         * to acknowledge and start the operation.
         */
        static final int STATE_WAITING_FOR_COOKIE = 4;

        /**
         * The {@link ClientMonitor.FinishCallback} has been invoked and the client is finished.
         */
        static final int STATE_FINISHED = 5;

        @IntDef({STATE_WAITING_IN_QUEUE,
                STATE_WAITING_IN_QUEUE_CANCELING,
                STATE_STARTED,
                STATE_STARTED_CANCELING,
                STATE_WAITING_FOR_COOKIE,
                STATE_FINISHED})
        @Retention(RetentionPolicy.SOURCE)
        @interface OperationState {}

        @NonNull final ClientMonitor<?> clientMonitor;
        @Nullable final ClientMonitor.FinishCallback clientFinishCallback;
        @OperationState int state;

        Operation(@NonNull ClientMonitor<?> clientMonitor,
                @Nullable ClientMonitor.FinishCallback finishCallback) {
            this.clientMonitor = clientMonitor;
            this.clientFinishCallback = finishCallback;
            state = STATE_WAITING_IN_QUEUE;
        }

        @Override
        public String toString() {
            return clientMonitor + ", State: " + state;
        }
    }

    /**
     * Monitors an operation's cancellation. If cancellation takes too long, the watchdog will
     * kill the current operation and forcibly start the next.
     */
    private static final class CancellationWatchdog implements Runnable {
        static final int DELAY_MS = 3000;

        final String tag;
        final Operation operation;
        CancellationWatchdog(String tag, Operation operation) {
            this.tag = tag;
            this.operation = operation;
        }

        @Override
        public void run() {
            if (operation.state != Operation.STATE_FINISHED) {
                Slog.e(tag, "[Watchdog Triggered]: " + operation);
                operation.clientMonitor.mFinishCallback
                        .onClientFinished(operation.clientMonitor, false /* success */);
            }
        }
    }

    private static final class CrashState {
        static final int NUM_ENTRIES = 10;
        final String timestamp;
        final String currentOperation;
        final List<String> pendingOperations;

        CrashState(String timestamp, String currentOperation, List<String> pendingOperations) {
            this.timestamp = timestamp;
            this.currentOperation = currentOperation;
            this.pendingOperations = pendingOperations;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append(timestamp).append(": ");
            sb.append("Current Operation: {").append(currentOperation).append("}");
            sb.append(", Pending Operations(").append(pendingOperations.size()).append(")");

            if (!pendingOperations.isEmpty()) {
                sb.append(": ");
            }
            for (int i = 0; i < pendingOperations.size(); i++) {
                sb.append(pendingOperations.get(i));
                if (i < pendingOperations.size() - 1) {
                    sb.append(", ");
                }
            }
            return sb.toString();
        }
    }

    @NonNull private final String mBiometricTag;
    @Nullable private final GestureAvailabilityTracker mGestureAvailabilityTracker;
    @NonNull private final IBiometricService mBiometricService;
    @NonNull private final Handler mHandler = new Handler(Looper.getMainLooper());
    @NonNull private final InternalFinishCallback mInternalFinishCallback;
    @NonNull private final Queue<Operation> mPendingOperations;
    @Nullable private Operation mCurrentOperation;
    @NonNull private final ArrayDeque<CrashState> mCrashStates;

    // Internal finish callback, notified when an operation is complete. Notifies the requester
    // that the operation is complete, before performing internal scheduler work (such as
    // starting the next client).
    private class InternalFinishCallback implements ClientMonitor.FinishCallback {
        @Override
        public void onClientFinished(ClientMonitor<?> clientMonitor, boolean success) {
            mHandler.post(() -> {
                if (mCurrentOperation == null) {
                    Slog.e(getTag(), "[Finishing] " + clientMonitor
                            + " but current operation is null, success: " + success
                            + ", possible lifecycle bug in clientMonitor implementation?");
                    return;
                }

                mCurrentOperation.state = Operation.STATE_FINISHED;

                if (mCurrentOperation.clientFinishCallback != null) {
                    mCurrentOperation.clientFinishCallback.onClientFinished(clientMonitor, success);
                }

                if (clientMonitor != mCurrentOperation.clientMonitor) {
                    throw new IllegalStateException("Mismatched operation, "
                            + " current: " + mCurrentOperation.clientMonitor
                            + " received: " + clientMonitor);
                }

                Slog.d(getTag(), "[Finished] " + clientMonitor + ", success: " + success);
                if (mGestureAvailabilityTracker != null) {
                    mGestureAvailabilityTracker.markSensorActive(
                            mCurrentOperation.clientMonitor.getSensorId(), false /* active */);
                }

                mCurrentOperation = null;
                startNextOperationIfIdle();
            });
        }
    }

    /**
     * Creates a new scheduler.
     * @param tag for the specific instance of the scheduler. Should be unique.
     * @param gestureAvailabilityTracker may be null if the sensor does not support gestures (such
     *                                   as fingerprint swipe).
     */
    public BiometricScheduler(@NonNull String tag,
            @Nullable GestureAvailabilityTracker gestureAvailabilityTracker) {
        mBiometricTag = tag;
        mInternalFinishCallback = new InternalFinishCallback();
        mGestureAvailabilityTracker = gestureAvailabilityTracker;
        mPendingOperations = new ArrayDeque<>();
        mBiometricService = IBiometricService.Stub.asInterface(
                ServiceManager.getService(Context.BIOMETRIC_SERVICE));
        mCrashStates = new ArrayDeque<>();
    }

    private String getTag() {
        return BASE_TAG + "/" + mBiometricTag;
    }

    private void startNextOperationIfIdle() {
        if (mCurrentOperation != null) {
            Slog.v(getTag(), "Not idle, current operation: " + mCurrentOperation);
            return;
        }
        if (mPendingOperations.isEmpty()) {
            Slog.d(getTag(), "No operations, returning to idle");
            return;
        }

        mCurrentOperation = mPendingOperations.poll();
        final ClientMonitor<?> currentClient = mCurrentOperation.clientMonitor;

        // If the operation at the front of the queue has been marked for cancellation, send
        // ERROR_CANCELED. No need to start this client.
        if (mCurrentOperation.state == Operation.STATE_WAITING_IN_QUEUE_CANCELING) {
            Slog.d(getTag(), "[Now Cancelling] " + mCurrentOperation);
            if (!(currentClient instanceof Interruptable)) {
                throw new IllegalStateException("Mis-implemented client or scheduler, "
                        + "trying to cancel non-interruptable operation: " + mCurrentOperation);
            }

            final Interruptable interruptable = (Interruptable) currentClient;
            interruptable.cancelWithoutStarting(mInternalFinishCallback);
            // Now we wait for the client to send its FinishCallback, which kicks off the next
            // operation.
            return;
        }

        if (mGestureAvailabilityTracker != null
                && mCurrentOperation.clientMonitor instanceof AcquisitionClient) {
            mGestureAvailabilityTracker.markSensorActive(
                    mCurrentOperation.clientMonitor.getSensorId(),
                    true /* active */);
        }

        // Not all operations start immediately. BiometricPrompt waits for its operation
        // to arrive at the head of the queue, before pinging it to start.
        final boolean shouldStartNow = currentClient.getCookie() == 0;
        if (shouldStartNow) {
            Slog.d(getTag(), "[Starting] " + mCurrentOperation);
            currentClient.start(mInternalFinishCallback);
            mCurrentOperation.state = Operation.STATE_STARTED;
        } else {
            try {
                mBiometricService.onReadyForAuthentication(currentClient.getCookie());
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when contacting BiometricService", e);
            }
            Slog.d(getTag(), "Waiting for cookie before starting: " + mCurrentOperation);
            mCurrentOperation.state = Operation.STATE_WAITING_FOR_COOKIE;
        }
    }

    /**
     * Starts the {@link #mCurrentOperation} if
     * 1) its state is {@link Operation#STATE_WAITING_FOR_COOKIE} and
     * 2) its cookie matches this cookie
     *
     * This is currently only used by {@link com.android.server.biometrics.BiometricService}, which
     * requests sensors to prepare for authentication with a cookie. Once sensor(s) are ready (e.g.
     * the BiometricService client becomes the current client in the scheduler), the cookie is
     * returned to BiometricService. Once BiometricService decides that authentication can start,
     * it invokes this code path.
     *
     * @param cookie of the operation to be started
     */
    public void startPreparedClient(int cookie) {
        if (mCurrentOperation == null) {
            Slog.e(getTag(), "Current operation is null");
            return;
        }
        if (mCurrentOperation.state != Operation.STATE_WAITING_FOR_COOKIE) {
            Slog.e(getTag(), "Operation is in the wrong state: " + mCurrentOperation
                    + ", expected STATE_WAITING_FOR_COOKIE");
            return;
        }
        if (mCurrentOperation.clientMonitor.getCookie() != cookie) {
            Slog.e(getTag(), "Mismatched cookie for operation: " + mCurrentOperation
                    + ", received: " + cookie);
            return;
        }

        Slog.d(getTag(), "[Starting] Prepared client: " + mCurrentOperation);
        mCurrentOperation.state = Operation.STATE_STARTED;
        mCurrentOperation.clientMonitor.start(mInternalFinishCallback);
    }

    /**
     * Adds a {@link ClientMonitor} to the pending queue
     *
     * @param clientMonitor operation to be scheduled
     */
    public void scheduleClientMonitor(@NonNull ClientMonitor<?> clientMonitor) {
        scheduleClientMonitor(clientMonitor, null /* clientFinishCallback */);
    }

    /**
     * Adds a {@link ClientMonitor} to the pending queue
     *
     * @param clientMonitor        operation to be scheduled
     * @param clientFinishCallback optional callback, invoked when the client is finished, but
     *                             before it has been removed from the queue.
     */
    public void scheduleClientMonitor(@NonNull ClientMonitor<?> clientMonitor,
            @Nullable ClientMonitor.FinishCallback clientFinishCallback) {
        // Mark any interruptable pending clients as canceling. Once they reach the head of the
        // queue, the scheduler will send ERROR_CANCELED and skip the operation.
        for (Operation operation : mPendingOperations) {
            if (operation.clientMonitor instanceof Interruptable
                    && operation.state != Operation.STATE_WAITING_IN_QUEUE_CANCELING) {
                Slog.d(getTag(), "New client incoming, marking pending client as canceling: "
                        + operation.clientMonitor);
                operation.state = Operation.STATE_WAITING_IN_QUEUE_CANCELING;
            }
        }

        mPendingOperations.add(new Operation(clientMonitor, clientFinishCallback));
        Slog.d(getTag(), "[Added] " + clientMonitor
                + ", new queue size: " + mPendingOperations.size());

        // If the current operation is cancellable, start the cancellation process.
        if (mCurrentOperation != null && mCurrentOperation.clientMonitor instanceof Interruptable
                && mCurrentOperation.state == Operation.STATE_STARTED) {
            Slog.d(getTag(), "[Cancelling Interruptable]: " + mCurrentOperation);
            cancelInternal(mCurrentOperation);
        }

        startNextOperationIfIdle();
    }

    private void cancelInternal(Operation operation) {
        if (operation != mCurrentOperation) {
            Slog.e(getTag(), "cancelInternal invoked on non-current operation: " + operation);
            return;
        }
        if (!(operation.clientMonitor instanceof Interruptable)) {
            Slog.w(getTag(), "Operation not interruptable: " + operation);
            return;
        }
        if (operation.state == Operation.STATE_STARTED_CANCELING) {
            Slog.w(getTag(), "Cancel already invoked for operation: " + operation);
            return;
        }
        Slog.d(getTag(), "[Cancelling] Current client: " + operation.clientMonitor);
        final Interruptable interruptable = (Interruptable) operation.clientMonitor;
        interruptable.cancel();
        operation.state = Operation.STATE_STARTED_CANCELING;

        // Add a watchdog. If the HAL does not acknowledge within the timeout, we will
        // forcibly finish this client.
        mHandler.postDelayed(new CancellationWatchdog(getTag(), operation),
                CancellationWatchdog.DELAY_MS);
    }

    /**
     * Requests to cancel enrollment.
     * @param token from the caller, should match the token passed in when requesting enrollment
     */
    public void cancelEnrollment(IBinder token) {
        if (mCurrentOperation == null) {
            Slog.e(getTag(), "Unable to cancel enrollment, null operation");
            return;
        }
        final boolean isEnrolling = mCurrentOperation.clientMonitor instanceof EnrollClient;
        final boolean tokenMatches = mCurrentOperation.clientMonitor.getToken() == token;
        if (!isEnrolling || !tokenMatches) {
            Slog.w(getTag(), "Not cancelling enrollment, isEnrolling: " + isEnrolling
                    + " tokenMatches: " + tokenMatches);
            return;
        }

        cancelInternal(mCurrentOperation);
    }

    /**
     * Requests to cancel authentication.
     * @param token from the caller, should match the token passed in when requesting authentication
     */
    public void cancelAuthentication(IBinder token) {
        if (mCurrentOperation == null) {
            Slog.e(getTag(), "Unable to cancel authentication, null operation");
            return;
        }
        final boolean isAuthenticating =
                mCurrentOperation.clientMonitor instanceof AuthenticationClient;
        final boolean tokenMatches = mCurrentOperation.clientMonitor.getToken() == token;
        if (!isAuthenticating || !tokenMatches) {
            Slog.w(getTag(), "Not cancelling authentication, isEnrolling: " + isAuthenticating
                    + " tokenMatches: " + tokenMatches);
            return;
        }

        cancelInternal(mCurrentOperation);
    }

    /**
     * @return the current operation
     */
    public ClientMonitor<?> getCurrentClient() {
        if (mCurrentOperation == null) {
            return null;
        }
        return mCurrentOperation.clientMonitor;
    }

    public void recordCrashState() {
        if (mCrashStates.size() >= CrashState.NUM_ENTRIES) {
            mCrashStates.removeFirst();
        }
        final SimpleDateFormat dateFormat =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        final String timestamp = dateFormat.format(new Date(System.currentTimeMillis()));
        final List<String> pendingOperations = new ArrayList<>();
        for (Operation operation : mPendingOperations) {
            pendingOperations.add(operation.toString());
        }

        final CrashState crashState = new CrashState(timestamp,
                mCurrentOperation != null ? mCurrentOperation.toString() : null,
                pendingOperations);
        mCrashStates.add(crashState);
        Slog.e(getTag(), "Recorded crash state: " + crashState.toString());
    }

    public void dump(PrintWriter pw) {
        pw.println("Dump of BiometricScheduler " + getTag());
        for (CrashState crashState : mCrashStates) {
            pw.println("Crash State " + crashState);
        }
    }
}
