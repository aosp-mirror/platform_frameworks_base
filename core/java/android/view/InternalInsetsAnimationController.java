/*
 * Copyright (C) 2021 The Android Open Source Project
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

/**
 * An internal interface which provides methods that will be only used by the framework.
 * @hide
 */
public interface InternalInsetsAnimationController extends WindowInsetsAnimationController {

    /**
     * Flags whether {@link WindowInsetsAnimationControlListener#onReady(
     * WindowInsetsAnimationController, int)} has been invoked.
     * @hide
     */
    void setReadyDispatched(boolean dispatched);

    /**
     * Returns the {@link InsetsState} based on the current animation progress.
     *
     * @param outState the insets state which matches the current animation progress.
     * @return {@code true} if the animation has been finished; {@code false} otherwise.
     * @hide
     */
    boolean applyChangeInsets(InsetsState outState);
}

