/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.servertransaction;

import static com.android.window.flags.Flags.syncWindowConfigUpdateFlag;

import static java.util.Objects.requireNonNull;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.os.Process;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * Singleton controller to manage listeners to individual {@link ClientTransaction}.
 *
 * TODO(b/260873529) make as TestApi to allow CTS.
 * @hide
 */
public class ClientTransactionListenerController {

    private static ClientTransactionListenerController sController;

    private final Object mLock = new Object();

    /**
     * Mapping from client registered listener for display change to the corresponding
     * {@link Executor} to invoke the listener on.
     * @see #registerDisplayChangeListener(IntConsumer, Executor)
     */
    @GuardedBy("mLock")
    private final ArrayMap<IntConsumer, Executor> mDisplayChangeListeners = new ArrayMap<>();

    private final ArrayList<IntConsumer> mTmpDisplayChangeListeners = new ArrayList<>();

    /** Gets the singleton controller. */
    @NonNull
    public static ClientTransactionListenerController getInstance() {
        synchronized (ClientTransactionListenerController.class) {
            if (sController == null) {
                sController = new ClientTransactionListenerController();
            }
            return sController;
        }
    }

    /** Creates a new instance for test only. */
    @VisibleForTesting
    @NonNull
    public static ClientTransactionListenerController createInstanceForTesting() {
        return new ClientTransactionListenerController();
    }

    private ClientTransactionListenerController() {}

    /**
     * Registers a new listener for display change. It will be invoked when receives a
     * {@link ClientTransaction} that is updating display-related window configuration, such as
     * bounds and rotation.
     *
     * WHen triggered, the listener will be invoked with the logical display id that was changed.
     *
     * @param listener the listener to invoke when receives a transaction with Display change.
     * @param executor the executor on which callback method will be invoked.
     */
    public void registerDisplayChangeListener(@NonNull IntConsumer listener,
            @NonNull @CallbackExecutor Executor executor) {
        if (!isSyncWindowConfigUpdateFlagEnabled()) {
            return;
        }
        requireNonNull(listener);
        requireNonNull(executor);
        synchronized (mLock) {
            mDisplayChangeListeners.put(listener, executor);
        }
    }

    /**
     * Unregisters the listener for display change that was previously registered through
     * {@link #registerDisplayChangeListener}.
     */
    public void unregisterDisplayChangeListener(@NonNull IntConsumer listener) {
        if (!isSyncWindowConfigUpdateFlagEnabled()) {
            return;
        }
        synchronized (mLock) {
            mDisplayChangeListeners.remove(listener);
        }
    }

    /**
     * Called when receives a {@link ClientTransaction} that is updating display-related
     * window configuration.
     */
    public void onDisplayChanged(int displayId) {
        if (!isSyncWindowConfigUpdateFlagEnabled()) {
            return;
        }
        synchronized (mLock) {
            // Make a copy of the list to avoid listener removal during callback.
            mTmpDisplayChangeListeners.addAll(mDisplayChangeListeners.keySet());
            final int num = mTmpDisplayChangeListeners.size();
            try {
                for (int i = 0; i < num; i++) {
                    final IntConsumer listener = mTmpDisplayChangeListeners.get(i);
                    final Executor executor = mDisplayChangeListeners.get(listener);
                    executor.execute(() -> listener.accept(displayId));
                }
            } finally {
                mTmpDisplayChangeListeners.clear();
            }
        }
    }

    /** Whether {@link #syncWindowConfigUpdateFlag} feature flag is enabled. */
    @VisibleForTesting
    public boolean isSyncWindowConfigUpdateFlagEnabled() {
        // Can't read flag from isolated process.
        return !Process.isIsolated() && syncWindowConfigUpdateFlag();
    }
}
