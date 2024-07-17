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
import java.lang.reflect.Method
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.spy
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
    val spy =
        spy<MotionEvent>(event) {
            on { getToolType(0) } doReturn toolType
            on { getPointerCount() } doReturn pointerCount
            axisValues.forEach { (key, value) -> on { getAxisValue(key) } doReturn value }
            on { getClassification() } doReturn classification
        }
    ensureFinalizeIsNotCalledTwice(spy)
    return spy
}

private fun ensureFinalizeIsNotCalledTwice(spy: MotionEvent) {
    // Spy in mockito will create copy of the spied object, copying all its field etc. Here it means
    // we create copy of MotionEvent and its mNativePtr, so we have two separate objects of type
    // MotionEvents with the same mNativePtr. That breaks because MotionEvent has custom finalize()
    // method which goes to native code and tries to delete the reference from mNativePtr. It works
    // first time but second time reference is already deleted and it breaks. That's why we have to
    // avoid calling finalize twice
    doNothing().whenever(spy).finalizeUsingReflection()
}

private fun MotionEvent.finalizeUsingReflection() {
    val finalizeMethod: Method = MotionEvent::class.java.getDeclaredMethod("finalize")
    finalizeMethod.isAccessible = true
    finalizeMethod.invoke(this)
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
