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
import android.app.PendingIntent;
import android.location.util.identity.CallerIdentity;

/**
 * A registration that works with PendingIntent keys, and registers a CancelListener to
 * automatically remove the registration if the PendingIntent is canceled. The key for this
 * registration must either be a {@link PendingIntent} or a {@link PendingIntentKey}.
 *
 * @param <TRequest>  request type
 * @param <TListener> listener type
 */
public abstract class PendingIntentListenerRegistration<TRequest, TListener> extends
        RemovableListenerRegistration<TRequest, TListener> implements PendingIntent.CancelListener {

    /**
     * Interface to allowed pending intent retrieval when keys are not themselves PendingIntents.
     */
    public interface PendingIntentKey {
        /**
         * Returns the pending intent associated with this key.
         */
        PendingIntent getPendingIntent();
    }

    protected PendingIntentListenerRegistration(@Nullable TRequest request,
            CallerIdentity callerIdentity, TListener listener) {
        super(request, callerIdentity, listener);
    }

    /**
     * May be overridden in place of {@link #onRegister(Object)}. Should return true if registration
     * is successful, and false otherwise.
     */
    protected boolean onPendingIntentRegister(Object key) {
        return true;
    }

    /**
     * May be overridden in place of {@link #onUnregister()}.
     */
    protected void onPendingIntentUnregister(Object key) {}

    @Override
    protected final boolean onRemovableRegister(Object key) {
        PendingIntent pendingIntent = getPendingIntentFromKey(key);
        pendingIntent.registerCancelListener(this);
        if (!onPendingIntentRegister(key)) {
            pendingIntent.unregisterCancelListener(this);
            return false;
        }
        return true;
    }

    @Override
    protected final void onRemovableUnregister(Object key) {
        PendingIntent pendingIntent = getPendingIntentFromKey(key);
        onPendingIntentUnregister(key);
        pendingIntent.unregisterCancelListener(this);
    }

    @Override
    public void onCancelled(PendingIntent intent) {
        remove();
    }

    private PendingIntent getPendingIntentFromKey(Object key) {
        if (key instanceof PendingIntent) {
            return (PendingIntent) key;
        } else if (key instanceof PendingIntentKey) {
            return ((PendingIntentKey) key).getPendingIntent();
        } else {
            throw new IllegalArgumentException("key must be PendingIntent or PendingIntentKey");
        }
    }
}
