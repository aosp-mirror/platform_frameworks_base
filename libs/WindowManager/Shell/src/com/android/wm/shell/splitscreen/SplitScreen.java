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

package com.android.wm.shell.splitscreen;

import android.graphics.Rect;
import android.window.WindowContainerToken;

import java.io.PrintWriter;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Interface to engage split screen feature.
 */
public interface SplitScreen {
    /** Returns {@code true} if split screen is supported on the device. */
    boolean isSplitScreenSupported();

    /** Called when keyguard showing state changed. */
    void onKeyguardVisibilityChanged(boolean isShowing);

    /** Returns {@link DividerView}. */
    DividerView getDividerView();

    /** Returns {@code true} if one of the split screen is in minimized mode. */
    boolean isMinimized();

    /** Returns {@code true} if the home stack is resizable. */
    boolean isHomeStackResizable();

    /** Returns {@code true} if the divider is visible. */
    boolean isDividerVisible();

    /** Switch to minimized state if appropriate. */
    void setMinimized(boolean minimized);

    /**
     * Workaround for b/62528361, at the time recents has drawn, it may happen before a
     * configuration change to the Divider, and internally, the event will be posted to the
     * subscriber, or DividerView, which has been removed and prevented from resizing. Instead,
     * register the event handler here and proxy the event to the current DividerView.
     */
    void onRecentsDrawn();

    /** Called when there's an activity forced resizable. */
    void onActivityForcedResizable(String packageName, int taskId, int reason);

    /** Called when there's an activity dismissing split screen. */
    void onActivityDismissingSplitScreen();

    /** Called when there's an activity launch on secondary display failed. */
    void onActivityLaunchOnSecondaryDisplayFailed();

    /** Called when there's a task undocking. */
    void onUndockingTask();

    /** Called when the first docked animation frame rendered. */
    void onDockedFirstAnimationFrame();

    /** Called when top task docked. */
    void onDockedTopTask();

    /** Called when app transition finished. */
    void onAppTransitionFinished();

    /** Dumps current status of Split Screen. */
    void dump(PrintWriter pw);

    /** Registers listener that gets called whenever the existence of the divider changes. */
    void registerInSplitScreenListener(Consumer<Boolean> listener);

    /** Registers listener that gets called whenever the split screen bounds changes. */
    void registerBoundsChangeListener(BiConsumer<Rect, Rect> listener);

    /** @return the container token for the secondary split root task. */
    WindowContainerToken getSecondaryRoot();
}
