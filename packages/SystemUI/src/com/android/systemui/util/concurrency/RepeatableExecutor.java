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
 * A sub-class of {@link Executor} that allows scheduling commands to execute periodically.
 */
public interface RepeatableExecutor extends Executor {

    /**
     * Execute supplied Runnable on the Executors thread after initial delay, and subsequently with
     * the given delay between the termination of one execution and the commencement of the next.
     *
     * Each invocation of the supplied Runnable will be scheduled after the previous invocation
     * completes. For example, if you schedule the Runnable with a 60 second delay, and the Runnable
     * itself takes 1 second, the effective delay will be 61 seconds between each invocation.
     *
     * See {@link java.util.concurrent.ScheduledExecutorService#scheduleRepeatedly(Runnable,
     * long, long)}
     *
     * @return A Runnable that, when run, removes the supplied argument from the Executor queue.
     */
    default Runnable executeRepeatedly(Runnable r, long initialDelayMillis, long delayMillis) {
        return executeRepeatedly(r, initialDelayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Execute supplied Runnable on the Executors thread after initial delay, and subsequently with
     * the given delay between the termination of one execution and the commencement of the next..
     *
     * See {@link java.util.concurrent.ScheduledExecutorService#scheduleRepeatedly(Runnable,
     * long, long)}
     *
     * @return A Runnable that, when run, removes the supplied argument from the Executor queue.
     */
    Runnable executeRepeatedly(Runnable r, long initialDelay, long delay, TimeUnit unit);
}
