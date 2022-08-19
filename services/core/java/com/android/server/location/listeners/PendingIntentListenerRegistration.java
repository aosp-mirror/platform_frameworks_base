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

import android.app.PendingIntent;
import android.util.Log;

/**
 * A registration that works with PendingIntent keys, and registers a CancelListener to
 * automatically remove the registration if the PendingIntent is canceled.
 *
 * @param <TKey>      key type
 * @param <TListener> listener type
 */
public abstract class PendingIntentListenerRegistration<TKey, TListener> extends
        RemovableListenerRegistration<TKey, TListener> implements PendingIntent.CancelListener {

    protected PendingIntentListenerRegistration(TListener listener) {
        super(DIRECT_EXECUTOR, listener);
    }

    protected abstract PendingIntent getPendingIntentFromKey(TKey key);

    @Override
    protected void onRegister() {
        super.onRegister();

        if (!getPendingIntentFromKey(getKey()).addCancelListener(DIRECT_EXECUTOR, this)) {
            remove();
        }
    }

    @Override
    protected void onUnregister() {
        getPendingIntentFromKey(getKey()).removeCancelListener(this);

        super.onUnregister();
    }

    @Override
    protected void onOperationFailure(ListenerOperation<TListener> operation, Exception e) {
        if (e instanceof PendingIntent.CanceledException) {
            Log.w(getTag(), "registration " + this + " removed", e);
            remove();
        } else {
            super.onOperationFailure(operation, e);
        }
    }

    @Override
    public void onCanceled(PendingIntent intent) {
        if (Log.isLoggable(getTag(), Log.DEBUG)) {
            Log.d(getTag(), "pending intent registration " + this + " canceled");
        }

        remove();
    }
}