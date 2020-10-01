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

import static com.android.internal.util.function.pooled.PooledLambda.obtainRunnable;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.RemoteException;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A base class to manage listeners that have a 1:N -> source:listener relationship.
 *
 * @hide
 */
abstract class AbstractListenerManager<TRequest, TListener> {

    private static class Registration<TRequest, TListener> {
        private final Executor mExecutor;
        @Nullable private TRequest mRequest;
        @Nullable private volatile TListener mListener;

        private Registration(@Nullable TRequest request, Executor executor, TListener listener) {
            Preconditions.checkArgument(listener != null, "invalid null listener/callback");
            Preconditions.checkArgument(executor != null, "invalid null executor");
            mExecutor = executor;
            mListener = listener;
            mRequest = request;
        }

        @Nullable
        public TRequest getRequest() {
            return mRequest;
        }

        private void unregister() {
            mRequest = null;
            mListener = null;
        }

        private void execute(Consumer<TListener> operation) {
            mExecutor.execute(
                    obtainRunnable(Registration<TRequest, TListener>::accept, this, operation)
                            .recycleOnUse());
        }

        private void accept(Consumer<TListener> operation) {
            TListener listener = mListener;
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
        }
    }

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private volatile ArrayMap<Object, Registration<TRequest, TListener>> mListeners =
            new ArrayMap<>();

    @GuardedBy("mLock")
    @Nullable
    private TRequest mMergedRequest;

    public boolean addListener(@NonNull TListener listener, @NonNull Handler handler)
            throws RemoteException {
        return addInternal(/* request= */ null, listener, handler);
    }

    public boolean addListener(@NonNull TListener listener, @NonNull Executor executor)
            throws RemoteException {
        return addInternal(/* request= */ null, listener, executor);
    }

    public boolean addListener(@Nullable TRequest request, @NonNull TListener listener,
            @NonNull Handler handler) throws RemoteException {
        return addInternal(request, listener, handler);
    }

    public boolean addListener(@Nullable TRequest request, @NonNull TListener listener,
            @NonNull Executor executor) throws RemoteException {
        return addInternal(request, listener, executor);
    }

    protected final boolean addInternal(@Nullable TRequest request, @NonNull Object listener,
            @NonNull Handler handler) throws RemoteException {
        return addInternal(request, listener, new HandlerExecutor(handler));
    }

    protected final boolean addInternal(@Nullable TRequest request, @NonNull Object listener,
            @NonNull Executor executor)
            throws RemoteException {
        Preconditions.checkArgument(listener != null, "invalid null listener/callback");
        return addInternal(listener, new Registration<>(request, executor, convertKey(listener)));
    }

    private boolean addInternal(Object key, Registration<TRequest, TListener> registration)
            throws RemoteException {
        Preconditions.checkNotNull(registration);

        synchronized (mLock) {
            boolean initialRequest = mListeners.isEmpty();

            ArrayMap<Object, Registration<TRequest, TListener>> newListeners = new ArrayMap<>(
                    mListeners.size() + 1);
            newListeners.putAll(mListeners);
            Registration<TRequest, TListener> oldRegistration = newListeners.put(key,
                    registration);
            mListeners = newListeners;

            if (oldRegistration != null) {
                oldRegistration.unregister();
            }
            TRequest merged = mergeRequests();

            if (initialRequest || !Objects.equals(merged, mMergedRequest)) {
                mMergedRequest = merged;
                if (!initialRequest) {
                    unregisterService();
                }
                registerService(mMergedRequest);
            }

            return true;
        }
    }

    public void removeListener(Object listener) throws RemoteException {
        synchronized (mLock) {
            ArrayMap<Object, Registration<TRequest, TListener>> newListeners = new ArrayMap<>(
                    mListeners);
            Registration<TRequest, TListener> oldRegistration = newListeners.remove(listener);
            mListeners = newListeners;

            if (oldRegistration == null) {
                return;
            }
            oldRegistration.unregister();

            boolean lastRequest = mListeners.isEmpty();
            TRequest merged = lastRequest ? null : mergeRequests();
            boolean newRequest = !lastRequest && !Objects.equals(merged, mMergedRequest);

            if (lastRequest || newRequest) {
                unregisterService();
                mMergedRequest = merged;
                if (newRequest) {
                    registerService(mMergedRequest);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected TListener convertKey(@NonNull Object listener) {
        return (TListener) listener;
    }

    protected abstract boolean registerService(TRequest request) throws RemoteException;
    protected abstract void unregisterService() throws RemoteException;

    @Nullable
    protected TRequest merge(@NonNull List<TRequest> requests) {
        for (TRequest request : requests) {
            Preconditions.checkArgument(request == null,
                    "merge() has to be overridden for non-null requests.");
        }
        return null;
    }

    protected void execute(Consumer<TListener> operation) {
        for (Registration<TRequest, TListener> registration : mListeners.values()) {
            registration.execute(operation);
        }
    }

    @GuardedBy("mLock")
    @SuppressWarnings("unchecked")
    @Nullable
    private TRequest mergeRequests() {
        Preconditions.checkState(Thread.holdsLock(mLock));

        if (mListeners.isEmpty()) {
            return null;
        }

        if (mListeners.size() == 1) {
            return mListeners.valueAt(0).getRequest();
        }

        ArrayList<TRequest> requests = new ArrayList<>(mListeners.size());
        for (int index = 0; index < mListeners.size(); index++) {
            requests.add(mListeners.valueAt(index).getRequest());
        }
        return merge(requests);
    }
}
