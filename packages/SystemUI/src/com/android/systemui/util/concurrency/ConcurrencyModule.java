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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.BgLooper;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.MainLooper;

import java.util.concurrent.Executor;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module for classes found within the concurrent package.
 */
@Module
public abstract class ConcurrencyModule {
    /**
     * Provide a Background-Thread Executor by default.
     */
    @Provides
    public static Executor provideExecutor(@BgLooper Looper looper) {
        return new ExecutorImpl(new Handler(looper));
    }

    /**
     * Provide a Background-Thread Executor.
     */
    @Provides
    @Background
    public static Executor provideBackgroundExecutor(@BgLooper Looper looper) {
        return new ExecutorImpl(new Handler(looper));
    }

    /**
     * Provide a Main-Thread Executor.
     */
    @Provides
    @Main
    public static Executor provideMainExecutor(Context context) {
        return context.getMainExecutor();
    }

    /**
     * Provide a Background-Thread Executor by default.
     */
    @Provides
    public static DelayableExecutor provideDelayableExecutor(@BgLooper Looper looper) {
        return new ExecutorImpl(new Handler(looper));
    }

    /**
     * Provide a Background-Thread Executor.
     */
    @Provides
    @Background
    public static DelayableExecutor provideBackgroundDelayableExecutor(@BgLooper Looper looper) {
        return new ExecutorImpl(new Handler(looper));
    }

    /**
     * Provide a Main-Thread Executor.
     */
    @Provides
    @Main
    public static DelayableExecutor provideMainDelayableExecutor(@MainLooper Looper looper) {
        return new ExecutorImpl(new Handler(looper));
    }
}
