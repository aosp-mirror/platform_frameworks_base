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

package com.android.server.location.listeners;


import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.Nullable;
import android.location.util.identity.CallerIdentity;
import android.os.Process;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.FgThread;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A listener registration representing a remote (possibly from a different process) listener.
 * Listeners from a different process will be run on a direct executor, since the x-process listener
 * invocation should already be asynchronous. Listeners from the same process will be run on a
 * normal executor, since in-process listener invocation may be synchronous.
 *
 * @param <TRequest>           request type
 * @param <TListener>          listener type
 */
public abstract class RemoteListenerRegistration<TRequest, TListener> extends
        RemovableListenerRegistration<TRequest, TListener> {

    @VisibleForTesting
    public static final Executor IN_PROCESS_EXECUTOR = FgThread.getExecutor();

    private static Executor chooseExecutor(CallerIdentity identity) {
        // if a client is in the same process as us, binder calls will execute synchronously and
        // we shouldn't run callbacks directly since they might be run under lock and deadlock
        if (identity.getPid() == Process.myPid()) {
            // there's a slight loophole here for pending intents - pending intent callbacks can
            // always be run on the direct executor since they're always asynchronous, but honestly
            // you shouldn't be using pending intent callbacks within the same process anyways
            return IN_PROCESS_EXECUTOR;
        } else {
            return DIRECT_EXECUTOR;
        }
    }

    private final CallerIdentity mIdentity;

    protected RemoteListenerRegistration(@Nullable TRequest request, CallerIdentity identity,
            TListener listener) {
        super(chooseExecutor(identity), request, listener);
        mIdentity = Objects.requireNonNull(identity);
    }

    /**
     * Returns the listener identity.
     */
    public final CallerIdentity getIdentity() {
        return mIdentity;
    }
}

