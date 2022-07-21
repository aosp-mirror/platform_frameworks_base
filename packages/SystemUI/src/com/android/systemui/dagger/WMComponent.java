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

package com.android.systemui.dagger;

import android.content.Context;
import android.os.HandlerThread;

import androidx.annotation.Nullable;

import com.android.systemui.SystemUIInitializerFactory;
import com.android.systemui.tv.TvWMComponent;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.TaskViewFactory;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.dagger.TvWMShellModule;
import com.android.wm.shell.dagger.WMShellModule;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.displayareahelper.DisplayAreaHelper;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.recents.RecentTasks;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.startingsurface.StartingSurface;
import com.android.wm.shell.sysui.ShellInterface;
import com.android.wm.shell.transition.ShellTransitions;

import java.util.Optional;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Dagger Subcomponent for WindowManager.  This class explicitly describes the interfaces exported
 * from the WM component into the SysUI component (in
 * {@link SystemUIInitializerFactory#init(Context, boolean)}), and references the specific dependencies
 * provided by its particular device/form-factor SystemUI implementation.
 *
 * ie. {@link WMComponent} includes {@link WMShellModule}
 *     and {@link TvWMComponent} includes {@link TvWMShellModule}
 */
@WMSingleton
@Subcomponent(modules = {WMShellModule.class})
public interface WMComponent {

    /**
     * Builder for a WMComponent.
     */
    @Subcomponent.Builder
    interface Builder {

        @BindsInstance
        Builder setShellMainThread(@Nullable @ShellMainThread HandlerThread t);

        WMComponent build();
    }

    /**
     * Initializes all the WMShell components before starting any of the SystemUI components.
     */
    default void init() {
        // TODO(238217847): To be removed once the dependencies are inverted and ShellController can
        // inject these classes directly, otherwise, it's currently needed to ensure that these
        // classes are created and set on the controller before onInit() is called
        getShellInit();
        getShellCommandHandler();
        getShell().onInit();
    }

    @WMSingleton
    ShellInterface getShell();

    // TODO(238217847): To be removed once ShellController can inject ShellInit directly
    @WMSingleton
    ShellInit getShellInit();

    // TODO(238217847): To be removed once ShellController can inject ShellCommandHandler directly
    @WMSingleton
    ShellCommandHandler getShellCommandHandler();

    @WMSingleton
    Optional<OneHanded> getOneHanded();

    @WMSingleton
    Optional<Pip> getPip();

    @WMSingleton
    Optional<SplitScreen> getSplitScreen();

    @WMSingleton
    Optional<Bubbles> getBubbles();

    @WMSingleton
    Optional<TaskViewFactory> getTaskViewFactory();

    @WMSingleton
    ShellTransitions getTransitions();

    @WMSingleton
    Optional<StartingSurface> getStartingSurface();

    @WMSingleton
    Optional<DisplayAreaHelper> getDisplayAreaHelper();

    @WMSingleton
    Optional<RecentTasks> getRecentTasks();

    @WMSingleton
    Optional<BackAnimation> getBackAnimation();
}
