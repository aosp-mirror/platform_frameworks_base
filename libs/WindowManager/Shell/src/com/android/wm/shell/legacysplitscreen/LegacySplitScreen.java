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

package com.android.wm.shell.legacysplitscreen;

import android.graphics.Rect;
import android.window.WindowContainerToken;

import com.android.wm.shell.common.annotations.ExternalThread;

import java.io.PrintWriter;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Interface to engage split screen feature.
 */
@ExternalThread
public interface LegacySplitScreen {
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

    /** Called when there's a task undocking. */
    void onUndockingTask();

    /** Called when app transition finished. */
    void onAppTransitionFinished();

    /** Dumps current status of Split Screen. */
    void dump(PrintWriter pw);

    /** Registers listener that gets called whenever the existence of the divider changes. */
    void registerInSplitScreenListener(Consumer<Boolean> listener);

    /** Unregisters listener that gets called whenever the existence of the divider changes. */
    void unregisterInSplitScreenListener(Consumer<Boolean> listener);

    /** Registers listener that gets called whenever the split screen bounds changes. */
    void registerBoundsChangeListener(BiConsumer<Rect, Rect> listener);

    /** @return the container token for the secondary split root task. */
    WindowContainerToken getSecondaryRoot();

    /**
     * Splits the primary task if feasible, this is to preserve legacy way to toggle split screen.
     * Like triggering split screen through long pressing recents app button or through
     * {@link android.accessibilityservice.AccessibilityService#GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN}.
     *
     * @return {@code true} if it successes to split the primary task.
     */
    boolean splitPrimaryTask();

    /**
     * Exits the split to make the primary task fullscreen.
     */
    void dismissSplitToPrimaryTask();
}
