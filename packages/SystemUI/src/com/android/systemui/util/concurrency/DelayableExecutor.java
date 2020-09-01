/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.util.concurrency;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * A sub-class of {@link Executor} that allows Runnables to be delayed and/or cancelled.
 */
public interface DelayableExecutor extends Executor {
    /**
     * Execute supplied Runnable on the Executors thread after a specified delay.
     *
     * See {@link android.os.Handler#postDelayed(Runnable, long)}.
     *
     * @return A Runnable that, when run, removes the supplied argument from the Executor queue.
     */
    default Runnable executeDelayed(Runnable r, long delayMillis) {
        return executeDelayed(r, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Execute supplied Runnable on the Executors thread after a specified delay.
     *
     * See {@link android.os.Handler#postDelayed(Runnable, long)}.
     *
     * @return A Runnable that, when run, removes the supplied argument from the Executor queue..
     */
    Runnable executeDelayed(Runnable r, long delay, TimeUnit unit);

    /**
     * Execute supplied Runnable on the Executors thread at a specified uptime.
     *
     * See {@link android.os.Handler#postAtTime(Runnable, long)}.
     *
     * @return A Runnable that, when run, removes the supplied argument from the Executor queue.
     */
    default Runnable executeAtTime(Runnable r, long uptime) {
        return executeAtTime(r, uptime, TimeUnit.MILLISECONDS);
    }

    /**
     * Execute supplied Runnable on the Executors thread at a specified uptime.
     *
     * See {@link android.os.Handler#postAtTime(Runnable, long)}.
     *
     * @return A Runnable that, when run, removes the supplied argument from the Executor queue.
     */
    Runnable executeAtTime(Runnable r, long uptimeMillis, TimeUnit unit);
}
