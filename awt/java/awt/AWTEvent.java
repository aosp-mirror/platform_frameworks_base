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
 * @author Dmitry A. Durnev, Michael Danilov
 * @version $Revision$
 */

package java.awt;

import java.util.EventObject;
import java.util.Hashtable;
import java.util.EventListener;

import java.awt.event.*;

/**
 * The abstract class AWTEvent is the base class for all AWT events. This class
 * and its subclasses supersede the original java.awt.Event class.
 * 
 * @since Android 1.0
 */
public abstract class AWTEvent extends EventObject {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -1825314779160409405L;

    /**
     * The Constant COMPONENT_EVENT_MASK indicates the event relates to a
     * component.
     */
    public static final long COMPONENT_EVENT_MASK = 1;

    /**
     * The Constant CONTAINER_EVENT_MASK indicates the event relates to a
     * container.
     */
    public static final long CONTAINER_EVENT_MASK = 2;

    /**
     * The Constant FOCUS_EVENT_MASK indicates the event relates to the focus.
     */
    public static final long FOCUS_EVENT_MASK = 4;

    /**
     * The Constant KEY_EVENT_MASK indicates the event relates to a key.
     */
    public static final long KEY_EVENT_MASK = 8;

    /**
     * The Constant MOUSE_EVENT_MASK indicates the event relates to the mouse.
     */
    public static final long MOUSE_EVENT_MASK = 16;

    /**
     * The Constant MOUSE_MOTION_EVENT_MASK indicates the event relates to a
     * mouse motion.
     */
    public static final long MOUSE_MOTION_EVENT_MASK = 32;

    /**
     * The Constant WINDOW_EVENT_MASK indicates the event relates to a window.
     */
    public static final long WINDOW_EVENT_MASK = 64;

    /**
     * The Constant ACTION_EVENT_MASK indicates the event relates to an action.
     */
    public static final long ACTION_EVENT_MASK = 128;

    /**
     * The Constant ADJUSTMENT_EVENT_MASK indicates the event relates to an
     * adjustment.
     */
    public static final long ADJUSTMENT_EVENT_MASK = 256;

    /**
     * The Constant ITEM_EVENT_MASK indicates the event relates to an item.
     */
    public static final long ITEM_EVENT_MASK = 512;

    /**
     * The Constant TEXT_EVENT_MASK indicates the event relates to text.
     */
    public static final long TEXT_EVENT_MASK = 1024;

    /**
     * The Constant INPUT_METHOD_EVENT_MASK indicates the event relates to an
     * input method.
     */
    public static final long INPUT_METHOD_EVENT_MASK = 2048;

    /**
     * The Constant PAINT_EVENT_MASK indicates the event relates to a paint
     * method.
     */
    public static final long PAINT_EVENT_MASK = 8192;

    /**
     * The Constant INVOCATION_EVENT_MASK indicates the event relates to a
     * method invocation.
     */
    public static final long INVOCATION_EVENT_MASK = 16384;

    /**
     * The Constant HIERARCHY_EVENT_MASK indicates the event relates to a
     * hierarchy.
     */
    public static final long HIERARCHY_EVENT_MASK = 32768;

    /**
     * The Constant HIERARCHY_BOUNDS_EVENT_MASK indicates the event relates to
     * hierarchy bounds.
     */
    public static final long HIERARCHY_BOUNDS_EVENT_MASK = 65536;

    /**
     * The Constant MOUSE_WHEEL_EVENT_MASK indicates the event relates to the
     * mouse wheel.
     */
    public static final long MOUSE_WHEEL_EVENT_MASK = 131072;

    /**
     * The Constant WINDOW_STATE_EVENT_MASK indicates the event relates to a
     * window state.
     */
    public static final long WINDOW_STATE_EVENT_MASK = 262144;

    /**
     * The Constant WINDOW_FOCUS_EVENT_MASK indicates the event relates to a
     * window focus.
     */
    public static final long WINDOW_FOCUS_EVENT_MASK = 524288;

