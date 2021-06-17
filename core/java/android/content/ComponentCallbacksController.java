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

package android.content;

import android.annotation.NonNull;
import android.content.res.Configuration;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A helper class to manage {@link ComponentCallbacks} and {@link ComponentCallbacks2}, such as
 * registering ,unregistering {@link ComponentCallbacks} and sending callbacks to all registered
 * {@link ComponentCallbacks}.
 *
 * @see Context#registerComponentCallbacks(ComponentCallbacks)
 * @see Context#unregisterComponentCallbacks(ComponentCallbacks)
 * @see ComponentCallbacks
 * @see ComponentCallbacks2
 *
 * @hide
 */
public class ComponentCallbacksController {
    @GuardedBy("mLock")
    private List<ComponentCallbacks> mComponentCallbacks;

    private final Object mLock = new Object();

    /**
     * Register the {@link ComponentCallbacks}.
     *
     * @see Context#registerComponentCallbacks(ComponentCallbacks)
     */
    public void registerCallbacks(@NonNull ComponentCallbacks callbacks) {
        synchronized (mLock) {
            if (mComponentCallbacks == null) {
                mComponentCallbacks = new ArrayList<>();
            }
            mComponentCallbacks.add(callbacks);
        }
    }

    /**
     * Unregister the {@link ComponentCallbacks}.
     *
     * @see Context#unregisterComponentCallbacks(ComponentCallbacks)
     */
    public void unregisterCallbacks(@NonNull ComponentCallbacks callbacks) {
        synchronized (mLock) {
            if (mComponentCallbacks == null || mComponentCallbacks.isEmpty()) {
                return;
            }
            mComponentCallbacks.remove(callbacks);
        }
    }

    /**
     * Clear all registered {@link ComponentCallbacks}.
     * It is useful when the associated {@link Context} is going to be released.
     */
    public void clearCallbacks() {
        synchronized (mLock) {
            if (mComponentCallbacks != null) {
                mComponentCallbacks.clear();
            }
        }
    }

    /**
     * Sending {@link ComponentCallbacks#onConfigurationChanged(Configuration)} to all registered
     * {@link ComponentCallbacks}.
     */
    public void dispatchConfigurationChanged(@NonNull Configuration newConfig) {
        forAllComponentCallbacks(callbacks -> callbacks.onConfigurationChanged(newConfig));
    }

    /**
     * Sending {@link ComponentCallbacks#onLowMemory()} to all registered
     * {@link ComponentCallbacks}.
     */
    public void dispatchLowMemory() {
        forAllComponentCallbacks(ComponentCallbacks::onLowMemory);
    }

    /**
     * Sending {@link ComponentCallbacks2#onTrimMemory(int)} to all registered
     * {@link ComponentCallbacks2}.
     */
    public void dispatchTrimMemory(int level) {
        forAllComponentCallbacks(callbacks -> {
            if (callbacks instanceof ComponentCallbacks2) {
                ((ComponentCallbacks2) callbacks).onTrimMemory(level);
            }
        });
    }

    private void forAllComponentCallbacks(Consumer<ComponentCallbacks> callbacksConsumer) {
        final ComponentCallbacks[] callbacksArray;
        synchronized (mLock) {
            if (mComponentCallbacks == null || mComponentCallbacks.isEmpty()) {
                return;
            }
            callbacksArray = new ComponentCallbacks[mComponentCallbacks.size()];
            mComponentCallbacks.toArray(callbacksArray);
        }
        for (ComponentCallbacks callbacks : callbacksArray) {
            callbacksConsumer.accept(callbacks);
        }
    }
}
