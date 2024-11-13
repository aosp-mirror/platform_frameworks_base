/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appfunctions;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Executors for App function operations. */
public final class AppFunctionExecutors {

    /** Executor for operations that do not need to block. */
    public static final Executor THREAD_POOL_EXECUTOR =
            new ThreadPoolExecutor(
                    /* corePoolSize= */ Runtime.getRuntime().availableProcessors(),
                    /* maxConcurrency= */ Runtime.getRuntime().availableProcessors(),
                    /* keepAliveTime= */ 0L,
                    /* unit= */ TimeUnit.SECONDS,
                    /* workQueue= */ new LinkedBlockingQueue<>());

    private AppFunctionExecutors() {}
}
