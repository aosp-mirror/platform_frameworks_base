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
 * @author Michael Danilov, Dmitry A. Durnev
 * @version $Revision$
 */
package java.awt;

import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.PaintEvent;
import java.awt.event.WindowEvent;

import org.apache.harmony.awt.internal.nls.Messages;
import org.apache.harmony.awt.wtk.NativeEvent;
import org.apache.harmony.awt.wtk.NativeWindow;


/**
 * Helper package-private class for managing lightweight components &
 * dispatching events from heavyweight source
 */
class Dispatcher {

    //???AWT: final PopupDispatcher popupDispatcher = new PopupDispatcher();

    //???AWT: final FocusDispatcher focusDispatcher;

    final MouseGrabManager mouseGrabManager = new MouseGrabManager();

    final MouseDispatcher mouseDispatcher;

    private final ComponentDispatcher componentDispatcher = new ComponentDispatcher();

    private final KeyDispatcher keyDispatcher = new KeyDispatcher();

    private final Toolkit toolkit;

    int clickInterval = 250;

    /**
     * @param toolkit - AWT toolkit
     */
    Dispatcher(Toolkit toolkit) {
        this.toolkit = toolkit;

        //???AWT: focusDispatcher = new FocusDispatcher(toolkit);
        mouseDispatcher = new MouseDispatcher(mouseGrabManager, toolkit);
    }

    /**
     * Dispatch native event: produce appropriate AWT events, 
     * update component's fields when needed
     * @param event - native event to dispatch
     * @return - true means default processing by OS is not needed
     */
    public boolean onEvent(NativeEvent event) {
        int eventId = event.getEventId();

        if (eventId == NativeEvent.ID_CREATED) {
            return toolkit.onWindowCreated(event.getWindowId());
        } else if (eventId == NativeEvent.ID_MOUSE_GRAB_CANCELED) {
            return mouseGrabManager.onGrabCanceled();
        //???AWT
//        } else if (popupDispatcher.onEvent(event)) {
//            return false;
        } else {
            Component src = toolkit.getComponentById(event.getWindowId());

            if (src != null) {
                if (((eventId >= ComponentEvent.COMPONENT_FIRST) && (eventId <= ComponentEvent.COMPONENT_LAST))
                        || ((eventId >= WindowEvent.WINDOW_FIRST) && (eventId <= WindowEvent.WINDOW_LAST))
                        || (eventId == NativeEvent.ID_INSETS_CHANGED)
                        || (eventId == NativeEvent.ID_BOUNDS_CHANGED)
                        || (eventId == NativeEvent.ID_THEME_CHANGED)) {
                    return componentDispatcher.dispatch(src, event);
                } else if ((eventId >= MouseEvent.MOUSE_FIRST)
                        && (eventId <= MouseEvent.MOUSE_LAST)) {
                    return mouseDispatcher.dispatch(src, event);
                } else if (eventId == PaintEvent.PAINT) {
                    //???AWT: src.redrawManager.addPaintRegion(src, event.getClipRects());
                    return true;
                }
            }
            if ((eventId >= FocusEvent.FOCUS_FIRST)
                    && (eventId <= FocusEvent.FOCUS_LAST)) {

                //???AWT: return focusDispatcher.dispatch(src, event);
                return false;
            } else if ((eventId >= KeyEvent.KEY_FIRST)
                    && (eventId <= KeyEvent.KEY_LAST)) {
                return keyDispatcher.dispatch(src, event);
            }
        }

        return false;
    }

    /**
     * The dispatcher of native events that affect 
     * component's state or bounds
     */
    final class ComponentDispatcher {

        /**
         * Handle native event that affects component's state or bounds
         * @param src - the component updated by the event
         * @param event - the native event
         * @return - as in Dispatcher.onEvent()
         * @see Dispatcher#onEvent(NativeEvent)
         */
        boolean dispatch(Component src, NativeEvent event) {
            int id = event.getEventId();

            if ((id == NativeEvent.ID_INSETS_CHANGED)
                    || (id == NativeEvent.ID_THEME_CHANGED)) {
                return dispatchInsets(event, src);
            } else if ((id >= WindowEvent.WINDOW_FIRST)
                    && (id <= WindowEvent.WINDOW_LAST)) {
                return dispatchWindow(event, src);
            } else {
                return dispatchPureComponent(event, src);
            }
        }

