/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.PersistableBundle;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A signal to perform orchestration actions on the inference and optionally receive a output about
 * the result of the signal. This is an extension of {@link android.os.CancellationSignal}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public final class ProcessingSignal {
    private final Object mLock = new Object();

    private static final int MAX_QUEUE_SIZE = 10;

    @GuardedBy("mLock")
    private final ArrayDeque<PersistableBundle> mActionParamsQueue;

    @GuardedBy("mLock")
    private IProcessingSignal mRemote;

    private OnProcessingSignalCallback mOnProcessingSignalCallback;
    private Executor mExecutor;

    public ProcessingSignal() {
        mActionParamsQueue = new ArrayDeque<>(MAX_QUEUE_SIZE);
    }

    /**
     * Interface definition for a callback to be invoked when processing signals are received.
     */
    public interface OnProcessingSignalCallback {
        /**
         * Called when a custom signal was received.
         * This method allows the receiver to provide logic to be executed based on the signal
         * received.
         *
         * @param actionParams Parameters for the signal in the form of a {@link PersistableBundle}.
         */

        void onSignalReceived(@NonNull PersistableBundle actionParams);
    }


    /**
     * Sends a custom signal with the provided parameters. If there are multiple concurrent
     * requests to this method, the actionParams are queued in a blocking fashion, in the order they
     * are received.
     *
     * It also signals the remote callback
     * with the same params if already configured, if not the action is queued to be sent when a
     * remote is configured. Similarly, on the receiver side, the callback will be invoked if
     * already set, if not all actions are queued to be sent to callback when it is set.
     *
     * @param actionParams Parameters for the signal.
     */
    public void sendSignal(@NonNull PersistableBundle actionParams) {
        final OnProcessingSignalCallback callback;
        final IProcessingSignal remote;
        synchronized (mLock) {
            if (mActionParamsQueue.size() > MAX_QUEUE_SIZE) {
                throw new RuntimeException(
                        "Maximum actions that can be queued are : " + MAX_QUEUE_SIZE);
            }

            mActionParamsQueue.add(actionParams);
            callback = mOnProcessingSignalCallback;
            remote = mRemote;

            if (callback != null) {
                while (!mActionParamsQueue.isEmpty()) {
                    PersistableBundle params = mActionParamsQueue.removeFirst();
                    mExecutor.execute(
                            () -> callback.onSignalReceived(params));
                }
            }
            if (remote != null) {
                while (!mActionParamsQueue.isEmpty()) {
                    try {
                        remote.sendSignal(mActionParamsQueue.removeFirst());
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }


    /**
     * Sets the processing signal callback to be called when signals are received.
     *
     * This method is intended to be used by the recipient of a processing signal
     * such as the remote implementation in
     * {@link android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService} to handle
     * processing signals while performing a long-running operation.  This method is not
     * intended to be used by the caller themselves.
     *
     * If {@link ProcessingSignal#sendSignal} has already been called, then the provided callback
     * is invoked immediately and all previously queued actions are passed to remote signal.
     *
     * This method is guaranteed that the callback will not be called after it
     * has been removed.
     *
     * @param callback The processing signal callback, or null to remove the current callback.
     * @param executor Executor to the run the callback methods on.
     */
    public void setOnProcessingSignalCallback(
            @NonNull @CallbackExecutor Executor executor,
            @Nullable OnProcessingSignalCallback callback) {
        Objects.requireNonNull(executor);
        synchronized (mLock) {
            if (mOnProcessingSignalCallback == callback) {
                return;
            }

            mOnProcessingSignalCallback = callback;
            mExecutor = executor;
            if (callback == null || mActionParamsQueue.isEmpty()) {
                return;
            }

            while (!mActionParamsQueue.isEmpty()) {
                PersistableBundle params = mActionParamsQueue.removeFirst();
                mExecutor.execute(() -> callback.onSignalReceived(params));
            }
        }
    }

    /**
     * Sets the remote transport.
     *
     * If there are actions queued from {@link ProcessingSignal#sendSignal}, they are also
     * sequentially sent to the configured remote.
     *
     * This method guarantees that the remote transport will not be called after it
     * has been removed.
     *
     * @param remote The remote transport, or null to remove.
     * @hide
     */
    void setRemote(IProcessingSignal remote) {
        synchronized (mLock) {
            mRemote = remote;
            if (mActionParamsQueue.isEmpty() || remote == null) {
                return;
            }

            while (!mActionParamsQueue.isEmpty()) {
                try {
                    remote.sendSignal(mActionParamsQueue.removeFirst());
                } catch (RemoteException e) {
                    throw new RuntimeException("Failed to send action to remote signal", e);
                }
            }
        }
    }

    /**
     * Creates a transport that can be returned back to the caller of
     * a Binder function and subsequently used to dispatch a processing signal.
     *
     * @return The new processing signal transport.
     * @hide
     */
    public static IProcessingSignal createTransport() {
        return new Transport();
    }

    /**
     * Given a locally created transport, returns its associated processing signal.
     *
     * @param transport The locally created transport, or null if none.
     * @return The associated processing signal, or null if none.
     * @hide
     */
    public static ProcessingSignal fromTransport(IProcessingSignal transport) {
        if (transport instanceof Transport) {
            return ((Transport) transport).mProcessingSignal;
        }
        return null;
    }

    private static final class Transport extends IProcessingSignal.Stub {
        final ProcessingSignal mProcessingSignal = new ProcessingSignal();

        @Override
        public void sendSignal(PersistableBundle actionParams) {
            mProcessingSignal.sendSignal(actionParams);
        }
    }

}