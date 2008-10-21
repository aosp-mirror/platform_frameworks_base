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

import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Point;
import java.awt.event.KeyEvent;

import org.apache.harmony.awt.gl.MultiRectArea;


/**
 * The interface describing cross-platform translation of system
 * messages.
 *
 * <p/>Some messages can appear only on specific platform,
 * but they still can have cross-platform interpretation if the
 * application should be aware of them and can react using
 * cross-platform API.
 *
 */
public abstract class NativeEvent {

    /**
     * Message has no common cross-platform
     * interpretation and should be skipped.
     */
    public static final int ID_PLATFORM = 0;

    /**
     * Window bounds have changed.
     */
    public static final int ID_BOUNDS_CHANGED = -1;

    /**
     * Window decoration size has changed.
     */
    public static final int ID_INSETS_CHANGED = -2;

    /**
     * Window was just created (WM_CREATE on Windows)
     */
    public static final int ID_CREATED = -3;

    /**
     * Mouse grab was canceled by the native system
     */
    public static final int ID_MOUSE_GRAB_CANCELED = -4;

    /**
     * System color scheme or visual theme was changed
     */
    public static final int ID_THEME_CHANGED = -5;

    protected long windowId;
    protected int eventId;
    protected long otherWindowId;

    protected Point screenPos;
    protected Point localPos;
    protected Rectangle windowRect;

    protected int modifiers;
    protected int mouseButton;
    protected int wheelRotation;

    protected KeyInfo keyInfo = new KeyInfo();

    protected int windowState = -1;
    protected long time;

    /**
     * Returns the system window id of the event recipient.
     * @return HWND on Windows, xwindnow on X
     */
    public long getWindowId() {
        return windowId;
    }

    /**
     * Returns cross-platform event id
     * should be one of ID_* constants or
     * id constants from java.awt.AWTEvent subclasess
     * @return cross-platform event id
     */
    public int getEventId() {
        return eventId;
    }

    /**
     * Returns the position of cursor when event occured relative to
     * top-left corner of recipient window
     * @return position of cursor in local coordinates
     */
    public Point getLocalPos() {
        return localPos;
    }

    /**
     * Returns the position of cursor when event occured
     * in screen coordinates.
     * @return position of cursor in screen coordinates
     */
    public Point getScreenPos() {
        return screenPos;
    }

    /**
     * The recipient window bounds when the event occured
     * @return window bounds
     */
    public Rectangle getWindowRect() {
        return windowRect;
    }

    /**
     * Returns the state of keyboard and mouse buttons when the event
     * occured if event from mouse or keyboard, for other events can
     * return junk values. The value is bitwise OR of
     * java.awt.event.InputEvent *_DOWN constants.
     *
     * Method is aware of system mouse button swap for left-hand
     * mouse and return swapped values.
     * @return bitwise OR of java.awt.event.InputEvent *_DOWN constants
     */
    public int getInputModifiers() {
        return modifiers;
    }

    /**
     * Returns the iconified/maximized state of recipient window if
     * event is state related, for other events can junk values.
     * The value has the same meaning as Frame.getExtendedState
     * It's bitwise OR of ICONIFIED, MAXIMIZED_HORIZ, MAXIMIZED_VERT
     * @return bitwise OR of ICONIFIED, MAXIMIZED_HORIZ, MAXIMIZED_VERT
     */
    public int getWindowState() {
        return windowState;
    }

    /**
     * The same meaning as java.awt.event.getKeyCode
     * @return java.awt.event VK_* constant
     */
    public int getVKey() {
        return (keyInfo != null) ? keyInfo.vKey : KeyInfo.DEFAULT_VKEY;
    }

    /**
     * The same meaning as java.awt.event.getKeyLocation
     * @return java.awt.event KEY_LOCATION_* constant
     */
    public int getKeyLocation() {
        return (keyInfo != null) ? keyInfo.keyLocation : KeyInfo.DEFAULT_LOCATION;
    }

    /**
     * Return the string of characters associated with the event
     * Has meaning only for KEY_PRESSED as should be translated to
     * serie of KEY_TYPED events. For dead keys and input methods
     * one key press can generate multiple key chars.
     * @return string of characters
     */
    public StringBuffer getKeyChars() {
        if (keyInfo == null) {
            return null;
        }
        if (keyInfo.vKey == KeyEvent.VK_ENTER) {
            keyInfo.keyChars.setLength(0);
            keyInfo.setKeyChars('\n');
        }
        return keyInfo.keyChars;
    }

    public char getLastChar() {
        if (keyInfo == null || keyInfo.keyChars.length() == 0) {
            return KeyEvent.CHAR_UNDEFINED;
        }
        return keyInfo.keyChars.charAt(keyInfo.keyChars.length()-1);
    }

    /**
     * Returns the number of mouse button which changed it's state,
     * otherwise 0.
     * Left button is 1, middle button is 2, right button is 3.
     *
     * Method is aware of system mouse button swap for left-hand
     * mouse and return swapped values.
     * @return mouse button number
     */
    public int getMouseButton() {
        return mouseButton;
    }

    /**
     * Returns time when the message was received
     * @return time in milliseconds
     */
    public long getTime() {
        return time;
    }

    /**
     * For the focus event contains the oposite window.
     * This means it lost focus if recipient gains it,
     * or will gain focus if recipient looses it.
     * @return HWND on Windows, xwindnow on X
     */
    public long getOtherWindowId() {
        return otherWindowId;
    }

    /**
     * Returns the "dirty" area of the window as set of non-intersecting
     * rectangles. This area is to be painted.
     * @return non-empty array of null if empty
     */
    public abstract MultiRectArea getClipRects();

    /**
     * Returns the "dirty" area of the window as one rectangle.
     * This area is to be painted.
     * @return non-null Rectangle
     */
    public abstract Rectangle getClipBounds();

    /**
     * Returns the window insets. Insets is area which belongs to
     * window somehow but is outside of it's client area,
     * it usually contains system provided border and titlebar.
     * @return non-null java.awt.Insets
     */
    public abstract Insets getInsets();

    /**
     * Returns true if event is popup menu trigger.
     * @return boolean flag
     */
    public abstract boolean getTrigger();

    /**
     * Returns the number of "clicks" the mouse wheel was rotated.
     * @return negative values if the mouse wheel was rotated up/away from the user,
     * and positive values if the mouse wheel was rotated down/ towards the user
     */
    public int getWheelRotation() {
        return wheelRotation;
    }
}
