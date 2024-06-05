/*
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

package com.android.systemui.haptics.slider

import androidx.annotation.FloatRange

/** Listener of events from a slider (such as [android.widget.SeekBar]) */
interface SliderStateListener {

    /** Notification that the handle is acquired by touch */
    fun onHandleAcquiredByTouch()

    /** Notification that the handle was released from touch */
    fun onHandleReleasedFromTouch()

    /** Notification that the handle reached the lower bookend */
    fun onLowerBookend()

    /** Notification that the handle reached the upper bookend */
    fun onUpperBookend()

    /**
     * Notification that the slider reached a certain progress on the slider track.
     *
     * This method is called in all intermediate steps of a continuous progress change as the slider
     * moves through the slider track. A single discrete movement of the handle by an external
     * button or by a jump on the slider track will not trigger this callback. See
     * [onSelectAndArrow] and [onProgressJump] for these cases.
     *
     * @param[progress] The progress of the slider in the range from 0F to 1F (inclusive).
     */
    fun onProgress(@FloatRange(from = 0.0, to = 1.0) progress: Float)

    /**
     * Notification that the slider handle jumped to a selected progress on the slider track.
     *
     * This method is specific to the case when the handle performed a single jump to a position on
     * the slider track and reached the corresponding progress. In this case, [onProgress] is not
     * called and the new progress reached is represented by the [progress] parameter.
     *
     * @param[progress] The selected progress on the slider track that the handle jumps to. The
     *   progress is in the range from 0F to 1F (inclusive).
     */
    fun onProgressJump(@FloatRange(from = 0.0, to = 1.0) progress: Float)

    /**
     * Notification that the slider handle was moved discretely by one step via a button press.
     *
     * @param[progress] The progress of the slider in the range from 0F to 1F (inclusive).
     */
    fun onSelectAndArrow(@FloatRange(from = 0.0, to = 1.0) progress: Float)
}
