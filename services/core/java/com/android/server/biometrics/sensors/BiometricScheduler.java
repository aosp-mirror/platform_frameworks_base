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
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.IBiometricService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.BiometricSchedulerProto;
import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A scheduler for biometric HAL operations. Maintains a queue of {@link BaseClientMonitor}
 * operations, without caring about its implementation details. Operations may perform one or more
 * interactions with the HAL before finishing.
 */
public class BiometricScheduler {

    private static final String BASE_TAG = "BiometricScheduler";
    // Number of recent operations to keep in our logs for dumpsys
    protected static final int LOG_NUM_RECENT_OPERATIONS = 50;

    /**
     * Contains all the necessary information for a HAL operation.
     */
    @VisibleForTesting
    static final class Operation {

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
         * The {@link BaseClientMonitor.Callback} has been invoked and the client is finished.
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

        @NonNull final BaseClientMonitor mClientMonitor;
        @Nullable final BaseClientMonitor.Callback mClientCallback;
        @OperationState int mState;

        Operation(@NonNull BaseClientMonitor clientMonitor,
                @Nullable BaseClientMonitor.Callback callback) {
            this.mClientMonitor = clientMonitor;
            this.mClientCallback = callback;
            mState = STATE_WAITING_IN_QUEUE;
        }

        public boolean isHalOperation() {
            return mClientMonitor instanceof HalClientMonitor<?>;
        }

