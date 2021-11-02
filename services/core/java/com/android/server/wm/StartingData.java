/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.server.wm.StartingSurfaceController.StartingSurface;

/**
 * Represents the model about how a starting window should be constructed.
 */
public abstract class StartingData {

    protected final WindowManagerService mService;
    protected final int mTypeParams;

    /**
     * Tell whether the launching activity should use
     * {@link android.view.WindowManager.LayoutParams#SOFT_INPUT_IS_FORWARD_NAVIGATION}.
     */
    boolean mIsTransitionForward;

    /**
     * Non-null if the starting window should cover the bounds of associated task. It is assigned
     * when the parent activity of starting window may be put in a partial area of the task.
     */
    Task mAssociatedTask;

    protected StartingData(WindowManagerService service, int typeParams) {
        mService = service;
        mTypeParams = typeParams;
    }

    /**
     * Creates the actual starting window surface. DO NOT HOLD THE WINDOW MANAGER LOCK WHEN CALLING
     * THIS METHOD.
     *
     * @param activity the app to add the starting window to
     * @return a class implementing {@link StartingSurface} for easy removal with
     *         {@link StartingSurface#remove}
     */
    abstract StartingSurface createStartingSurface(ActivityRecord activity);

    /**
     * @return Whether to apply reveal animation when exiting the starting window.
     */
    abstract boolean needRevealAnimation();

    /** @see android.window.TaskSnapshot#hasImeSurface() */
    boolean hasImeSurface() {
        return false;
    }
}
