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
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Interface given to an AccessibilitySerivce to talk to the AccessibilityManagerService.
 *
 * @hide
 */
interface IAccessibilityServiceConnection {

    void setServiceInfo(in AccessibilityServiceInfo info);

    /**
     * Finds an {@link AccessibilityNodeInfo} by accessibility id.
     * <p>
     *   <strong>
     *     It is a client responsibility to recycle the received info by
     *     calling {@link AccessibilityNodeInfo#recycle()} to avoid creating
     *     of multiple instances.
     *   </strong>
     * </p>
     *
     * @param accessibilityWindowId A unique window id.
     * @param accessibilityViewId A unique View accessibility id.
     * @return The node info.
     */
    AccessibilityNodeInfo findAccessibilityNodeInfoByAccessibilityId(int accessibilityWindowId,
        int accessibilityViewId);

    /**
     * Finds {@link AccessibilityNodeInfo}s by View text. The match is case
     * insensitive containment. The search is performed in the window whose
     * id is specified and starts from the View whose accessibility id is
     * specified.
     * <p>
     *   <strong>
     *     It is a client responsibility to recycle the received infos by
     *     calling {@link AccessibilityNodeInfo#recycle()} to avoid creating
     *     of multiple instances.
     *   </strong>
     * </p>
     *
     * @param text The searched text.
     * @param accessibilityId The id of the view from which to start searching.
     *        Use {@link android.view.View#NO_ID} to start from the root.
     * @return A list of node info.
     */
    List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewText(String text,
        int accessibilityWindowId, int accessibilityViewId);

    /**
     * Finds {@link AccessibilityNodeInfo}s by View text. The match is case
     * insensitive containment. The search is performed in the currently
     * active window and start from the root View in the window.
     * <p>
     *   <strong>
     *     It is a client responsibility to recycle the received infos by
     *     calling {@link AccessibilityNodeInfo#recycle()} to avoid creating
     *     of multiple instances.
     *   </strong>
     * </p>
     *
     * @param text The searched text.
     * @param accessibilityId The id of the view from which to start searching.
     *        Use {@link android.view.View#NO_ID} to start from the root.
     * @return A list of node info.
     */
    List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewTextInActiveWindow(String text);

    /**
     * Finds an {@link AccessibilityNodeInfo} by View id. The search is performed
     * in the currently active window and start from the root View in the window.
     * <p>
     *   <strong>
     *     It is a client responsibility to recycle the received info by
     *     calling {@link AccessibilityNodeInfo#recycle()} to avoid creating
     *     of multiple instances.
     *   </strong>
     * </p>
     *
     * @param id The id of the node.
     * @return The node info.
     */
    AccessibilityNodeInfo findAccessibilityNodeInfoByViewIdInActiveWindow(int viewId);

    /**
     * Performs an accessibility action on an {@link AccessibilityNodeInfo}.
     *
     * @param accessibilityWindowId The id of the window.
     * @param accessibilityViewId The of a view in the .
     * @return Whether the action was performed.
     */
    boolean performAccessibilityAction(int accessibilityWindowId, int accessibilityViewId,
        int action);
}
