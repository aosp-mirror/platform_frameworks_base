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

package android.view;

import android.annotation.Nullable;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsController.AnimationType;
import android.view.InsetsController.LayoutInsetsDuringAnimation;
import android.view.WindowInsets.Type.InsetsType;
import android.view.inputmethod.ImeTracker;

/**
 * Interface representing a runner for an insets animation.
 *
 * @hide
 */
public interface InsetsAnimationControlRunner {

    /**
     * @return The {@link InsetsType} the animation of this runner controls.
     */
    @InsetsType int getTypes();

    /**
     * @return The {@link InsetsType} the animation of this runner is controlling. This can be
     *         changed if a control is revoked.
     */
    @InsetsType int getControllingTypes();

    /**
     * Notifies {@link InsetsType types} of control are getting revoked.
     */
    void notifyControlRevoked(@InsetsType int types);

    /**
     * Updates the surface positions of the controls owned by this runner if there is any.
     *
     * @param controls An array of {@link InsetsSourceControl} that the caller newly receives.
     */
    void updateSurfacePosition(SparseArray<InsetsSourceControl> controls);

    /**
     * Cancels the animation.
     */
    void cancel();

    /**
     * @return The animation this runner is running.
     */
    WindowInsetsAnimation getAnimation();

    /**
     * @return Whether {@link #getTypes()} contains a specific {@link InsetsType}.
     */
    default boolean controlsType(@InsetsType int type) {
        return (getTypes() & type) != 0;
    }

    /**
     * @return The animation type this runner is running.
     */
    @AnimationType int getAnimationType();

    /**
     * @return The token tracking the current IME request or {@code null} otherwise.
     */
    @Nullable
    ImeTracker.Token getStatsToken();

    /**
     * Updates the desired layout insets during the animation.
     *
     * @param layoutInsetsDuringAnimation Whether the insets should be shown or hidden
     */
    void updateLayoutInsetsDuringAnimation(
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation);

    /**
     *
     * Export the state of classes that implement this interface into a protocol buffer
     * output stream.
     *
     * @param proto Stream to write the state to
     * @param fieldId FieldId of the implementation class
     */
    void dumpDebug(ProtoOutputStream proto, long fieldId);
}
