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

/** State of a slider */
enum class SliderState {
    /* The slider is idle */
    IDLE,
    /* Waiting state to disambiguate between handle acquisition and select and jump operations */
    WAIT,
    /* The slider handle was acquired by touch. */
    DRAG_HANDLE_ACQUIRED_BY_TOUCH,
    /* The slider handle was released. */
    DRAG_HANDLE_RELEASED_FROM_TOUCH,
    /* The slider handle is being dragged by touch. */
    DRAG_HANDLE_DRAGGING,
    /* The slider handle reached a bookend. */
    DRAG_HANDLE_REACHED_BOOKEND,
    /* A location in the slider track has been selected. */
    JUMP_TRACK_LOCATION_SELECTED,
    /* The slider handle moved to a bookend after it was selected. */
    JUMP_BOOKEND_SELECTED,
    /** The slider handle moved due to single select-and-arrow operation */
    ARROW_HANDLE_MOVED_ONCE,
    /** The slider handle moves continuously due to constant select-and-arrow operations */
    ARROW_HANDLE_MOVES_CONTINUOUSLY,
    /** The slider handle reached a bookend due to a select-and-arrow operation */
    ARROW_HANDLE_REACHED_BOOKEND,
}
