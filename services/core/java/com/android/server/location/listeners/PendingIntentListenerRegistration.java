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
import android.util.Log;

/**
 * A registration that works with PendingIntent keys, and registers a CancelListener to
 * automatically remove the registration if the PendingIntent is canceled. The key for this
 * registration must either be a {@link PendingIntent} or a {@link PendingIntentKey}.
 *
 * @param <TRequest>  request type
 * @param <TListener> listener type
 */
public abstract class PendingIntentListenerRegistration<TRequest, TListener> extends
        RemoteListenerRegistration<TRequest, TListener> implements PendingIntent.CancelListener {

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

    @Override
    protected final void onRemovableListenerRegister() {
        getPendingIntentFromKey(getKey()).registerCancelListener(this);
        onPendingIntentListenerRegister();
    }

    @Override
    protected final void onRemovableListenerUnregister() {
        onPendingIntentListenerUnregister();
        getPendingIntentFromKey(getKey()).unregisterCancelListener(this);
    }

    /**
     * May be overridden in place of {@link #onRemovableListenerRegister()}.
     */
    protected void onPendingIntentListenerRegister() {}

    /**
     * May be overridden in place of {@link #onRemovableListenerUnregister()}.
     */
    protected void onPendingIntentListenerUnregister() {}

    @Override
    protected void onOperationFailure(ListenerOperation<TListener> operation, Exception e) {
        if (e instanceof PendingIntent.CanceledException) {
            Log.w(getOwner().getTag(), "registration " + this + " removed", e);
            remove();
        } else {
            super.onOperationFailure(operation, e);
        }
    }

    @Override
    public void onCancelled(PendingIntent intent) {
        if (Log.isLoggable(getOwner().getTag(), Log.DEBUG)) {
            Log.d(getOwner().getTag(),
                    "pending intent registration " + getIdentity() + " canceled");
        }

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
