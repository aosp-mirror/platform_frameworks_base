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
        RemovableListenerRegistration<TRequest, TListener> implements Binder.DeathRecipient {

    /**
     * Interface to allowed binder retrieval when keys are not themselves IBinder.
     */
    public interface BinderKey {
        /**
         * Returns the binder associated with this key.
         */
        IBinder getBinder();
    }

    protected BinderListenerRegistration(String tag, @Nullable TRequest request,
            CallerIdentity callerIdentity, TListener listener) {
        super(tag, request, callerIdentity, listener);
    }

    /**
     * May be overridden in place of {@link #onRegister(Object)}. Should return true if registration
     * is successful, and false otherwise.
     */
    protected boolean onBinderRegister(Object key) {
        return true;
    }

    /**
     * May be overridden in place of {@link #onUnregister()}.
     */
    protected void onBinderUnregister(Object key) {}

    @Override
    protected final boolean onRemovableRegister(Object key) {
        IBinder binder = getBinderFromKey(key);
        try {
            binder.linkToDeath(this, 0);
            if (!onBinderRegister(key)) {
                binder.unlinkToDeath(this, 0);
                return false;
            }
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    protected final void onRemovableUnregister(Object key) {
        IBinder binder = getBinderFromKey(key);
        onBinderUnregister(key);
        binder.unlinkToDeath(this, 0);
    }

    @Override
    public void binderDied() {
        try {
            if (Log.isLoggable(mTag, Log.DEBUG)) {
                Log.d(mTag, "binder registration " + getIdentity() + " died");
            }
            remove();
        } catch (RuntimeException e) {
            // the caller may swallow runtime exceptions, so we rethrow as assertion errors to
            // ensure the crash is seen
            throw new AssertionError(e);
        }
    }

    private IBinder getBinderFromKey(Object key) {
        if (key instanceof IBinder) {
            return (IBinder) key;
        } else if (key instanceof BinderKey) {
            return ((BinderKey) key).getBinder();
        } else {
            throw new IllegalArgumentException("key must be IBinder or BinderKey");
        }
    }
}
