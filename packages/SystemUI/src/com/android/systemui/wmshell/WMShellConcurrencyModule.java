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

package com.android.systemui.wmshell;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;

import android.animation.AnimationHandler;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.R;
import com.android.systemui.dagger.WMSingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ChoreographerSfVsync;
import com.android.wm.shell.common.annotations.ShellAnimationThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.common.annotations.ShellSplashscreenThread;

import dagger.Module;
import dagger.Provides;

/**
 * Provides basic concurrency-related dependencies from {@link com.android.wm.shell}, these
 * dependencies are only accessible from components within the WM subcomponent.
 */
@Module
public abstract class WMShellConcurrencyModule {

    private static final int MSGQ_SLOW_DELIVERY_THRESHOLD_MS = 30;
    private static final int MSGQ_SLOW_DISPATCH_THRESHOLD_MS = 30;

    /**
     * Returns whether to enable a separate shell thread for the shell features.
     */
    private static boolean enableShellMainThread(Context context) {
        return context.getResources().getBoolean(R.bool.config_enableShellMainThread);
    }

    //
    // Shell Concurrency - Components used for managing threading in the Shell and SysUI
    //

    /**
     * Provide a SysUI main-thread Executor.
     */
    @WMSingleton
    @Provides
    @Main
    public static ShellExecutor provideSysUIMainExecutor(@Main Handler sysuiMainHandler) {
        return new HandlerExecutor(sysuiMainHandler);
    }

    /**
     * Shell main-thread Handler, don't use this unless really necessary (ie. need to dedupe
     * multiple types of messages, etc.)
     */
    @WMSingleton
    @Provides
    @ShellMainThread
    public static Handler provideShellMainHandler(Context context, @Main Handler sysuiMainHandler) {
        if (enableShellMainThread(context)) {
             HandlerThread mainThread = new HandlerThread("wmshell.main", THREAD_PRIORITY_DISPLAY);
             mainThread.start();
             if (Build.IS_DEBUGGABLE) {
                 mainThread.getLooper().setTraceTag(Trace.TRACE_TAG_WINDOW_MANAGER);
                 mainThread.getLooper().setSlowLogThresholdMs(MSGQ_SLOW_DISPATCH_THRESHOLD_MS,
                         MSGQ_SLOW_DELIVERY_THRESHOLD_MS);
             }
             return Handler.createAsync(mainThread.getLooper());
        }
        return sysuiMainHandler;
    }

    /**
     * Provide a Shell main-thread Executor.
     */
    @WMSingleton
    @Provides
    @ShellMainThread
    public static ShellExecutor provideShellMainExecutor(Context context,
            @ShellMainThread Handler mainHandler, @Main ShellExecutor sysuiMainExecutor) {
        if (enableShellMainThread(context)) {
            return new HandlerExecutor(mainHandler);
        }
        return sysuiMainExecutor;
    }

    /**
     * Provide a Shell animation-thread Executor.
     */
    @WMSingleton
    @Provides
    @ShellAnimationThread
    public static ShellExecutor provideShellAnimationExecutor() {
         HandlerThread shellAnimationThread = new HandlerThread("wmshell.anim",
                 THREAD_PRIORITY_DISPLAY);
         shellAnimationThread.start();
        if (Build.IS_DEBUGGABLE) {
            shellAnimationThread.getLooper().setTraceTag(Trace.TRACE_TAG_WINDOW_MANAGER);
            shellAnimationThread.getLooper().setSlowLogThresholdMs(MSGQ_SLOW_DISPATCH_THRESHOLD_MS,
                    MSGQ_SLOW_DELIVERY_THRESHOLD_MS);
        }
         return new HandlerExecutor(Handler.createAsync(shellAnimationThread.getLooper()));
    }

    /**
     * Provides a Shell splashscreen-thread Executor
     */
    @WMSingleton
    @Provides
    @ShellSplashscreenThread
    public static ShellExecutor provideSplashScreenExecutor() {
        HandlerThread shellSplashscreenThread = new HandlerThread("wmshell.splashscreen",
                THREAD_PRIORITY_TOP_APP_BOOST);
        shellSplashscreenThread.start();
        return new HandlerExecutor(shellSplashscreenThread.getThreadHandler());
    }

    /**
     * Provide a Shell main-thread AnimationHandler.  The AnimationHandler can be set on
     * {@link android.animation.ValueAnimator}s and will ensure that the animation will run on
     * the Shell main-thread with the SF vsync.
     */
    @WMSingleton
    @Provides
    @ChoreographerSfVsync
    public static AnimationHandler provideShellMainExecutorSfVsyncAnimationHandler(
            @ShellMainThread ShellExecutor mainExecutor) {
        try {
            AnimationHandler handler = new AnimationHandler();
            mainExecutor.executeBlocking(() -> {
                // This is called on the animation thread since it calls
                // Choreographer.getSfInstance() which returns a thread-local Choreographer instance
                // that uses the SF vsync
                handler.setProvider(new SfVsyncFrameCallbackProvider());
            });
            return handler;
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to initialize SfVsync animation handler in 1s", e);
        }
    }
}
