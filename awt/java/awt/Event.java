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
 * @author Dmitry A. Durnev
 * @version $Revision$
 */

package java.awt;

import java.io.Serializable;

/**
 * The Event class is obsolete and has been replaced by AWTEvent class.
 * 
 * @since Android 1.0
 */
public class Event implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 5488922509400504703L;

    /**
     * The Constant SHIFT_MASK indicates that the Shift key is down when the
     * event occurred.
     */
    public static final int SHIFT_MASK = 1;

    /**
     * The Constant CTRL_MASK indicates that the Control key is down when the
     * event occurred.
     */
    public static final int CTRL_MASK = 2;

    /**
     * The Constant META_MASK indicates that the Meta key is down when t he
     * event occurred (or the right mouse button).
     */
    public static final int META_MASK = 4;

    /**
     * The Constant ALT_MASK indicates that the Alt key is down when the event
     * occurred (or the middle mouse button).
     */
    public static final int ALT_MASK = 8;

    /**
     * The Constant HOME indicates Home key.
     */
    public static final int HOME = 1000;

    /**
     * The Constant END indicates End key.
     */
    public static final int END = 1001;

    /**
     * The Constant PGUP indicates Page Up key.
     */
    public static final int PGUP = 1002;

    /**
     * The Constant PGDN indicates Page Down key.
     */
    public static final int PGDN = 1003;

    /**
     * The Constant UP indicates Up key.
     */
    public static final int UP = 1004;

    /**
     * The Constant DOWN indicates Down key.
     */
    public static final int DOWN = 1005;

    /**
     * The Constant LEFT indicates Left key.
     */
    public static final int LEFT = 1006;

    /**
     * The Constant RIGHT indicates Right key.
     */
    public static final int RIGHT = 1007;

    /**
     * The Constant F1 indicates F1 key.
     */
    public static final int F1 = 1008;

    /**
     * The Constant F2 indicates F2 key.
     */
    public static final int F2 = 1009;

    /**
     * The Constant F3 indicates F3 key.
     */
    public static final int F3 = 1010;

    /**
     * The Constant F4 indicates F4 key.
     */
    public static final int F4 = 1011;

    /**
     * The Constant F5 indicates F5 key.
     */
    public static final int F5 = 1012;

    /**
     * The Constant F6 indicates F6 key.
     */
    public static final int F6 = 1013;

    /**
     * The Constant F7 indicates F7 key.
     */
    public static final int F7 = 1014;

    /**
     * The Constant F8 indicates F8 key.
     */
    public static final int F8 = 1015;

    /**
     * The Constant F9 indicates F9 key.
     */
    public static final int F9 = 1016;

    /**
     * The Constant F10 indicates F10 key.
     */
    public static final int F10 = 1017;

    /**
     * The Constant F11 indicates F11 key.
     */
    public static final int F11 = 1018;

    /**
     * The Constant F12 indicates F12 key.
     */
    public static final int F12 = 1019;

    /**
     * The Constant PRINT_SCREEN indicates Print Screen key.
     */
    public static final int PRINT_SCREEN = 1020;

    /**
     * The Constant SCROLL_LOCK indicates Scroll Lock key.
     */
    public static final int SCROLL_LOCK = 1021;

    /**
     * The Constant CAPS_LOCK indicates Caps Lock key.
     */
    public static final int CAPS_LOCK = 1022;

    /**
     * The Constant NUM_LOCK indicates Num Lock key.
     */
    public static final int NUM_LOCK = 1023;

    /**
     * The Constant PAUSE indicates Pause key.
     */
    public static final int PAUSE = 1024;

    /**
     * The Constant INSERT indicates Insert key.
     */
    public static final int INSERT = 1025;

    /**
     * The Constant ENTER indicates Enter key.
     */
    public static final int ENTER = 10;

    /**
     * The Constant BACK_SPACE indicates Back Space key.
     */
    public static final int BACK_SPACE = 8;

    /**
     * The Constant TAB indicates TAb key.
     */
    public static final int TAB = 9;

    /**
     * The Constant ESCAPE indicates Escape key.
     */
    public static final int ESCAPE = 27;

    /**
     * The Constant DELETE indicates Delete key.
     */
    public static final int DELETE = 127;

    /**
     * The Constant WINDOW_DESTROY indicates an event when the user has asked
     * the window manager to kill the window.
     */
    public static final int WINDOW_DESTROY = 201;

    /**
     * The Constant WINDOW_EXPOSE indicates an event when the user has asked the
     * window manager to expose the window.
     */
    public static final int WINDOW_EXPOSE = 202;

    /**
     * The Constant WINDOW_ICONIFY indicates an event when the user has asked
     * the window manager to iconify the window.
     */
    public static final int WINDOW_ICONIFY = 203;

    /**
     * The Constant WINDOW_DEICONIFY indicates an event when the user has asked
     * the window manager to deiconify the window.
     */
    public static final int WINDOW_DEICONIFY = 204;

    /**
     * The Constant WINDOW_MOVED indicates an event when the user has asked the
     * window manager to move the window.
     */
    public static final int WINDOW_MOVED = 205;

    /**
     * The Constant KEY_PRESS indicates an event when the user presses a normal
     * key.
     */
    public static final int KEY_PRESS = 401;

    /**
     * The Constant KEY_RELEASE indicates an event when the user releases a
     * normal key.
     */
    public static final int KEY_RELEASE = 402;

    /**
     * The Constant KEY_ACTION indicates an event when the user pressed a
     * non-ASCII action key.
     */
    public static final int KEY_ACTION = 403;

    /**
     * The Constant KEY_ACTION_RELEASE indicates an event when the user released
     * a non-ASCII action key.
     */
    public static final int KEY_ACTION_RELEASE = 404;

    /**
     * The Constant MOUSE_DOWN indicates an event when the user has pressed the
     * mouse button.
     */
    public static final int MOUSE_DOWN = 501;

    /**
     * The Constant MOUSE_UP indicates an event when the user has released the
     * mouse button.
     */
    public static final int MOUSE_UP = 502;

    /**
     * The Constant MOUSE_MOVE indicates an event when the user has moved the
     * mouse with no button pressed.
     */
    public static final int MOUSE_MOVE = 503;

    /**
     * The Constant MOUSE_ENTER indicates an event when the mouse has entered a
     * component.
     */
    public static final int MOUSE_ENTER = 504;

    /**
     * The Constant MOUSE_EXIT indicates an event when the mouse has exited a
     * component.
     */
    public static final int MOUSE_EXIT = 505;

    /**
     * The Constant MOUSE_DRAG indicates an event when the user has moved a
     * mouse with the pressed button.
     */
    public static final int MOUSE_DRAG = 506;

    /**
     * The Constant SCROLL_LINE_UP indicates an event when the user has
     * activated line-up area of scrollbar.
     */
    public static final int SCROLL_LINE_UP = 601;

    /**
     * The Constant SCROLL_LINE_DOWN indicates an event when the user has
     * activated line-down area of scrollbar.
     */
    public static final int SCROLL_LINE_DOWN = 602;

    /**
     * The Constant SCROLL_PAGE_UP indicates an event when the user has
     * activated page up area of scrollbar.
     */
    public static final int SCROLL_PAGE_UP = 603;

    /**
     * The Constant SCROLL_PAGE_DOWN indicates an event when the user has
     * activated page down area of scrollbar.
     */
    public static final int SCROLL_PAGE_DOWN = 604;

    /**
     * The Constant SCROLL_ABSOLUTE indicates an event when the user has moved
     * the bubble in a scroll bar.
     */
    public static final int SCROLL_ABSOLUTE = 605;

    /**
     * The Constant SCROLL_BEGIN indicates a scroll begin event.
     */
    public static final int SCROLL_BEGIN = 606;

    /**
     * The Constant SCROLL_END indicates a scroll end event.
     */
    public static final int SCROLL_END = 607;

    /**
     * The Constant LIST_SELECT indicates that an item in a list has been
     * selected.
     */
    public static final int LIST_SELECT = 701;

    /**
     * The Constant LIST_DESELECT indicates that an item in a list has been
     * unselected.
     */
    public static final int LIST_DESELECT = 702;

    /**
     * The Constant ACTION_EVENT indicates that the user wants some action to
     * occur.
     */
    public static final int ACTION_EVENT = 1001;

    /**
     * The Constant LOAD_FILE indicates a file loading event.
     */
    public static final int LOAD_FILE = 1002;

    /**
     * The Constant SAVE_FILE indicates a file saving event.
     */
    public static final int SAVE_FILE = 1003;

    /**
     * The Constant GOT_FOCUS indicates that a component got the focus.
     */
    public static final int GOT_FOCUS = 1004;

    /**
     * The Constant LOST_FOCUS indicates that the component lost the focus.
     */
    public static final int LOST_FOCUS = 1005;

    /**
     * The target is the component with which the event is associated.
     */
    public Object target;

    /**
     * The when is timestamp when event has occured.
     */
    public long when;

    /**
     * The id indicates the type of the event.
     */
    public int id;

    /**
     * The x coordinate of event.
     */
    public int x;

    /**
     * The y coordinate of event.
     */
    public int y;

    /**
     * The key code of key event.
     */
    public int key;

    /**
     * The state of the modifier keys (given by a bitmask).
     */
    public int modifiers;

    /**
     * The click count indicates the number of consecutive clicks.
     */
    public int clickCount;

    /**
     * The argument of the event.
     */
    public Object arg;

    /**
     * The next event.
     */
    public Event evt;

    /**
     * Instantiates a new event with the specified target component, event type,
     * and argument.
     * 
     * @param target
     *            the target component.
     * @param id
     *            the event type.
     * @param arg
     *            the argument.
     */
    public Event(Object target, int id, Object arg) {
        this(target, 0l, id, 0, 0, 0, 0, arg);
    }

    /**
     * Instantiates a new event with the specified target component, time stamp,
     * event type, x and y coordinates, keyboard key, state of the modifier
     * keys, and an argument set to null.
     * 
     * @param target
     *            the target component.
     * @param when
     *            the time stamp.
     * @param id
     *            the event type.
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @param key
     *            the key.
     * @param modifiers
     *            the modifier keys state.
     */
    public Event(Object target, long when, int id, int x, int y, int key, int modifiers) {
        this(target, when, id, x, y, key, modifiers, null);
    }

    /**
     * Instantiates a new event with the specified target component, time stamp,
     * event type, x and y coordinates, keyboard key, state of the modifier
     * keys, and an argument.
     * 
     * @param target
     *            the target component.
     * @param when
     *            the time stamp.
     * @param id
     *            the event type.
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @param key
     *            the key.
     * @param modifiers
     *            the modifier keys state.
     * @param arg
     *            the specified argument.
     */
    public Event(Object target, long when, int id, int x, int y, int key, int modifiers, Object arg) {
        this.target = target;
        this.when = when;
        this.id = id;
        this.x = x;
        this.y = y;
        this.key = key;
        this.modifiers = modifiers;
        this.arg = arg;
    }

    /**
     * Returns a string representation of this Event.
     * 
     * @return a string representation of this Event.
     */
    @Override
    public String toString() {
        /*
         * The format is based on 1.5 release behavior which can be revealed by
         * the following code: Event e = new Event(new Button(), 0l,
         * Event.KEY_PRESS, 0, 0, Event.TAB, Event.SHIFT_MASK, "arg");
         * System.out.println(e);
         */

        return getClass().getName() + "[" + paramString() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns a string representing the state of this Event.
     * 
     * @return a string representing the state of this Event.
     */
    protected String paramString() {
        return "id=" + id + ",x=" + x + ",y=" + y + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                (key != 0 ? ",key=" + key + getModifiersString() : "") + //$NON-NLS-1$ //$NON-NLS-2$
                ",target=" + target + //$NON-NLS-1$
                (arg != null ? ",arg=" + arg : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Gets a string representation of the modifiers.
     * 
     * @return a string representation of the modifiers.
     */
    private String getModifiersString() {
        String strMod = ""; //$NON-NLS-1$
        if (shiftDown()) {
            strMod += ",shift"; //$NON-NLS-1$
        }
        if (controlDown()) {
            strMod += ",control"; //$NON-NLS-1$
        }
        if (metaDown()) {
            strMod += ",meta"; //$NON-NLS-1$
        }
        return strMod;
    }

    /**
     * Translates x and y coordinates of his event to the x+dx and x+dy
     * coordinates.
     * 
     * @param dx
     *            the distance by which the event's x coordinate is increased.
     * @param dy
     *            the distance by which the event's y coordinate is increased.
     */
    public void translate(int dx, int dy) {
        x += dx;
        y += dy;
    }

    /**
     * Checks if Control key is down or not.
     * 
     * @return true, if Control key is down; false otherwise.
     */
    public boolean controlDown() {
        return (modifiers & CTRL_MASK) != 0;
    }

    /**
     * Checks if Meta key is down or not.
     * 
     * @return true, if Meta key is down; false otherwise.
     */
    public boolean metaDown() {
        return (modifiers & META_MASK) != 0;
    }

    /**
     * Checks if Shift key is down or not.
     * 
     * @return true, if Shift key is down; false otherwise.
     */
    public boolean shiftDown() {
        return (modifiers & SHIFT_MASK) != 0;
    }

}
