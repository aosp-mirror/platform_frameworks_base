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

package com.android.systemui.util.concurrency;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;

/**
 * Factory for building Executors running on a unique named thread.
 *
 * Use this when our generally available @Main, @Background, @UiBackground, @LongRunning, or
 * similar global qualifiers don't quite cut it. Note that the methods here can create entirely new
 * threads; there are no singletons here. Use responsibly.
 */
public interface ThreadFactory {
    /**
     * Returns a {@link Looper} running on a named thread.
     *
     * The thread is implicitly started and may be left running indefinitely, depending on the
     * implementation. Assume this is the case and use responsibly.
     */
    Looper buildLooperOnNewThread(String threadName);

    /**
     * Returns a {@link Handler} running on a named thread.
     *
     * The thread is implicitly started and may be left running indefinitely, depending on the
     * implementation. Assume this is the case and use responsibly.
     */
    Handler buildHandlerOnNewThread(String threadName);

    /**
     * Return an {@link java.util.concurrent.Executor} running on a named thread.
     *
     * The thread is implicitly started and may be left running indefinitely, depending on the
     * implementation. Assume this is the case and use responsibly.
     **/
    Executor buildExecutorOnNewThread(String threadName);

    /**
     * Return an {@link DelayableExecutor} running on a named thread.
     *
     * The thread is implicitly started and may be left running indefinitely, depending on the
     * implementation. Assume this is the case and use responsibly.
     **/
    DelayableExecutor buildDelayableExecutorOnNewThread(String threadName);

    /**
     * Return an {@link DelayableExecutor} running on the given HandlerThread.
     **/
    DelayableExecutor buildDelayableExecutorOnHandler(Handler handler);

    /**
     * Return an {@link DelayableExecutor} running the given Looper
     **/
    DelayableExecutor buildDelayableExecutorOnLooper(Looper looper);
}
