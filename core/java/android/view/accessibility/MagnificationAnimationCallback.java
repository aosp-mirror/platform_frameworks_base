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

package android.view.accessibility;

import android.view.MagnificationSpec;

/**
 * A callback for magnification animation result.
 * @hide
 */
public interface MagnificationAnimationCallback {
    MagnificationAnimationCallback STUB_ANIMATION_CALLBACK = success -> {
    };

    /**
     * Called when the animation is finished or interrupted during animating.
     *
     * @param success {@code true} if animating successfully with given spec or the spec did not
     *                change. Otherwise {@code false}
     */
    void onResult(boolean success);

    /**
     * Called when the animation is finished or interrupted during animating.
     *
     * @param success {@code true} if animating successfully with given spec or the spec did not
     *                change. Otherwise {@code false}
     * @param lastSpecSent the last spec that was sent to WindowManager for animation, in case you
     *                     need to update the local copy
     */
    default void onResult(boolean success, MagnificationSpec lastSpecSent) {
        onResult(success);
    }
}