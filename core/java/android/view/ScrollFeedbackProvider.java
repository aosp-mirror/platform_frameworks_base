/**
 * Copyright (C) 2023 The Android Open Source Project
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
 * Interface to represent an entity giving consistent feedback for different events surrounding view
 * scroll.
 *
 * @hide
 */
public interface ScrollFeedbackProvider {
    /**
     * The view has snapped to an item, with a motion from a given {@link MotionEvent} on a given
     * {@code axis}.
     *
     * <p>The interface is not aware of the internal scroll states of the view for which scroll
     * feedback is played. As such, the client should call
     * {@link #onScrollLimit(MotionEvent, int, int)} when scrolling has reached limit.
     *
     * @param event the {@link MotionEvent} that caused the item to snap.
     * @param axis the axis of {@code event} that caused the item to snap.
     */
    void onSnapToItem(MotionEvent event, int axis);

    /**
     * The view has reached the scroll limit when scrolled by the motion from a given
     * {@link MotionEvent} on a given {@code axis}.
     *
     * @param event the {@link MotionEvent} that caused scrolling to hit the limit.
     * @param axis the axis of {@code event} that caused scrolling to hit the limit.
     * @param isStart {@code true} if scrolling hit limit at the start of the scrolling list, and
     *                {@code false} if the scrolling hit limit at the end of the scrolling list.
     */
    void onScrollLimit(MotionEvent event, int axis, boolean isStart);

    /**
     * The view has scrolled by {@code deltaInPixels} due to the motion from a given
     * {@link MotionEvent} on a given {@code axis}.
     *
     * <p>The interface is not aware of the internal scroll states of the view for which scroll
     * feedback is played. As such, the client should call
     * {@link #onScrollLimit(MotionEvent, int, int)} when scrolling has reached limit.
     *
     * @param event the {@link MotionEvent} that caused scroll progress.
     * @param axis the axis of {@code event} that caused scroll progress.
     * @param deltaInPixels the amount of scroll progress, in pixels.
     */
    void onScrollProgress(MotionEvent event, int axis, int deltaInPixels);
}