    /**
     * The Constant RESERVED_ID_MAX indicates the maximum value for reserved AWT
     * event IDs.
     */
    public static final int RESERVED_ID_MAX = 1999;

    /**
     * The Constant eventsMap.
     */
    private static final Hashtable<Integer, EventDescriptor> eventsMap = new Hashtable<Integer, EventDescriptor>();

    /**
     * The converter.
     */
    private static EventConverter converter;

    /**
     * The ID of the event.
     */
    protected int id;

    /**
     * The consumed indicates whether or not the event is sent back down to the
     * peer once the source has processed it (false means it's sent to the peer,
     * true means it's not).
     */
    protected boolean consumed;

    /**
     * The dispatched by kfm.
     */
    boolean dispatchedByKFM;

    /**
     * The is posted.
     */
    transient boolean isPosted;

    static {
        eventsMap.put(new Integer(KeyEvent.KEY_TYPED), new EventDescriptor(KEY_EVENT_MASK,
                KeyListener.class));
        eventsMap.put(new Integer(KeyEvent.KEY_PRESSED), new EventDescriptor(KEY_EVENT_MASK,
                KeyListener.class));
        eventsMap.put(new Integer(KeyEvent.KEY_RELEASED), new EventDescriptor(KEY_EVENT_MASK,
                KeyListener.class));
        eventsMap.put(new Integer(MouseEvent.MOUSE_CLICKED), new EventDescriptor(MOUSE_EVENT_MASK,
                MouseListener.class));
        eventsMap.put(new Integer(MouseEvent.MOUSE_PRESSED), new EventDescriptor(MOUSE_EVENT_MASK,
                MouseListener.class));
        eventsMap.put(new Integer(MouseEvent.MOUSE_RELEASED), new EventDescriptor(MOUSE_EVENT_MASK,
                MouseListener.class));
        eventsMap.put(new Integer(MouseEvent.MOUSE_MOVED), new EventDescriptor(
                MOUSE_MOTION_EVENT_MASK, MouseMotionListener.class));
        eventsMap.put(new Integer(MouseEvent.MOUSE_ENTERED), new EventDescriptor(MOUSE_EVENT_MASK,
                MouseListener.class));
        eventsMap.put(new Integer(MouseEvent.MOUSE_EXITED), new EventDescriptor(MOUSE_EVENT_MASK,
                MouseListener.class));
        eventsMap.put(new Integer(MouseEvent.MOUSE_DRAGGED), new EventDescriptor(
                MOUSE_MOTION_EVENT_MASK, MouseMotionListener.class));
        eventsMap.put(new Integer(MouseEvent.MOUSE_WHEEL), new EventDescriptor(
                MOUSE_WHEEL_EVENT_MASK, MouseWheelListener.class));
        eventsMap.put(new Integer(ComponentEvent.COMPONENT_MOVED), new EventDescriptor(
                COMPONENT_EVENT_MASK, ComponentListener.class));
        eventsMap.put(new Integer(ComponentEvent.COMPONENT_RESIZED), new EventDescriptor(
                COMPONENT_EVENT_MASK, ComponentListener.class));
        eventsMap.put(new Integer(ComponentEvent.COMPONENT_SHOWN), new EventDescriptor(
                COMPONENT_EVENT_MASK, ComponentListener.class));
        eventsMap.put(new Integer(ComponentEvent.COMPONENT_HIDDEN), new EventDescriptor(
                COMPONENT_EVENT_MASK, ComponentListener.class));
        eventsMap.put(new Integer(FocusEvent.FOCUS_GAINED), new EventDescriptor(FOCUS_EVENT_MASK,
                FocusListener.class));
        eventsMap.put(new Integer(FocusEvent.FOCUS_LOST), new EventDescriptor(FOCUS_EVENT_MASK,
                FocusListener.class));
        eventsMap.put(new Integer(PaintEvent.PAINT), new EventDescriptor(PAINT_EVENT_MASK, null));
        eventsMap.put(new Integer(PaintEvent.UPDATE), new EventDescriptor(PAINT_EVENT_MASK, null));
        eventsMap.put(new Integer(WindowEvent.WINDOW_OPENED), new EventDescriptor(
                WINDOW_EVENT_MASK, WindowListener.class));
        eventsMap.put(new Integer(WindowEvent.WINDOW_CLOSING), new EventDescriptor(
                WINDOW_EVENT_MASK, WindowListener.class));
        eventsMap.put(new Integer(WindowEvent.WINDOW_CLOSED), new EventDescriptor(
                WINDOW_EVENT_MASK, WindowListener.class));
        eventsMap.put(new Integer(WindowEvent.WINDOW_DEICONIFIED), new EventDescriptor(
                WINDOW_EVENT_MASK, WindowListener.class));
        eventsMap.put(new Integer(WindowEvent.WINDOW_ICONIFIED), new EventDescriptor(
                WINDOW_EVENT_MASK, WindowListener.class));
        eventsMap.put(new Integer(WindowEvent.WINDOW_STATE_CHANGED), new EventDescriptor(
                WINDOW_STATE_EVENT_MASK, WindowStateListener.class));
        eventsMap.put(new Integer(WindowEvent.WINDOW_LOST_FOCUS), new EventDescriptor(
                WINDOW_FOCUS_EVENT_MASK, WindowFocusListener.class));
        eventsMap.put(new Integer(WindowEvent.WINDOW_GAINED_FOCUS), new EventDescriptor(
                WINDOW_FOCUS_EVENT_MASK, WindowFocusListener.class));
        eventsMap.put(new Integer(WindowEvent.WINDOW_DEACTIVATED), new EventDescriptor(
                WINDOW_EVENT_MASK, WindowListener.class));
        eventsMap.put(new Integer(WindowEvent.WINDOW_ACTIVATED), new EventDescriptor(
                WINDOW_EVENT_MASK, WindowListener.class));
        eventsMap.put(new Integer(HierarchyEvent.HIERARCHY_CHANGED), new EventDescriptor(
                HIERARCHY_EVENT_MASK, HierarchyListener.class));
        eventsMap.put(new Integer(HierarchyEvent.ANCESTOR_MOVED), new EventDescriptor(
                HIERARCHY_BOUNDS_EVENT_MASK, HierarchyBoundsListener.class));
        eventsMap.put(new Integer(HierarchyEvent.ANCESTOR_RESIZED), new EventDescriptor(
                HIERARCHY_BOUNDS_EVENT_MASK, HierarchyBoundsListener.class));
        eventsMap.put(new Integer(ContainerEvent.COMPONENT_ADDED), new EventDescriptor(
                CONTAINER_EVENT_MASK, ContainerListener.class));
        eventsMap.put(new Integer(ContainerEvent.COMPONENT_REMOVED), new EventDescriptor(
                CONTAINER_EVENT_MASK, ContainerListener.class));
        eventsMap.put(new Integer(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED), new EventDescriptor(
                INPUT_METHOD_EVENT_MASK, InputMethodListener.class));
        eventsMap.put(new Integer(InputMethodEvent.CARET_POSITION_CHANGED), new EventDescriptor(
                INPUT_METHOD_EVENT_MASK, InputMethodListener.class));
        eventsMap.put(new Integer(InvocationEvent.INVOCATION_DEFAULT), new EventDescriptor(
                INVOCATION_EVENT_MASK, null));
        eventsMap.put(new Integer(ItemEvent.ITEM_STATE_CHANGED), new EventDescriptor(
                ITEM_EVENT_MASK, ItemListener.class));
        eventsMap.put(new Integer(TextEvent.TEXT_VALUE_CHANGED), new EventDescriptor(
                TEXT_EVENT_MASK, TextListener.class));
        eventsMap.put(new Integer(ActionEvent.ACTION_PERFORMED), new EventDescriptor(
                ACTION_EVENT_MASK, ActionListener.class));
        eventsMap.put(new Integer(AdjustmentEvent.ADJUSTMENT_VALUE_CHANGED), new EventDescriptor(
                ADJUSTMENT_EVENT_MASK, AdjustmentListener.class));
        converter = new EventConverter();
    }

