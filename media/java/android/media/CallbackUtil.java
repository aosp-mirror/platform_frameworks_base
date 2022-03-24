/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.media.permission.ClearCallingIdentityContext;
import android.media.permission.SafeCloseable;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * @hide
 * A utility class to implement callback listeners and their management.
 * This is meant to be used for lazily-initialized listener lists and stubs for event reception,
 * typically received from server (e.g. AudioService).
 */

/*package*/ class CallbackUtil {

    private static final String TAG = "CallbackUtil";

    /**
     * Container class to store a listener and associated Executor
     * @param <T> the type of the listener
     */
    static class ListenerInfo<T> {
        final @NonNull T mListener;
        final @NonNull Executor mExecutor;

        ListenerInfo(@NonNull T listener, @NonNull Executor exe) {
            mListener = listener;
            mExecutor = exe;
        }
    }

    /**
     * Finds the listener information (listener + Executor) in a given list of listeners
     * @param listener the listener to find
     * @param listeners the list of listener informations, can be null if not instantiated yet
     * @param <T> the type of the listeners
     * @return null if the listener is not in the given list of listener informations
     */
    static <T> @Nullable ListenerInfo<T> getListenerInfo(
            @NonNull T listener, @Nullable ArrayList<ListenerInfo<T>> listeners) {
        if (listeners == null) {
            return null;
        }
        for (ListenerInfo<T> info : listeners) {
            if (info.mListener == listener) {
                return info;
            }
        }
        return null;
    }

    /**
     * Returns true if the given listener is present in the list of listener informations
     * @param listener the listener to find
     * @param listeners the list of listener informations, can be null if not instantiated yet
     * @param <T> the type of the listeners
     * @return true if the listener is in the list
     */
    static <T> boolean hasListener(@NonNull T listener,
            @Nullable ArrayList<ListenerInfo<T>> listeners) {
        return getListenerInfo(listener, listeners) != null;
    }

    /**
     * Removes the given listener from the list of listener informations
     * @param listener the listener to remove
     * @param listeners the list of listener informations, can be null if not instantiated yet
     * @param <T> the type of the listeners
     * @return true if the listener was found and removed from the list, false otherwise
     */
    static <T> boolean removeListener(@NonNull T listener,
            @Nullable ArrayList<ListenerInfo<T>> listeners) {
        final ListenerInfo<T> infoToRemove = getListenerInfo(listener, listeners);
        if (infoToRemove != null) {
            listeners.remove(infoToRemove);
            return true;
        }
        return false;
    }

    /**
     * Adds a listener and associated Executor in the list of listeners.
     * This method handles the lazy initialization of both the list of listeners and the stub
     * used to receive the events that will be forwarded to the listener, see the returned pair
     * for the updated references.
     * @param methodName the name of the method calling this, for inclusion in the
     *                   string in case of IllegalArgumentException
     * @param executor the Executor for the listener
     * @param listener the listener to add
     * @param listeners the list of listener informations, can be null if not instantiated yet
     * @param dispatchStub the stub that receives the events to be forwarded to the listeners,
     *                    can be null if not instantiated yet
     * @param newStub the function to create a new stub if needed
     * @param registerStub the function for the stub registration if needed
     * @param <T> the type of the listener interface
     * @param <S> the type of the event receiver stub
     * @return a pair of the listener list and the event receiver stub which may have been
     *         initialized if needed (e.g. on the first ever addition of a listener)
     */
    static <T, S> Pair<ArrayList<ListenerInfo<T>>, S> addListener(String methodName,
            @NonNull Executor executor,
            @NonNull T listener,
            @Nullable ArrayList<ListenerInfo<T>> listeners,
            @Nullable S dispatchStub,
            @NonNull java.util.function.Supplier<S> newStub,
            @NonNull java.util.function.Consumer<S> registerStub) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);

        if (hasListener(listener, listeners)) {
            throw new IllegalArgumentException("attempt to call " + methodName
                    + "on a previously registered listener");
        }
        // lazy initialization of the list of strategy-preferred device listener
        if (listeners == null) {
            listeners = new ArrayList<>();
        }
        if (listeners.size() == 0) {
            // register binder for callbacks
            if (dispatchStub == null) {
                try {
                    dispatchStub = newStub.get();
                } catch (Exception e) {
                    Log.e(TAG, "Exception while creating stub in " + methodName, e);
                    return new Pair<>(null, null);
                }
            }
            registerStub.accept(dispatchStub);
        }
        listeners.add(new ListenerInfo<T>(listener, executor));
        return new Pair(listeners, dispatchStub);
    }

    /**
     * Removes a listener from the list of listeners.
     * This method handles the freeing of both the list of listeners and the stub
     * used to receive the events that will be forwarded to the listener,see the returned pair
     * for the updated references.
     * @param methodName the name of the method calling this, for inclusion in the
     *                   string in case of IllegalArgumentException
     * @param listener the listener to remove
     * @param listeners the list of listener informations, can be null if not instantiated yet
     * @param dispatchStub the stub that receives the events to be forwarded to the listeners,
     *                    can be null if not instantiated yet
     * @param unregisterStub the function to unregister the stub if needed
     * @param <T> the type of the listener interface
     * @param <S> the type of the event receiver stub
     * @return a pair of the listener list and the event receiver stub which may have been
     *         changed if needed (e.g. on the removal of the last listener)
     */
    static <T, S> Pair<ArrayList<ListenerInfo<T>>, S> removeListener(String methodName,
            @NonNull T listener,
            @Nullable ArrayList<ListenerInfo<T>> listeners,
            @Nullable S dispatchStub,
            @NonNull java.util.function.Consumer<S> unregisterStub) {
        Objects.requireNonNull(listener);

        if (!removeListener(listener, listeners)) {
            throw new IllegalArgumentException("attempt to call " + methodName
                    + "on an unregistered listener");
        }
        if (listeners.size() == 0) {
            unregisterStub.accept(dispatchStub);
            return new Pair<>(null, null);
        } else {
            return new Pair<>(listeners, dispatchStub);
        }
    }

    interface CallbackMethod<T> {
        void callbackMethod(T listener);
    }

    /**
     * Exercise the callback of the listeners
     * @param listeners the list of listeners
     * @param listenerLock the lock guarding the list of listeners
     * @param callback the function to call for each listener
     * @param <T>  the type of the listener interface
     */
    static <T> void callListeners(
            @Nullable ArrayList<ListenerInfo<T>> listeners,
            @NonNull Object listenerLock,
            @NonNull CallbackMethod<T> callback) {
        Objects.requireNonNull(listenerLock);
        // make a shallow copy of listeners so callback is not executed under lock
        final ArrayList<ListenerInfo<T>> listenersShallowCopy;
        synchronized (listenerLock) {
            if (listeners == null || listeners.size() == 0) {
                return;
            }
            listenersShallowCopy = (ArrayList<ListenerInfo<T>>) listeners.clone();
        }
        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            for (ListenerInfo<T> info : listenersShallowCopy) {
                info.mExecutor.execute(() -> callback.callbackMethod(info.mListener));
            }
        }

    }

    /**
     * Interface to be implemented by stub implementation for the events received from a server
     * to the class managing the listener API.
     * For an example see {@link AudioManager#ModeDispatcherStub} which registers with AudioService.
     */
    interface DispatcherStub {
        /**
         * Register/unregister the stub as a listener of the events to be forwarded to the listeners
         * managed by LazyListenerManager.
         * @param register true for registering, false to unregister
         */
        void register(boolean register);
    }

    /**
     * Class to manage a list of listeners and their callback, and the associated stub which
     * receives the events to be forwarded to the listeners.
     * The list of listeners and the stub and its registration are lazily initialized and registered
     * @param <T> the listener class
     */
    static class LazyListenerManager<T> {
        private final Object mListenerLock = new Object();

        @GuardedBy("mListenerLock")
        private @Nullable ArrayList<ListenerInfo<T>> mListeners;

        @GuardedBy("mListenerLock")
        private @Nullable DispatcherStub mDispatcherStub;

        LazyListenerManager() {
            // nothing to initialize as instances of dispatcher and list of listeners
            // are lazily initialized
        }

        /**
         * Add a new listener / executor pair for the configured listener
         * @param executor Executor for the callback
         * @param listener the listener to register
         * @param methodName the name of the method calling this utility method for easier to read
         *          exception messages
         * @param newStub how to build a new instance of the stub receiving the events when the
         *          number of listeners goes from 0 to 1, not called until then.
         */
        void addListener(@NonNull Executor executor, @NonNull T listener, String methodName,
                @NonNull java.util.function.Supplier<DispatcherStub> newStub) {
            synchronized (mListenerLock) {
                final Pair<ArrayList<ListenerInfo<T>>, DispatcherStub> res =
                        CallbackUtil.addListener(methodName,
                                executor, listener, mListeners, mDispatcherStub,
                                newStub,
                                stub -> stub.register(true));
                mListeners = res.first;
                mDispatcherStub = res.second;
            }
        }

        /**
         * Remove a previously registered listener
         * @param listener the listener to unregister
         * @param methodName the name of the method calling this utility method for easier to read
         *          exception messages
         */
        void removeListener(@NonNull T listener, String methodName) {
            synchronized (mListenerLock) {
                final Pair<ArrayList<ListenerInfo<T>>, DispatcherStub> res =
                        CallbackUtil.removeListener(methodName,
                                listener, mListeners, mDispatcherStub,
                                stub -> stub.register(false));
                mListeners = res.first;
                mDispatcherStub = res.second;
            }
        }

        /**
         * Call the registered listeners with the given callback method
         * @param callback the listener method to invoke
         */
        @SuppressLint("GuardedBy") // lock applied inside callListeners method
        void callListeners(CallbackMethod<T> callback) {
            CallbackUtil.callListeners(mListeners, mListenerLock, callback);
        }
    }
}
