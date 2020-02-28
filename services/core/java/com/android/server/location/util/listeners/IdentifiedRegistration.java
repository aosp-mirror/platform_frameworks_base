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

package com.android.server.location.util.listeners;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.Nullable;
import android.location.util.listeners.AbstractListenerManager;
import android.os.Process;

import com.android.server.FgThread;
import com.android.server.location.CallerIdentity;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A listener manager for system server side implementations where client callers are identified via
 * {@link CallerIdentity}. Listener callbacks going to other processes will be run on a direct
 * executor, listener callbacks going to the same process will be run asynchronously.
 *
 * @param <TRequest>  request type
 * @param <TListener> listener type
 */
public class IdentifiedRegistration<TRequest, TListener> extends
            AbstractListenerManager.Registration<TRequest, TListener> {

    private static Executor chooseExecutor(CallerIdentity identity) {
        // if a client is in the same process as us, binder calls will execute synchronously and
        // we shouldn't run callbacks directly since they might be run under lock and deadlock
        if (identity.pid == Process.myPid()) {
            // there's a slight loophole here for pending intents - pending intent callbacks can
            // always be run on the direct executor since they're always asynchronous, but honestly
            // you shouldn't be using pending intent callbacks within the same process anyways
            return FgThread.getExecutor();
        } else {
            return DIRECT_EXECUTOR;
        }
    }

    private final CallerIdentity mCallerIdentity;

    protected IdentifiedRegistration(@Nullable TRequest request, CallerIdentity callerIdentity,
            TListener listener) {
        super(request, chooseExecutor(callerIdentity), listener);
        mCallerIdentity = Objects.requireNonNull(callerIdentity);
    }

    /**
     * Returns the listener identity.
     */
    public CallerIdentity getIdentity() {
        return mCallerIdentity;
    }
}
