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
package com.android.systemui.util.concurrency

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import com.android.systemui.Dependency
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.BroadcastRunning
import com.android.systemui.dagger.qualifiers.LongRunning
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.qualifiers.NotifInflation
import dagger.Module
import dagger.Provides
import java.util.concurrent.Executor
import javax.inject.Named
import javax.inject.Qualifier

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class BackPanelUiThread

/** Dagger Module for classes found within the concurrent package. */
@Module
object SysUIConcurrencyModule {
    // Slow BG executor can potentially affect UI if UI is waiting for an updated state from this
    // thread
    private const val BG_SLOW_DISPATCH_THRESHOLD = 1000L
    private const val BG_SLOW_DELIVERY_THRESHOLD = 1000L
    private const val LONG_SLOW_DISPATCH_THRESHOLD = 2500L
    private const val LONG_SLOW_DELIVERY_THRESHOLD = 2500L
    private const val BROADCAST_SLOW_DISPATCH_THRESHOLD = 1000L
    private const val BROADCAST_SLOW_DELIVERY_THRESHOLD = 1000L
    private const val NOTIFICATION_INFLATION_SLOW_DISPATCH_THRESHOLD = 1000L
    private const val NOTIFICATION_INFLATION_SLOW_DELIVERY_THRESHOLD = 1000L

    /** Background Looper */
    @Provides
    @SysUISingleton
    @Background
    fun provideBgLooper(): Looper {
        val thread = HandlerThread("SysUiBg", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        thread
            .getLooper()
            .setSlowLogThresholdMs(BG_SLOW_DISPATCH_THRESHOLD, BG_SLOW_DELIVERY_THRESHOLD)
        return thread.getLooper()
    }

    /** BroadcastRunning Looper (for sending and receiving broadcasts) */
    @Provides
    @SysUISingleton
    @BroadcastRunning
    fun provideBroadcastRunningLooper(): Looper {
        val thread = HandlerThread("BroadcastRunning", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        thread
            .getLooper()
            .setSlowLogThresholdMs(
                BROADCAST_SLOW_DISPATCH_THRESHOLD,
                BROADCAST_SLOW_DELIVERY_THRESHOLD
            )
        return thread.getLooper()
    }

    /** Long running tasks Looper */
    @Provides
    @SysUISingleton
    @LongRunning
    fun provideLongRunningLooper(): Looper {
        val thread = HandlerThread("SysUiLng", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        thread
            .getLooper()
            .setSlowLogThresholdMs(LONG_SLOW_DISPATCH_THRESHOLD, LONG_SLOW_DELIVERY_THRESHOLD)
        return thread.getLooper()
    }

    /** Notification inflation Looper */
    @Provides
    @SysUISingleton
    @NotifInflation
    fun provideNotifInflationLooper(@Background bgLooper: Looper): Looper {
        if (!Flags.dedicatedNotifInflationThread()) {
            return bgLooper
        }
        val thread = HandlerThread("NotifInflation", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        val looper = thread.getLooper()
        looper.setSlowLogThresholdMs(
            NOTIFICATION_INFLATION_SLOW_DISPATCH_THRESHOLD,
            NOTIFICATION_INFLATION_SLOW_DELIVERY_THRESHOLD
        )
        return looper
    }

    @Provides
    @SysUISingleton
    @BackPanelUiThread
    fun provideBackPanelUiThreadContext(
        @Main mainLooper: Looper,
        @Main mainHandler: Handler,
        @Main mainExecutor: Executor
    ): UiThreadContext {
        return if (Flags.edgeBackGestureHandlerThread()) {
            val thread =
                HandlerThread("BackPanelUiThread", Process.THREAD_PRIORITY_DISPLAY).apply {
                    start()
                    looper.setSlowLogThresholdMs(
                        LONG_SLOW_DISPATCH_THRESHOLD,
                        LONG_SLOW_DELIVERY_THRESHOLD
                    )
                }
            UiThreadContext(
                thread.looper,
                thread.threadHandler,
                thread.threadExecutor,
                thread.threadHandler.runWithScissors { Choreographer.getInstance() }
            )
        } else {
            UiThreadContext(
                mainLooper,
                mainHandler,
                mainExecutor,
                mainHandler.runWithScissors { Choreographer.getInstance() }
            )
        }
    }

    /**
     * Background Handler.
     *
     * Prefer the Background Executor when possible.
     */
    @Provides
    @Background
    fun provideBgHandler(@Background bgLooper: Looper): Handler = Handler(bgLooper)

    /** Provide a BroadcastRunning Executor (for sending and receiving broadcasts). */
    @Provides
    @SysUISingleton
    @BroadcastRunning
    fun provideBroadcastRunningExecutor(@BroadcastRunning looper: Looper): Executor =
        ExecutorImpl(looper)

    /** Provide a Long running Executor. */
    @Provides
    @SysUISingleton
    @LongRunning
    fun provideLongRunningExecutor(@LongRunning looper: Looper): Executor = ExecutorImpl(looper)

    /** Provide a Long running Executor. */
    @Provides
    @SysUISingleton
    @LongRunning
    fun provideLongRunningDelayableExecutor(@LongRunning looper: Looper): DelayableExecutor =
        ExecutorImpl(looper)

    /** Provide a Background-Thread Executor. */
    @Provides
    @SysUISingleton
    @Background
    fun provideBackgroundExecutor(@Background looper: Looper): Executor = ExecutorImpl(looper)

    /** Provide a Background-Thread Executor. */
    @Provides
    @SysUISingleton
    @Background
    fun provideBackgroundDelayableExecutor(@Background looper: Looper): DelayableExecutor =
        ExecutorImpl(looper)

    /** Provide a Background-Thread Executor. */
    @Provides
    @SysUISingleton
    @Background
    fun provideBackgroundRepeatableExecutor(
        @Background exec: DelayableExecutor
    ): RepeatableExecutor = RepeatableExecutorImpl(exec)

    /** Provide a Main-Thread Executor. */
    @Provides
    @SysUISingleton
    @Main
    fun provideMainRepeatableExecutor(@Main exec: DelayableExecutor): RepeatableExecutor =
        RepeatableExecutorImpl(exec)

    /**  */
    @Provides
    @Main
    fun providesMainMessageRouter(@Main executor: DelayableExecutor): MessageRouter =
        MessageRouterImpl(executor)

    /**  */
    @Provides
    @Background
    fun providesBackgroundMessageRouter(@Background executor: DelayableExecutor): MessageRouter =
        MessageRouterImpl(executor)

    /**  */
    @Provides
    @SysUISingleton
    @Named(Dependency.TIME_TICK_HANDLER_NAME)
    fun provideTimeTickHandler(): Handler {
        val thread = HandlerThread("TimeTick")
        thread.start()
        return Handler(thread.getLooper())
    }

    /**  */
    @Provides
    @SysUISingleton
    @NotifInflation
    fun provideNotifInflationExecutor(@NotifInflation looper: Looper): Executor =
        ExecutorImpl(looper)
}
