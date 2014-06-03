/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.graphics.Rect;
import com.android.systemui.recents.ReferenceCountedTrigger;

/* Common code related to view animations */
public class ViewAnimation {

    /* The animation context for a task view animation into Recents */
    public static class TaskViewEnterContext {
        // The full screenshot view that we are animating down
        FullScreenTransitionView fullScreenshot;
        // The transform of the current task view
        TaskViewTransform transform;
        // The stack rect that the transform is relative to
        Rect stackRectSansPeek;
        // The task rect
        Rect taskRect;
        // The view index of the current task view
        int stackViewIndex;
        // The total number of task views
        int stackViewCount;
        // Whether this is the front most task view
        boolean isFrontMost;

        public TaskViewEnterContext(FullScreenTransitionView fss) {
            fullScreenshot = fss;
        }
    }

    /* The animation context for a task view animation out of Recents */
    public static class TaskViewExitContext {
        // A trigger to run some logic when all the animations complete.  This works around the fact
        // that it is difficult to coordinate ViewPropertyAnimators
        ReferenceCountedTrigger postAnimationTrigger;
        // The translationY to apply to a TaskView to move it off screen
        int offscreenTranslationY;

        public TaskViewExitContext(ReferenceCountedTrigger t) {
            postAnimationTrigger = t;
        }
    }

}
