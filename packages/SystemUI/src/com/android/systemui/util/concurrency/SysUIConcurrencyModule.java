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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.LongRunning;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module for classes found within the concurrent package.
 */
@Module
public abstract class SysUIConcurrencyModule {
    /** Background Looper */
    @Provides
    @SysUISingleton
    @Background
    public static Looper provideBgLooper() {
        HandlerThread thread = new HandlerThread("SysUiBg",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        return thread.getLooper();
    }

    /** Long running tasks Looper */
    @Provides
    @SysUISingleton
    @LongRunning
    public static Looper provideLongRunningLooper() {
        HandlerThread thread = new HandlerThread("SysUiLng",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        return thread.getLooper();
    }

    /**
     * Background Handler.
     *
     * Prefer the Background Executor when possible.
     */
    @Provides
    @Background
    public static Handler provideBgHandler(@Background Looper bgLooper) {
        return new Handler(bgLooper);
    }

    /**
     * Provide a Background-Thread Executor by default.
     */
    @Provides
    @SysUISingleton
    public static Executor provideExecutor(@Background Looper looper) {
        return new ExecutorImpl(looper);
    }

    /**
     * Provide a Long running Executor by default.
     */
    @Provides
    @SysUISingleton
    @LongRunning
    public static Executor provideLongRunningExecutor(@LongRunning Looper looper) {
        return new ExecutorImpl(looper);
    }

    /**
     * Provide a Background-Thread Executor.
     */
    @Provides
    @SysUISingleton
    @Background
    public static Executor provideBackgroundExecutor(@Background Looper looper) {
        return new ExecutorImpl(looper);
    }

    /**
     * Provide a Background-Thread Executor by default.
     */
    @Provides
    @SysUISingleton
    public static DelayableExecutor provideDelayableExecutor(@Background Looper looper) {
        return new ExecutorImpl(looper);
    }

    /**
     * Provide a Background-Thread Executor.
     */
    @Provides
    @SysUISingleton
    @Background
    public static DelayableExecutor provideBackgroundDelayableExecutor(@Background Looper looper) {
        return new ExecutorImpl(looper);
    }

    /**
     * Provide a Background-Thread Executor by default.
     */
    @Provides
    @SysUISingleton
    public static RepeatableExecutor provideRepeatableExecutor(@Background DelayableExecutor exec) {
        return new RepeatableExecutorImpl(exec);
    }

    /**
     * Provide a Background-Thread Executor.
     */
    @Provides
    @SysUISingleton
    @Background
    public static RepeatableExecutor provideBackgroundRepeatableExecutor(
            @Background DelayableExecutor exec) {
        return new RepeatableExecutorImpl(exec);
    }

    /**
     * Provide a Main-Thread Executor.
     */
    @Provides
    @SysUISingleton
    @Main
    public static RepeatableExecutor provideMainRepeatableExecutor(@Main DelayableExecutor exec) {
        return new RepeatableExecutorImpl(exec);
    }

    /**
     * Provide an Executor specifically for running UI operations on a separate thread.
     *
     * Keep submitted runnables short and to the point, just as with any other UI code.
     */
    @Provides
    @SysUISingleton
    @UiBackground
    public static Executor provideUiBackgroundExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    /** */
    @Provides
    @Main
    public static MessageRouter providesMainMessageRouter(
            @Main DelayableExecutor executor) {
        return new MessageRouterImpl(executor);
    }

    /** */
    @Provides
    @Background
    public static MessageRouter providesBackgroundMessageRouter(
            @Background DelayableExecutor executor) {
        return new MessageRouterImpl(executor);
    }
}
