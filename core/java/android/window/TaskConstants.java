/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import android.annotation.IntDef;

/**
 * Holds constants related to task managements but not suitable in {@code TaskOrganizer}.
 * @hide
 */
public class TaskConstants {

    /**
     * Sizes of a z-order region assigned to child layers of task layers. Components are allowed to
     * use all values in [assigned value, assigned value + region size).
     * @hide
     */
    public static final int TASK_CHILD_LAYER_REGION_SIZE = 10000;

    /**
     * Indicates system responding to task drag resizing while app content isn't updated.
     * @hide
     */
    public static final int TASK_CHILD_LAYER_TASK_BACKGROUND = -3 * TASK_CHILD_LAYER_REGION_SIZE;

    /**
     * Provides solid color letterbox background or blur effect and dimming for the wallpaper
     * letterbox background. It also listens to touches for double tap gesture for repositioning
     * letterbox.
     * @hide
     */
    public static final int TASK_CHILD_LAYER_LETTERBOX_BACKGROUND =
            -2 * TASK_CHILD_LAYER_REGION_SIZE;

    /**
     * Compat UI components: reachability education, size compat restart
     * button, letterbox education, restart dialog.
     * @hide
     */
    public static final int TASK_CHILD_LAYER_COMPAT_UI = TASK_CHILD_LAYER_REGION_SIZE;


    /**
     * Captions, window frames and resize handlers around task windows.
     * @hide
     */
    public static final int TASK_CHILD_LAYER_WINDOW_DECORATIONS = 2 * TASK_CHILD_LAYER_REGION_SIZE;

    /**
     * Overlays the task when going into PIP w/ gesture navigation.
     * @hide
     */
    public static final int TASK_CHILD_LAYER_RECENTS_ANIMATION_PIP_OVERLAY =
            3 * TASK_CHILD_LAYER_REGION_SIZE;

    /**
     * Allows other apps to add overlays on the task (i.e. game dashboard)
     * @hide
     */
    public static final int TASK_CHILD_LAYER_TASK_OVERLAY = 4 * TASK_CHILD_LAYER_REGION_SIZE;


    /**
     * Veil to cover task surface and other window decorations during resizes.
     * @hide
     */
    public static final int TASK_CHILD_LAYER_RESIZE_VEIL = 6 * TASK_CHILD_LAYER_REGION_SIZE;

    /**
     * Floating menus belonging to a task (e.g. maximize menu).
     * @hide
     */
    public  static final int TASK_CHILD_LAYER_FLOATING_MENU = 7 * TASK_CHILD_LAYER_REGION_SIZE;

    /**
     * Z-orders of task child layers other than activities, task fragments and layers interleaved
     * with them, e.g. IME windows. [-10000, 10000) is reserved for these layers.
     * @hide
     */
    @IntDef({
            TASK_CHILD_LAYER_TASK_BACKGROUND,
            TASK_CHILD_LAYER_LETTERBOX_BACKGROUND,
            TASK_CHILD_LAYER_COMPAT_UI,
            TASK_CHILD_LAYER_WINDOW_DECORATIONS,
            TASK_CHILD_LAYER_RECENTS_ANIMATION_PIP_OVERLAY,
            TASK_CHILD_LAYER_TASK_OVERLAY,
            TASK_CHILD_LAYER_RESIZE_VEIL
    })
    public @interface TaskChildLayer {}
}
