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
 * @author Dmitry A. Durnev, Michael Danilov, Pavel Dolgov
 * @version $Revision$
 */
package java.awt;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.Dispatcher.MouseGrabManager;
import java.util.EventListener;

import org.apache.harmony.awt.wtk.NativeEvent;
import org.apache.harmony.awt.wtk.NativeWindow;


class MouseDispatcher {

    // Fields for synthetic mouse click events generation
    private static final int clickDelta = 5;
    private final long[] lastPressTime = new long[] {0l, 0l, 0l};
    private final Point[] lastPressPos = new Point[] {null, null, null};
    private final boolean[] buttonPressed = new boolean[] {false, false, false};
    private final int[] clickCount = new int[] {0, 0, 0};

    // Fields for mouse entered/exited support
    private Component lastUnderPointer = null;
    private final Point lastScreenPos = new Point(-1, -1);

    // Fields for redundant mouse moved/dragged filtering
    private Component lastUnderMotion = null;
    private Point lastLocalPos = new Point(-1, -1);

    private final MouseGrabManager mouseGrabManager;
    private final Toolkit toolkit;

    static Point convertPoint(Component src, int x, int y, Component dest) {
        Point srcPoint = getAbsLocation(src);
        Point destPoint = getAbsLocation(dest);

        return new Point(x + (srcPoint.x - destPoint.x),
                         y + (srcPoint.y - destPoint.y));
    }

    static Point convertPoint(Component src, Point p, Component dst) {
        return convertPoint(src, p.x, p.y, dst);
    }

    private static Point getAbsLocation(Component comp) {
        Point location = new Point(0, 0);
// BEGIN android-changed: AWT components not supported
//        for (Component parent = comp; parent != null; parent = parent.parent) {
//            Point parentPos = (parent instanceof EmbeddedWindow ?
//                               parent.getNativeWindow().getScreenPos() :
//                               parent.getLocation());
//
//            location.translate(parentPos.x, parentPos.y);
//
//            if (parent instanceof Window) {
//                break;
//            }
//        }
// END android-changed

        return location;
    }

    MouseDispatcher(MouseGrabManager mouseGrabManager,
                    Toolkit toolkit) {
        this.mouseGrabManager = mouseGrabManager;
        this.toolkit = toolkit;
    }

    Point getPointerPos() {
        return lastScreenPos;
    }

    boolean dispatch(Component src, NativeEvent event) {
        int id = event.getEventId();

        lastScreenPos.setLocation(event.getScreenPos());
        checkMouseEnterExit(event.getInputModifiers(), event.getTime());

        if (id == MouseEvent.MOUSE_WHEEL) {
// BEGIN android-changed: AWT components not supported
//            dispatchWheelEvent(src, event);
// END android-changed
        } else if ((id != MouseEvent.MOUSE_ENTERED) &&
                   (id != MouseEvent.MOUSE_EXITED)) {
            PointerInfo info = new PointerInfo(src, event.getLocalPos());

            mouseGrabManager.preprocessEvent(event);
            findEventSource(info);
            if ((id == MouseEvent.MOUSE_PRESSED) ||
                (id == MouseEvent.MOUSE_RELEASED)) {

                dispatchButtonEvent(info, event);
            } else if ((id == MouseEvent.MOUSE_MOVED) ||
                       (id == MouseEvent.MOUSE_DRAGGED)) {

                dispatchMotionEvent(info, event);
            }
        }

        return false;
    }

    private void checkMouseEnterExit(int modifiers, long when) {
// BEGIN android-changed: AWT components not supported
//        PointerInfo info = findComponentUnderPointer();
//        Component curUnderPointer =
//                propagateEvent(info, AWTEvent.MOUSE_EVENT_MASK,
//                               MouseListener.class, false).src;
//
//        if (curUnderPointer != lastUnderPointer) {
//            Point pos = info.position;
//            if ((lastUnderPointer != null) &&
//                 lastUnderPointer.isMouseExitedExpected()) {
//
//                Point exitPos = convertPoint(null, lastScreenPos.x,
//                                             lastScreenPos.y, lastUnderPointer);
//
//                postMouseEnterExit(MouseEvent.MOUSE_EXITED, modifiers, when,
//                                   exitPos.x, exitPos.y, lastUnderPointer);
//            }
//            setCursor(curUnderPointer);
//            if (curUnderPointer != null) {
//                postMouseEnterExit(MouseEvent.MOUSE_ENTERED, modifiers, when,
//                                   pos.x, pos.y, curUnderPointer);
//            }
//            lastUnderPointer = curUnderPointer;
//        }
// END android-changed
    }

