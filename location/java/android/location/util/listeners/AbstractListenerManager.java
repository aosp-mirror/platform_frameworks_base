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

package android.location.util.listeners;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Binder;
import android.os.Build;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A base class to manage listeners multiplexed from some source.
 *
 * @param <TKey>           key type
 * @param <TRequest>       request type
 * @param <TListener>      listener type
 * @param <TRegistration>  registration type
 * @param <TMergedRequest> merged request type
 * @hide
 */
public abstract class AbstractListenerManager<TKey, TRequest, TListener,
        TRegistration extends AbstractListenerManager.Registration<TRequest, TListener>,
        TMergedRequest> {

    /**
     * A listener registration object which holds data associated with the listener.
     *
     * @param <TRequest>  request type
     * @param <TListener> listener type
     */
    public static class Registration<TRequest, TListener> {

        private final Executor mExecutor;
        private volatile @Nullable TRequest mRequest;
        private volatile @Nullable TListener mListener;

        protected Registration(@Nullable TRequest request, @NonNull Executor executor,
                @NonNull TListener listener) {
            Preconditions.checkArgument(executor != null, "invalid null executor");
            Preconditions.checkArgument(listener != null, "invalid null listener/callback");
            mExecutor = executor;
            mRequest = request;
            mListener = listener;
        }

        /**
         * Returns the request associated with this listener, or null if one wasn't supplied.
         */
        public @Nullable TRequest getRequest() {
            return mRequest;
        }

        boolean register() {
            Preconditions.checkState(mListener != null);
            return onRegister();
        }

        protected final void unregister() {
            if (mListener != null) {
                onUnregister();
                mRequest = null;
                mListener = null;
            }
        }

        /**
         * May be overridden by subclasses. Invoked when registration is occurring. If this returns
         * true, then registration will complete successfully. If this returns false, registration
         * will fail.
         */
        protected boolean onRegister() {
            return true;
        }

        /**
         * May be overridden by subclasses. Invoked before unregistration occurs.
         */
        protected void onUnregister() {}

        void execute(Consumer<TListener> operation) {
            mExecutor.execute(() -> {
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
            });
        }

        @Override
        public String toString() {
            if (mRequest == null) {
                return "[]";
            } else {
                return mRequest.toString();
            }
        }
    }

    @GuardedBy("mRegistrations")
    private final ArrayMap<TKey, TRegistration> mRegistrations = new ArrayMap<>();

    @GuardedBy("mRegistrations")
    private final ReentrancyGuard mReentrancyGuard = new ReentrancyGuard();

    @GuardedBy("mRegistrations")
    private boolean mActiveRegistrations = false;

    @GuardedBy("mRegistrations")
    private boolean mServiceRegistered = false;

    @GuardedBy("mRegistrations")
    private TMergedRequest mCurrentRequest;

    /**
     * Adds a new registration with the given key. If the registration succeeds,
     * {@link #onRegistrationAdded(Object, Registration)} will be invoked.
     */
    protected final void addRegistration(@NonNull TKey key, @NonNull TRegistration registration) {
        synchronized (mRegistrations) {
            // this class does not support adding listeners reentrantly
            Preconditions.checkState(!mReentrancyGuard.isReentrant());

            if (!registration.register()) {
                registration.unregister();
                return;
            }

            TRegistration old = mRegistrations.put(Objects.requireNonNull(key), registration);
            if (old != null) {
                onRegistrationRemoved(key, old);
                old.unregister();
            }

            onRegistrationAdded(key, registration);
            updateService();
        }
    }

    /**
     * Removes the given registration with the given key. If unregistration occurs,
     * {@link #onRegistrationRemoved(Object, Registration)} will be called.
     */
    protected final void removeRegistration(@NonNull TKey key,
            @NonNull TRegistration registration) {
        synchronized (mRegistrations) {
            if (mRegistrations.remove(key, registration)) {
                unregisterRegistration(key, registration);
            }
        }
    }

    /**
     * Removes the registration with the given key. If unregistration occurs,
     * {@link #onRegistrationRemoved(Object, Registration)} will be called.
     */
    protected final void removeRegistration(@NonNull TKey key) {
        synchronized (mRegistrations) {
            TRegistration registration = mRegistrations.remove(key);
            if (registration != null) {
                unregisterRegistration(key, registration);
            }
        }
    }

    @GuardedBy("mRegistrations")
    private void unregisterRegistration(TKey key, TRegistration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mRegistrations));
        }

        // this class does not support removing listeners reentrantly
        Preconditions.checkState(!mReentrancyGuard.isReentrant());

        onRegistrationRemoved(key, registration);
        registration.unregister();
        updateService();
    }

    /**
     * Forces a re-evalution of the active state of all registrations, the merged request for all
     * active registrations, and service registration state.
     */
    protected void updateService() {
        synchronized (mRegistrations) {
            ArrayList<TRegistration> actives = new ArrayList<>(mRegistrations.size());
            for (int i = 0; i < mRegistrations.size(); i++) {
                TRegistration registration = mRegistrations.valueAt(i);
                if (isActive(registration)) {
                    actives.add(registration);
                }
            }

            if (actives.isEmpty()) {
                if (mServiceRegistered) {
                    unregisterService();
                    mServiceRegistered = false;
                }
                mCurrentRequest = null;

                if (mActiveRegistrations) {
                    mActiveRegistrations = false;
                    onInactive();
                }
                return;
            } else {
                if (!mActiveRegistrations) {
                    mActiveRegistrations = true;
                    onActive();
                }
            }

            TMergedRequest merged = mergeRequests(actives);
            if (!mServiceRegistered || !Objects.equals(merged, mCurrentRequest)) {
                if (mServiceRegistered) {
                    unregisterService();
                }
                mCurrentRequest = merged;
                mServiceRegistered = registerService(mCurrentRequest);
            }
        }
    }

    /**
     * Defines whether a registration is currently active or not. Only active registrations will be
     * considered within {@link #mergeRequests(List)} to calculate the merged request, and listener
     * invocations will only be delivered to active requests. If a registration's active state
     * changes, {@link #updateRegistrations(Function)} should be invoked with a function that
     * returns true for any registrations that have changed their active state in order to inform
     * this manager of the active status change.
     */
    protected boolean isActive(@NonNull TRegistration registration) {
        return true;
    }

    /**
     * Performs some function on all (not just active) registrations. The function should return
     * true if the active state of the registration has changed, or if the change to the
     * registration may have changed the result of {@link #mergeRequests(List)}. If the function
     * returns true for any registration, {@link #updateService()} will be invoked.
     */
    protected final void updateRegistrations(@NonNull Function<TRegistration, Boolean> function) {
        synchronized (mRegistrations) {
            boolean changed = false;
            try (ReentrancyGuard ignored = mReentrancyGuard.acquire()) {
                for (int i = 0; i < mRegistrations.size(); i++) {
                    changed |= function.apply(mRegistrations.valueAt(i));
                }
            }
            if (changed) {
                updateService();
            }
        }
    }

    /**
     * Called in order to generate a merged request from all active requests. The list of
     * registrations will never be empty.
     */
    @Nullable
    protected TMergedRequest mergeRequests(@NonNull List<TRegistration> registrations) {
        if (Build.IS_DEBUGGABLE) {
            for (TRegistration registration : registrations) {
                // if using non-null requests then implementations must override this method
                Preconditions.checkState(registration.getRequest() == null);
            }
        }

        return null;
    }

    /**
     * Should be implemented to register the service with the given request, and should return true
     * if registration succeeds.
     */
    protected abstract boolean registerService(@Nullable TMergedRequest mergedRequest);

    /**
     * Should be implemented to unregister the service.
     */
    protected abstract void unregisterService();

    /**
     * Invoked when the listener goes from having no active registrations to having some active
     * registrations. This is a convenient entry point for registering listeners, etc, which only
     * need to be present while there are active registrations. This method will always be invoked
     * before a corrosponding call to {@link #registerService(Object)}.
     */
    protected void onActive() {}

    /**
     * Invoked when the listener goes from having some active registrations to having no active
     * registrations. This is a convenient entry point for unregistering listeners, etc, which only
     * need to be present while there are active registrations. This method will always be invoked
     * after a corrosponding call to {@link #unregisterService()}.
     */
    protected void onInactive() {}

    /**
     * Invoked when a registration is added.
     */
    protected void onRegistrationAdded(@NonNull TKey key, @NonNull TRegistration registration) {}

    /**
     * Invoked when a registration is removed.
     */
    protected void onRegistrationRemoved(@NonNull TKey key, @NonNull TRegistration registration) {}

    /**
     * Executes the given delivery operation for all active listeners.
     */
    protected final void deliverToListeners(@NonNull Consumer<TListener> operation) {
        deliverToListeners(operation, registration -> true);
    }

    /**
     * Executes the given delivery operation for all active listeners which pass the given
     * predicate.
     */
    protected final void deliverToListeners(@NonNull Consumer<TListener> operation,
            @NonNull Predicate<TRegistration> deliveryPredicate) {
        synchronized (mRegistrations) {
            try (ReentrancyGuard ignored = mReentrancyGuard.acquire()) {
                for (int i = 0; i < mRegistrations.size(); i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    if (isActive(registration) && deliveryPredicate.test(registration)) {
                        registration.execute(operation);
                    }
                }
            }
        }
    }

    /**
     * Dumps debug information.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        synchronized (mRegistrations) {
            ipw.print("service: ");
            ipw.println(serviceStateToString());

            if (!mRegistrations.isEmpty()) {
                ipw.println("listeners:");

                ipw.increaseIndent();
                for (int i = 0; i < mRegistrations.size(); i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    ipw.print(registration);
                    if (!isActive(registration)) {
                        ipw.println(" (inactive)");
                    } else {
                        ipw.println();
                    }
                }
                ipw.decreaseIndent();
            }
        }
    }

    /**
     * May be override to provide additional details on service state when dumping the manager
     * state.
     */
    protected String serviceStateToString() {
        if (mServiceRegistered) {
            if (mCurrentRequest == null) {
                return "registered";
            } else {
                return "registered with " + mCurrentRequest;
            }
        } else {
            return "unregistered";
        }
    }

    // this class does not have an idempotent close(), always use via try-with-resources
    private static class ReentrancyGuard implements AutoCloseable {

        private int mGuard = 0;

        ReentrancyGuard() {
        }

        public boolean isReentrant() {
            return mGuard != 0;
        }

        public ReentrancyGuard acquire() {
            ++mGuard;
            return this;
        }

        @Override
        public void close() {
            --mGuard;
        }
    }
}
