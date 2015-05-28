/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.uiautomator.core;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * A UiObject is a representation of a view. It is not in any way directly bound to a
 * view as an object reference. A UiObject contains information to help it
 * locate a matching view at runtime based on the {@link UiSelector} properties specified in
 * its constructor. Once you create an instance of a UiObject, it can
 * be reused for different views that match the selector criteria.
 * @since API Level 16
 */
public class UiObject {
    private static final String LOG_TAG = UiObject.class.getSimpleName();
    /**
     * @since API Level 16
     * @deprecated use {@link Configurator#setWaitForSelectorTimeout(long)}
     **/
    @Deprecated
    protected static final long WAIT_FOR_SELECTOR_TIMEOUT = 10 * 1000;
    /**
     * @since API Level 16
     **/
    protected static final long WAIT_FOR_SELECTOR_POLL = 1000;
    // set a default timeout to 5.5s, since ANR threshold is 5s
    /**
     * @since API Level 16
     **/
    protected static final long WAIT_FOR_WINDOW_TMEOUT = 5500;
    /**
     * @since API Level 16
     **/
    protected static final int SWIPE_MARGIN_LIMIT = 5;
    /**
     * @since API Level 17
     * @deprecated use {@link Configurator#setScrollAcknowledgmentTimeout(long)}
     **/
    @Deprecated
    protected static final long WAIT_FOR_EVENT_TMEOUT = 3 * 1000;
    /**
     * @since API Level 18
     **/
    protected static final int FINGER_TOUCH_HALF_WIDTH = 20;

    private final UiSelector mSelector;

    private final Configurator mConfig = Configurator.getInstance();

    /**
     * Constructs a UiObject to represent a view that matches the specified
     * selector criteria.
     * @param selector
     * @since API Level 16
     */
    public UiObject(UiSelector selector) {
        mSelector = selector;
    }

    /**
     * Debugging helper. A test can dump the properties of a selector as a string
     * to its logs if needed. <code>getSelector().toString();</code>
     *
     * @return {@link UiSelector}
     * @since API Level 16
     */
    public final UiSelector getSelector() {
        Tracer.trace();
        return new UiSelector(mSelector);
    }

    /**
     * Retrieves the {@link QueryController} to translate a {@link UiSelector} selector
     * into an {@link AccessibilityNodeInfo}.
     *
     * @return {@link QueryController}
     */
    QueryController getQueryController() {
        return UiDevice.getInstance().getAutomatorBridge().getQueryController();
    }

    /**
     * Retrieves the {@link InteractionController} to perform finger actions such as tapping,
     * swiping, or entering text.
     *
     * @return {@link InteractionController}
     */
    InteractionController getInteractionController() {
        return UiDevice.getInstance().getAutomatorBridge().getInteractionController();
    }

    /**
     * Creates a new UiObject for a child view that is under the present UiObject.
     *
     * @param selector for child view to match
     * @return a new UiObject representing the child view
     * @since API Level 16
     */
    public UiObject getChild(UiSelector selector) throws UiObjectNotFoundException {
        Tracer.trace(selector);
        return new UiObject(getSelector().childSelector(selector));
    }

    /**
     * Creates a new UiObject for a sibling view or a child of the sibling view, 
     * relative to the present UiObject.
     *
     * @param selector for a sibling view or children of the sibling view
     * @return a new UiObject representing the matched view
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public UiObject getFromParent(UiSelector selector) throws UiObjectNotFoundException {
        Tracer.trace(selector);
        return new UiObject(getSelector().fromParent(selector));
    }

    /**
     * Counts the child views immediately under the present UiObject.
     *
     * @return the count of child views.
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public int getChildCount() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.getChildCount();
    }

    /**
     * Finds a matching UI element in the accessibility hierarchy, by
     * using the selector for this UiObject.
     *
     * @param timeout in milliseconds
     * @return AccessibilityNodeInfo if found else null
     * @since API Level 16
     */
    protected AccessibilityNodeInfo findAccessibilityNodeInfo(long timeout) {
        AccessibilityNodeInfo node = null;
        long startMills = SystemClock.uptimeMillis();
        long currentMills = 0;
        while (currentMills <= timeout) {
            node = getQueryController().findAccessibilityNodeInfo(getSelector());
            if (node != null) {
                break;
            } else {
                // does nothing if we're reentering another runWatchers()
                UiDevice.getInstance().runWatchers();
            }
            currentMills = SystemClock.uptimeMillis() - startMills;
            if(timeout > 0) {
                SystemClock.sleep(WAIT_FOR_SELECTOR_POLL);
            }
        }
        return node;
    }

