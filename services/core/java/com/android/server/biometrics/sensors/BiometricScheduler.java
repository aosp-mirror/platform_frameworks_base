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
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.IBiometricService;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.expresslog.Counter;
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
import java.util.function.Consumer;

/**
 * A scheduler for biometric HAL operations. Maintains a queue of {@link BaseClientMonitor}
 * operations, without caring about its implementation details. Operations may perform zero or more
 * interactions with the HAL before finishing.
 *
 * We currently assume (and require) that each biometric sensor have its own instance of a
 * {@link BiometricScheduler}.
 */
@MainThread
public class BiometricScheduler {

    private static final String BASE_TAG = "BiometricScheduler";
    // Number of recent operations to keep in our logs for dumpsys
    protected static final int LOG_NUM_RECENT_OPERATIONS = 50;

    /**
     * Unknown sensor type. This should never be used, and is a sign that something is wrong during
     * initialization.
     */
    public static final int SENSOR_TYPE_UNKNOWN = 0;

    /**
     * Face authentication.
     */
    public static final int SENSOR_TYPE_FACE = 1;

    /**
     * Any UDFPS type. See {@link FingerprintSensorPropertiesInternal#isAnyUdfpsType()}.
     */
    public static final int SENSOR_TYPE_UDFPS = 2;

    /**
     * Any other fingerprint sensor. We can add additional definitions in the future when necessary.
     */
    public static final int SENSOR_TYPE_FP_OTHER = 3;