    /**
     * Instantiates a new AWT event from the specified Event object.
     * 
     * @param event
     *            the Event object.
     */
    public AWTEvent(Event event) {
        this(event.target, event.id);
    }

    /**
     * Instantiates a new AWT event with the specified object and type.
     * 
     * @param source
     *            the source Object.
     * @param id
     *            the event's type.
     */
    public AWTEvent(Object source, int id) {
        super(source);
        this.id = id;
        consumed = false;
    }

    /**
     * Gets the event's type.
     * 
     * @return the event type ID.
     */
    public int getID() {
        return id;
    }

    /**
     * Sets a new source for the AWTEvent.
     * 
     * @param newSource
     *            the new source Object for the AWTEvent.
     */
    public void setSource(Object newSource) {
        source = newSource;
    }

    /**
     * Returns a String representation of the AWTEvent.
     * 
     * @return the String representation of the AWTEvent.
     */
    @Override
    public String toString() {
        /*
         * The format is based on 1.5 release behavior which can be revealed by
         * the following code: AWTEvent event = new AWTEvent(new Component(){},
         * 1){}; System.out.println(event);
         */
        String name = ""; //$NON-NLS-1$

        if (source instanceof Component && (source != null)) {
            Component comp = (Component)getSource();
            name = comp.getName();
            if (name == null) {
                name = ""; //$NON-NLS-1$
            }
        }

        return (getClass().getName() + "[" + paramString() + "]" //$NON-NLS-1$ //$NON-NLS-2$
                + " on " + (name.length() > 0 ? name : source)); //$NON-NLS-1$
    }

