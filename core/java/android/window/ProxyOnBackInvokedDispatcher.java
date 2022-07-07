/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import android.util.Pair;
import android.window.WindowOnBackInvokedDispatcher.Checker;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link OnBackInvokedDispatcher} only used to hold callbacks while an actual
 * dispatcher becomes available. <b>It does not dispatch the back events</b>.
 * <p>
 * Once the actual {@link OnBackInvokedDispatcher} becomes available,
 * {@link #setActualDispatcher(OnBackInvokedDispatcher)} needs to
 * be called and this {@link ProxyOnBackInvokedDispatcher} will pass the callback registrations
 * onto it.
 * <p>
 * This dispatcher will continue to keep track of callback registrations and when a dispatcher is
 * removed or set it will unregister the callbacks from the old one and register them on the new
 * one unless {@link #reset()} is called before.
 *
 * @hide
 */
public class ProxyOnBackInvokedDispatcher implements OnBackInvokedDispatcher {

    /**
     * List of pair representing an {@link OnBackInvokedCallback} and its associated priority.
     *
     * @see OnBackInvokedDispatcher#registerOnBackInvokedCallback(int, OnBackInvokedCallback)
     */
    private final List<Pair<OnBackInvokedCallback, Integer>> mCallbacks = new ArrayList<>();
    private final Object mLock = new Object();
    private OnBackInvokedDispatcher mActualDispatcher = null;
    private ImeOnBackInvokedDispatcher mImeDispatcher;
    private final Checker mChecker;

    public ProxyOnBackInvokedDispatcher(boolean applicationCallBackEnabled) {
        mChecker = new Checker(applicationCallBackEnabled);
    }

    @Override
    public void registerOnBackInvokedCallback(
            int priority, @NonNull OnBackInvokedCallback callback) {
        if (DEBUG) {
            Log.v(TAG, String.format("Proxy register %s. mActualDispatcher=%s", callback,
                    mActualDispatcher));
        }
        if (mChecker.checkApplicationCallbackRegistration(priority, callback)) {
            registerOnBackInvokedCallbackUnchecked(callback, priority);
        }
    }

    @Override
    public void registerSystemOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) {
        registerOnBackInvokedCallbackUnchecked(callback, PRIORITY_SYSTEM);
    }

    @Override
    public void unregisterOnBackInvokedCallback(
            @NonNull OnBackInvokedCallback callback) {
        if (DEBUG) {
            Log.v(TAG, String.format("Proxy unregister %s. Actual=%s", callback,
                    mActualDispatcher));
        }
        synchronized (mLock) {
            mCallbacks.removeIf((p) -> p.first.equals(callback));
            if (mActualDispatcher != null) {
                mActualDispatcher.unregisterOnBackInvokedCallback(callback);
            }
        }
    }

    private void registerOnBackInvokedCallbackUnchecked(
            @NonNull OnBackInvokedCallback callback, int priority) {
        synchronized (mLock) {
            mCallbacks.add(Pair.create(callback, priority));
            if (mActualDispatcher != null) {
                if (priority <= PRIORITY_SYSTEM) {
                    mActualDispatcher.registerSystemOnBackInvokedCallback(callback);
                } else {
                    mActualDispatcher.registerOnBackInvokedCallback(priority, callback);
                }
            }
        }
    }

    /**
     * Transfers all the pending callbacks to the provided dispatcher.
     * <p>
     * The callbacks are registered on the dispatcher in the same order as they were added on this
     * proxy dispatcher.
     */
    private void transferCallbacksToDispatcher() {
        if (mActualDispatcher == null) {
            return;
        }
        if (DEBUG) {
            Log.v(TAG, String.format("Proxy transferring %d callbacks to %s", mCallbacks.size(),
                    mActualDispatcher));
        }
        if (mImeDispatcher != null) {
            mActualDispatcher.setImeOnBackInvokedDispatcher(mImeDispatcher);
        }
        for (Pair<OnBackInvokedCallback, Integer> callbackPair : mCallbacks) {
            int priority = callbackPair.second;
            if (priority >= 0) {
                mActualDispatcher.registerOnBackInvokedCallback(priority, callbackPair.first);
            } else {
                mActualDispatcher.registerSystemOnBackInvokedCallback(callbackPair.first);
            }
        }
        mCallbacks.clear();
        mImeDispatcher = null;
    }

    private void clearCallbacksOnDispatcher() {
        if (mActualDispatcher == null) {
            return;
        }
        for (Pair<OnBackInvokedCallback, Integer> callback : mCallbacks) {
            mActualDispatcher.unregisterOnBackInvokedCallback(callback.first);
        }
    }

    /**
     * Resets this {@link ProxyOnBackInvokedDispatcher} so it loses track of the currently
     * registered callbacks.
     * <p>
     * Using this method means that when setting a new {@link OnBackInvokedDispatcher}, the
     * callbacks registered on the old one won't be removed from it and won't be registered on
     * the new one.
     */
    public void reset() {
        if (DEBUG) {
            Log.v(TAG, "Proxy: reset callbacks");
        }
        synchronized (mLock) {
            mCallbacks.clear();
            mImeDispatcher = null;
        }
    }

    /**
     * Sets the actual {@link OnBackInvokedDispatcher} onto which the callbacks will be registered.
     * <p>
     * If any dispatcher was already present, all the callbacks that were added via this
     * {@link ProxyOnBackInvokedDispatcher} will be unregistered from the old one and registered
     * on the new one if it is not null.
     * <p>
     * If you do not wish for the previously registered callbacks to be reassigned to the new
     * dispatcher, {@link #reset} must be called beforehand.
     */
    public void setActualDispatcher(@Nullable OnBackInvokedDispatcher actualDispatcher) {
        if (DEBUG) {
            Log.v(TAG, String.format("Proxy setActual %s. Current %s",
                            actualDispatcher, mActualDispatcher));
        }
        synchronized (mLock) {
            if (actualDispatcher == mActualDispatcher) {
                return;
            }
            clearCallbacksOnDispatcher();
            if (actualDispatcher instanceof ProxyOnBackInvokedDispatcher) {
                // We don't want to nest ProxyDispatchers, so if we are given on, we unwrap its
                // actual dispatcher.
                // This can happen when an Activity is recreated but the Window is preserved (e.g.
                // when going from split-screen back to single screen)
                mActualDispatcher =
                        ((ProxyOnBackInvokedDispatcher) actualDispatcher).mActualDispatcher;
            } else {
                mActualDispatcher = actualDispatcher;
            }
            transferCallbacksToDispatcher();
        }
    }

    @Override
    public void setImeOnBackInvokedDispatcher(
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        if (mActualDispatcher != null) {
            mActualDispatcher.setImeOnBackInvokedDispatcher(imeDispatcher);
        } else {
            mImeDispatcher = imeDispatcher;
        }
    }
}
