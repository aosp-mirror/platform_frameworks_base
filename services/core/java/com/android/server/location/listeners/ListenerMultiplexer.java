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
import android.os.Build;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.listeners.ListenerExecutor.ListenerOperation;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A base class to multiplex client listener registrations within system server. Every listener is
 * represented by a registration object which stores all required state for a listener. Keys are
 * used to uniquely identify every registration. Listener operations may be executed on
 * registrations in order to invoke the represented listener.
 *
 * Registrations are divided into two categories, active registrations and inactive registrations,
 * as defined by {@link #isActive(ListenerRegistration)}. If a registration's active state changes,
 * {@link #updateRegistrations(Predicate)} must be invoked and return true for any registration
 * whose active state may have changed. Listeners will only be invoked for active registrations.
 *
 * The set of active registrations is combined into a single merged registration, which is submitted
 * to the backing service when necessary in order to register the service. The merged registration
 * is updated whenever the set of active registration changes.
 *
 * Callbacks invoked for various changes will always be ordered according to this lifecycle list:
 *
 * <ul>
 * <li>{@link #onRegister()}</li>
 * <li>{@link ListenerRegistration#onRegister(Object)}</li>
 * <li>{@link #onRegistrationAdded(Object, ListenerRegistration)}</li>
 * <li>{@link #onRegistrationReplaced(Object, ListenerRegistration, ListenerRegistration)} (only
 * invoked if this registration is replacing a prior registration)</li>
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
 * @param <TKey>                key type
 * @param <TListener>           listener type
 * @param <TRegistration>       registration type
 * @param <TMergedRegistration> merged registration type
 */
public abstract class ListenerMultiplexer<TKey, TListener,
        TRegistration extends ListenerRegistration<TListener>, TMergedRegistration> {

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
    @Nullable private TMergedRegistration mMerged;

    /**
     * Should be implemented to return a unique identifying tag that may be used for logging, etc...
     */
    public abstract @NonNull String getTag();

    /**
     * Should be implemented to register with the backing service with the given merged
     * registration, and should return true if a matching call to {@link #unregisterWithService()}
     * is required to unregister (ie, if registration succeeds). The set of registrations passed in
     * is the same set passed into {@link #mergeRegistrations(Collection)} to generate the merged
     * registration.
     *
     * <p class="note">It may seem redundant to pass in the set of active registrations when they
     * have already been used to generate the merged request, and indeed, for many implementations
     * this parameter can likely simply be ignored. However, some implementations may require access
     * to the set of registrations used to generate the merged requestion for further logic even
     * after the merged registration has been generated.
     *
     * @see #mergeRegistrations(Collection)
     * @see #reregisterWithService(Object, Object, Collection)
     */
    protected abstract boolean registerWithService(TMergedRegistration merged,
            @NonNull Collection<TRegistration> registrations);

    /**
     * Invoked when the service has already been registered with some merged registration, and is
     * now being registered with a different merged registration. The default implementation simply
     * invokes {@link #registerWithService(Object, Collection)}.
     *
     * @see #registerWithService(Object, Collection)
     */
    protected boolean reregisterWithService(TMergedRegistration oldMerged,
            TMergedRegistration newMerged, @NonNull Collection<TRegistration> registrations) {
        return registerWithService(newMerged, registrations);
    }

    /**
     * Should be implemented to unregister from the backing service.
     */
    protected abstract void unregisterWithService();

    /**
     * Defines whether a registration is currently active or not. Only active registrations will be
     * forwarded to {@link #registerWithService(Object, Collection)}, and listener invocations will
     * only be delivered to active requests. If a registration's active state changes,
     * {@link #updateRegistrations(Predicate)} must be invoked with a function that returns true for
     * any registrations that may have changed their active state.
     */
    protected abstract boolean isActive(@NonNull TRegistration registration);

    /**
     * Called in order to generate a merged registration from the given set of active registrations.
     * The list of registrations will never be empty. If the resulting merged registration is equal
     * to the currently registered merged registration, nothing further will happen. If the merged
     * registration differs, {@link #registerWithService(Object, Collection)} or
     * {@link #reregisterWithService(Object, Object, Collection)} will be invoked with the new
     * merged registration so that the backing service can be updated.
     */
    protected abstract @Nullable TMergedRegistration mergeRegistrations(
            @NonNull Collection<TRegistration> registrations);

    /**
     * Invoked when the multiplexer goes from having no registrations to having some registrations.
     * This is a convenient entry point for registering listeners, etc, which only need to be
     * present while there are any registrations. Invoked while holding the multiplexer's internal
     * lock.
     */
    protected void onRegister() {}

    /**
     * Invoked when the multiplexer goes from having some registrations to having no registrations.
     * This is a convenient entry point for unregistering listeners, etc, which only need to be
     * present while there are any registrations. Invoked while holding the multiplexer's internal
     * lock.
     */
    protected void onUnregister() {}

    /**
     * Invoked when a registration is added. Invoked while holding the multiplexer's internal lock.
     */
    protected void onRegistrationAdded(@NonNull TKey key, @NonNull TRegistration registration) {}

    /**
     * Invoked instead of {@link #onRegistrationAdded(Object, ListenerRegistration)} if a
     * registration is replacing an old registration. The old registration will have already been
     * unregistered. Invoked while holding the multiplexer's internal lock. The default behavior is
     * simply to call into {@link #onRegistrationAdded(Object, ListenerRegistration)}.
     */
    protected void onRegistrationReplaced(@NonNull TKey key, @NonNull TRegistration oldRegistration,
            @NonNull TRegistration newRegistration) {
        onRegistrationAdded(key, newRegistration);
    }

    /**
     * Invoked when a registration is removed. Invoked while holding the multiplexer's internal
     * lock.
     */
    protected void onRegistrationRemoved(@NonNull TKey key, @NonNull TRegistration registration) {}

    /**
     * Invoked when the multiplexer goes from having no active registrations to having some active
     * registrations. This is a convenient entry point for registering listeners, etc, which only
     * need to be present while there are active registrations. Invoked while holding the
     * multiplexer's internal lock.
     */
    protected void onActive() {}

    /**
     * Invoked when the multiplexer goes from having some active registrations to having no active
     * registrations. This is a convenient entry point for unregistering listeners, etc, which only
     * need to be present while there are active registrations. Invoked while holding the
     * multiplexer's internal lock.
     */
    protected void onInactive() {}

    /**
     * Puts a new registration with the given key, replacing any previous registration under the
     * same key. This method cannot be called to put a registration re-entrantly.
     */
    protected final void putRegistration(@NonNull TKey key, @NonNull TRegistration registration) {
        replaceRegistration(key, key, registration);
    }

    /**
     * Atomically removes the registration with the old key and adds a new registration with the
     * given key. If there was a registration for the old key,
     * {@link #onRegistrationReplaced(Object, ListenerRegistration, ListenerRegistration)} will be
     * invoked for the new registration and key instead of
     * {@link #onRegistrationAdded(Object, ListenerRegistration)}, even though they may not share
     * the same key. The old key may be the same value as the new key, in which case this function
     * is equivalent to {@link #putRegistration(Object, ListenerRegistration)}. This method cannot
     * be called to add a registration re-entrantly.
     */
    protected final void replaceRegistration(@NonNull TKey oldKey, @NonNull TKey key,
            @NonNull TRegistration registration) {
        Objects.requireNonNull(oldKey);
        Objects.requireNonNull(key);
        Objects.requireNonNull(registration);

        synchronized (mRegistrations) {
            // adding listeners reentrantly is not supported
            Preconditions.checkState(!mReentrancyGuard.isReentrant());

            // new key may only have a prior registration if the oldKey is the same as the key
            Preconditions.checkArgument(oldKey == key || !mRegistrations.containsKey(key));

            // since adding a registration can invoke a variety of callbacks, we need to ensure
            // those callbacks themselves do not re-enter, as this could lead to out-of-order
            // callbacks. further, we buffer service updates since adding a registration may
            // involve removing a prior registration. note that try-with-resources ordering is
            // meaningful here as well. we want to close the reentrancy guard first, as this may
            // generate additional service updates, then close the update service buffer.
            try (UpdateServiceBuffer ignored1 = mUpdateServiceBuffer.acquire();
                 ReentrancyGuard ignored2 = mReentrancyGuard.acquire()) {

                boolean wasEmpty = mRegistrations.isEmpty();

                TRegistration oldRegistration = null;
                int index = mRegistrations.indexOfKey(oldKey);
                if (index >= 0) {
                    oldRegistration = removeRegistration(index, oldKey != key);
                }
                if (oldKey == key && index >= 0) {
                    mRegistrations.setValueAt(index, registration);
                } else {
                    mRegistrations.put(key, registration);
                }

                if (wasEmpty) {
                    onRegister();
                }
                registration.onRegister(key);
                if (oldRegistration == null) {
                    onRegistrationAdded(key, registration);
                } else {
                    onRegistrationReplaced(key, oldRegistration, registration);
                }
                onRegistrationActiveChanged(registration);
            }
        }
    }

    /**
     * Removes the registration with the given key. This method cannot be called to remove a
     * registration re-entrantly.
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
     * Removes all registrations with keys that satisfy the given predicate. This method cannot be
     * called to remove a registration re-entrantly.
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
     * registration at the time this method is called, nothing happens. This method allows for
     * re-entrancy, and may be called to remove a registration re-entrantly.
     */
    protected final void removeRegistration(@NonNull Object key,
            @NonNull ListenerRegistration<?> registration) {
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
    private TRegistration removeRegistration(int index, boolean removeEntry) {
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
        }

        return registration;
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

            if (actives.isEmpty()) {
                if (mServiceRegistered) {
                    mMerged = null;
                    mServiceRegistered = false;
                    unregisterWithService();
                }
                return;
            }

            TMergedRegistration merged = mergeRegistrations(actives);
            if (!mServiceRegistered || !Objects.equals(merged, mMerged)) {
                if (mServiceRegistered) {
                    mServiceRegistered = reregisterWithService(mMerged, merged, actives);
                } else {
                    mServiceRegistered = registerWithService(merged, actives);
                }
                mMerged = mServiceRegistered ? merged : null;
            }
        }
    }

    /**
     * If the service is currently registered, unregisters it and then calls
     * {@link #updateService()} so that {@link #registerWithService(Object, Collection)} will be
     * re-invoked. This is useful, for instance, if the backing service has crashed or otherwise
     * lost state, and needs to be re-initialized. Because this unregisters first, this is safe to
     * use even if there is a possibility the backing server has not crashed, or has already been
     * reinitialized.
     */
    protected final void resetService() {
        synchronized (mRegistrations) {
            if (mServiceRegistered) {
                mMerged = null;
                mServiceRegistered = false;
                unregisterWithService();
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
     * state of the registration may have changed as a result. If the active state of any
     * registration has changed, {@link #updateService()} will automatically be invoked to handle
     * the resulting changes.
     */
    protected final void updateRegistrations(@NonNull Predicate<TRegistration> predicate) {
        synchronized (mRegistrations) {
            // since updating a registration can invoke a variety of callbacks, we need to ensure
            // those callbacks themselves do not re-enter, as this could lead to out-of-order
            // callbacks. note that try-with-resources ordering is meaningful here as well. we want
            // to close the reentrancy guard first, as this may generate additional service updates,
            // then close the update service buffer.
            try (UpdateServiceBuffer ignored1 = mUpdateServiceBuffer.acquire();
                 ReentrancyGuard ignored2 = mReentrancyGuard.acquire()) {

                final int size = mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    if (predicate.test(registration)) {
                        onRegistrationActiveChanged(registration);
                    }
                }
            }
        }
    }

    /**
     * Evaluates the predicate on a registration with the given key. The predicate should return
     * true if the active state of the registration may have changed as a result. If the active
     * state of the registration has changed, {@link #updateService()} will automatically be invoked
     * to handle the resulting changes. Returns true if there is a registration with the given key
     * (and thus the predicate was invoked), and false otherwise.
     */
    protected final boolean updateRegistration(@NonNull Object key,
            @NonNull Predicate<TRegistration> predicate) {
        synchronized (mRegistrations) {
            // since updating a registration can invoke a variety of callbacks, we need to ensure
            // those callbacks themselves do not re-enter, as this could lead to out-of-order
            // callbacks. note that try-with-resources ordering is meaningful here as well. we want
            // to close the reentrancy guard first, as this may generate additional service updates,
            // then close the update service buffer.
            try (UpdateServiceBuffer ignored1 = mUpdateServiceBuffer.acquire();
                 ReentrancyGuard ignored2 = mReentrancyGuard.acquire()) {

                int index = mRegistrations.indexOfKey(key);
                if (index < 0) {
                    return false;
                }

                TRegistration registration = mRegistrations.valueAt(index);
                if (predicate.test(registration)) {
                    onRegistrationActiveChanged(registration);
                }
                return true;
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
                registration.onActive();
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
            try (ReentrancyGuard ignored = mReentrancyGuard.acquire()) {
                final int size = mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    if (registration.isActive()) {
                        ListenerOperation<TListener> operation = function.apply(registration);
                        if (operation != null) {
                            registration.executeOperation(operation);
                        }
                    }
                }
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
            try (ReentrancyGuard ignored = mReentrancyGuard.acquire()) {
                final int size = mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    if (registration.isActive()) {
                        registration.executeOperation(operation);
                    }
                }
            }
        }
    }

    private void unregister(TRegistration registration) {
        registration.unregisterInternal();
        onRegistrationActiveChanged(registration);
    }

    /**
     * Dumps debug information.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mRegistrations) {
            pw.print("service: ");
            pw.print(getServiceState());
            pw.println();

            if (!mRegistrations.isEmpty()) {
                pw.println("listeners:");

                final int size = mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = mRegistrations.valueAt(i);
                    pw.print("  ");
                    pw.print(registration);
                    if (!registration.isActive()) {
                        pw.println(" (inactive)");
                    } else {
                        pw.println();
                    }
                }
            }
        }
    }

    /**
     * May be overridden to provide additional details on service state when dumping the manager
     * state. Invoked while holding the multiplexer's internal lock.
     */
    protected String getServiceState() {
        if (mServiceRegistered) {
            if (mMerged != null) {
                return mMerged.toString();
            } else {
                return "registered";
            }
        } else {
            return "unregistered";
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
        private @Nullable ArraySet<Entry<Object, ListenerRegistration<?>>> mScheduledRemovals;

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
        void markForRemoval(Object key, ListenerRegistration<?> registration) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mRegistrations));
            }
            Preconditions.checkState(isReentrant());

            if (mScheduledRemovals == null) {
                mScheduledRemovals = new ArraySet<>(mRegistrations.size());
            }
            mScheduledRemovals.add(new AbstractMap.SimpleImmutableEntry<>(key, registration));
        }

        ReentrancyGuard acquire() {
            ++mGuardCount;
            return this;
        }

        @Override
        public void close() {
            ArraySet<Entry<Object, ListenerRegistration<?>>> scheduledRemovals = null;

            Preconditions.checkState(mGuardCount > 0);
            if (--mGuardCount == 0) {
                scheduledRemovals = mScheduledRemovals;
                mScheduledRemovals = null;
            }

            if (scheduledRemovals == null) {
                return;
            }

            try (UpdateServiceBuffer ignored = mUpdateServiceBuffer.acquire()) {
                final int size = scheduledRemovals.size();
                for (int i = 0; i < size; i++) {
                    Entry<Object, ListenerRegistration<?>> entry = scheduledRemovals.valueAt(i);
                    removeRegistration(entry.getKey(), entry.getValue());
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
