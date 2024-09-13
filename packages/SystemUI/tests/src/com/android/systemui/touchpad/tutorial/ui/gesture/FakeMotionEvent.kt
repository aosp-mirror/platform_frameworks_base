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
 * limitations under the License.
 */

package com.android.systemui.touchpad.tutorial.ui.gesture

import android.view.InputDevice.SOURCE_CLASS_POINTER
import android.view.InputDevice.SOURCE_MOUSE
import android.view.MotionEvent
import android.view.MotionEvent.CLASSIFICATION_NONE
import android.view.MotionEvent.TOOL_TYPE_FINGER
import org.mockito.AdditionalAnswers
import org.mockito.Mockito.mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

fun motionEvent(
    action: Int,
    x: Float,
    y: Float,
    source: Int = 0,
    toolType: Int = TOOL_TYPE_FINGER,
    pointerCount: Int = 1,
    axisValues: Map<Int, Float> = emptyMap(),
    classification: Int = CLASSIFICATION_NONE,
): MotionEvent {
    val event =
        MotionEvent.obtain(/* downTime= */ 0, /* eventTime= */ 0, action, x, y, /* metaState= */ 0)
    event.source = source
    // we need to use mock with delegation instead of spy because:
    // 1. Spy will try to deallocate the same memory again when finalize() is called as it keep the
    // same memory pointer to native MotionEvent
    // 2. Even after workaround for issue above there still remains problem with destructor of
    // native event trying to free the same chunk of native memory. I'm not sure why it happens but
    // mock seems to fix the issue and because it delegates all calls seems safer overall
    val delegate = mock(MotionEvent::class.java, AdditionalAnswers.delegatesTo<MotionEvent>(event))
    doReturn(toolType).whenever(delegate).getToolType(0)
    doReturn(pointerCount).whenever(delegate).pointerCount
    doReturn(classification).whenever(delegate).classification
    axisValues.forEach { (key, value) -> doReturn(value).whenever(delegate).getAxisValue(key) }
    return delegate
}

fun touchpadEvent(
    action: Int,
    x: Float,
    y: Float,
    pointerCount: Int = 1,
    classification: Int = CLASSIFICATION_NONE,
    axisValues: Map<Int, Float> = emptyMap()
): MotionEvent {
    return motionEvent(
        action = action,
        x = x,
        y = y,
        source = SOURCE_MOUSE or SOURCE_CLASS_POINTER,
        toolType = TOOL_TYPE_FINGER,
        pointerCount = pointerCount,
        classification = classification,
        axisValues = axisValues
    )
}
