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

import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.IWindowManager;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.bubbles.Bubbles;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.animation.FlingAnimationUtils;
import com.android.wm.shell.common.AnimationThread;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PipAppOpsListener;
import com.android.wm.shell.pip.phone.PipMediaController;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.util.Optional;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;

/**
 * Provides basic dependencies from {@link com.android.wm.shell}, the dependencies declared here
 * should be shared among different branches of SystemUI.
 */
// TODO(b/162923491): Move most of these dependencies into WMSingleton scope.
@Module
public abstract class WMShellBaseModule {
    @SysUISingleton
    @Provides
    static TransactionPool provideTransactionPool() {
        return new TransactionPool();
    }

    @SysUISingleton
    @Provides
    static DisplayController provideDisplayController(Context context, @Main Handler handler,
            IWindowManager wmService) {
        return new DisplayController(context, handler, wmService);
    }

    @SysUISingleton
    @Provides
    static DeviceConfigProxy provideDeviceConfigProxy() {
        return new DeviceConfigProxy();
    }

    @SysUISingleton
    @Provides
    static InputConsumerController provideInputConsumerController() {
        return InputConsumerController.getPipInputConsumer();
    }

    @SysUISingleton
    @Provides
    static FloatingContentCoordinator provideFloatingContentCoordinator() {
        return new FloatingContentCoordinator();
    }

    @SysUISingleton
    @Provides
    static PipAppOpsListener providePipAppOpsListener(Context context,
            IActivityManager activityManager,
            PipTouchHandler pipTouchHandler) {
        return new PipAppOpsListener(context, activityManager, pipTouchHandler.getMotionHelper());
    }

    @SysUISingleton
    @Provides
    static PipMediaController providePipMediaController(Context context,
            IActivityManager activityManager) {
        return new PipMediaController(context, activityManager);
    }

    @SysUISingleton
    @Provides
    static PipUiEventLogger providePipUiEventLogger(UiEventLogger uiEventLogger,
            PackageManager packageManager) {
        return new PipUiEventLogger(uiEventLogger, packageManager);
    }

    @SysUISingleton
    @Provides
    static PipSurfaceTransactionHelper providePipSurfaceTransactionHelper(Context context) {
        return new PipSurfaceTransactionHelper(context);
    }

    @SysUISingleton
    @Provides
    static SystemWindows provideSystemWindows(DisplayController displayController,
            IWindowManager wmService) {
        return new SystemWindows(displayController, wmService);
    }

    @SysUISingleton
    @Provides
    static SyncTransactionQueue provideSyncTransactionQueue(@Main Handler handler,
            TransactionPool pool) {
        return new SyncTransactionQueue(pool, handler);
    }

    @SysUISingleton
    @Provides
    static ShellTaskOrganizer provideShellTaskOrganizer(SyncTransactionQueue syncQueue,
            @Main Handler handler, TransactionPool transactionPool) {
        ShellTaskOrganizer organizer = new ShellTaskOrganizer(syncQueue, transactionPool,
                new HandlerExecutor(handler), AnimationThread.instance().getExecutor());
        organizer.registerOrganizer();
        return organizer;
    }

    @SysUISingleton
    @Provides
    static WindowManagerShellWrapper provideWindowManagerShellWrapper() {
        return new WindowManagerShellWrapper();
    }

    @SysUISingleton
    @Provides
    static FlingAnimationUtils.Builder provideFlingAnimationUtilsBuilder(
            DisplayMetrics displayMetrics) {
        return new FlingAnimationUtils.Builder(displayMetrics);
    }

    @BindsOptionalOf
    abstract SplitScreen optionalSplitScreen();

    @BindsOptionalOf
    abstract Bubbles optionalBubbles();

    @SysUISingleton
    @Provides
    static Optional<OneHanded> provideOneHandedController(Context context,
            DisplayController displayController) {
        return Optional.ofNullable(OneHandedController.create(context, displayController));
    }
}
