/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Mikhail Danilov
 * @version $Revision$
 */
package org.apache.harmony.awt.wtk;

import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;

import org.apache.harmony.awt.gl.MultiRectArea;


/**
 * Provides cross-platform way to manipulate native window.
 *
 * Results of methods are reported through native messages.
 */
public interface NativeWindow {
    /**
     * Returns system id of the associated window
     * @return HWND on Windows, xwindow on X
     */
    long getId();

    /**
     * Shows/hides window
     * @param v - new visibility
     */
    void setVisible(boolean v);

    /**
     * Means only size should be changed
     */
    static final int BOUNDS_NOMOVE = 1;

    /**
     * Means only position should be changed
     */
    static final int BOUNDS_NOSIZE = 2;

    /**
     * Tries to set desired window bounds. It's not gurantied the
     * property will have the desired value. The value change
     * should be reported by system event (as for other properties).
     *
     * <p/>  If child, position is relative to parent window.
     * @param x - desired x
     * @param y - desired y
     * @param w - desired width
     * @param h - desired height
     * @param boundsMask - bitwise OR of BOUNDS_* constants.
     * Governs the new bounds interpretation.
     */
    void setBounds(int x, int y, int w, int h, int boundsMask);

    /**
     * Returns last notified window bounds. This means the last bounds
     * reported by system event.
     *
     * <p/>  If child, position is relative to parent window.
     * @return last notified window bounds
     */
    Rectangle getBounds();

    /**
     * Returns last notified insets. This means the last insets
     * reported by system event. Insets are margins around client area
     * ocupied by system provided decor, ususally border and titlebar.
     * @return last notified insets
     */
    Insets getInsets();

    /**
     * Enables/disables processing of input (key, mouse) event
     * by window. If disabled input events are ignored.
     * @param value - if enabled
     */
    void setEnabled(boolean value);

    /**
     * Sets the "focusable" window state.
     * @param value - if true makes window focusable
     */
    void setFocusable(boolean value);

    /**
     *
     * @return current focusable window state
     */
    boolean isFocusable();

    /**
     * Tries to set application input focus to the window or clear
     * current focus from focused window.
     *
     * <p/> For toplevel windows it's not gurantied focus will land in
     * desired window even if function returns true. Focus traversal should be tracked
     * by processing system events.
     *
     * @param focus  - if true sets focus, else clears focus
     * @return if success
     */
    boolean setFocus(boolean focus);

    /**
     * Destroys the asscoiated window.
     * Attempts to use it thereafter can result in
     * unpredictable bechavior.
     */
    void dispose();

    /**
     * Changes window Z-order to place this window under, If w is null
     * places places this window on the top. Z-order is per parent.
     * Toplevels a children of desktop in terms of Z-order.
     * @param w - window to place under.
     */
    void placeAfter(NativeWindow w);

    /**
     * Places window on top of Z-order
     */
    void toFront();

    /**
     * Places window on bottom of Z-order
     */
    void toBack();

    /**
     * Makes the window resizable/not resizable by user
     * @param value - if resizable
     */
    void setResizable(boolean value);

    /**
     * Sets the window caption
     * @param title - caption text
     */
    void setTitle(String title);

    /**
     * Activate the mouse event capturing
     */
    void grabMouse();

    /**
     * Deactivate mouse event capturing
     */
    void ungrabMouse();

    /**
     * Set extended state for top-level window.
     *
     * @param state - new state, bitmask of ICONIFIED, MAXIMIZED_BOTH, etc.
     */
    void setState(int state);

    /**
     * Set the image to be displayed in the minimized icon for
     * top-level [decorated] window.
     * @param image the icon image to be displayed
     */
    void setIconImage(Image image);

    /**
     * Makes window top-most if value is true,
     * non-topmost(normal) otherwise.
     */
    void setAlwaysOnTop(boolean value);

    /**
     * Set desired [top-level] window bounds when being in maximized state.
     * Fields set to Integer.MAX_VALUE are ignored[system-supplied values are
     * used instead]
     */
    void setMaximizedBounds(Rectangle bounds);

    /**
     * Get absolute position on the screen
     */
    Point getScreenPos();

    /**
     * Set a window "packed" flag:
     * the flag indicates that if insets change
     * client area shouldn't be resized, but frame
     * must be resized instead
     */
    void setPacked(boolean packed);
    
    /**
     * Make window an "input method window" by setting
     * special window style, e. g. small title bar, no
     * close, minimize/maximize buttons. For internal
     * use by input method framework.
     *
     */
    void setIMStyle();

    MultiRectArea getObscuredRegion(Rectangle part);
}