    /**
     * Returns a string representation of the AWTEvent state.
     * 
     * @return a string representation of the AWTEvent state.
     */
    public String paramString() {
        // nothing to implement: all event types must override this method
        return ""; //$NON-NLS-1$
    }

    /**
     * Checks whether or not this AWTEvent has been consumed.
     * 
     * @return true, if this AWTEvent has been consumed, false otherwise.
     */
    protected boolean isConsumed() {
        return consumed;
    }

    /**
     * Consumes the AWTEvent.
     */
    protected void consume() {
        consumed = true;
    }

    /**
     * Convert AWTEvent object to a corresponding (deprecated) Event object.
     * 
     * @return new Event object which is a converted AWTEvent object or null if
     *         the conversion is not possible
     */
    Event getEvent() {

        if (id == ActionEvent.ACTION_PERFORMED) {
            ActionEvent ae = (ActionEvent)this;
            return converter.convertActionEvent(ae);

        } else if (id == AdjustmentEvent.ADJUSTMENT_VALUE_CHANGED) {
            AdjustmentEvent ae = (AdjustmentEvent)this;
            return converter.convertAdjustmentEvent(ae);

            // ???AWT
            // } else if (id == ComponentEvent.COMPONENT_MOVED
            // && source instanceof Window) {
            // //the only type of Component events is COMPONENT_MOVED on window
            // ComponentEvent ce = (ComponentEvent) this;
            // return converter.convertComponentEvent(ce);

        } else if (id >= FocusEvent.FOCUS_FIRST && id <= FocusEvent.FOCUS_LAST) {
            // nothing to convert

            // ???AWT
            // } else if (id == ItemEvent.ITEM_STATE_CHANGED) {
            // ItemEvent ie = (ItemEvent) this;
            // return converter.convertItemEvent(ie);

        } else if (id == KeyEvent.KEY_PRESSED || id == KeyEvent.KEY_RELEASED) {
            KeyEvent ke = (KeyEvent)this;
            return converter.convertKeyEvent(ke);
        } else if (id >= MouseEvent.MOUSE_FIRST && id <= MouseEvent.MOUSE_LAST) {
            MouseEvent me = (MouseEvent)this;
            return converter.convertMouseEvent(me);
        } else if (id == WindowEvent.WINDOW_CLOSING || id == WindowEvent.WINDOW_ICONIFIED
                || id == WindowEvent.WINDOW_DEICONIFIED) {
            // nothing to convert
        } else {
            return null;
        }
        return new Event(source, id, null);
    }