    private void setCursor(Component comp) {
        if (comp == null) {
            return;
        }
        Component grabOwner = mouseGrabManager.getSyntheticGrabOwner();
        Component cursorComp = ((grabOwner != null) &&
                                 grabOwner.isShowing() ? grabOwner : comp);
        cursorComp.setCursor();
    }

    private void postMouseEnterExit(int id, int mod, long when,
                                    int x, int y, Component comp) {
        if (comp.isIndirectlyEnabled()) {
            toolkit.getSystemEventQueueImpl().postEvent(
                    new MouseEvent(comp, id, when, mod, x, y, 0, false));
            comp.setMouseExitedExpected(id == MouseEvent.MOUSE_ENTERED);
        } else {
            comp.setMouseExitedExpected(false);
        }
    }

 // BEGIN android-changed: AWT components not supported
//    private PointerInfo findComponentUnderPointer() {
//        NativeWindow nativeWindow = toolkit.getWindowFactory().
//        getWindowFromPoint(lastScreenPos);
//
//        if (nativeWindow != null) {
//            Component comp = toolkit.getComponentById(nativeWindow.getId());
//
//            if (comp != null) {
//                Window window = comp.getWindowAncestor();
//                Point pointerPos = convertPoint(null, lastScreenPos.x,
//                                                lastScreenPos.y, window);
//
//                if (window.getClient().contains(pointerPos)) {
//                    PointerInfo info = new PointerInfo(window, pointerPos);
//
//                    fall2Child(info);
//
//                    return info;
//                }
//            }
//        }
//
//        return new PointerInfo(null, null);
//    }
// END android-changed
    
    private void findEventSource(PointerInfo info) {
        Component grabOwner = mouseGrabManager.getSyntheticGrabOwner();

        if (grabOwner != null && grabOwner.isShowing()) {
            info.position = convertPoint(info.src, info.position, grabOwner);
            info.src = grabOwner;
        } else {
            //???AWT: rise2TopLevel(info);
            //???AWT: fall2Child(info);
        }
    }

 // BEGIN android-changed: AWT components not supported
//    private void rise2TopLevel(PointerInfo info) {
//        while (!(info.src instanceof Window)) {
//            info.position.translate(info.src.x, info.src.y);
//            info.src = info.src.parent;
//        }
//    }
//
//    private void fall2Child(PointerInfo info) {
//        Insets insets = info.src.getInsets();
//
//        final Point pos = info.position;
//        final int x = pos.x;
//        final int y = pos.y;
//        if ((x >= insets.left) && (y >= insets.top) &&
//                (x < (info.src.w - insets.right)) &&
//                (y < (info.src.h - insets.bottom)))
//        {
//            Component[] children = ((Container) info.src).getComponents();
//
//            for (Component child : children) {
//                if (child.isShowing()) {
//                    if (child.contains(x - child.getX(),
//                            y - child.getY()))
//                    {
//                        info.src = child;
//                        pos.translate(-child.x, -child.y);
//
//                        if (child instanceof Container) {
//                            fall2Child(info);
//                        }
//
//                        return;
//                    }
//                }
//            }
//        }
//    }
// END android-changed

    private void dispatchButtonEvent(PointerInfo info, NativeEvent event) {
        int button = event.getMouseButton();
        long time = event.getTime();
        int id = event.getEventId();
        int index = button - 1;
        boolean clickRequired = false;

        propagateEvent(info, AWTEvent.MOUSE_EVENT_MASK,
                       MouseListener.class, false);
        if (id == MouseEvent.MOUSE_PRESSED) {
            int clickInterval = toolkit.dispatcher.clickInterval;
            mouseGrabManager.onMousePressed(info.src);
            buttonPressed[index] = true;
            clickCount[index] = (!deltaExceeded(index, info) &&
                    ((time - lastPressTime[index]) <= clickInterval)) ?
                    clickCount[index] + 1 : 1;
            lastPressTime[index] = time;
            lastPressPos[index] = info.position;
        } else {
            mouseGrabManager.onMouseReleased(info.src);
            // set cursor back on synthetic mouse grab end:
// BEGIN android-changed: AWT components not supported
//            setCursor(findComponentUnderPointer().src);
// END android-changed
            if (buttonPressed[index]) {
                buttonPressed[index] = false;
                clickRequired = !deltaExceeded(index, info);
            } else {
                clickCount[index] = 0;
            }
        }
        if (info.src.isIndirectlyEnabled()) {
            final Point pos = info.position;
            final int mod = event.getInputModifiers();
            toolkit.getSystemEventQueueImpl().postEvent(
                            new MouseEvent(info.src, id, time, mod, pos.x,
                            pos.y, clickCount[index],
                            event.getTrigger(), button));
            if (clickRequired) {
                toolkit.getSystemEventQueueImpl().postEvent(
                            new MouseEvent(info.src,
                            MouseEvent.MOUSE_CLICKED,
                            time, mod, pos.x, pos.y,
                            clickCount[index], false,
                            button));
            }
        }
    }

