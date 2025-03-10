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
import android.graphics.Rect;
import android.view.SurfaceControl;

/**
 * Abstraction for different Letterbox state implementations.
 */
interface AppCompatLetterboxPolicyState {

    /**
     * Checks if a relayout is necessary for the letterbox implementations.
     * @param w The {@link WindowState} to use for defining Letterbox sizes.
     */
    void layoutLetterboxIfNeeded(@NonNull WindowState w);

    /**
     * @return  {@code true} if the policy is running and so if the current activity is
     *          letterboxed.
     */
    boolean isRunning();

    /**
     * Called when the activity is moved to a new display.
     * @param displayId Id for the new display
     */
    void onMovedToDisplay(int displayId);

    /** Cleans up {@link Letterbox} if it exists.*/
    void stop();

    /** Hides the letterbox surfaces implementation. */
    void hide();

    /** Gets the letterbox insets. The insets will be empty if there is no letterbox. */
    @NonNull
    Rect getLetterboxInsets();

    /** Gets the inner bounds of letterbox. The bounds will be empty with no letterbox. */
    void getLetterboxInnerBounds(@NonNull Rect outBounds);

    /** Gets the outer bounds of letterbox. The bounds will be empty with no letterbox. */
    void getLetterboxOuterBounds(@NonNull Rect outBounds);

    /**
     * Updates the letterbox surfaces in case this is needed.
     *
     * @param winHint   The WindowState for the letterboxed Activity.
     * @param t         The current Transaction.
     * @param inputT    The pending transaction used for the input surface.
     */
    void updateLetterboxSurfaceIfNeeded(@NonNull WindowState winHint,
            @NonNull SurfaceControl.Transaction t,
            @NonNull SurfaceControl.Transaction inputT);

    /**
     * @return {@code true} if bar shown within a given rectangle is allowed to be fully
     *          transparent when the current activity is displayed.
     */
    boolean isFullyTransparentBarAllowed(@NonNull Rect rect);

}