        /**
         * Handle the change of top-level window's native decorations 
         * @param event - the native event
         * @param src - the component updated by the event
         * @return - as in Dispatcher.onEvent()
         * @see Dispatcher#onEvent(NativeEvent)
         */
        boolean dispatchInsets(NativeEvent event, Component src) {
            //???AWT
            /*
            if (src instanceof Window) {
                ((Window) src).setNativeInsets(event.getInsets());
            }
            */
            return false;
        }

        /**
         * Handle the change of top-level window's state
         * @param event - the native event
         * @param src - the component updated by the event
         * @return - as in Dispatcher.onEvent()
         * @see Dispatcher#onEvent(NativeEvent)
         */
        boolean dispatchWindow(NativeEvent event, Component src) {
            //???AWT
            /*
            Window window = (Window) src;
            int id = event.getEventId();

            if (id == WindowEvent.WINDOW_CLOSING) {
                toolkit.getSystemEventQueueImpl().postEvent(
                          new WindowEvent(window, WindowEvent.WINDOW_CLOSING));

                return true;
            } else if (id == WindowEvent.WINDOW_STATE_CHANGED) {
                if (window instanceof Frame) {
                    ((Frame) window)
                            .updateExtendedState(event.getWindowState());
                }
            }
            */

            return false;
        }

        /**
         * Handle the change of component's size and/or position
         * @param event - the native event
         * @param src - the component updated by the event
         * @return - as in Dispatcher.onEvent()
         * @see Dispatcher#onEvent(NativeEvent)
         */
        private boolean dispatchPureComponent(NativeEvent event, Component src) {
            Rectangle rect = event.getWindowRect();
            Point loc = rect.getLocation();
            int mask;

            switch (event.getEventId()) {
            case NativeEvent.ID_BOUNDS_CHANGED:
                mask = 0;
                break;
            case ComponentEvent.COMPONENT_MOVED:
                mask = NativeWindow.BOUNDS_NOSIZE;
                break;
            case ComponentEvent.COMPONENT_RESIZED:
                mask = NativeWindow.BOUNDS_NOMOVE;
                break;
            default:
                // awt.12E=Unknown component event id.
                throw new RuntimeException(Messages.getString("awt.12E")); //$NON-NLS-1$
            }

            //???AWT
            /*
            if (!(src instanceof Window)) {
                Component compTo = src.getParent();
                Component compFrom = src.getHWAncestor();

                if ((compTo != null) && (compFrom != null)) {
                    loc = MouseDispatcher.convertPoint(compFrom, loc, compTo);
                }
            } else {
                int windowState = event.getWindowState();

                if ((windowState >= 0) && (src instanceof Frame)) {
                    ((Frame) src).updateExtendedState(windowState);
                }
            }
            src.setBounds(loc.x, loc.y, rect.width, rect.height, mask, false);
            */
            
            return false;
        }

    }

    /**
     * The dispatcher of the keyboard events
     */
    final class KeyDispatcher {

        /**
         * Handle the keyboard event using the KeyboardFocusManager
         * @param src - the component receiving the event
         * @param event - the native event
         * @return - as in Dispatcher.onEvent()
         * @see Dispatcher#onEvent(NativeEvent)
         */
        boolean dispatch(Component src, NativeEvent event) {
            int id = event.getEventId();
            int modifiers = event.getInputModifiers();
            int location = event.getKeyLocation();
            int code = event.getVKey();
            StringBuffer chars = event.getKeyChars();
            int charsLength = chars.length();
            long time = event.getTime();
            char keyChar = event.getLastChar();

            //???AWT
            /*
            if (src == null) {
                //retarget focus proxy key events to focusOwner:
                Window focusProxyOwner = toolkit.getFocusProxyOwnerById(event
                        .getWindowId());
                if (focusProxyOwner == null) {
                    return false;
                }
                src = KeyboardFocusManager.actualFocusOwner;
            }
            */

            EventQueue eventQueue = toolkit.getSystemEventQueueImpl();
            
            if (src != null) {
                eventQueue.postEvent(new KeyEvent(src, id, time, modifiers,
                        code, keyChar, location));
                // KEY_TYPED goes after KEY_PRESSED
                if (id == KeyEvent.KEY_PRESSED) {
                    for (int i = 0; i < charsLength; i++) {
                        keyChar = chars.charAt(i);
                        if (keyChar != KeyEvent.CHAR_UNDEFINED) {
                            eventQueue.postEvent(new KeyEvent(src,
                                    KeyEvent.KEY_TYPED, time, modifiers,
                                    KeyEvent.VK_UNDEFINED, keyChar,
                                    KeyEvent.KEY_LOCATION_UNKNOWN));
                        }
                    }
                }
            }

            return false;
        }

    }

