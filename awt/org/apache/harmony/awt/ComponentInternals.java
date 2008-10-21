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
 * @author Pavel Dolgov
 * @version $Revision$
 */
package org.apache.harmony.awt;

//???AWT
//import java.awt.Component;
//import java.awt.Container;
//import java.awt.Dialog;
import java.awt.Dimension;
//import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
//import java.awt.Window;
//import java.awt.Choice;
import java.lang.reflect.InvocationTargetException;

import org.apache.harmony.awt.gl.MultiRectArea;
//import org.apache.harmony.awt.text.TextFieldKit;
//import org.apache.harmony.awt.text.TextKit;
//import org.apache.harmony.awt.wtk.NativeWindow;

import org.apache.harmony.luni.util.NotImplementedException;

/**
 *  The accessor to AWT private API
 */
public abstract class ComponentInternals {

    /**
     * @return the ComponentInternals instance to serve the requests
     */
    public static ComponentInternals getComponentInternals() {
        return ContextStorage.getComponentInternals();
    }

    /**
     * This method must be called by AWT to establish the connection
     * @param internals - implementation of ComponentInternals created by AWT
     */
    public static void setComponentInternals(ComponentInternals internals) {
        ContextStorage.setComponentInternals(internals);
    }

    /**
     * The accessor to native resource connected to a component.
     * It returns non-<code>null</code> value only if component
     * already has the native resource
     */
    //public abstract NativeWindow getNativeWindow(Component component);

    /**
     * Connect Window object to existing native resource
     * @param nativeWindowId - id of native window to attach
     * @return Window object with special behaviour that
     * restricts manupulation with that window
     */
    //public abstract Window attachNativeWindow(long nativeWindowId);

    /**
     * Start mouse grab in "client" mode.
     * All mouse events in AWT components will be reported as usual,
     * mouse events that occured outside of AWT components will be sent to
     * the window passed as grabWindow parameter. When mouse grab is canceled
     * (because of click in non-AWT window or by task switching)
     * the whenCanceled callback is called
     *
     * @param grabWindow - window that will own the grab
     * @param whenCanceled - callback called when grab is canceled by user's action
     */
    //public abstract void startMouseGrab(Window grabWindow, Runnable whenCanceled);

    /**
     * End mouse grab and resume normal processing of mouse events
     */
    //public abstract void endMouseGrab();

    /**
     * Set the <code>popup</code> flag of the window to true.
     * This window won't be controlled by window manager on Linux.
     * Call this method before the window is shown first time
     * @param window - the window that should become popup one
     */
    //public abstract void makePopup(Window window);

    /**
     * This method must be called by Graphics at the beginning of drawImage()
     * to store image drawing parameters (defined by application developer) in component
     *
     * @param comp - component that draws the image
     * @param image - image to be drawn
     * @param destLocation - location of the image upon the component's surface. Never null.
     * @param destSize - size of the component's area to be filled with the image.
     *                  Equals to null if size parameters omitted in drawImage.
     * @param source - area of the image to be drawn on the component.
     *                  Equals to null if src parameters omitted in drawImage.
     */
    /*
    public abstract void onDrawImage(Component comp, Image image, Point destLocation,
            Dimension destSize, Rectangle source);
*/
    /**
     * Sets system's caret position.
     * This method should be called by text component to synchronize our caret position
     * with system's caret position.
     * @param x
     * @param y
     */
    //public abstract void setCaretPos(Component c, int x, int y);

    /**
     * NEVER USE IT. FORGET IT. IT DOES NOT EXIST.
     * See Toolkit.unsafeInvokeAndWait(Runnable).
     *
     * Accessor for Toolkit.unsafeInvokeAndWait(Runnable) method.
     * For use in exceptional cases only.
     * Read comments for Toolkit.unsafeInvokeAndWait(Runnable) before use.
     */
    /*
    public abstract void unsafeInvokeAndWait(Runnable runnable)
            throws InterruptedException, InvocationTargetException;

    public abstract TextKit getTextKit(Component comp);

    public abstract void setTextKit(Component comp, TextKit kit);

    public abstract TextFieldKit getTextFieldKit(Component comp);

    public abstract void setTextFieldKit(Component comp, TextFieldKit kit);
*/
    /**
     * Terminate event dispatch thread, completely destroy AWT context.<br>
     * Intended for multi-context mode, in single-context mode does nothing.
     *
     */
    public abstract void shutdown();

    /**
     * Sets mouse events preprocessor for event queue
     */
    //public abstract void setMouseEventPreprocessor(MouseEventPreprocessor preprocessor);

    /**
     * Create customized Choice using style
     */
    //public abstract Choice createCustomChoice(ChoiceStyle style);

    //public abstract Insets getNativeInsets(Window w);

    /**
     * Region to be repainted (could be null). Use this in overridden repaint()
     */
    //public abstract MultiRectArea getRepaintRegion(Component c);

    //public abstract MultiRectArea subtractPendingRepaintRegion(Component c, MultiRectArea mra);

    /**
     * Returns true if the window was at least once painted due to native paint events
     */
    //public abstract boolean wasPainted(Window w);

    /**
     * The component's region hidden behind top-level windows
     * (belonging to both this Java app and all other apps), and behind
     * heavyweight components overlapping with passed component
     */
    //public abstract MultiRectArea getObscuredRegion(Component c);
    
    /**
     * An accessor to Container.addObscuredRegions() method
     * @see java.awt.Container#addObscuredRegions(MultiRectArea, Component)
     */
    //public abstract void addObscuredRegions(MultiRectArea mra, Component c, Container container);
    
    /**
     * Makes it possible to call protected Toolkit.setDesktopProperty()
     * method from any class outside of java.awt package
     */
    public abstract void setDesktopProperty(String name, Object value);
    
    /**
     * Makes it possible to start/stop dialog modal loop
     * from anywhere outside of java.awt package
     */
    //public abstract void runModalLoop(Dialog dlg);
    //public abstract void endModalLoop(Dialog dlg);
    
    /**
     * Sets component's visible flag only
     * (the component is not actually shown/hidden)
     */
    //public abstract void setVisibleFlag(Component comp, boolean visible);
    
}
