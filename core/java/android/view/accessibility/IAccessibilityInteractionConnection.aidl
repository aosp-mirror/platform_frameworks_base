/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

/**
 * Interface for interaction between the AccessibilityManagerService
 * and the ViewRoot in a given window.
 *
 * @hide
 */
oneway interface IAccessibilityInteractionConnection {

    void findAccessibilityNodeInfoByAccessibilityId(long accessibilityNodeId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
        long interrogatingTid);

    void findAccessibilityNodeInfoByViewId(long accessibilityNodeId, int viewId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
        long interrogatingTid);

    void findAccessibilityNodeInfosByText(long accessibilityNodeId, String text, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
        long interrogatingTid);

    void findFocus(long accessibilityNodeId, int focusType, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
        long interrogatingTid);

    void focusSearch(long accessibilityNodeId, int direction, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int flags, int interrogatingPid,
        long interrogatingTid);

    void performAccessibilityAction(long accessibilityNodeId, int action, in Bundle arguments,
        int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
        int interrogatingPid, long interrogatingTid);
}
