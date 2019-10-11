/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.NonNull;

/**
 * Multi-cast delegate implementation for {@link ActivityMetricsLaunchObserver}.
 *
 * <br/><br/>
 * This enables multiple launch observers to subscribe to {@link ActivityMetricsLogger}
 * independently of each other.
 *
 * <br/><br/>
 * Some callbacks in {@link ActivityMetricsLaunchObserver} have a {@code byte[]}
 * parameter; this array is reused by all the registered observers, so it must not be written to
 * (i.e. all observers must treat any array parameters as immutable).
 *
 * <br /><br />
 * Multi-cast invocations occurs sequentially in-order of registered observers.
 */
public interface ActivityMetricsLaunchObserverRegistry {
    /**
     * Register an extra launch observer to receive the multi-cast.
     *
     * <br /><br />
     * Multi-cast invocation happens in the same order the observers were registered. For example,
     * <pre>
     *     registerLaunchObserver(A)
     *     registerLaunchObserver(B)
     *
     *     obs.onIntentFailed() ->
     *       A.onIntentFailed()
     *       B.onIntentFailed()
     * </pre>
     */
    void registerLaunchObserver(@NonNull ActivityMetricsLaunchObserver launchObserver);

    /**
     * Unregister an existing launch observer. It will not receive the multi-cast in the future.
     *
     * <br /><br />
     * This does nothing if this observer was not already registered.
     */
    void unregisterLaunchObserver(@NonNull ActivityMetricsLaunchObserver launchObserver);
}