    private boolean deltaExceeded(int index, PointerInfo info) {
        final Point lastPos = lastPressPos[index];
        if (lastPos == null) {
            return true;
        }
        return ((Math.abs(lastPos.x - info.position.x) > clickDelta) ||
                (Math.abs(lastPos.y - info.position.y) > clickDelta));
    }

    private void dispatchMotionEvent(PointerInfo info, NativeEvent event) {
        propagateEvent(info, AWTEvent.MOUSE_MOTION_EVENT_MASK,
                       MouseMotionListener.class, false);
        final Point pos = info.position;
        if ((lastUnderMotion != info.src) ||
            !lastLocalPos.equals(pos)) {

            lastUnderMotion = info.src;
            lastLocalPos = pos;

            if (info.src.isIndirectlyEnabled()) {
                toolkit.getSystemEventQueueImpl().postEvent(
                            new MouseEvent(info.src, event.getEventId(),
                            event.getTime(),
                            event.getInputModifiers(),
                            pos.x, pos.y, 0, false));
            }
        }
    }

    MouseWheelEvent createWheelEvent(Component src, NativeEvent event,
                                     Point where) {

        Integer scrollAmountProperty =
            (Integer)toolkit.getDesktopProperty("awt.wheelScrollingSize"); //$NON-NLS-1$
        int amount = 1;
        int type = MouseWheelEvent.WHEEL_UNIT_SCROLL;

        if (scrollAmountProperty != null) {
            amount = scrollAmountProperty.intValue();
            if (amount == -1) {
                type = MouseWheelEvent.WHEEL_BLOCK_SCROLL;
                amount = 1;
            }
        }
        return new MouseWheelEvent(src, event.getEventId(),
                event.getTime(), event.getInputModifiers(),
                where.x, where.y, 0, false, type, amount,
                event.getWheelRotation());
    }

// BEGIN android-changed: AWT components not supported
//    private void dispatchWheelEvent(Component src, NativeEvent event) {
//        PointerInfo info = findComponentUnderPointer();
//
//        if (info.src == null) {
//            info.src = src;
//            info.position = event.getLocalPos();
//        }
//
//        propagateEvent(info, AWTEvent.MOUSE_WHEEL_EVENT_MASK,
//                       MouseWheelListener.class, true);
//        if ((info.src != null) && info.src.isIndirectlyEnabled()) {
//            toolkit.getSystemEventQueueImpl().postEvent(
//                    createWheelEvent(info.src, event, info.position));
//        }
//    }
// END android-changed

    private PointerInfo propagateEvent(PointerInfo info, long mask,
                                       Class<? extends EventListener> type, boolean pierceHW) {
        Component src = info.src;
        while ((src != null) &&
               (src.isLightweight() || pierceHW) &&
              !(src.isMouseEventEnabled(mask) ||
               (src.getListeners(type).length > 0))) {

            info.position.translate(src.x, src.y);
// BEGIN android-changed: AWT components not supported
//            src = src.parent;
// END android-changed
            info.src = src;
        }

        return info;
    }

// BEGIN android-changed: AWT components not supported
//    Window findWindowAt(Point p) {
//        NativeWindow nativeWindow =
//            toolkit.getWindowFactory().getWindowFromPoint(p);
//
//        Window window = null;
//        if (nativeWindow != null) {
//            Component comp = toolkit.getComponentById(nativeWindow.getId());
//
//            if (comp != null) {
//                window = comp.getWindowAncestor();
//            }
//        }
//        return window;
//    }
// END android-changed

    private class PointerInfo {

        Component src;
        Point position;

        PointerInfo(Component src, Point position) {
            this.src = src;
            this.position = position;
        }

    }

}
