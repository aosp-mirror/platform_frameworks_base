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

import android.util.proto.ProtoOutputStream;
import android.view.InsetsController.AnimationType;
import android.view.InsetsState.InternalInsetsType;
import android.view.WindowInsets.Type.InsetsType;

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
     * Cancels the animation.
     */
    void cancel();

    /**
     * @return The animation this runner is running.
     */
    WindowInsetsAnimation getAnimation();

    /**
     * @return Whether {@link #getTypes()} maps to a specific {@link InternalInsetsType}.
     */
    default boolean controlsInternalType(@InternalInsetsType int type) {
        return InsetsState.toInternalType(getTypes()).contains(type);
    }

    /**
     * @return The animation type this runner is running.
     */
    @AnimationType int getAnimationType();

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
