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

package com.android.systemui.navigationbar.buttons;

import android.annotation.Nullable;
import android.graphics.drawable.Drawable;

public interface ButtonInterface {

    void setImageDrawable(@Nullable Drawable drawable);

    void abortCurrentGesture();

    void setVertical(boolean vertical);

    void setDarkIntensity(float intensity);

    void setDelayTouchFeedback(boolean shouldDelay);

    /**
     * Animate the button being long-pressed.
     *
     * @param isTouchDown {@code true} if the button is starting to be pressed ({@code false} if
     *                    released or canceled)
     * @param shrink      {@code true} if the handle should shrink, {@code false} if it should grow
     * @param durationMs  how long the animation should take (for the {@code isTouchDown} case, this
     *                    should be the same as the amount of time to trigger a long-press)
     */
    default void animateLongPress(boolean isTouchDown, boolean shrink, long durationMs) {}
}
