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

import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.util.Log;

import java.util.NoSuchElementException;
import java.util.concurrent.Executor;

/**
 * A registration that works with IBinder keys, and registers a DeathListener to automatically
 * remove the registration if the binder dies.
 *
 * @param <TKey>      key type
 * @param <TListener> listener type
 */
public abstract class BinderListenerRegistration<TKey, TListener> extends
        RemovableListenerRegistration<TKey, TListener> implements DeathRecipient {

    protected BinderListenerRegistration(Executor executor, TListener listener) {
        super(executor, listener);
    }

    protected abstract IBinder getBinderFromKey(TKey key);

    @Override
    protected void onRegister() {
        super.onRegister();

        try {
            getBinderFromKey(getKey()).linkToDeath(this, 0);
        } catch (RemoteException e) {
            remove();
        }
    }

    @Override
    protected void onUnregister() {
        try {
            getBinderFromKey(getKey()).unlinkToDeath(this, 0);
        } catch (NoSuchElementException e) {
            // the only way this exception can occur should be if another exception has been thrown
            // prior to registration completing, and that exception is currently unwinding the call
            // stack and causing this cleanup. since that exception should crash us anyways, drop
            // this exception so we're not hiding the original exception.
            Log.w(getTag(), "failed to unregister binder death listener", e);
        }

        super.onUnregister();
    }

    public void onOperationFailure(ListenerOperation<TListener> operation, Exception e) {
        if (e instanceof RemoteException) {
            Log.w(getTag(), "registration " + this + " removed", e);
            remove();
        } else {
            super.onOperationFailure(operation, e);
        }
    }

    @Override
    public void binderDied() {
        try {
            if (Log.isLoggable(getTag(), Log.DEBUG)) {
                Log.d(getTag(), "binder registration " + this + " died");
            }

            remove();
        } catch (RuntimeException e) {
            // the caller may swallow runtime exceptions, so we rethrow as assertion errors to
            // ensure the crash is seen
            throw new AssertionError(e);
        }
    }
}