    /**
     * The class EventDescriptor.
     */
    static final class EventDescriptor {

        /**
         * The event mask.
         */
        final long eventMask;

        /**
         * The listener type.
         */
        final Class<? extends EventListener> listenerType;

        /**
         * Instantiates a new event descriptor.
         * 
         * @param eventMask
         *            the event mask.
         * @param listenerType
         *            the listener type.
         */
        EventDescriptor(long eventMask, Class<? extends EventListener> listenerType) {
            this.eventMask = eventMask;
            this.listenerType = listenerType;
        }

    }

    /**
     * The class EventTypeLookup.
     */
    static final class EventTypeLookup {

        /**
         * The last event.
         */
        private AWTEvent lastEvent = null;

        /**
         * The last event descriptor.
         */
        private EventDescriptor lastEventDescriptor = null;

        /**
         * Gets the event descriptor.
         * 
         * @param event
         *            the event.
         * @return the event descriptor.
         */
        EventDescriptor getEventDescriptor(AWTEvent event) {
            synchronized (this) {
                if (event != lastEvent) {
                    lastEvent = event;
                    lastEventDescriptor = eventsMap.get(new Integer(event.id));
                }

                return lastEventDescriptor;
            }
        }

        /**
         * Gets the event mask.
         * 
         * @param event
         *            the event.
         * @return the event mask.
         */
        long getEventMask(AWTEvent event) {
            final EventDescriptor ed = getEventDescriptor(event);
            return ed == null ? -1 : ed.eventMask;
        }
    }

    /**
     * The class EventConverter.
     */
    static final class EventConverter {

        /**
         * The constant OLD_MOD_MASK.
         */
        static final int OLD_MOD_MASK = Event.ALT_MASK | Event.CTRL_MASK | Event.META_MASK
                | Event.SHIFT_MASK;

        /**
         * Convert action event.
         * 
         * @param ae
         *            the ae.
         * @return the event.
         */
        Event convertActionEvent(ActionEvent ae) {
            Event evt = new Event(ae.getSource(), ae.getID(), ae.getActionCommand());
            evt.when = ae.getWhen();
            evt.modifiers = ae.getModifiers() & OLD_MOD_MASK;

            /*
             * if (source instanceof Button) { arg = ((Button)
             * source).getLabel(); } else if (source instanceof Checkbox) { arg
             * = new Boolean(((Checkbox) source).getState()); } else if (source
             * instanceof CheckboxMenuItem) { arg = ((CheckboxMenuItem)
             * source).getLabel(); } else if (source instanceof Choice) { arg =
             * ((Choice) source).getSelectedItem(); } else if (source instanceof
             * List) { arg = ((List) source).getSelectedItem(); } else if
             * (source instanceof MenuItem) { arg = ((MenuItem)
             * source).getLabel(); } else if (source instanceof TextField) { arg
             * = ((TextField) source).getText(); }
             */
            return evt;
        }

        /**
         * Convert adjustment event.
         * 
         * @param ae
         *            the ae.
         * @return the event.
         */
        Event convertAdjustmentEvent(AdjustmentEvent ae) {
            // TODO: Event.SCROLL_BEGIN/SCROLL_END
            return new Event(ae.source, ae.id + ae.getAdjustmentType() - 1, new Integer(ae
                    .getValue()));
        }

        /**
         * Convert component event.
         * 
         * @param ce
         *            the ce.
         * @return the event.
         */
        Event convertComponentEvent(ComponentEvent ce) {
            Component comp = ce.getComponent();
            Event evt = new Event(comp, Event.WINDOW_MOVED, null);
            evt.x = comp.getX();
            evt.y = comp.getY();
            return evt;
        }

