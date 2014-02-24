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

import android.os.Bundle;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.MagnificationSpec;
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

    boolean findAccessibilityNodeInfoByAccessibilityId(int accessibilityWindowId,
        long accessibilityNodeId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int flags, long threadId);

    boolean findAccessibilityNodeInfosByText(int accessibilityWindowId, long accessibilityNodeId,
        String text, int interactionId, IAccessibilityInteractionConnectionCallback callback,
        long threadId);

    boolean findAccessibilityNodeInfosByViewId(int accessibilityWindowId,
        long accessibilityNodeId, String viewId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, long threadId);

    boolean findFocus(int accessibilityWindowId, long accessibilityNodeId, int focusType,
        int interactionId, IAccessibilityInteractionConnectionCallback callback, long threadId);

    boolean focusSearch(int accessibilityWindowId, long accessibilityNodeId, int direction,
        int interactionId, IAccessibilityInteractionConnectionCallback callback, long threadId);

    boolean performAccessibilityAction(int accessibilityWindowId, long accessibilityNodeId,
        int action, in Bundle arguments, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, long threadId);

    AccessibilityWindowInfo getWindow(int windowId);

    List<AccessibilityWindowInfo> getWindows();

    AccessibilityServiceInfo getServiceInfo();

    boolean performGlobalAction(int action);

    oneway void setOnKeyEventResult(boolean handled, int sequence);
}