    /**
     * Retargets the mouse events to the grab owner when mouse is grabbed,
     * grab and ungrab mouse when mouse buttons are pressed and released
     */

    static final class MouseGrabManager {

        /** 
         * The top-level window holding the mouse grab 
         * that was explicitly started by startGrab() method
         */
        //???AWT: private Window nativeGrabOwner = null;
        /** 
         * The component that owns the synthetic 
         * mouse grab while at least one of the
         * mouse buttons is pressed
         */
        private Component syntheticGrabOwner = null;

        /**
         * Previous value of syntheticGrabOwner
         */
        private Component lastSyntheticGrabOwner = null;

        /**
         * Number of mouse buttons currently pressed
         */
        private int syntheticGrabDepth = 0;

        /**
         * The callback to be called when the explicit mouse grab ends
         */
        private Runnable whenCanceled;

        /**
         * Explicitly start the mouse grab
         * @param grabWindow - the window that will own the grab
         * @param whenCanceled - the callback to call when the grab ends. 
         * This parameter can be null
         */
        //???AWT
        /*
        void startGrab(Window grabWindow, Runnable whenCanceled) {

            if (nativeGrabOwner != null) {
                // awt.12F=Attempt to start nested mouse grab
                throw new RuntimeException(Messages.getString("awt.12F")); //$NON-NLS-1$
            }

            NativeWindow win = grabWindow.getNativeWindow();
            if (win == null) {
                // awt.130=Attempt to grab mouse in not displayable window
                throw new RuntimeException(Messages.getString("awt.130")); //$NON-NLS-1$
            }

            nativeGrabOwner = grabWindow;
            this.whenCanceled = whenCanceled;
            win.grabMouse();
        }
        */

        /**
         * Ends the explicit mouse grab. If the non-null callback was provided
         * in the startGrab() method, this callback is called 
         */
        void endGrab() {
            //???AWT
            /*
            if (nativeGrabOwner == null) {
                return;
            }

            Window grabWindow = nativeGrabOwner;
            nativeGrabOwner = null;
            NativeWindow win = grabWindow.getNativeWindow();

            if (win != null) {
                win.ungrabMouse();
                if (whenCanceled != null) {
                    whenCanceled.run();
                    whenCanceled = null;
                }
            }
            */
        }

        /**
         * Ends both explicit and synthetic grans 
         * @return - always returns false
         */
        boolean onGrabCanceled() {
            endGrab();
            resetSyntheticGrab();

            return false;
        }

        /**
         * Starts the synthetic mouse grab, increases the counter 
         * of currently pressed mouse buttons
         * @param source - the component where mouse press event occured
         * @return - the component that owns the synthetic grab
         */
        Component onMousePressed(Component source) {
            if (syntheticGrabDepth == 0) {
                syntheticGrabOwner = source;
                lastSyntheticGrabOwner = source;
            }
            syntheticGrabDepth++;

            return syntheticGrabOwner;
        }

        /**
         * Decreases the counter of currently pressed mouse buttons,
         * ends the synthetic mouse grab, when this counter becomes zero
         * @param source - the component where mouse press event occured
         * @return - the component that owns the synthetic grab, 
         * or source parameter if mouse grab was released
         */
        Component onMouseReleased(Component source) {
            Component ret = source;

            //???AWT
            /*
            if (syntheticGrabOwner != null && nativeGrabOwner == null) {
                ret = syntheticGrabOwner;
            }
            */
            syntheticGrabDepth--;
            if (syntheticGrabDepth <= 0) {
                resetSyntheticGrab();
                lastSyntheticGrabOwner = null;
            }

            return ret;
        }