    @IntDef({SENSOR_TYPE_UNKNOWN, SENSOR_TYPE_FACE, SENSOR_TYPE_UDFPS, SENSOR_TYPE_FP_OTHER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SensorType {}

    public static @SensorType int sensorTypeFromFingerprintProperties(
            @NonNull FingerprintSensorPropertiesInternal props) {
        if (props.isAnyUdfpsType()) {
            return SENSOR_TYPE_UDFPS;
        }

        return SENSOR_TYPE_FP_OTHER;
    }

    public static String sensorTypeToString(@SensorType int sensorType) {
        switch (sensorType) {
            case SENSOR_TYPE_UNKNOWN:
                return "Unknown";
            case SENSOR_TYPE_FACE:
                return "Face";
            case SENSOR_TYPE_UDFPS:
                return "Udfps";
            case SENSOR_TYPE_FP_OTHER:
                return "OtherFp";
            default:
                return "UnknownUnknown";
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
    private final @SensorType int mSensorType;
    @Nullable private final GestureAvailabilityDispatcher mGestureAvailabilityDispatcher;
    @NonNull private final IBiometricService mBiometricService;
    @NonNull protected final Handler mHandler;
    @VisibleForTesting @NonNull final Deque<BiometricSchedulerOperation> mPendingOperations;
    @VisibleForTesting @Nullable BiometricSchedulerOperation mCurrentOperation;
    @NonNull private final ArrayDeque<CrashState> mCrashStates;

    private int mTotalOperationsHandled;
    private final int mRecentOperationsLimit;
    @NonNull private final List<Integer> mRecentOperations;

    // Internal callback, notified when an operation is complete. Notifies the requester
    // that the operation is complete, before performing internal scheduler work (such as
    // starting the next client).
    private final ClientMonitorCallback mInternalCallback = new ClientMonitorCallback() {
        @Override
        public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
            Slog.d(getTag(), "[Started] " + clientMonitor);
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

                if (!mCurrentOperation.isFor(clientMonitor)) {
                    Slog.e(getTag(), "[Ignoring Finish] " + clientMonitor + " does not match"
                            + " current: " + mCurrentOperation);
                    return;
                }

                Slog.d(getTag(), "[Finishing] " + clientMonitor + ", success: " + success);

                if (mGestureAvailabilityDispatcher != null) {
                    mGestureAvailabilityDispatcher.markSensorActive(
                            mCurrentOperation.getSensorId(), false /* active */);
                }

                if (mRecentOperations.size() >= mRecentOperationsLimit) {
                    mRecentOperations.remove(0);
                }
                mRecentOperations.add(mCurrentOperation.getProtoEnum());
                mCurrentOperation = null;
                mTotalOperationsHandled++;
                startNextOperationIfIdle();
            });
        }
    };

    @VisibleForTesting
    BiometricScheduler(@NonNull String tag,
            @NonNull Handler handler,
            @SensorType int sensorType,
            @Nullable GestureAvailabilityDispatcher gestureAvailabilityDispatcher,
            @NonNull IBiometricService biometricService,
            int recentOperationsLimit) {
        mBiometricTag = tag;
        mHandler = handler;
        mSensorType = sensorType;
        mGestureAvailabilityDispatcher = gestureAvailabilityDispatcher;
        mPendingOperations = new ArrayDeque<>();
        mBiometricService = biometricService;
        mCrashStates = new ArrayDeque<>();
        mRecentOperationsLimit = recentOperationsLimit;
        mRecentOperations = new ArrayList<>();
    }

    /**
     * Creates a new scheduler.
     *
     * @param tag for the specific instance of the scheduler. Should be unique.
     * @param sensorType the sensorType that this scheduler is handling.
     * @param gestureAvailabilityDispatcher may be null if the sensor does not support gestures
     *                                      (such as fingerprint swipe).
     */
    public BiometricScheduler(@NonNull String tag,
            @SensorType int sensorType,
            @Nullable GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        this(tag, new Handler(Looper.getMainLooper()), sensorType, gestureAvailabilityDispatcher,
                IBiometricService.Stub.asInterface(
                        ServiceManager.getService(Context.BIOMETRIC_SERVICE)),
                LOG_NUM_RECENT_OPERATIONS);
    }

    @VisibleForTesting
    public ClientMonitorCallback getInternalCallback() {
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
        Slog.d(getTag(), "[Polled] " + mCurrentOperation);

        // If the operation at the front of the queue has been marked for cancellation, send
        // ERROR_CANCELED. No need to start this client.
        if (mCurrentOperation.isMarkedCanceling()) {
            Slog.d(getTag(), "[Now Cancelling] " + mCurrentOperation);
            mCurrentOperation.cancel(mHandler, mInternalCallback);
            // Now we wait for the client to send its FinishCallback, which kicks off the next
            // operation.
            return;
        }

        if (mCurrentOperation.isAcquisitionOperation()) {
            AcquisitionClient client = (AcquisitionClient) mCurrentOperation.getClientMonitor();
            if (client.isAlreadyCancelled()) {
                mCurrentOperation.cancel(mHandler, mInternalCallback);
                return;
            }
        }

        if (mGestureAvailabilityDispatcher != null && mCurrentOperation.isAcquisitionOperation()) {
            mGestureAvailabilityDispatcher.markSensorActive(
                    mCurrentOperation.getSensorId(), true /* active */);
        }

        // Not all operations start immediately. BiometricPrompt waits for its operation
        // to arrive at the head of the queue, before pinging it to start.
        final int cookie = mCurrentOperation.isReadyToStart(mInternalCallback);
        if (cookie == 0) {
            if (!mCurrentOperation.start(mInternalCallback)) {
                // Note down current length of queue
                final int pendingOperationsLength = mPendingOperations.size();
                final BiometricSchedulerOperation lastOperation = mPendingOperations.peekLast();
                Slog.e(getTag(), "[Unable To Start] " + mCurrentOperation
                        + ". Last pending operation: " + lastOperation);

                // Then for each operation currently in the pending queue at the time of this
                // failure, do the same as above. Otherwise, it's possible that something like
                // setActiveUser fails, but then authenticate (for the wrong user) is invoked.
                for (int i = 0; i < pendingOperationsLength; i++) {
                    final BiometricSchedulerOperation operation = mPendingOperations.pollFirst();
                    if (operation != null) {
                        Slog.w(getTag(), "[Aborting Operation] " + operation);
                        operation.abort();
                    } else {
                        Slog.e(getTag(), "Null operation, index: " + i
                                + ", expected length: " + pendingOperationsLength);
                    }
                }

                // It's possible that during cleanup a new set of operations came in. We can try to
                // run these. A single request from the manager layer to the service layer may
                // actually be multiple operations (i.e. updateActiveUser + authenticate).
                mCurrentOperation = null;
                startNextOperationIfIdle();
            }
        } else {
            try {
                mBiometricService.onReadyForAuthentication(
                        mCurrentOperation.getClientMonitor().getRequestId(), cookie);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when contacting BiometricService", e);
            }
            Slog.d(getTag(), "Waiting for cookie before starting: " + mCurrentOperation);
        }
    }

    /**
     * Starts the {@link #mCurrentOperation} if
     * 1) its state is {@link BiometricSchedulerOperation#STATE_WAITING_FOR_COOKIE} and
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

        if (mCurrentOperation.startWithCookie(mInternalCallback, cookie)) {
            Slog.d(getTag(), "[Started] Prepared client: " + mCurrentOperation);
        } else {
            Slog.e(getTag(), "[Unable To Start] Prepared client: " + mCurrentOperation);
            mCurrentOperation = null;
            startNextOperationIfIdle();
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
     * @param clientCallback optional callback, invoked when the client state changes.
     */
    public void scheduleClientMonitor(@NonNull BaseClientMonitor clientMonitor,
            @Nullable ClientMonitorCallback clientCallback) {
        // If the incoming operation should interrupt preceding clients, mark any interruptable
        // pending clients as canceling. Once they reach the head of the queue, the scheduler will
        // send ERROR_CANCELED and skip the operation.
        if (clientMonitor.interruptsPrecedingClients()) {
            for (BiometricSchedulerOperation operation : mPendingOperations) {
                if (operation.markCanceling()) {
                    Slog.d(getTag(), "New client, marking pending op as canceling: " + operation);
                }
            }
        }

        mPendingOperations.add(new BiometricSchedulerOperation(clientMonitor, clientCallback));
        Slog.d(getTag(), "[Added] " + clientMonitor
                + ", new queue size: " + mPendingOperations.size());

        // If the new operation should interrupt preceding clients, and if the current operation is
        // cancellable, start the cancellation process.
        if (clientMonitor.interruptsPrecedingClients()
                && mCurrentOperation != null
                && mCurrentOperation.isInterruptable()
                && mCurrentOperation.isStarted()) {
            Slog.d(getTag(), "[Cancelling Interruptable]: " + mCurrentOperation);
            mCurrentOperation.cancel(mHandler, mInternalCallback);
        } else {
            startNextOperationIfIdle();
        }
    }

    /**
     * Requests to cancel enrollment.
     * @param token from the caller, should match the token passed in when requesting enrollment
     */
    public void cancelEnrollment(IBinder token, long requestId) {
        Slog.d(getTag(), "cancelEnrollment, requestId: " + requestId);

        if (mCurrentOperation != null
                && canCancelEnrollOperation(mCurrentOperation, token, requestId)) {
            Slog.d(getTag(), "Cancelling enrollment op: " + mCurrentOperation);
            mCurrentOperation.cancel(mHandler, mInternalCallback);
        } else {
            for (BiometricSchedulerOperation operation : mPendingOperations) {
                if (canCancelEnrollOperation(operation, token, requestId)) {
                    Slog.d(getTag(), "Cancelling pending enrollment op: " + operation);
                    operation.markCanceling();
                }
            }
        }
    }

    /**
     * Requests to cancel authentication or detection.
     * @param token from the caller, should match the token passed in when requesting authentication
     * @param requestId the id returned when requesting authentication
     */
    public void cancelAuthenticationOrDetection(IBinder token, long requestId) {
        Slog.d(getTag(), "cancelAuthenticationOrDetection, requestId: " + requestId);

        if (mCurrentOperation != null
                && canCancelAuthOperation(mCurrentOperation, token, requestId)) {
            Slog.d(getTag(), "Cancelling auth/detect op: " + mCurrentOperation);
            mCurrentOperation.cancel(mHandler, mInternalCallback);
        } else {
            for (BiometricSchedulerOperation operation : mPendingOperations) {
                if (canCancelAuthOperation(operation, token, requestId)) {
                    Slog.d(getTag(), "Cancelling pending auth/detect op: " + operation);
                    operation.markCanceling();
                }
            }
        }
    }

    private static boolean canCancelEnrollOperation(BiometricSchedulerOperation operation,
            IBinder token, long requestId) {
        return operation.isEnrollOperation()
                && operation.isMatchingToken(token)
                && operation.isMatchingRequestId(requestId);
    }

    private static boolean canCancelAuthOperation(BiometricSchedulerOperation operation,
            IBinder token, long requestId) {
        // TODO: restrict callers that can cancel without requestId (negative value)?
        return operation.isAuthenticationOrDetectionOperation()
                && operation.isMatchingToken(token)
                && operation.isMatchingRequestId(requestId);
    }

    /**
     * Get current operation <code>BaseClientMonitor</code>
     * @deprecated TODO: b/229994966, encapsulate client monitors
     * @return the current operation
     */
    @Deprecated
    @Nullable
    public BaseClientMonitor getCurrentClient() {
        return mCurrentOperation != null ? mCurrentOperation.getClientMonitor() : null;
    }

    /**
     * The current operation if the requestId is set and matches.
     * @deprecated TODO: b/229994966, encapsulate client monitors
     */
    @Deprecated
    @Nullable
    public void getCurrentClientIfMatches(long requestId,
            @NonNull Consumer<BaseClientMonitor> clientMonitorConsumer) {
        mHandler.post(() -> {
            if (mCurrentOperation != null) {
                if (mCurrentOperation.isMatchingRequestId(requestId)) {
                    clientMonitorConsumer.accept(mCurrentOperation.getClientMonitor());
                    return;
                }
            }
            clientMonitorConsumer.accept(null);
        });
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
        for (BiometricSchedulerOperation operation : mPendingOperations) {
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
        pw.println("Type: " + mSensorType);
        pw.println("Current operation: " + mCurrentOperation);
        pw.println("Pending operations: " + mPendingOperations.size());
        for (BiometricSchedulerOperation operation : mPendingOperations) {
            pw.println("Pending operation: " + operation);
        }
        for (CrashState crashState : mCrashStates) {
            pw.println("Crash State " + crashState);
        }
    }

    public byte[] dumpProtoState(boolean clearSchedulerBuffer) {
        final ProtoOutputStream proto = new ProtoOutputStream();
        proto.write(BiometricSchedulerProto.CURRENT_OPERATION, mCurrentOperation != null
                ? mCurrentOperation.getProtoEnum() : BiometricsProto.CM_NONE);
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
        Slog.d(getTag(), "Resetting scheduler");
        mPendingOperations.clear();
        mCurrentOperation = null;
    }

    /**
     * Marks all pending operations as canceling and cancels the current
     * operation.
     */
    private void clearScheduler() {
        if (mCurrentOperation == null) {
            return;
        }
        for (BiometricSchedulerOperation pendingOperation : mPendingOperations) {
            Slog.d(getTag(), "[Watchdog cancelling pending] "
                    + pendingOperation.getClientMonitor());
            pendingOperation.markCancelingForWatchdog();
        }
        Slog.d(getTag(), "[Watchdog cancelling current] "
                + mCurrentOperation.getClientMonitor());
        mCurrentOperation.cancel(mHandler, getInternalCallback());
    }

    /**
     * Start the timeout for the watchdog.
     */
    public void startWatchdog() {
        if (mCurrentOperation == null) {
            return;
        }
        final BiometricSchedulerOperation operation = mCurrentOperation;
        mHandler.postDelayed(() -> {
            if (operation == mCurrentOperation && !operation.isFinished()) {
                Counter.logIncrement("biometric.value_scheduler_watchdog_triggered_count");
                clearScheduler();
            }
        }, 10000);
    }

    /**
     * Handle stop user client when user switching occurs.
     */
    public void onUserStopped() {}

    public Handler getHandler() {
        return mHandler;
    }
}
