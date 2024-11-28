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

package com.android.server.wm;

import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.Rect;

/**
 * Some utility methods used by different Letterbox implementations.
 */
class AppCompatLetterboxUtils {
    /**
     * Provides the position of the top left letterbox area in the display coordinate system.
     *
     * @param activity             The Letterboxed activity.
     * @param outLetterboxPosition InOut parameter that will contain the desired letterbox position.
     */
    static void calculateLetterboxPosition(@NonNull ActivityRecord activity,
            @NonNull Point outLetterboxPosition) {
        if (!activity.mAppCompatController.getAppCompatLetterboxPolicy().isRunning()) {
            outLetterboxPosition.set(0, 0);
            return;
        }
        if (activity.isInLetterboxAnimation()) {
            // In this case we attach the letterbox to the task instead of the activity.
            activity.getTask().getPosition(outLetterboxPosition);
        } else {
            activity.getPosition(outLetterboxPosition);
        }
    }

    /**
     * Provides all the available space, in display coordinate, to fill with the letterboxed
     * activity and the letterbox areas.
     *
     * @param activity       The Letterboxed activity.
     * @param outOuterBounds InOut parameter that will contain the outer bounds for the letterboxed
     *                       activity.
     */
    static void calculateLetterboxOuterBounds(@NonNull ActivityRecord activity,
            @NonNull Rect outOuterBounds) {
        if (!activity.mAppCompatController.getAppCompatLetterboxPolicy().isRunning()) {
            outOuterBounds.setEmpty();
            return;
        }
        // Get the bounds of the "space-to-fill". The transformed bounds have the highest
        // priority because the activity is launched in a rotated environment. In multi-window
        // mode, the taskFragment-level represents this for both split-screen
        // and activity-embedding. In fullscreen-mode, the task container does
        // (since the orientation letterbox is also applied to the task).
        final Rect transformedBounds =
                activity.getFixedRotationTransformDisplayBounds();
        outOuterBounds.set(transformedBounds != null
                ? transformedBounds
                : activity.inMultiWindowMode()
                        ? activity.getTaskFragment().getBounds()
                        : activity.getRootTask().getParent().getBounds());
    }

    /**
     * Provides the inner bounds for the letterboxed activity in display coordinates. This is the
     * space the letterboxed activity will use.
     *
     * @param activity       The Letterboxed activity.
     * @param outInnerBounds InOut parameter that will contain the inner bounds for the letterboxed
     *                       activity.
     */
    static void calculateLetterboxInnerBounds(@NonNull ActivityRecord activity,
            @NonNull WindowState window, @NonNull Rect outInnerBounds) {
        if (!activity.mAppCompatController.getAppCompatLetterboxPolicy().isRunning()) {
            outInnerBounds.setEmpty();
            return;
        }
        // In case of translucent activities an option is to use the WindowState#getFrame() of
        // the first opaque activity beneath. In some cases (e.g. an opaque activity is using
        // non MATCH_PARENT layouts or a Dialog theme) this might not provide the correct
        // information and in particular it might provide a value for a smaller area making
        // the letterbox overlap with the translucent activity's frame.
        // If we use WindowState#getFrame() for the translucent activity's letterbox inner
        // frame, the letterbox will then be overlapped with the translucent activity's frame.
        // Because the surface layer of letterbox is lower than an activity window, this
        // won't crop the content, but it may affect other features that rely on values stored
        // in mLetterbox, e.g. transitions, a status bar scrim and recents preview in Launcher
        // For this reason we use ActivityRecord#getBounds() that the translucent activity
        // inherits from the first opaque activity beneath and also takes care of the scaling
        // in case of activities in size compat mode.
        final TransparentPolicy transparentPolicy =
                activity.mAppCompatController.getTransparentPolicy();
        outInnerBounds.set(
                transparentPolicy.isRunning() ? activity.getBounds() : window.getFrame());
    }
}
