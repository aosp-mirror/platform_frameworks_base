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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Binder;
import android.os.Build;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.listeners.ListenerExecutor.ListenerOperation;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A base class to multiplex client listener registrations within system server. Registrations are
 * divided into two categories, active registrations and inactive registrations, as defined by
 * {@link #isActive(ListenerRegistration)}. If a registration's active state changes,
 * {@link #updateRegistrations(Predicate)} must be invoked and return true for any registration
 * whose active state may have changed.
 *
 * Callbacks invoked for various changes will always be ordered according to this lifecycle list:
 *
 * <ul>
 * <li>{@link #onRegister()}</li>
 * <li>{@link ListenerRegistration#onRegister(Object)}</li>
 * <li>{@link #onRegistrationAdded(Object, ListenerRegistration)}</li>
 * <li>{@link #onActive()}</li>
 * <li>{@link ListenerRegistration#onActive()}</li>
 * <li>{@link ListenerRegistration#onInactive()}</li>
 * <li>{@link #onInactive()}</li>
 * <li>{@link #onRegistrationRemoved(Object, ListenerRegistration)}</li>
 * <li>{@link ListenerRegistration#onUnregister()}</li>
 * <li>{@link #onUnregister()}</li>
 * </ul>
 *
 * Adding registrations is not allowed to be called re-entrantly (ie, while in the middle of some
 * other operation or callback. Removal is allowed re-entrantly, however only via
 * {@link #removeRegistration(Object, ListenerRegistration)}, not via any other removal method. This
 * ensures re-entrant removal does not accidentally remove the incorrect registration.
 *
 * All callbacks will be invoked with a cleared binder identity.
 *
 * Listeners owned by other processes will be run on a direct executor (and thus while holding a
 * lock). Listeners owned by the same process this multiplexer is in will be run asynchronously (and
 * thus without holding a lock). The underlying assumption is that listeners owned by other
 * processes will simply be forwarding the call to those other processes and perhaps performing
 * simple bookkeeping, with no potential for deadlock.
 *
 * @param <TKey>           key type
 * @param <TRequest>       request type
 * @param <TListener>      listener type
 * @param <TRegistration>  registration type
 * @param <TMergedRequest> merged request type
 */
public abstract class ListenerMultiplexer<TKey, TRequest, TListener,
        TRegistration extends ListenerRegistration<TRequest, TListener>, TMergedRequest> {

    @GuardedBy("mRegistrations")
    private final ArrayMap<TKey, TRegistration> mRegistrations = new ArrayMap<>();

    @GuardedBy("mRegistrations")
    private final UpdateServiceBuffer mUpdateServiceBuffer = new UpdateServiceBuffer();

    @GuardedBy("mRegistrations")
    private final ReentrancyGuard mReentrancyGuard = new ReentrancyGuard();

    @GuardedBy("mRegistrations")
    private int mActiveRegistrationsCount = 0;

    @GuardedBy("mRegistrations")
    private boolean mServiceRegistered = false;

    @GuardedBy("mRegistrations")
    private TMergedRequest mCurrentRequest;

    /**
     * Should be implemented to register with the backing service with the given merged request, and
     * should return true if a matching call to {@link #unregisterWithService()} is required to
     * unregister (ie, if registration succeeds).
     *
     * @see #reregisterWithService(Object)
     */
    protected abstract boolean registerWithService(TMergedRequest mergedRequest);

    /**
     * Invoked when the service already has a request, and it is being replaced with a new request.
     * The default implementation unregisters first, then registers with the new merged request, but
     * this may be overridden by subclasses in order to reregister more efficiently.
     */
    protected boolean reregisterWithService(TMergedRequest mergedRequest) {
        unregisterWithService();
        return registerWithService(mergedRequest);
    }

    /**
     * Should be implemented to unregister from the backing service.
     */
    protected abstract void unregisterWithService();

    /**
     * Defines whether a registration is currently active or not. Only active registrations will be
     * considered within {@link #mergeRequests(Collection)} to calculate the merged request, and
     * listener invocations will only be delivered to active requests. If a registration's active
     * state changes, {@link #updateRegistrations(Predicate)} must be invoked with a function that
     * returns true for any registrations that may have changed their active state.
     */
    protected abstract boolean isActive(@NonNull TRegistration registration);

    /**
     * Called in order to generate a merged request from the given registrations. The list of
     * registrations will never be empty.
     */
    protected @Nullable TMergedRequest mergeRequests(
            @NonNull Collection<TRegistration> registrations) {
        if (Build.IS_DEBUGGABLE) {
            for (TRegistration registration : registrations) {
                // if using non-null requests then implementations must override this method
                Preconditions.checkState(registration.getRequest() == null);
            }
        }

        return null;
    }

    /**
     * Invoked before the first registration occurs. This is a convenient entry point for
     * registering listeners, etc, which only need to be present while there are any registrations.
     */
    protected void onRegister() {}

    /**
     * Invoked after the last unregistration occurs. This is a convenient entry point for
     * unregistering listeners, etc, which only need to be present while there are any
     * registrations.
     */
    protected void onUnregister() {}

    /**
     * Invoked when a registration is added.
     */
    protected void onRegistrationAdded(@NonNull TKey key, @NonNull TRegistration registration) {}

    /**
     * Invoked when a registration is removed.
     */
    protected void onRegistrationRemoved(@NonNull TKey key, @NonNull TRegistration registration) {}

    /**
     * Invoked when the manager goes from having no active registrations to having some active
     * registrations. This is a convenient entry point for registering listeners, etc, which only
     * need to be present while there are active registrations.
     */
    protected void onActive() {}

    /**
     * Invoked when the manager goes from having some active registrations to having no active
     * registrations. This is a convenient entry point for unregistering listeners, etc, which only
     * need to be present while there are active registrations.
     */
    protected void onInactive() {}

    /**
     * Adds a new registration with the given key. Registration may fail if
     * {@link ListenerRegistration#onRegister(Object)} returns false, in which case the registration
     * will not be added. This method cannot be called to add a registration re-entrantly.
     */
    protected final void addRegistration(@NonNull TKey key, @NonNull TRegistration registration) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(registration);

        synchronized (mRegistrations) {
            // adding listeners reentrantly is not supported
            Preconditions.checkState(!mReentrancyGuard.isReentrant());

            // since adding a registration can invoke a variety of callbacks, we need to ensure
            // those callbacks themselves do not re-enter, as this could lead to out-of-order
            // callbacks. further, we buffer service updates since adding a registration may
            // involve removing a prior registration. note that try-with-resources ordering is
            // meaningful here as well. we want to close the reentrancy guard first, as this may
            // generate additional service updates, then close the update service buffer.
            long identity = Binder.clearCallingIdentity();
            try (UpdateServiceBuffer ignored1 = mUpdateServiceBuffer.acquire();
                 ReentrancyGuard ignored2 = mReentrancyGuard.acquire()) {

                boolean wasEmpty = mRegistrations.isEmpty();

                int index = mRegistrations.indexOfKey(key);
                if (index >= 0) {
                    removeRegistration(index, false);
                    mRegistrations.setValueAt(index, registration);
                } else {
                    mRegistrations.put(key, registration);
                }

                if (wasEmpty) {
                    onRegister();
                }
                registration.onRegister(key);
                onRegistrationAdded(key, registration);
                onRegistrationActiveChanged(registration);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * Removes the registration with the given key. If unregistration occurs,
     * {@link #onRegistrationRemoved(Object, ListenerRegistration)} will be called. This method
     * cannot be called to remove a registration re-entrantly.
     */
    protected final void removeRegistration(@NonNull Object key) {
        synchronized (mRegistrations) {
            // this method does not support removing listeners reentrantly
            Preconditions.checkState(!mReentrancyGuard.isReentrant());

            int index = mRegistrations.indexOfKey(key);
            if (index < 0) {
                return;
            }

            removeRegistration(index, true);
        }
    }

    /**
     * Removes all registrations with keys that satisfy the given predicate. If unregistration
     * occurs, {@link #onRegistrationRemoved(Object, ListenerRegistration)} will be called. This
     * method cannot be called to remove a registration re-entrantly.
     */
    protected final void removeRegistrationIf(@NonNull Predicate<TKey> predicate) {
        synchronized (mRegistrations) {
            // this method does not support removing listeners reentrantly
            Preconditions.checkState(!mReentrancyGuard.isReentrant());

            // since removing a registration can invoke a variety of callbacks, we need to ensure
            // those callbacks themselves do not re-enter, as this could lead to out-of-order
            // callbacks. further, we buffer service updates since chains of removeLater()
            // invocations could result in multiple service updates. note that try-with-resources
            // ordering is meaningful here as well. we want to close the reentrancy guard first, as
            // this may generate additional service updates, then close the update service buffer.
            try (UpdateServiceBuffer ignored1 = mUpdateServiceBuffer.acquire();
                 ReentrancyGuard ignored2 = mReentrancyGuard.acquire()) {

                final int size = mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TKey key = mRegistrations.keyAt(i);
                    if (predicate.test(key)) {
                        removeRegistration(key, mRegistrations.valueAt(i));
                    }
                }
            }
        }
    }

    /**
     * Removes the given registration with the given key. If the given key has a different
     * registration at the time this method is called, nothing happens. If unregistration occurs,
     * {@link #onRegistrationRemoved(Object, ListenerRegistration)} will be called. This method
     * allows for re-entrancy, and may be called to remove a registration re-entrantly. In this case
     * the registration will immediately be marked inactive and unregistered, and will be removed
     * completely at some later time.
     */
    protected final void removeRegistration(@NonNull Object key,
            @NonNull ListenerRegistration<?, ?> registration) {
        synchronized (mRegistrations) {
            int index = mRegistrations.indexOfKey(key);
            if (index < 0) {
                return;
            }

            TRegistration typedRegistration = mRegistrations.valueAt(index);
            if (typedRegistration != registration) {
                return;
            }

            if (mReentrancyGuard.isReentrant()) {
                unregister(typedRegistration);
                mReentrancyGuard.markForRemoval(key, typedRegistration);
            } else {
                removeRegistration(index, true);
            }
        }
    }

    @GuardedBy("mRegistrations")
    private void removeRegistration(int index, boolean removeEntry) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mRegistrations));
        }

        TKey key = mRegistrations.keyAt(index);
        TRegistration registration = mRegistrations.valueAt(index);

        // since removing a registration can invoke a variety of callbacks, we need to ensure those
        // callbacks themselves do not re-enter, as this could lead to out-of-order callbacks.
        // further, we buffer service updates since chains of removeLater() invocations could result
        // in multiple service updates. note that try-with-resources ordering is meaningful here as
        // well. we want to close the reentrancy guard first, as this may generate additional
        // service updates, then close the update service buffer.
        long identity = Binder.clearCallingIdentity();
        try (UpdateServiceBuffer ignored1 = mUpdateServiceBuffer.acquire();
             ReentrancyGuard ignored2 = mReentrancyGuard.acquire()) {

            unregister(registration);
            onRegistrationRemoved(key, registration);
            registration.onUnregister();
            if (removeEntry) {
                mRegistrations.removeAt(index);
                if (mRegistrations.isEmpty()) {
                    onUnregister();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Forces a re-evalution of the merged request for all active registrations and updates service
     * registration accordingly.
     */
    protected final void updateService() {
        synchronized (mRegistrations) {
            if (mUpdateServiceBuffer.isBuffered()) {
                mUpdateServiceBuffer.markUpdateServiceRequired();
                return;
            }

            ArrayList<TRegistration> actives = new ArrayList<>(mRegistrations.size());
            final int size = mRegistrations.size();
            for (int i = 0; i < size; i++) {
                TRegistration registration = mRegistrations.valueAt(i);
                if (registration.isActive()) {
                    actives.add(registration);
                }
            }

            long identity = Binder.clearCallingIdentity();
            try {
                if (actives.isEmpty()) {
                    mCurrentRequest = null;
                    if (mServiceRegistered) {
                        mServiceRegistered = false;
                        unregisterWithService();
                    }
                    return;
                }

                TMergedRequest merged = mergeRequests(actives);
                if (!mServiceRegistered || !Objects.equals(merged, mCurrentRequest)) {
                    if (mServiceRegistered) {
                        mServiceRegistered = reregisterWithService(merged);
                    } else {
                        mServiceRegistered = registerWithService(merged);
                    }
                    mCurrentRequest = merged;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * Evaluates the given predicate for all registrations, and forces an {@link #updateService()}
     * if any predicate returns true for an active registration. The predicate will always be
     * evaluated for all registrations, even inactive registrations, or if it has already returned
     * true for a prior registration.
     */
    protected final void updateService(Predicate<TRegistration> predicate) {
        synchronized (mRegistrations) {
            boolean updateService = false;
            final int size = mRegistrations.size();
            for (int i = 0; i < size; i++) {
                TRegistration registration = mRegistrations.valueAt(i);
                if (predicate.test(registration) && registration.isActive()) {
                    updateService = true;
                }
            }

            if (updateService) {
                updateService();
            }
        }
    }

    /**
     * Begins buffering calls to {@link #updateService()} until {@link UpdateServiceLock#close()}
     * is called. This is useful to prevent extra work when combining multiple calls (for example,
     * buffering {@code updateService()} until after multiple adds/removes/updates occur.
     */
    public UpdateServiceLock newUpdateServiceLock() {
        return new UpdateServiceLock(mUpdateServiceBuffer.acquire());
    }

    /**
     * Evaluates the predicate on all registrations. The predicate should return true if the active
     * state of the registration may have changed as a result. Any {@link #updateService()}
     * invocations made while this method is executing will be deferred until after the method is
     * complete so as to avoid redundant work.
     */
    protected final void updateRegistrations(@NonNull Predicate<TRegistration> predicate) {
        synchronized (mRegistrations) {
            // since updating a registration can invoke a variety of callbacks, we need to ensure
            // those callbacks themselves do not re-enter, as this could lead to out-of-order
            // callbacks. note that try-with-resources ordering is meaningful here as well. we want
            // to close the reentrancy guard first, as this may generate additional service updates,
            // then close the update service buffer.
            long identity = Binder.clearCallingIdentity();
            try (UpdateServiceBuffer ignored1 = mUpdateServiceBuffer.acquire();
                 ReentrancyGuard ignored2 = mReentrancyGuard.acquire()) {

                final int size = mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    if (predicate.test(registration)) {
                        onRegistrationActiveChanged(registration);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mRegistrations")
    private void onRegistrationActiveChanged(TRegistration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mRegistrations));
        }

        boolean active = registration.isRegistered() && isActive(registration);
        boolean changed = registration.setActive(active);
        if (changed) {
            if (active) {
                if (++mActiveRegistrationsCount == 1) {
                    onActive();
                }
                ListenerOperation<TListener> operation = registration.onActive();
                if (operation != null) {
                    execute(registration, operation);
                }
            } else {
                registration.onInactive();
                if (--mActiveRegistrationsCount == 0) {
                    onInactive();
                }
            }

            updateService();
        }
    }

    /**
     * Executes the given function for all active registrations. If the function returns a non-null
     * operation, that operation will be invoked with the associated listener. The function may not
     * change the active state of the registration.
     */
    protected final void deliverToListeners(
            @NonNull Function<TRegistration, ListenerOperation<TListener>> function) {
        synchronized (mRegistrations) {
            long identity = Binder.clearCallingIdentity();
            try (ReentrancyGuard ignored = mReentrancyGuard.acquire()) {
                final int size = mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    if (registration.isActive()) {
                        ListenerOperation<TListener> operation = function.apply(registration);
                        if (operation != null) {
                            execute(registration, operation);
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * Executes the given operation for all active listeners. This is a convenience function
     * equivalent to:
     * <pre>
     * deliverToListeners(registration -> operation);
     * </pre>
     */
    protected final void deliverToListeners(@NonNull ListenerOperation<TListener> operation) {
        synchronized (mRegistrations) {
            long identity = Binder.clearCallingIdentity();
            try (ReentrancyGuard ignored = mReentrancyGuard.acquire()) {
                final int size = mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    if (registration.isActive()) {
                        execute(registration, operation);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private void unregister(TRegistration registration) {
        registration.unregisterInternal();
        onRegistrationActiveChanged(registration);
    }

    private void execute(TRegistration registration, ListenerOperation<TListener> operation) {
        registration.executeInternal(operation);
    }

    /**
     * Dumps debug information.
     */
    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        synchronized (mRegistrations) {
            ipw.print("service: ");
            dumpServiceState(ipw);
            ipw.println();

            if (!mRegistrations.isEmpty()) {
                ipw.println("listeners:");

                ipw.increaseIndent();
                final int size = mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    ipw.print(registration);
                    if (!registration.isActive()) {
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
     * May be overridden to provide additional details on service state when dumping the manager
     * state.
     */
    protected void dumpServiceState(PrintWriter pw) {
        if (mServiceRegistered) {
            if (mCurrentRequest == null) {
                pw.print("registered");
            } else {
                pw.print("registered with " + mCurrentRequest);
            }
        } else {
            pw.print("unregistered");
        }
    }

    /**
     * A reference counted helper class that guards against re-entrancy, and also helps implement
     * registration removal during reentrancy. When this class is {@link #acquire()}d, it increments
     * the reference count. To check whether re-entrancy is occurring, clients may use
     * {@link #isReentrant()}, and modify their behavior (such as by failing the call, or calling
     * {@link #markForRemoval(Object, ListenerRegistration)}). When this class is {@link #close()}d,
     * any key/registration pairs that were marked for removal prior to the close operation will
     * then be removed - which is safe since the operation will no longer be re-entrant.
     */
    private final class ReentrancyGuard implements AutoCloseable {

        @GuardedBy("mRegistrations")
        private int mGuardCount;
        @GuardedBy("mRegistrations")
        private @Nullable ArraySet<Pair<Object, ListenerRegistration<?, ?>>> mScheduledRemovals;

        ReentrancyGuard() {
            mGuardCount = 0;
            mScheduledRemovals = null;
        }

        @GuardedBy("mRegistrations")
        boolean isReentrant() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mRegistrations));
            }
            return mGuardCount != 0;
        }

        @GuardedBy("mRegistrations")
        void markForRemoval(Object key, ListenerRegistration<?, ?> registration) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mRegistrations));
            }
            Preconditions.checkState(isReentrant());

            if (mScheduledRemovals == null) {
                mScheduledRemovals = new ArraySet<>(mRegistrations.size());
            }
            mScheduledRemovals.add(new Pair<>(key, registration));
        }

        ReentrancyGuard acquire() {
            ++mGuardCount;
            return this;
        }

        @Override
        public void close() {
            ArraySet<Pair<Object, ListenerRegistration<?, ?>>> scheduledRemovals = null;

            Preconditions.checkState(mGuardCount > 0);
            if (--mGuardCount == 0) {
                scheduledRemovals = mScheduledRemovals;
                mScheduledRemovals = null;
            }

            if (scheduledRemovals != null) {
                try (UpdateServiceBuffer ignored = mUpdateServiceBuffer.acquire()) {
                    final int size = scheduledRemovals.size();
                    for (int i = 0; i < size; i++) {
                        Pair<Object, ListenerRegistration<?, ?>> pair = scheduledRemovals.valueAt(
                                i);
                        removeRegistration(pair.first, pair.second);
                    }
                }
            }
        }
    }

    /**
     * A reference counted helper class that buffers class to {@link #updateService()}. Since
     * {@link #updateService()} iterates through every registration and performs request merging
     * work, it can often be the most expensive part of any update to the multiplexer. This means
     * that if multiple calls to updateService() can be buffered, work will be saved. This class
     * allows clients to begin buffering calls after {@link #acquire()}ing this class, and when
     * {@link #close()} is called, any buffered calls to {@link #updateService()} will be combined
     * into a single final call. Clients should acquire this class when they are doing work that
     * could potentially result in multiple calls to updateService(), and close when they are done
     * with that work.
     */
    private final class UpdateServiceBuffer implements AutoCloseable {

        // requires internal locking because close() may be exposed externally and could be called
        // from any thread

        @GuardedBy("this")
        private int mBufferCount;
        @GuardedBy("this")
        private boolean mUpdateServiceRequired;

        UpdateServiceBuffer() {
            mBufferCount = 0;
            mUpdateServiceRequired = false;
        }

        synchronized boolean isBuffered() {
            return mBufferCount != 0;
        }

        synchronized void markUpdateServiceRequired() {
            Preconditions.checkState(isBuffered());
            mUpdateServiceRequired = true;
        }

        synchronized UpdateServiceBuffer acquire() {
            ++mBufferCount;
            return this;
        }

        @Override
        public void close() {
            boolean updateServiceRequired = false;
            synchronized (this) {
                Preconditions.checkState(mBufferCount > 0);
                if (--mBufferCount == 0) {
                    updateServiceRequired = mUpdateServiceRequired;
                    mUpdateServiceRequired = false;
                }
            }

            if (updateServiceRequired) {
                updateService();
            }
        }
    }

    /**
     * Acquiring this lock will buffer all calls to {@link #updateService()} until the lock is
     * {@link #close()}ed. This can be used to save work by acquiring the lock before multiple calls
     * to updateService() are expected, and closing the lock after.
     */
    public final class UpdateServiceLock implements AutoCloseable {

        private @Nullable UpdateServiceBuffer mUpdateServiceBuffer;

        UpdateServiceLock(UpdateServiceBuffer updateServiceBuffer) {
            mUpdateServiceBuffer = updateServiceBuffer;
        }

        @Override
        public void close() {
            if (mUpdateServiceBuffer != null) {
                UpdateServiceBuffer buffer = mUpdateServiceBuffer;
                mUpdateServiceBuffer = null;
                buffer.close();
            }
        }
    }
}
