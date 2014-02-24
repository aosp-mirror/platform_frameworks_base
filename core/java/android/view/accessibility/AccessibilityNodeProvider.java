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

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.View;

import java.util.List;

/**
 * This class is the contract a client should implement to enable support of a
 * virtual view hierarchy rooted at a given view for accessibility purposes. A virtual
 * view hierarchy is a tree of imaginary Views that is reported as a part of the view
 * hierarchy when an {@link AccessibilityService} explores the window content.
 * Since the virtual View tree does not exist this class is responsible for
 * managing the {@link AccessibilityNodeInfo}s describing that tree to accessibility
 * services.
 * </p>
 * <p>
 * The main use case of these APIs is to enable a custom view that draws complex content,
 * for example a monthly calendar grid, to be presented as a tree of logical nodes,
 * for example month days each containing events, thus conveying its logical structure.
 * <p>
 * <p>
 * A typical use case is to override {@link View#getAccessibilityNodeProvider()} of the
 * View that is a root of a virtual View hierarchy to return an instance of this class.
 * In such a case this instance is responsible for managing {@link AccessibilityNodeInfo}s
 * describing the virtual sub-tree rooted at the View including the one representing the
 * View itself. Similarly the returned instance is responsible for performing accessibility
 * actions on any virtual view or the root view itself. For example:
 * </p>
 * <pre>
 *     getAccessibilityNodeProvider(
 *         if (mAccessibilityNodeProvider == null) {
 *             mAccessibilityNodeProvider = new AccessibilityNodeProvider() {
 *                 public boolean performAction(int action, int virtualDescendantId) {
 *                     // Implementation.
 *                     return false;
 *                 }
 *
 *                 public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text,
 *                         int virtualDescendantId) {
 *                     // Implementation.
 *                     return null;
 *                 }
 *
 *                 public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualDescendantId) {
 *                     // Implementation.
 *                     return null;
 *                 }
 *             });
 *     return mAccessibilityNodeProvider;
 * </pre>
 */
public abstract class AccessibilityNodeProvider {

    /**
     * The virtual id for the hosting View.
     */
    public static final int HOST_VIEW_ID = -1;

    /**
     * Returns an {@link AccessibilityNodeInfo} representing a virtual view,
     * i.e. a descendant of the host View, with the given <code>virtualViewId</code>
     * or the host View itself if <code>virtualViewId</code> equals to {@link #HOST_VIEW_ID}.
     * <p>
     * A virtual descendant is an imaginary View that is reported as a part of the view
     * hierarchy for accessibility purposes. This enables custom views that draw complex
     * content to report them selves as a tree of virtual views, thus conveying their
     * logical structure.
     * </p>
     * <p>
     * The implementer is responsible for obtaining an accessibility node info from the
     * pool of reusable instances and setting the desired properties of the node info
     * before returning it.
     * </p>
     *
     * @param virtualViewId A client defined virtual view id.
     * @return A populated {@link AccessibilityNodeInfo} for a virtual descendant or the
     *     host View.
     *
     * @see View#createAccessibilityNodeInfo()
     * @see AccessibilityNodeInfo
     */
    public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
        return null;
    }

    /**
     * Performs an accessibility action on a virtual view, i.e. a descendant of the
     * host View, with the given <code>virtualViewId</code> or the host View itself
     * if <code>virtualViewId</code> equals to {@link #HOST_VIEW_ID}.
     *
     * @param virtualViewId A client defined virtual view id.
     * @param action The action to perform.
     * @param arguments Optional action arguments.
     * @return True if the action was performed.
     *
     * @see View#performAccessibilityAction(int, Bundle)
     * @see #createAccessibilityNodeInfo(int)
     * @see AccessibilityNodeInfo
     */
    public boolean performAction(int virtualViewId, int action, Bundle arguments) {
        return false;
    }

    /**
     * Finds {@link AccessibilityNodeInfo}s by text. The match is case insensitive
     * containment. The search is relative to the virtual view, i.e. a descendant of the
     * host View, with the given <code>virtualViewId</code> or the host View itself
     * <code>virtualViewId</code> equals to {@link #HOST_VIEW_ID}.
     *
     * @param virtualViewId A client defined virtual view id which defined
     *     the root of the tree in which to perform the search.
     * @param text The searched text.
     * @return A list of node info.
     *
     * @see #createAccessibilityNodeInfo(int)
     * @see AccessibilityNodeInfo
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text,
            int virtualViewId) {
        return null;
    }

    /**
     * Find the virtual view, i.e. a descendant of the host View, that has the
     * specified focus type.
     *
     * @param focus The focus to find. One of
     *            {@link AccessibilityNodeInfo#FOCUS_INPUT} or
     *            {@link AccessibilityNodeInfo#FOCUS_ACCESSIBILITY}.
     * @return The node info of the focused view or null.
     * @see AccessibilityNodeInfo#FOCUS_INPUT
     * @see AccessibilityNodeInfo#FOCUS_ACCESSIBILITY
     */
    public AccessibilityNodeInfo findFocus(int focus) {
        return null;
    }
}