        // ???AWT
        /*
         * Event convertItemEvent(ItemEvent ie) { int oldId = ie.id +
         * ie.getStateChange() - 1; Object source = ie.source; int idx = -1; if
         * (source instanceof List) { List list = (List) source; idx =
         * list.getSelectedIndex(); } else if (source instanceof Choice) {
         * Choice choice = (Choice) source; idx = choice.getSelectedIndex(); }
         * Object arg = idx >= 0 ? new Integer(idx) : null; return new
         * Event(source, oldId, arg); }
         */

        /**
         * Convert key event.
         * 
         * @param ke
         *            the ke.
         * @return the event.
         */
        Event convertKeyEvent(KeyEvent ke) {
            int oldId = ke.id;
            // leave only old Event's modifiers

            int mod = ke.getModifiers() & OLD_MOD_MASK;
            Component comp = ke.getComponent();
            char keyChar = ke.getKeyChar();
            int keyCode = ke.getKeyCode();
            int key = convertKey(keyChar, keyCode);
            if (key >= Event.HOME && key <= Event.INSERT) {
                oldId += 2; // non-ASCII key -> action key
            }
            return new Event(comp, ke.getWhen(), oldId, 0, 0, key, mod);
        }

        /**
         * Convert mouse event.
         * 
         * @param me
         *            the me.
         * @return the event.
         */
        Event convertMouseEvent(MouseEvent me) {
            int id = me.id;
            if (id != MouseEvent.MOUSE_CLICKED) {
                Event evt = new Event(me.source, id, null);
                evt.x = me.getX();
                evt.y = me.getY();
                int mod = me.getModifiers();
                // in Event modifiers mean button number for mouse events:
                evt.modifiers = mod & (Event.ALT_MASK | Event.META_MASK);
                if (id == MouseEvent.MOUSE_PRESSED) {
                    evt.clickCount = me.getClickCount();
                }
                return evt;
            }
            return null;
        }

        /**
         * Convert key.
         * 
         * @param keyChar
         *            the key char.
         * @param keyCode
         *            the key code.
         * @return the int.
         */
        int convertKey(char keyChar, int keyCode) {
            int key;
            // F1 - F12
            if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12) {
                key = Event.F1 + keyCode - KeyEvent.VK_F1;
            } else {
                switch (keyCode) {
                    default: // non-action key
                        key = keyChar;
                        break;
                    // action keys:
                    case KeyEvent.VK_HOME:
                        key = Event.HOME;
                        break;
                    case KeyEvent.VK_END:
                        key = Event.END;
                        break;
                    case KeyEvent.VK_PAGE_UP:
                        key = Event.PGUP;
                        break;
                    case KeyEvent.VK_PAGE_DOWN:
                        key = Event.PGDN;
                        break;
                    case KeyEvent.VK_UP:
                        key = Event.UP;
                        break;
                    case KeyEvent.VK_DOWN:
                        key = Event.DOWN;
                        break;
                    case KeyEvent.VK_LEFT:
                        key = Event.LEFT;
                        break;
                    case KeyEvent.VK_RIGHT:
                        key = Event.RIGHT;
                        break;
                    case KeyEvent.VK_PRINTSCREEN:
                        key = Event.PRINT_SCREEN;
                        break;
                    case KeyEvent.VK_SCROLL_LOCK:
                        key = Event.SCROLL_LOCK;
                        break;
                    case KeyEvent.VK_CAPS_LOCK:
                        key = Event.CAPS_LOCK;
                        break;
                    case KeyEvent.VK_NUM_LOCK:
                        key = Event.NUM_LOCK;
                        break;
                    case KeyEvent.VK_PAUSE:
                        key = Event.PAUSE;
                        break;
                    case KeyEvent.VK_INSERT:
                        key = Event.INSERT;
                        break;
                }
            }
            return key;
        }

    }

}
