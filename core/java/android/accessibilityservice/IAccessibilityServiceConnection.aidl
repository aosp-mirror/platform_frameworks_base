/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accessibilityservice;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ParceledListSlice;
import android.graphics.Region;
import android.os.Bundle;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * Interface given to an AccessibilitySerivce to talk to the AccessibilityManagerService.
 *
 * @hide
 */
interface IAccessibilityServiceConnection {

    void setServiceInfo(in AccessibilityServiceInfo info);

    String[] findAccessibilityNodeInfoByAccessibilityId(int accessibilityWindowId,
        long accessibilityNodeId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int flags, long threadId);

    String[] findAccessibilityNodeInfosByText(int accessibilityWindowId, long accessibilityNodeId,
        String text, int interactionId, IAccessibilityInteractionConnectionCallback callback,
        long threadId);

    String[] findAccessibilityNodeInfosByViewId(int accessibilityWindowId,
        long accessibilityNodeId, String viewId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, long threadId);

    String[] findFocus(int accessibilityWindowId, long accessibilityNodeId, int focusType,
        int interactionId, IAccessibilityInteractionConnectionCallback callback, long threadId);

    String[] focusSearch(int accessibilityWindowId, long accessibilityNodeId, int direction,
        int interactionId, IAccessibilityInteractionConnectionCallback callback, long threadId);

    boolean performAccessibilityAction(int accessibilityWindowId, long accessibilityNodeId,
        int action, in Bundle arguments, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, long threadId);

    AccessibilityWindowInfo getWindow(int windowId);

    List<AccessibilityWindowInfo> getWindows();

    AccessibilityServiceInfo getServiceInfo();

    boolean performGlobalAction(int action);

    void disableSelf();

    oneway void setOnKeyEventResult(boolean handled, int sequence);

    float getMagnificationScale();

    float getMagnificationCenterX();

    float getMagnificationCenterY();

    Region getMagnificationRegion();

    boolean resetMagnification(boolean animate);

    boolean setMagnificationScaleAndCenter(float scale, float centerX, float centerY,
        boolean animate);

    void setMagnificationCallbackEnabled(boolean enabled);

    boolean setSoftKeyboardShowMode(int showMode);

    void setSoftKeyboardCallbackEnabled(boolean enabled);

    void sendGesture(int sequence, in ParceledListSlice gestureSteps);
}