    /**
     * Drags this object to a destination UiObject.
     * The number of steps specified in your input parameter can influence the
     * drag speed, and varying speeds may impact the results. Consider
     * evaluating different speeds when using this method in your tests.
     *
     * @param destObj the destination UiObject.
     * @param steps usually 40 steps. You can increase or decrease the steps to change the speed.
     * @return true if successful
     * @throws UiObjectNotFoundException
     * @since API Level 18
     */
    public boolean dragTo(UiObject destObj, int steps) throws UiObjectNotFoundException {
        Rect srcRect = getVisibleBounds();
        Rect dstRect = destObj.getVisibleBounds();
        return getInteractionController().swipe(srcRect.centerX(), srcRect.centerY(),
                dstRect.centerX(), dstRect.centerY(), steps, true);
    }

    /**
     * Drags this object to arbitrary coordinates.
     * The number of steps specified in your input parameter can influence the
     * drag speed, and varying speeds may impact the results. Consider
     * evaluating different speeds when using this method in your tests.
     *
     * @param destX the X-axis coordinate.
     * @param destY the Y-axis coordinate.
     * @param steps usually 40 steps. You can increase or decrease the steps to change the speed.
     * @return true if successful
     * @throws UiObjectNotFoundException
     * @since API Level 18
     */
    public boolean dragTo(int destX, int destY, int steps) throws UiObjectNotFoundException {
        Rect srcRect = getVisibleBounds();
        return getInteractionController().swipe(srcRect.centerX(), srcRect.centerY(), destX, destY,
                steps, true);
    }

    /**
     * Performs the swipe up action on the UiObject. 
     * See also:
     * <ul>
     * <li>{@link UiScrollable#scrollToBeginning(int)}</li>
     * <li>{@link UiScrollable#scrollToEnd(int)}</li>
     * <li>{@link UiScrollable#scrollBackward()}</li>
     * <li>{@link UiScrollable#scrollForward()}</li>
     * </ul>
     *
     * @param steps indicates the number of injected move steps into the system. Steps are
     * injected about 5ms apart. So a 100 steps may take about 1/2 second to complete.
     * @return true of successful
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean swipeUp(int steps) throws UiObjectNotFoundException {
        Tracer.trace(steps);
        Rect rect = getVisibleBounds();
        if(rect.height() <= SWIPE_MARGIN_LIMIT * 2)
            return false; // too small to swipe
        return getInteractionController().swipe(rect.centerX(),
                rect.bottom - SWIPE_MARGIN_LIMIT, rect.centerX(), rect.top + SWIPE_MARGIN_LIMIT,
                steps);
    }

    /**
     * Performs the swipe down action on the UiObject. 
     * The swipe gesture can be performed over any surface. The targeted
     * UI element does not need to be scrollable.
     * See also:
     * <ul>
     * <li>{@link UiScrollable#scrollToBeginning(int)}</li>
     * <li>{@link UiScrollable#scrollToEnd(int)}</li>
     * <li>{@link UiScrollable#scrollBackward()}</li>
     * <li>{@link UiScrollable#scrollForward()}</li>
     * </ul>
     *
     * @param steps indicates the number of injected move steps into the system. Steps are
     * injected about 5ms apart. So a 100 steps may take about 1/2 second to complete.
     * @return true if successful
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean swipeDown(int steps) throws UiObjectNotFoundException {
        Tracer.trace(steps);
        Rect rect = getVisibleBounds();
        if(rect.height() <= SWIPE_MARGIN_LIMIT * 2)
            return false; // too small to swipe
        return getInteractionController().swipe(rect.centerX(),
                rect.top + SWIPE_MARGIN_LIMIT, rect.centerX(),
                rect.bottom - SWIPE_MARGIN_LIMIT, steps);
    }

    /**
     * Performs the swipe left action on the UiObject. 
     * The swipe gesture can be performed over any surface. The targeted
     * UI element does not need to be scrollable.
     * See also:
     * <ul>
     * <li>{@link UiScrollable#scrollToBeginning(int)}</li>
     * <li>{@link UiScrollable#scrollToEnd(int)}</li>
     * <li>{@link UiScrollable#scrollBackward()}</li>
     * <li>{@link UiScrollable#scrollForward()}</li>
     * </ul>
     *
     * @param steps indicates the number of injected move steps into the system. Steps are
     * injected about 5ms apart. So a 100 steps may take about 1/2 second to complete.
     * @return true if successful
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean swipeLeft(int steps) throws UiObjectNotFoundException {
        Tracer.trace(steps);
        Rect rect = getVisibleBounds();
        if(rect.width() <= SWIPE_MARGIN_LIMIT * 2)
            return false; // too small to swipe
        return getInteractionController().swipe(rect.right - SWIPE_MARGIN_LIMIT,
                rect.centerY(), rect.left + SWIPE_MARGIN_LIMIT, rect.centerY(), steps);
    }

    /**
     * Performs the swipe right action on the UiObject. 
     * The swipe gesture can be performed over any surface. The targeted
     * UI element does not need to be scrollable.
     * See also:
     * <ul>
     * <li>{@link UiScrollable#scrollToBeginning(int)}</li>
     * <li>{@link UiScrollable#scrollToEnd(int)}</li>
     * <li>{@link UiScrollable#scrollBackward()}</li>
     * <li>{@link UiScrollable#scrollForward()}</li>
     * </ul>
     *
     * @param steps indicates the number of injected move steps into the system. Steps are
     * injected about 5ms apart. So a 100 steps may take about 1/2 second to complete.
     * @return true if successful
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean swipeRight(int steps) throws UiObjectNotFoundException {
        Tracer.trace(steps);
        Rect rect = getVisibleBounds();
        if(rect.width() <= SWIPE_MARGIN_LIMIT * 2)
            return false; // too small to swipe
        return getInteractionController().swipe(rect.left + SWIPE_MARGIN_LIMIT,
                rect.centerY(), rect.right - SWIPE_MARGIN_LIMIT, rect.centerY(), steps);
    }

    /**
     * Finds the visible bounds of a partially visible UI element
     *
     * @param node
     * @return null if node is null, else a Rect containing visible bounds
     */
    private Rect getVisibleBounds(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }

