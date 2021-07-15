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

package com.android.wm.shell.pip;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.RemoteAction;
import android.content.pm.ParceledListSlice;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.WindowManager;

/**
 *  Interface to allow {@link com.android.wm.shell.pip.PipTaskOrganizer} to call into
 *  PiP menu when certain events happen (task appear/vanish, PiP move, etc.)
 */
public interface PipMenuController {

    String MENU_WINDOW_TITLE = "PipMenuView";

    /**
     * Called when
     * {@link PipTaskOrganizer#onTaskAppeared(RunningTaskInfo, SurfaceControl)}
     * is called.
     */
    void attach(SurfaceControl leash);

    /**
     * Called when
     * {@link PipTaskOrganizer#onTaskVanished(RunningTaskInfo)} is called.
     */
    void detach();

    /**
     * Check if menu is visible or not.
     */
    boolean isMenuVisible();

    /**
     * Show the PIP menu.
     */
    void showMenu();

    /**
     * Given a set of actions, update the menu.
     */
    void setAppActions(ParceledListSlice<RemoteAction> appActions);

    /**
     * Resize the PiP menu with the given bounds. The PiP SurfaceControl is given if there is a
     * need to synchronize the movements on the same frame as PiP.
     */
    default void resizePipMenu(@Nullable SurfaceControl pipLeash,
            @Nullable SurfaceControl.Transaction t,
            Rect destinationBounds) {}

    /**
     * Move the PiP menu with the given bounds. The PiP SurfaceControl is given if there is a
     * need to synchronize the movements on the same frame as PiP.
     */
    default void movePipMenu(@Nullable SurfaceControl pipLeash,
            @Nullable SurfaceControl.Transaction t,
            Rect destinationBounds) {}

    /**
     * Update the PiP menu with the given bounds for re-layout purposes.
     */
    default void updateMenuBounds(Rect destinationBounds) {}

    /**
     * Returns a default LayoutParams for the PIP Menu.
     * @param width the PIP stack width.
     * @param height the PIP stack height.
     */
    default WindowManager.LayoutParams getPipMenuLayoutParams(String title, int width, int height) {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                TYPE_APPLICATION_OVERLAY,
                FLAG_WATCH_OUTSIDE_TOUCH | FLAG_SPLIT_TOUCH | FLAG_SLIPPERY | FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= PRIVATE_FLAG_TRUSTED_OVERLAY;
        lp.setTitle(title);
        return lp;
    }
}
