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

import android.annotation.Nullable;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * A registration that works with IBinder keys, and registers a DeathListener to automatically
 * remove the registration if the binder dies. The key for this registration must either be an
 * {@link IBinder} or a {@link BinderKey}.
 *
 * @param <TRequest>  request type
 * @param <TListener> listener type
 */
public abstract class BinderListenerRegistration<TRequest, TListener> extends
        RemoteListenerRegistration<TRequest, TListener> implements Binder.DeathRecipient {

    /**
     * Interface to allow binder retrieval when keys are not themselves IBinders.
     */
    public interface BinderKey {
        /**
         * Returns the binder associated with this key.
         */
        IBinder getBinder();
    }

    protected BinderListenerRegistration(@Nullable TRequest request, CallerIdentity callerIdentity,
            TListener listener) {
        super(request, callerIdentity, listener);
    }

    @Override
    protected final void onRemovableListenerRegister() {
        IBinder binder = getBinderFromKey(getKey());
        try {
            binder.linkToDeath(this, 0);
        } catch (RemoteException e) {
            remove();
        }

        onBinderListenerRegister();
    }

    @Override
    protected final void onRemovableListenerUnregister() {
        onBinderListenerUnregister();
        getBinderFromKey(getKey()).unlinkToDeath(this, 0);
    }

    /**
     * May be overridden in place of {@link #onRemovableListenerRegister()}.
     */
    protected void onBinderListenerRegister() {}

    /**
     * May be overridden in place of {@link #onRemovableListenerUnregister()}.
     */
    protected void onBinderListenerUnregister() {}

    @Override
    public void onOperationFailure(ListenerOperation<TListener> operation, Exception e) {
        if (e instanceof RemoteException) {
            Log.w(getOwner().getTag(), "registration " + this + " removed", e);
            remove();
        } else {
            super.onOperationFailure(operation, e);
        }
    }

    @Override
    public void binderDied() {
        try {
            if (Log.isLoggable(getOwner().getTag(), Log.DEBUG)) {
                Log.d(getOwner().getTag(), "binder registration " + getIdentity() + " died");
            }
            remove();
        } catch (RuntimeException e) {
            // the caller may swallow runtime exceptions, so we rethrow as assertion errors to
            // ensure the crash is seen
            throw new AssertionError(e);
        }
    }

    private static IBinder getBinderFromKey(Object key) {
        if (key instanceof IBinder) {
            return (IBinder) key;
        } else if (key instanceof BinderKey) {
            return ((BinderKey) key).getBinder();
        } else {
            throw new IllegalArgumentException("key must be IBinder or BinderKey");
        }
    }
}
