/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.RemoteException;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A base class to manage listeners that have a 1:N -> source:listener relationship.
 *
 * @hide
 */
abstract class AbstractListenerManager<T> {

    private static class Registration<T> {
        private final Executor mExecutor;
        @Nullable private volatile T mListener;

        private Registration(Executor executor, T listener) {
            Preconditions.checkArgument(listener != null, "invalid null listener/callback");
            Preconditions.checkArgument(executor != null, "invalid null executor");
            mExecutor = executor;
            mListener = listener;
        }

        private void unregister() {
            mListener = null;
        }

        private void execute(Consumer<T> operation) {
            mExecutor.execute(() -> {
                T listener = mListener;
                if (listener == null) {
                    return;
                }

                // we may be under the binder identity if a direct executor is used
                long identity = Binder.clearCallingIdentity();
                try {
                    operation.accept(listener);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            });
        }
    }

    @GuardedBy("mListeners")
    private final ArrayMap<Object, Registration<T>> mListeners = new ArrayMap<>();

    public boolean addListener(@NonNull T listener, @NonNull Handler handler)
            throws RemoteException {
        return addInternal(listener, handler);
    }

    public boolean addListener(@NonNull T listener, @NonNull Executor executor)
            throws RemoteException {
        return addInternal(listener, executor);
    }

    protected final boolean addInternal(@NonNull Object listener, @NonNull Handler handler)
            throws RemoteException {
        return addInternal(listener, new HandlerExecutor(handler));
    }

    protected final boolean addInternal(@NonNull Object listener, @NonNull Executor executor)
            throws RemoteException {
        Preconditions.checkArgument(listener != null, "invalid null listener/callback");
        return addInternal(listener, new Registration<>(executor, convertKey(listener)));
    }

    private boolean addInternal(Object key, Registration<T> registration) throws RemoteException {
        Preconditions.checkNotNull(registration);

        synchronized (mListeners) {
            if (mListeners.isEmpty() && !registerService()) {
                return false;
            }
            Registration<T> oldRegistration = mListeners.put(key, registration);
            if (oldRegistration != null) {
                oldRegistration.unregister();
            }
            return true;
        }
    }

    public void removeListener(Object listener) throws RemoteException {
        synchronized (mListeners) {
            Registration<T> oldRegistration = mListeners.remove(listener);
            if (oldRegistration == null) {
                return;
            }
            oldRegistration.unregister();

            if (mListeners.isEmpty()) {
                unregisterService();
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected T convertKey(@NonNull Object listener) {
        return (T) listener;
    }

    protected abstract boolean registerService() throws RemoteException;
    protected abstract void unregisterService() throws RemoteException;

    protected void execute(Consumer<T> operation) {
        synchronized (mListeners) {
            for (Registration<T> registration : mListeners.values()) {
                registration.execute(operation);
            }
        }
    }
}
