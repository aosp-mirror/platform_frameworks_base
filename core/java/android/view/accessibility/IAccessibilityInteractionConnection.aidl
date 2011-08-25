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

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

/**
 * Interface for interaction between the AccessibilityManagerService
 * and the ViewRoot in a given window.
 *
 * @hide
 */
oneway interface IAccessibilityInteractionConnection {

    void findAccessibilityNodeInfoByAccessibilityId(int accessibilityViewId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback,
        int interrogatingPid, long interrogatingTid);

    void findAccessibilityNodeInfoByViewId(int id, int interactionId,
        IAccessibilityInteractionConnectionCallback callback,
        int interrogatingPid, long interrogatingTid);

    void findAccessibilityNodeInfosByViewText(String text, int accessibilityViewId,
        int interactionId, IAccessibilityInteractionConnectionCallback callback,
        int interrogatingPid, long interrogatingTid);

    void performAccessibilityAction(int accessibilityId, int action, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int interrogatingPid,
        long interrogatingTid);
}
