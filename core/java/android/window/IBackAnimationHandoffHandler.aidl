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
 * limitations under the License

 */

package android.window;

import android.os.Bundle;
import android.view.RemoteAnimationTarget;
import android.window.BackMotionEvent;
import android.window.WindowAnimationState;


/**
 * Interface that allows an {@link OnBackInvokedCallback} object to hand off an animation to another
 * handler.
 *
 * @hide
 */
oneway interface IBackAnimationHandoffHandler {
    /**
     * Triggers a handoff of the animation of the given targets and their associated states.
     * Important: since this is a one-way method, the caller must first make sure that the animation
     * can indeed be taken over.
     */
    oneway void handOffAnimation(in RemoteAnimationTarget[] targets,
                    in WindowAnimationState[] states);
}
