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

/** The type of a [SliderEvent]. */
enum class SliderEventType {
    /* No event. */
    NOTHING,
    /* The slider has captured a touch input and is tracking touch events. */
    STARTED_TRACKING_TOUCH,
    /* The slider started tracking programmatic value changes */
    STARTED_TRACKING_PROGRAM,
    /* The slider progress is changing due to user touch input. */
    PROGRESS_CHANGE_BY_USER,
    /* The slider progress is changing programmatically. */
    PROGRESS_CHANGE_BY_PROGRAM,
    /* The slider has stopped tracking touch events. */
    STOPPED_TRACKING_TOUCH,
    /* The external (not touch) stimulus that was modifying the slider progress has stopped. */
    STOPPED_TRACKING_PROGRAM,
}