        /**
         * Update the state of synthetic ouse gram 
         * when the mouse is moved/dragged
         * @param event - the native event
         */
        void preprocessEvent(NativeEvent event) {
            int id = event.getEventId();
            switch (id) {
            case MouseEvent.MOUSE_MOVED:
                if (syntheticGrabOwner != null) {
                    syntheticGrabOwner = null;
                    syntheticGrabDepth = 0;
                }
                if (lastSyntheticGrabOwner != null) {
                    lastSyntheticGrabOwner = null;
                }
            case MouseEvent.MOUSE_DRAGGED:
                if (syntheticGrabOwner == null
                        && lastSyntheticGrabOwner != null) {
                    syntheticGrabOwner = lastSyntheticGrabOwner;
                    syntheticGrabDepth = 0;
                    int mask = event.getInputModifiers();
                    syntheticGrabDepth += (mask & InputEvent.BUTTON1_DOWN_MASK) != 0 ? 1
                            : 0;
                    syntheticGrabDepth += (mask & InputEvent.BUTTON2_DOWN_MASK) != 0 ? 1
                            : 0;
                    syntheticGrabDepth += (mask & InputEvent.BUTTON3_DOWN_MASK) != 0 ? 1
                            : 0;
                }
            }
        }

        /**
         * @return the component that currently owns the synthetic grab 
         */
        Component getSyntheticGrabOwner() {
            return syntheticGrabOwner;
        }

        /**
         * ends synthetic grab
         */
        private void resetSyntheticGrab() {
            syntheticGrabOwner = null;
            syntheticGrabDepth = 0;
        }

    }
    
