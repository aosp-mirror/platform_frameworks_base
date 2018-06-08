/*
 * Copyright 2017 The Android Open Source Project
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

package android.view.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.content.pm.ParceledListSlice;
import android.graphics.Region;
import android.os.Bundle;

import java.util.List;

/**
 * Stub implementation of IAccessibilityServiceConnection so each test doesn't need to implement
 * all of the methods
 */
public class AccessibilityServiceConnectionImpl extends IAccessibilityServiceConnection.Stub {
    public void setServiceInfo(AccessibilityServiceInfo info) {}

    public String[] findAccessibilityNodeInfoByAccessibilityId(int accessibilityWindowId,
            long accessibilityNodeId, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags, long threadId,
            Bundle arguments) {
        return null;
    }

    public String[] findAccessibilityNodeInfosByText(int accessibilityWindowId,
            long accessibilityNodeId, String text, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, long threadId) {
        return null;
    }

    public String[] findAccessibilityNodeInfosByViewId(int accessibilityWindowId,
            long accessibilityNodeId, String viewId, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, long threadId) {
        return null;
    }

    public String[] findFocus(int accessibilityWindowId, long accessibilityNodeId, int focusType,
            int interactionId, IAccessibilityInteractionConnectionCallback callback,
            long threadId) {
        return null;
    }

    public String[] focusSearch(int accessibilityWindowId, long accessibilityNodeId, int direction,
            int interactionId, IAccessibilityInteractionConnectionCallback callback,
            long threadId) {
        return null;
    }

    public boolean performAccessibilityAction(int accessibilityWindowId, long accessibilityNodeId,
            int action, Bundle arguments, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, long threadId) {
        return false;
    }

    public AccessibilityWindowInfo getWindow(int windowId) {
        return null;
    }

    public List<AccessibilityWindowInfo> getWindows() {
        return null;
    }

    public AccessibilityServiceInfo getServiceInfo() {
        return null;
    }

    public boolean performGlobalAction(int action) {
        return false;
    }

    public void disableSelf() {}

    public void setOnKeyEventResult(boolean handled, int sequence) {}

    public float getMagnificationScale() {
        return 0.0f;
    }

    public float getMagnificationCenterX() {
        return 0.0f;
    }

    public float getMagnificationCenterY() {
        return 0.0f;
    }

    public Region getMagnificationRegion() {
        return null;
    }

    public boolean resetMagnification(boolean animate) {
        return false;
    }

    public boolean setMagnificationScaleAndCenter(float scale, float centerX, float centerY,
            boolean animate) {
        return false;
    }

    public void setMagnificationCallbackEnabled(boolean enabled) {}

    public boolean setSoftKeyboardShowMode(int showMode) {
        return false;
    }

    public void setSoftKeyboardCallbackEnabled(boolean enabled) {}

    public boolean isAccessibilityButtonAvailable() {
        return false;
    }

    public void sendGesture(int sequence, ParceledListSlice gestureSteps) {}

    public boolean isFingerprintGestureDetectionAvailable() {
        return false;
    }
}
