/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.graphics.drawable;

import android.annotation.NonNull;

/**
 * Abstract class that drawables supporting animations and callbacks should extend.
 */
public interface Animatable2 extends Animatable {

    /**
     * Adds a callback to listen to the animation events.
     *
     * @param callback Callback to add.
     */
    void registerAnimationCallback(@NonNull AnimationCallback callback);

    /**
     * Removes the specified animation callback.
     *
     * @param callback Callback to remove.
     * @return {@code false} if callback didn't exist in the call back list, or {@code true} if
     *         callback has been removed successfully.
     */
    boolean unregisterAnimationCallback(@NonNull AnimationCallback callback);

    /**
     * Removes all existing animation callbacks.
     */
    void clearAnimationCallbacks();

    public static abstract class AnimationCallback {
        /**
         * Called when the animation starts.
         *
         * @param drawable The drawable started the animation.
         */
        public void onAnimationStart(Drawable drawable) {};
        /**
         * Called when the animation ends.
         *
         * @param drawable The drawable finished the animation.
         */
        public void onAnimationEnd(Drawable drawable) {};
    }
}