        // targeted node's bounds
        int w = UiDevice.getInstance().getDisplayWidth();
        int h = UiDevice.getInstance().getDisplayHeight();
        Rect nodeRect = AccessibilityNodeInfoHelper.getVisibleBoundsInScreen(node, w, h);

        // is the targeted node within a scrollable container?
        AccessibilityNodeInfo scrollableParentNode = getScrollableParent(node);
        if(scrollableParentNode == null) {
            // nothing to adjust for so return the node's Rect as is
            return nodeRect;
        }

        // Scrollable parent's visible bounds
        Rect parentRect = AccessibilityNodeInfoHelper
                .getVisibleBoundsInScreen(scrollableParentNode, w, h);
        // adjust for partial clipping of targeted by parent node if required
        if (nodeRect.intersect(parentRect)) {
            return nodeRect;
        } else {
            // Node rect has no intersection with parent Rect
            return new Rect();
        }
    }

    /**
     * Walks up the layout hierarchy to find a scrollable parent. A scrollable parent
     * indicates that this node might be in a container where it is partially
     * visible due to scrolling. In this case, its clickable center might not be visible and
     * the click coordinates should be adjusted.
     *
     * @param node
     * @return The accessibility node info.
     */
    private AccessibilityNodeInfo getScrollableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node;
        while(parent != null) {
            parent = parent.getParent();
            if (parent != null && parent.isScrollable()) {
                return parent;
            }
        }
        return null;
    }

    /**
     * Performs a click at the center of the visible bounds of the UI element represented
     * by this UiObject.
     *
     * @return true id successful else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean click() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        Rect rect = getVisibleBounds(node);
        return getInteractionController().clickAndSync(rect.centerX(), rect.centerY(),
                mConfig.getActionAcknowledgmentTimeout());
    }

    /**
     * Waits for window transitions that would typically take longer than the
     * usual default timeouts.
     * See {@link #clickAndWaitForNewWindow(long)}
     *
     * @return true if the event was triggered, else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean clickAndWaitForNewWindow() throws UiObjectNotFoundException {
        Tracer.trace();
        return clickAndWaitForNewWindow(WAIT_FOR_WINDOW_TMEOUT);
    }

    /**
     * Performs a click at the center of the visible bounds of the UI element represented
     * by this UiObject and waits for window transitions.
     *
     * This method differ from {@link UiObject#click()} only in that this method waits for a
     * a new window transition as a result of the click. Some examples of a window transition:
     * <li>launching a new activity</li>
     * <li>bringing up a pop-up menu</li>
     * <li>bringing up a dialog</li>
     *
     * @param timeout timeout before giving up on waiting for a new window
     * @return true if the event was triggered, else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean clickAndWaitForNewWindow(long timeout) throws UiObjectNotFoundException {
        Tracer.trace(timeout);
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        Rect rect = getVisibleBounds(node);
        return getInteractionController().clickAndWaitForNewWindow(rect.centerX(), rect.centerY(),
                mConfig.getActionAcknowledgmentTimeout());
    }

    /**
     * Clicks the top and left corner of the UI element
     *
     * @return true on success
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean clickTopLeft() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        Rect rect = getVisibleBounds(node);
        return getInteractionController().clickNoSync(rect.left + 5, rect.top + 5);
    }

    /**
     * Long clicks bottom and right corner of the UI element
     *
     * @return true if operation was successful
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean longClickBottomRight() throws UiObjectNotFoundException  {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        Rect rect = getVisibleBounds(node);
        return getInteractionController().longTapNoSync(rect.right - 5, rect.bottom - 5);
    }

    /**
     * Clicks the bottom and right corner of the UI element
     *
     * @return true on success
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean clickBottomRight() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        Rect rect = getVisibleBounds(node);
        return getInteractionController().clickNoSync(rect.right - 5, rect.bottom - 5);
    }

    /**
     * Long clicks the center of the visible bounds of the UI element
     *
     * @return true if operation was successful
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean longClick() throws UiObjectNotFoundException  {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        Rect rect = getVisibleBounds(node);
        return getInteractionController().longTapNoSync(rect.centerX(), rect.centerY());
    }

    /**
     * Long clicks on the top and left corner of the UI element
     *
     * @return true if operation was successful
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean longClickTopLeft() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        Rect rect = getVisibleBounds(node);
        return getInteractionController().longTapNoSync(rect.left + 5, rect.top + 5);
    }

    /**
     * Reads the <code>text</code> property of the UI element
     *
     * @return text value of the current node represented by this UiObject
     * @throws UiObjectNotFoundException if no match could be found
     * @since API Level 16
     */
    public String getText() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        String retVal = safeStringReturn(node.getText());
        Log.d(LOG_TAG, String.format("getText() = %s", retVal));
        return retVal;
    }

    /**
     * Retrieves the <code>className</code> property of the UI element.
     *
     * @return class name of the current node represented by this UiObject
     * @throws UiObjectNotFoundException if no match was found
     * @since API Level 18
     */
    public String getClassName() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        String retVal = safeStringReturn(node.getClassName());
        Log.d(LOG_TAG, String.format("getClassName() = %s", retVal));
        return retVal;
    }

    /**
     * Reads the <code>content_desc</code> property of the UI element
     *
     * @return value of node attribute "content_desc"
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public String getContentDescription() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return safeStringReturn(node.getContentDescription());
    }

    /**
     * Sets the text in an editable field, after clearing the field's content.
     *
     * The {@link UiSelector} selector of this object must reference a UI element that is editable.
     *
     * When you call this method, the method first simulates a {@link #click()} on
     * editable field to set focus. The method then clears the field's contents
     * and injects your specified text into the field.
     *
     * If you want to capture the original contents of the field, call {@link #getText()} first.
     * You can then modify the text and use this method to update the field.
     *
     * @param text string to set
     * @return true if operation is successful
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean setText(String text) throws UiObjectNotFoundException {
        Tracer.trace(text);
        clearTextField();
        return getInteractionController().sendText(text);
    }

    /**
     * Clears the existing text contents in an editable field.
     *
     * The {@link UiSelector} of this object must reference a UI element that is editable.
     *
     * When you call this method, the method first sets focus at the start edge of the field.
     * The method then simulates a long-press to select the existing text, and deletes the
     * selected text.
     *
     * If a "Select-All" option is displayed, the method will automatically attempt to use it
     * to ensure full text selection.
     *
     * Note that it is possible that not all the text in the field is selected; for example,
     * if the text contains separators such as spaces, slashes, at symbol etc.
     * Also, not all editable fields support the long-press functionality.
     *
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public void clearTextField() throws UiObjectNotFoundException {
        Tracer.trace();
        // long click left + center
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        Rect rect = getVisibleBounds(node);
        getInteractionController().longTapNoSync(rect.left + 20, rect.centerY());
        // check if the edit menu is open
        UiObject selectAll = new UiObject(new UiSelector().descriptionContains("Select all"));
        if(selectAll.waitForExists(50))
            selectAll.click();
        // wait for the selection
        SystemClock.sleep(250);
        // delete it
        getInteractionController().sendKey(KeyEvent.KEYCODE_DEL, 0);
    }

    /**
     * Check if the UI element's <code>checked</code> property is currently true
     *
     * @return true if it is else false
     * @since API Level 16
     */
    public boolean isChecked() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.isChecked();
    }

    /**
     * Checks if the UI element's <code>selected</code> property is currently true.
     *
     * @return true if it is else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean isSelected() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.isSelected();
    }

    /**
     * Checks if the UI element's <code>checkable</code> property is currently true.
     *
     * @return true if it is else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean isCheckable() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.isCheckable();
    }

    /**
     * Checks if the UI element's <code>enabled</code> property is currently true.
     *
     * @return true if it is else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean isEnabled() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.isEnabled();
    }

    /**
     * Checks if the UI element's <code>clickable</code> property is currently true.
     *
     * @return true if it is else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean isClickable() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.isClickable();
    }

    /**
     * Check if the UI element's <code>focused</code> property is currently true
     *
     * @return true if it is else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean isFocused() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.isFocused();
    }

    /**
     * Check if the UI element's <code>focusable</code> property is currently true.
     *
     * @return true if it is else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean isFocusable() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.isFocusable();
    }

    /**
     * Check if the view's <code>scrollable</code> property is currently true
     *
     * @return true if it is else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean isScrollable() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.isScrollable();
    }

    /**
     * Check if the view's <code>long-clickable</code> property is currently true
     *
     * @return true if it is else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public boolean isLongClickable() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return node.isLongClickable();
    }

    /**
     * Reads the view's <code>package</code> property
     *
     * @return true if it is else false
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public String getPackageName() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return safeStringReturn(node.getPackageName());
    }

    /**
     * Returns the visible bounds of the view.
     *
     * If a portion of the view is visible, only the bounds of the visible portion are
     * reported.
     *
     * @return Rect
     * @throws UiObjectNotFoundException
     * @see {@link #getBounds()}
     * @since API Level 17
     */
    public Rect getVisibleBounds() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        return getVisibleBounds(node);
    }

    /**
     * Returns the view's <code>bounds</code> property. See {@link #getVisibleBounds()}
     *
     * @return Rect
     * @throws UiObjectNotFoundException
     * @since API Level 16
     */
    public Rect getBounds() throws UiObjectNotFoundException {
        Tracer.trace();
        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if(node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }
        Rect nodeRect = new Rect();
        node.getBoundsInScreen(nodeRect);

        return nodeRect;
    }

    /**
     * Waits a specified length of time for a view to become visible.
     *
     * This method waits until the view becomes visible on the display, or
     * until the timeout has elapsed. You can use this method in situations where
     * the content that you want to select is not immediately displayed.
     *
     * @param timeout the amount of time to wait (in milliseconds)
     * @return true if the view is displayed, else false if timeout elapsed while waiting
     * @since API Level 16
     */
    public boolean waitForExists(long timeout) {
        Tracer.trace(timeout);
        if(findAccessibilityNodeInfo(timeout) != null) {
            return true;
        }
        return false;
    }

    /**
     * Waits a specified length of time for a view to become undetectable.
     *
     * This method waits until a view is no longer matchable, or until the
     * timeout has elapsed.
     *
     * A view becomes undetectable when the {@link UiSelector} of the object is
     * unable to find a match because the element has either changed its state or is no
     * longer displayed.
     *
     * You can use this method when attempting to wait for some long operation
     * to compete, such as downloading a large file or connecting to a remote server.
     *
     * @param timeout time to wait (in milliseconds)
     * @return true if the element is gone before timeout elapsed, else false if timeout elapsed
     * but a matching element is still found.
     * @since API Level 16
     */
    public boolean waitUntilGone(long timeout) {
        Tracer.trace(timeout);
        long startMills = SystemClock.uptimeMillis();
        long currentMills = 0;
        while (currentMills <= timeout) {
            if(findAccessibilityNodeInfo(0) == null)
                return true;
            currentMills = SystemClock.uptimeMillis() - startMills;
            if(timeout > 0)
                SystemClock.sleep(WAIT_FOR_SELECTOR_POLL);
        }
        return false;
    }

    /**
     * Check if view exists.
     *
     * This methods performs a {@link #waitForExists(long)} with zero timeout. This
     * basically returns immediately whether the view represented by this UiObject
     * exists or not. If you need to wait longer for this view, then see
     * {@link #waitForExists(long)}.
     *
     * @return true if the view represented by this UiObject does exist
     * @since API Level 16
     */
    public boolean exists() {
        Tracer.trace();
        return waitForExists(0);
    }

    private String safeStringReturn(CharSequence cs) {
        if(cs == null)
            return "";
        return cs.toString();
    }

    /**
     * Performs a two-pointer gesture, where each pointer moves diagonally
     * opposite across the other, from the center out towards the edges of the
     * this UiObject.
     * @param percent percentage of the object's diagonal length for the pinch gesture
     * @param steps the number of steps for the gesture. Steps are injected 
     * about 5 milliseconds apart, so 100 steps may take around 0.5 seconds to complete.
     * @return <code>true</code> if all touch events for this gesture are injected successfully,
     *         <code>false</code> otherwise
     * @throws UiObjectNotFoundException
     * @since API Level 18
     */
    public boolean pinchOut(int percent, int steps) throws UiObjectNotFoundException {
        // make value between 1 and 100
        percent = (percent < 0) ? 1 : (percent > 100) ? 100 : percent;
        float percentage = percent / 100f;

        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if (node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }

        Rect rect = getVisibleBounds(node);
        if (rect.width() <= FINGER_TOUCH_HALF_WIDTH * 2)
            throw new IllegalStateException("Object width is too small for operation");

        // start from the same point at the center of the control
        Point startPoint1 = new Point(rect.centerX() - FINGER_TOUCH_HALF_WIDTH, rect.centerY());
        Point startPoint2 = new Point(rect.centerX() + FINGER_TOUCH_HALF_WIDTH, rect.centerY());

        // End at the top-left and bottom-right corners of the control
        Point endPoint1 = new Point(rect.centerX() - (int)((rect.width()/2) * percentage),
                rect.centerY());
        Point endPoint2 = new Point(rect.centerX() + (int)((rect.width()/2) * percentage),
                rect.centerY());

        return performTwoPointerGesture(startPoint1, startPoint2, endPoint1, endPoint2, steps);
    }

    /**
     * Performs a two-pointer gesture, where each pointer moves diagonally
     * toward the other, from the edges to the center of this UiObject .
     * @param percent percentage of the object's diagonal length for the pinch gesture
     * @param steps the number of steps for the gesture. Steps are injected 
     * about 5 milliseconds apart, so 100 steps may take around 0.5 seconds to complete.
     * @return <code>true</code> if all touch events for this gesture are injected successfully,
     *         <code>false</code> otherwise
     * @throws UiObjectNotFoundException
     * @since API Level 18
     */
    public boolean pinchIn(int percent, int steps) throws UiObjectNotFoundException {
        // make value between 1 and 100
        percent = (percent < 0) ? 0 : (percent > 100) ? 100 : percent;
        float percentage = percent / 100f;

        AccessibilityNodeInfo node = findAccessibilityNodeInfo(mConfig.getWaitForSelectorTimeout());
        if (node == null) {
            throw new UiObjectNotFoundException(getSelector().toString());
        }

        Rect rect = getVisibleBounds(node);
        if (rect.width() <= FINGER_TOUCH_HALF_WIDTH * 2)
            throw new IllegalStateException("Object width is too small for operation");

        Point startPoint1 = new Point(rect.centerX() - (int)((rect.width()/2) * percentage),
                rect.centerY());
        Point startPoint2 = new Point(rect.centerX() + (int)((rect.width()/2) * percentage),
                rect.centerY());

        Point endPoint1 = new Point(rect.centerX() - FINGER_TOUCH_HALF_WIDTH, rect.centerY());
        Point endPoint2 = new Point(rect.centerX() + FINGER_TOUCH_HALF_WIDTH, rect.centerY());

        return performTwoPointerGesture(startPoint1, startPoint2, endPoint1, endPoint2, steps);
    }

    /**
     * Generates a two-pointer gesture with arbitrary starting and ending points.
     *
     * @param startPoint1 start point of pointer 1
     * @param startPoint2 start point of pointer 2
     * @param endPoint1 end point of pointer 1
     * @param endPoint2 end point of pointer 2
     * @param steps the number of steps for the gesture. Steps are injected 
     * about 5 milliseconds apart, so 100 steps may take around 0.5 seconds to complete.
     * @return <code>true</code> if all touch events for this gesture are injected successfully,
     *         <code>false</code> otherwise
     * @since API Level 18
     */
    public boolean performTwoPointerGesture(Point startPoint1, Point startPoint2, Point endPoint1,
            Point endPoint2, int steps) {

        // avoid a divide by zero
        if(steps == 0)
            steps = 1;

        final float stepX1 = (endPoint1.x - startPoint1.x) / steps;
        final float stepY1 = (endPoint1.y - startPoint1.y) / steps;
        final float stepX2 = (endPoint2.x - startPoint2.x) / steps;
        final float stepY2 = (endPoint2.y - startPoint2.y) / steps;

        int eventX1, eventY1, eventX2, eventY2;
        eventX1 = startPoint1.x;
        eventY1 = startPoint1.y;
        eventX2 = startPoint2.x;
        eventY2 = startPoint2.y;

        // allocate for steps plus first down and last up
        PointerCoords[] points1 = new PointerCoords[steps + 2];
        PointerCoords[] points2 = new PointerCoords[steps + 2];

        // Include the first and last touch downs in the arrays of steps
        for (int i = 0; i < steps + 1; i++) {
            PointerCoords p1 = new PointerCoords();
            p1.x = eventX1;
            p1.y = eventY1;
            p1.pressure = 1;
            p1.size = 1;
            points1[i] = p1;

            PointerCoords p2 = new PointerCoords();
            p2.x = eventX2;
            p2.y = eventY2;
            p2.pressure = 1;
            p2.size = 1;
            points2[i] = p2;

            eventX1 += stepX1;
            eventY1 += stepY1;
            eventX2 += stepX2;
            eventY2 += stepY2;
        }

        // ending pointers coordinates
        PointerCoords p1 = new PointerCoords();
        p1.x = endPoint1.x;
        p1.y = endPoint1.y;
        p1.pressure = 1;
        p1.size = 1;
        points1[steps + 1] = p1;

        PointerCoords p2 = new PointerCoords();
        p2.x = endPoint2.x;
        p2.y = endPoint2.y;
        p2.pressure = 1;
        p2.size = 1;
        points2[steps + 1] = p2;

        return performMultiPointerGesture(points1, points2);
    }

    /**
     * Performs a multi-touch gesture. You must specify touch coordinates for
     * at least 2 pointers. Each pointer must have all of its touch steps
     * defined in an array of {@link PointerCoords}. You can use this method to
     * specify complex gestures, like circles and irregular shapes, where each
     * pointer may take a different path.
     *
     * To create a single point on a pointer's touch path:
     * <code>
     *       PointerCoords p = new PointerCoords();
     *       p.x = stepX;
     *       p.y = stepY;
     *       p.pressure = 1;
     *       p.size = 1;
     * </code>
     * @param touches represents the pointers' paths. Each {@link PointerCoords}
     * array represents a different pointer. Each {@link PointerCoords} in an
     * array element represents a touch point on a pointer's path.
     * @return <code>true</code> if all touch events for this gesture are injected successfully,
     *         <code>false</code> otherwise
     * @since API Level 18
     */
    public boolean performMultiPointerGesture(PointerCoords[] ...touches) {
        return getInteractionController().performMultiPointerGesture(touches);
    }
}