    /**
     * Dispatches native events related to the pop-up boxes 
     * (the non-component windows such as menus and drop lists)
     */
//    final class PopupDispatcher {
//
//        private PopupBox activePopup;
//
//        private PopupBox underCursor;
//
//        private final MouseGrab grab = new MouseGrab();
//
//        /**
//         * Handles the mouse grab for pop-up boxes
//         */
//        private final class MouseGrab {
//            private int depth;
//
//            private PopupBox owner;
//
//            private final Point start = new Point();
//
//            /**
//             * Starts the grab when mouse is pressed
//             * @param src - the pop-up box where mouse event has occured
//             * @param where - the mouse pointer location
//             * @return - the grab owner
//             */
//            PopupBox mousePressed(PopupBox src, Point where) {
//                if (depth == 0) {
//                    owner = src;
//                    start.setLocation(where);
//                }
//                depth++;
//                return owner;
//            }
//
//            /**
//             * Ends the grab when all mousebuttons are released
//             * @param src - the pop-up box where mouse event has occured
//             * @param where - the mouse pointer location
//             * @return - the grab owner, or src parameter if the grab has ended
//             */
//            PopupBox mouseReleased(PopupBox src, Point where) {
//                PopupBox ret = (owner != null) ? owner : src;
//                if (depth == 0) {
//                    return ret;
//                }
//                depth--;
//                if (depth == 0) {
//                    PopupBox tgt = owner;
//                    owner = null;
//                    if (tgt != null && src == null) {
//                        Point a = new Point(start);
//                        Point b = new Point(where);
//                        Point pos = tgt.getScreenLocation();
//                        a.translate(-pos.x, -pos.y);
//                        b.translate(-pos.x, -pos.y);
//                        if (tgt.closeOnUngrab(a, b)) {
//                            return null;
//                        }
//                    }
//                }
//                return ret;
//            }
//
//            /**
//             * Set the grab owner to null
//             */
//            void reset() {
//                depth = 0;
//                owner = null;
//                start.setLocation(0, 0);
//            }
//
//            /**
//             * @return - the pop-up box currently owning the grab
//             */
//            public PopupBox getOwner() {
//                return owner;
//            }
//        }
//
//        /**
//         * Call the mouse event handler of the pop-up box
//         * @param src - the pop-up box where the mouse event occured
//         * @param eventId - the event ID, one of MouseEvent.MOUSE_* constants
//         * @param where - the mouse pointer location
//         * @param event - native event
//         */
//        private void mouseEvent(PopupBox src, int eventId, Point where,
//                NativeEvent event) {
//            Point pos = src.getScreenLocation();
//            pos.setLocation(where.x - pos.x, where.y - pos.y);
//
//            src.onMouseEvent(eventId, pos, event.getMouseButton(), event
//                    .getTime(), event.getInputModifiers(), event
//                    .getWheelRotation());
//        }
//
//        /**
//         * Handle the native event targeted by a pop-up box. This could be 
//         * paint event, mouse or keyboard event.
//         * @param event - the native event
//         * @return - false if the event was handled and doesn't 
//         * need the further processing; true when the further 
//         * processing is needed
//         */
//        boolean onEvent(NativeEvent event) {
//            PopupBox src = toolkit.getPopupBoxById(event.getWindowId());
//            int id = event.getEventId();
//
//            if ((id == PaintEvent.PAINT)) {
//                if (src != null) {
//                    src.paint(event.getClipRects());
//                    return true;
//                }
//                Component c = toolkit.getComponentById(event.getWindowId());
//                if ((c != null) && (c instanceof Frame)) {
//                    ((Frame) c).paintMenuBar(event.getClipRects());
//                }
//                return false;
//            }
//
//            if ((id >= MouseEvent.MOUSE_FIRST) && (id <= MouseEvent.MOUSE_LAST)) {
//                Point where = event.getScreenPos();
//
//                if (src != underCursor) {
//                    if (underCursor != null) {
//                        mouseEvent(underCursor, MouseEvent.MOUSE_EXITED, where,
//                                event);
//                    }
//                    underCursor = src;
//                    if (underCursor != null) {
//                        mouseEvent(underCursor, MouseEvent.MOUSE_ENTERED,
//                                where, event);
//                        underCursor.setDefaultCursor();
//                    }
//                }
//                if (id == MouseEvent.MOUSE_EXITED) {
//                    underCursor = null;
//                }
//
//                if ((activePopup == null) && (src == null || !src.isMenuBar())) {
//                    return false;
//                }
//
//                if (id == MouseEvent.MOUSE_PRESSED) {
//                    src = grab.mousePressed(src, where);
//                } else if (id == MouseEvent.MOUSE_RELEASED) {
//                    src = grab.mouseReleased(src, where);
//                } else if (src == null) {
//                    src = grab.getOwner();
//                }
//
//                PopupBox wasActive = activePopup;
//
//                if (src != null) {
//                    mouseEvent(src, id, where, event);
//                    return src.isMenu() || src.contains(where);
//                }
//
//                if (wasActive != null && activePopup == null) {
//                    return wasActive.isMenu();
//                }
//
//                if ((id == MouseEvent.MOUSE_PRESSED)
//                        || (id == MouseEvent.MOUSE_RELEASED)) {
//                    boolean isMenu = activePopup.isMenu();
//                    deactivateAll();
//                    return !isMenu;
//                }
//                return true;
//            }
//
//            if (activePopup == null) {
//                return false;
//            }
//
//            if ((id >= KeyEvent.KEY_FIRST) && (id <= KeyEvent.KEY_LAST)) {
//                boolean isMenu = activePopup.isMenu();
//                activePopup.dispatchKeyEvent(id, event.getVKey(), event
//                        .getTime(), event.getInputModifiers());
//
//                return isMenu;
//            }
//
//            return false;
//        }
//
//        /**
//         * Remember the pop-up as active and grab the mouse on it
//         * @param popup - the pop-up box to activate
//         */
//        void activate(final PopupBox popup) {
//            if (activePopup == null) {
//
//                activePopup = popup;
//                mouseGrabManager.startGrab(popup.getOwner(), new Runnable() {
//                    public void run() {
//                        deactivate(popup);
//                    }
//                });
//            }
//        }
//
//        /**
//         * Deactivate the currently active pop-up box
//         */
//        void deactivateAll() {
//            deactivate(activePopup);
//        }
//
//        /**
//         * Deactivate the pop-up box, end the mouse grab
//         */
//        void deactivate(PopupBox popup) {
//            grab.reset();
//
//            if (activePopup != null && activePopup == popup) {
//                activePopup = null;
//                mouseGrabManager.endGrab();
//                popup.hide();
//                underCursor = null;
//            }
//        }
//
//        /**
//         * Check that the pop-up box is currently active
//         * @param popup - the pop-up box to check
//         * @return - true if active
//         */
//        boolean isActive(PopupBox popup) {
//            return (popup == activePopup) && (popup != null);
//        }
//    }

}