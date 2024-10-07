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

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Singleton;

/**
 * Dagger Module for classes found within the concurrent package.
 */
@Module
public abstract class GlobalConcurrencyModule {
    public static final String PRE_HANDLER = "pre_handler";

    /**
     * Binds {@link ThreadFactoryImpl} to {@link ThreadFactory}.
     */
    @Binds
    public abstract ThreadFactory bindExecutorFactory(ThreadFactoryImpl impl);

    /** Main Looper */
    @Provides
    @Main
    public static  Looper provideMainLooper() {
        return Looper.getMainLooper();
    }

    /**
     * Main Handler.
     *
     * Prefer the Main Executor when possible.
     */
    @Provides
    @Main
    public static Handler provideMainHandler(@Main Looper mainLooper) {
        return new Handler(mainLooper);
    }

    /**
     * @deprecated Use @Main Handler.
     */
    @Deprecated
    @Provides
    public static Handler provideHandler() {
        return new Handler();
    }

    /**
     * Provide an Executor specifically for running UI operations on a separate thread.
     *
     * Keep submitted runnables short and to the point, just as with any other UI code.
     */
    @Provides
    @Singleton
    @UiBackground
    public static Executor provideUiBackgroundExecutor() {
        return provideUiBackgroundExecutorService();
    }

    /**
     * Provide an ExecutorService specifically for running UI operations on a separate thread.
     *
     * <p>Keep submitted runnables short and to the point, just as with any other UI code.
     */
    @Provides
    @Singleton
    @UiBackground
    public static ExecutorService provideUiBackgroundExecutorService() {
        return Executors.newSingleThreadExecutor();
    }

    /**
     * Provide a Main-Thread Executor.
     */
    @Provides
    @Singleton
    @Main
    public static Executor provideMainExecutor(Context context) {
        return context.getMainExecutor();
    }

    /**
     * Provide a Main-Thread DelayableExecutor.
     */
    @Provides
    @Singleton
    @Main
    public static DelayableExecutor provideMainDelayableExecutor(@Main Looper looper) {
        return new ExecutorImpl(looper);
    }

    /** */
    @Binds
    @Singleton
    public abstract Execution provideExecution(ExecutionImpl execution);
}