        /**
         * @return true if the operation requires the HAL, and the HAL is null.
         */
        public boolean isUnstartableHalOperation() {
            if (isHalOperation()) {
                final HalClientMonitor<?> client = (HalClientMonitor<?>) mClientMonitor;
                if (client.getFreshDaemon() == null) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return mClientMonitor + ", State: " + mState;
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
            if (operation.mState != Operation.STATE_FINISHED) {
                Slog.e(tag, "[Watchdog Triggered]: " + operation);
                operation.mClientMonitor.mCallback
                        .onClientFinished(operation.mClientMonitor, false /* success */);
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

    @NonNull protected final String mBiometricTag;
    @Nullable private final GestureAvailabilityDispatcher mGestureAvailabilityDispatcher;
    @NonNull private final IBiometricService mBiometricService;
    @NonNull protected final Handler mHandler = new Handler(Looper.getMainLooper());
    @NonNull private final InternalCallback mInternalCallback;
    @VisibleForTesting @NonNull final Deque<Operation> mPendingOperations;
    @VisibleForTesting @Nullable Operation mCurrentOperation;
    @NonNull private final ArrayDeque<CrashState> mCrashStates;

    private int mTotalOperationsHandled;
    private final int mRecentOperationsLimit;
    @NonNull private final List<Integer> mRecentOperations;

    // Internal callback, notified when an operation is complete. Notifies the requester
    // that the operation is complete, before performing internal scheduler work (such as
    // starting the next client).
    public class InternalCallback implements BaseClientMonitor.Callback {
        @Override
        public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
            Slog.d(getTag(), "[Started] " + clientMonitor);
            if (mCurrentOperation.mClientCallback != null) {
                mCurrentOperation.mClientCallback.onClientStarted(clientMonitor);
            }
        }

        @Override
        public void onClientFinished(@NonNull BaseClientMonitor clientMonitor, boolean success) {
            mHandler.post(() -> {
                if (mCurrentOperation == null) {
                    Slog.e(getTag(), "[Finishing] " + clientMonitor
                            + " but current operation is null, success: " + success
                            + ", possible lifecycle bug in clientMonitor implementation?");
                    return;
                }

                if (clientMonitor != mCurrentOperation.mClientMonitor) {
                    Slog.e(getTag(), "[Ignoring Finish] " + clientMonitor + " does not match"
                            + " current: " + mCurrentOperation.mClientMonitor);
                    return;
                }

                Slog.d(getTag(), "[Finishing] " + clientMonitor + ", success: " + success);
                mCurrentOperation.mState = Operation.STATE_FINISHED;

                if (mCurrentOperation.mClientCallback != null) {
                    mCurrentOperation.mClientCallback.onClientFinished(clientMonitor, success);
                }

                if (mGestureAvailabilityDispatcher != null) {
                    mGestureAvailabilityDispatcher.markSensorActive(
                            mCurrentOperation.mClientMonitor.getSensorId(), false /* active */);
                }

                if (mRecentOperations.size() >= mRecentOperationsLimit) {
                    mRecentOperations.remove(0);
                }
                mRecentOperations.add(mCurrentOperation.mClientMonitor.getProtoEnum());
                mCurrentOperation = null;
                mTotalOperationsHandled++;
                startNextOperationIfIdle();
            });
        }
    }

    @VisibleForTesting
    BiometricScheduler(@NonNull String tag,
            @Nullable GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull IBiometricService biometricService, int recentOperationsLimit) {
        mBiometricTag = tag;
        mInternalCallback = new InternalCallback();
        mGestureAvailabilityDispatcher = gestureAvailabilityDispatcher;
        mPendingOperations = new ArrayDeque<>();
        mBiometricService = biometricService;
        mCrashStates = new ArrayDeque<>();
        mRecentOperationsLimit = recentOperationsLimit;
        mRecentOperations = new ArrayList<>();
    }

    /**
     * Creates a new scheduler.
     * @param tag for the specific instance of the scheduler. Should be unique.
     * @param gestureAvailabilityDispatcher may be null if the sensor does not support gestures
     *                                      (such as fingerprint swipe).
     */
    public BiometricScheduler(@NonNull String tag,
            @Nullable GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        this(tag, gestureAvailabilityDispatcher, IBiometricService.Stub.asInterface(
                ServiceManager.getService(Context.BIOMETRIC_SERVICE)), LOG_NUM_RECENT_OPERATIONS);
    }

    /**
     * @return A reference to the internal callback that should be invoked whenever the scheduler
     *         needs to (e.g. client started, client finished).
     */
    @NonNull protected InternalCallback getInternalCallback() {
        return mInternalCallback;
    }

    protected String getTag() {
        return BASE_TAG + "/" + mBiometricTag;
    }

    protected void startNextOperationIfIdle() {
        if (mCurrentOperation != null) {
            Slog.v(getTag(), "Not idle, current operation: " + mCurrentOperation);
            return;
        }
        if (mPendingOperations.isEmpty()) {
            Slog.d(getTag(), "No operations, returning to idle");
            return;
        }

        mCurrentOperation = mPendingOperations.poll();
        final BaseClientMonitor currentClient = mCurrentOperation.mClientMonitor;
        Slog.d(getTag(), "[Polled] " + mCurrentOperation);

        // If the operation at the front of the queue has been marked for cancellation, send
        // ERROR_CANCELED. No need to start this client.
        if (mCurrentOperation.mState == Operation.STATE_WAITING_IN_QUEUE_CANCELING) {
            Slog.d(getTag(), "[Now Cancelling] " + mCurrentOperation);
            if (!(currentClient instanceof Interruptable)) {
                throw new IllegalStateException("Mis-implemented client or scheduler, "
                        + "trying to cancel non-interruptable operation: " + mCurrentOperation);
            }

            final Interruptable interruptable = (Interruptable) currentClient;
            interruptable.cancelWithoutStarting(getInternalCallback());
            // Now we wait for the client to send its FinishCallback, which kicks off the next
            // operation.
            return;
        }

        if (mGestureAvailabilityDispatcher != null
                && mCurrentOperation.mClientMonitor instanceof AcquisitionClient) {
            mGestureAvailabilityDispatcher.markSensorActive(
                    mCurrentOperation.mClientMonitor.getSensorId(),
                    true /* active */);
        }

        // Not all operations start immediately. BiometricPrompt waits for its operation
        // to arrive at the head of the queue, before pinging it to start.
        final boolean shouldStartNow = currentClient.getCookie() == 0;
        if (shouldStartNow) {
            if (mCurrentOperation.isUnstartableHalOperation()) {
                final HalClientMonitor<?> halClientMonitor =
                        (HalClientMonitor<?>) mCurrentOperation.mClientMonitor;
                // Note down current length of queue
                final int pendingOperationsLength = mPendingOperations.size();
                final Operation lastOperation = mPendingOperations.peekLast();
                Slog.e(getTag(), "[Unable To Start] " + mCurrentOperation
                        + ". Last pending operation: " + lastOperation);

                // For current operations, 1) unableToStart, which notifies the caller-side, then
                // 2) notify operation's callback, to notify applicable system service that the
                // operation failed.
                halClientMonitor.unableToStart();
                if (mCurrentOperation.mClientCallback != null) {
                    mCurrentOperation.mClientCallback.onClientFinished(
                            mCurrentOperation.mClientMonitor, false /* success */);
                }

                // Then for each operation currently in the pending queue at the time of this
                // failure, do the same as above. Otherwise, it's possible that something like
                // setActiveUser fails, but then authenticate (for the wrong user) is invoked.
                for (int i = 0; i < pendingOperationsLength; i++) {
                    final Operation operation = mPendingOperations.pollFirst();
                    if (operation == null) {
                        Slog.e(getTag(), "Null operation, index: " + i
                                + ", expected length: " + pendingOperationsLength);
                        break;
                    }
                    if (operation.isHalOperation()) {
                        ((HalClientMonitor<?>) operation.mClientMonitor).unableToStart();
                    }
                    if (operation.mClientCallback != null) {
                        operation.mClientCallback.onClientFinished(operation.mClientMonitor,
                                false /* success */);
                    }
                    Slog.w(getTag(), "[Aborted Operation] " + operation);
                }

                // It's possible that during cleanup a new set of operations came in. We can try to
                // run these. A single request from the manager layer to the service layer may
                // actually be multiple operations (i.e. updateActiveUser + authenticate).
                mCurrentOperation = null;
                startNextOperationIfIdle();
            } else {
                Slog.d(getTag(), "[Starting] " + mCurrentOperation);
                currentClient.start(getInternalCallback());
                mCurrentOperation.mState = Operation.STATE_STARTED;
            }
        } else {
            try {
                mBiometricService.onReadyForAuthentication(currentClient.getCookie());
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when contacting BiometricService", e);
            }
            Slog.d(getTag(), "Waiting for cookie before starting: " + mCurrentOperation);
            mCurrentOperation.mState = Operation.STATE_WAITING_FOR_COOKIE;
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
        if (mCurrentOperation.mState != Operation.STATE_WAITING_FOR_COOKIE) {
            if (mCurrentOperation.mState == Operation.STATE_WAITING_IN_QUEUE_CANCELING) {
                Slog.d(getTag(), "Operation was marked for cancellation, cancelling now: "
                        + mCurrentOperation);
                // This should trigger the internal onClientFinished callback, which clears the
                // operation and starts the next one.
                final Interruptable interruptable =
                        (Interruptable) mCurrentOperation.mClientMonitor;
                interruptable.onError(BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                        0 /* vendorCode */);
                return;
            } else {
                Slog.e(getTag(), "Operation is in the wrong state: " + mCurrentOperation
                        + ", expected STATE_WAITING_FOR_COOKIE");
                return;
            }
        }
        if (mCurrentOperation.mClientMonitor.getCookie() != cookie) {
            Slog.e(getTag(), "Mismatched cookie for operation: " + mCurrentOperation
                    + ", received: " + cookie);
            return;
        }

        if (mCurrentOperation.isUnstartableHalOperation()) {
            Slog.e(getTag(), "[Unable To Start] Prepared client: " + mCurrentOperation);
            // This is BiometricPrompt trying to auth but something's wrong with the HAL.
            final HalClientMonitor<?> halClientMonitor =
                    (HalClientMonitor<?>) mCurrentOperation.mClientMonitor;
            halClientMonitor.unableToStart();
            if (mCurrentOperation.mClientCallback != null) {
                mCurrentOperation.mClientCallback.onClientFinished(mCurrentOperation.mClientMonitor,
                        false /* success */);
            }
            mCurrentOperation = null;
            startNextOperationIfIdle();
        } else {
            Slog.d(getTag(), "[Starting] Prepared client: " + mCurrentOperation);
            mCurrentOperation.mState = Operation.STATE_STARTED;
            mCurrentOperation.mClientMonitor.start(getInternalCallback());
        }
    }

    /**
     * Adds a {@link BaseClientMonitor} to the pending queue
     *
     * @param clientMonitor operation to be scheduled
     */
    public void scheduleClientMonitor(@NonNull BaseClientMonitor clientMonitor) {
        scheduleClientMonitor(clientMonitor, null /* clientFinishCallback */);
    }

    /**
     * Adds a {@link BaseClientMonitor} to the pending queue
     *
     * @param clientMonitor        operation to be scheduled
     * @param clientCallback optional callback, invoked when the client is finished, but
     *                             before it has been removed from the queue.
     */
    public void scheduleClientMonitor(@NonNull BaseClientMonitor clientMonitor,
            @Nullable BaseClientMonitor.Callback clientCallback) {
        // If the incoming operation should interrupt preceding clients, mark any interruptable
        // pending clients as canceling. Once they reach the head of the queue, the scheduler will
        // send ERROR_CANCELED and skip the operation.
        if (clientMonitor.interruptsPrecedingClients()) {
            for (Operation operation : mPendingOperations) {
                if (operation.mClientMonitor instanceof Interruptable
                        && operation.mState != Operation.STATE_WAITING_IN_QUEUE_CANCELING) {
                    Slog.d(getTag(), "New client incoming, marking pending client as canceling: "
                            + operation.mClientMonitor);
                    operation.mState = Operation.STATE_WAITING_IN_QUEUE_CANCELING;
                }
            }
        }

        mPendingOperations.add(new Operation(clientMonitor, clientCallback));
        Slog.d(getTag(), "[Added] " + clientMonitor
                + ", new queue size: " + mPendingOperations.size());

        // If the new operation should interrupt preceding clients, and if the current operation is
        // cancellable, start the cancellation process.
        if (clientMonitor.interruptsPrecedingClients()
                && mCurrentOperation != null
                && mCurrentOperation.mClientMonitor instanceof Interruptable
                && mCurrentOperation.mState == Operation.STATE_STARTED) {
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
        if (!(operation.mClientMonitor instanceof Interruptable)) {
            Slog.w(getTag(), "Operation not interruptable: " + operation);
            return;
        }
        if (operation.mState == Operation.STATE_STARTED_CANCELING) {
            Slog.w(getTag(), "Cancel already invoked for operation: " + operation);
            return;
        }
        if (operation.mState == Operation.STATE_WAITING_FOR_COOKIE) {
            Slog.w(getTag(), "Skipping cancellation for non-started operation: " + operation);
            // We can set it to null immediately, since the HAL was never notified to start.
            mCurrentOperation = null;
            startNextOperationIfIdle();
            return;
        }
        Slog.d(getTag(), "[Cancelling] Current client: " + operation.mClientMonitor);
        final Interruptable interruptable = (Interruptable) operation.mClientMonitor;
        interruptable.cancel();
        operation.mState = Operation.STATE_STARTED_CANCELING;

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
        final boolean isEnrolling = mCurrentOperation.mClientMonitor instanceof EnrollClient;
        final boolean tokenMatches = mCurrentOperation.mClientMonitor.getToken() == token;
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
                mCurrentOperation.mClientMonitor instanceof AuthenticationConsumer;
        final boolean tokenMatches = mCurrentOperation.mClientMonitor.getToken() == token;

        if (isAuthenticating && tokenMatches) {
            Slog.d(getTag(), "Cancelling authentication: " + mCurrentOperation);
            cancelInternal(mCurrentOperation);
        } else if (!isAuthenticating) {
            // Look through the current queue for all authentication clients for the specified
            // token, and mark them as STATE_WAITING_IN_QUEUE_CANCELING. Note that we're marking
            // all of them, instead of just the first one, since the API surface currently doesn't
            // allow us to distinguish between multiple authentication requests from the same
            // process. However, this generally does not happen anyway, and would be a class of
            // bugs on its own.
            for (Operation operation : mPendingOperations) {
                if (operation.mClientMonitor instanceof AuthenticationConsumer
                        && operation.mClientMonitor.getToken() == token) {
                    Slog.d(getTag(), "Marking " + operation
                            + " as STATE_WAITING_IN_QUEUE_CANCELING");
                    operation.mState = Operation.STATE_WAITING_IN_QUEUE_CANCELING;
                }
            }
        }
    }

    /**
     * @return the current operation
     */
    public BaseClientMonitor getCurrentClient() {
        if (mCurrentOperation == null) {
            return null;
        }
        return mCurrentOperation.mClientMonitor;
    }

    public int getCurrentPendingCount() {
        return mPendingOperations.size();
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
        pw.println("Current operation: " + mCurrentOperation);
        pw.println("Pending operations: " + mPendingOperations.size());
        for (Operation operation : mPendingOperations) {
            pw.println("Pending operation: " + operation);
        }
        for (CrashState crashState : mCrashStates) {
            pw.println("Crash State " + crashState);
        }
    }

    public byte[] dumpProtoState(boolean clearSchedulerBuffer) {
        final ProtoOutputStream proto = new ProtoOutputStream();
        proto.write(BiometricSchedulerProto.CURRENT_OPERATION, mCurrentOperation != null
                ? mCurrentOperation.mClientMonitor.getProtoEnum() : BiometricsProto.CM_NONE);
        proto.write(BiometricSchedulerProto.TOTAL_OPERATIONS, mTotalOperationsHandled);

        if (!mRecentOperations.isEmpty()) {
            for (int i = 0; i < mRecentOperations.size(); i++) {
                proto.write(BiometricSchedulerProto.RECENT_OPERATIONS, mRecentOperations.get(i));
            }
        } else {
            // TODO:(b/178828362) Unsure why protobuf has a problem decoding when an empty list
            //  is returned. So, let's just add a no-op for this case.
            proto.write(BiometricSchedulerProto.RECENT_OPERATIONS, BiometricsProto.CM_NONE);
        }
        proto.flush();

        if (clearSchedulerBuffer) {
            mRecentOperations.clear();
        }
        return proto.getBytes();
    }

    /**
     * Clears the scheduler of anything work-related. This should be used for example when the
     * HAL dies.
     */
    public void reset() {
        mPendingOperations.clear();
        mCurrentOperation = null;
    }
}
