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

package java.awt;

//import java.awt.dnd.DropTarget;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.HierarchyBoundsListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.InvocationEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.PaintEvent;
import java.awt.event.WindowEvent;
import java.awt.im.InputContext;
import java.awt.im.InputMethodRequests;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;
import java.awt.image.VolatileImage;
import java.awt.image.WritableRaster;
import java.awt.peer.ComponentPeer;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

//???AWT
//import javax.accessibility.Accessible;
//import javax.accessibility.AccessibleComponent;
//import javax.accessibility.AccessibleContext;
//import javax.accessibility.AccessibleRole;
//import javax.accessibility.AccessibleState;
//import javax.accessibility.AccessibleStateSet;

import org.apache.harmony.awt.ClipRegion; //import org.apache.harmony.awt.FieldsAccessor;
import org.apache.harmony.awt.gl.MultiRectArea;
import org.apache.harmony.awt.internal.nls.Messages;
import org.apache.harmony.awt.state.State; //import org.apache.harmony.awt.text.TextFieldKit;
//import org.apache.harmony.awt.text.TextKit;
import org.apache.harmony.awt.wtk.NativeWindow;
import org.apache.harmony.luni.util.NotImplementedException;

/**
 * The abstract Component class specifies an object with a graphical
 * representation that can be displayed on the screen and that can interact with
 * the user (for example: scrollbars, buttons, checkboxes).
 * 
 * @since Android 1.0
 */
public abstract class Component implements ImageObserver, MenuContainer, Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -7644114512714619750L;

    /**
     * The Constant TOP_ALIGNMENT indicates the top alignment of the component.
     */
    public static final float TOP_ALIGNMENT = 0.0f;

    /**
     * The Constant CENTER_ALIGNMENT indicates the center alignment of the
     * component.
     */
    public static final float CENTER_ALIGNMENT = 0.5f;

    /**
     * The Constant BOTTOM_ALIGNMENT indicates the bottom alignment of the
     * component.
     */
    public static final float BOTTOM_ALIGNMENT = 1.0f;

    /**
     * The Constant LEFT_ALIGNMENT indicates the left alignment of the
     * component.
     */
    public static final float LEFT_ALIGNMENT = 0.0f;

    /**
     * The Constant RIGHT_ALIGNMENT indicates the right alignment of the
     * component.
     */
    public static final float RIGHT_ALIGNMENT = 1.0f;

    /**
     * The Constant childClassesFlags.
     */
    private static final Hashtable<Class<?>, Boolean> childClassesFlags = new Hashtable<Class<?>, Boolean>();

    /**
     * The Constant peer.
     */
    private static final ComponentPeer peer = new ComponentPeer() {
    };

    /**
     * The Constant incrementalImageUpdate.
     */
    private static final boolean incrementalImageUpdate;

    /**
     * The toolkit.
     */
    final transient Toolkit toolkit = Toolkit.getDefaultToolkit();

    // ???AWT
    /*
     * protected abstract class AccessibleAWTComponent extends AccessibleContext
     * implements Serializable, AccessibleComponent { private static final long
     * serialVersionUID = 642321655757800191L; protected class
     * AccessibleAWTComponentHandler implements ComponentListener { protected
     * AccessibleAWTComponentHandler() { } public void
     * componentHidden(ComponentEvent e) { if (behaviour.isLightweight()) {
     * return; } firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY,
     * AccessibleState.VISIBLE, null); } public void
     * componentMoved(ComponentEvent e) { } public void
     * componentResized(ComponentEvent e) { } public void
     * componentShown(ComponentEvent e) { if (behaviour.isLightweight()) {
     * return; } firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY,
     * null, AccessibleState.VISIBLE); } } protected class
     * AccessibleAWTFocusHandler implements FocusListener { public void
     * focusGained(FocusEvent e) { if (behaviour.isLightweight()) { return; }
     * firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY, null,
     * AccessibleState.FOCUSED); } public void focusLost(FocusEvent e) { if
     * (behaviour.isLightweight()) { return; }
     * firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY,
     * AccessibleState.FOCUSED, null); } } protected ComponentListener
     * accessibleAWTComponentHandler; protected FocusListener
     * accessibleAWTFocusHandler;
     */
    /*
     * Number of registered property change listeners.
     */
    /*
     * int listenersCount; public void addFocusListener(FocusListener l) {
     * Component.this.addFocusListener(l); }
     * @Override public void addPropertyChangeListener(PropertyChangeListener
     * listener) { toolkit.lockAWT(); try {
     * super.addPropertyChangeListener(listener); listenersCount++; if
     * (accessibleAWTComponentHandler == null) { accessibleAWTComponentHandler =
     * new AccessibleAWTComponentHandler();
     * Component.this.addComponentListener(accessibleAWTComponentHandler); } if
     * (accessibleAWTFocusHandler == null) { accessibleAWTFocusHandler = new
     * AccessibleAWTFocusHandler();
     * Component.this.addFocusListener(accessibleAWTFocusHandler); } } finally {
     * toolkit.unlockAWT(); } } public boolean contains(Point p) {
     * toolkit.lockAWT(); try { return Component.this.contains(p); } finally {
     * toolkit.unlockAWT(); } } public Accessible getAccessibleAt(Point arg0) {
     * toolkit.lockAWT(); try { return null; } finally { toolkit.unlockAWT(); }
     * } public Color getBackground() { toolkit.lockAWT(); try { return
     * Component.this.getBackground(); } finally { toolkit.unlockAWT(); } }
     * public Rectangle getBounds() { toolkit.lockAWT(); try { return
     * Component.this.getBounds(); } finally { toolkit.unlockAWT(); } } public
     * Cursor getCursor() { toolkit.lockAWT(); try { return
     * Component.this.getCursor(); } finally { toolkit.unlockAWT(); } } public
     * Font getFont() { toolkit.lockAWT(); try { return
     * Component.this.getFont(); } finally { toolkit.unlockAWT(); } } public
     * FontMetrics getFontMetrics(Font f) { toolkit.lockAWT(); try { return
     * Component.this.getFontMetrics(f); } finally { toolkit.unlockAWT(); } }
     * public Color getForeground() { toolkit.lockAWT(); try { return
     * Component.this.getForeground(); } finally { toolkit.unlockAWT(); } }
     * public Point getLocation() { toolkit.lockAWT(); try { return
     * Component.this.getLocation(); } finally { toolkit.unlockAWT(); } } public
     * Point getLocationOnScreen() { toolkit.lockAWT(); try { return
     * Component.this.getLocationOnScreen(); } finally { toolkit.unlockAWT(); }
     * } public Dimension getSize() { toolkit.lockAWT(); try { return
     * Component.this.getSize(); } finally { toolkit.unlockAWT(); } } public
     * boolean isEnabled() { toolkit.lockAWT(); try { return
     * Component.this.isEnabled(); } finally { toolkit.unlockAWT(); } } public
     * boolean isFocusTraversable() { toolkit.lockAWT(); try { return
     * Component.this.isFocusTraversable(); } finally { toolkit.unlockAWT(); } }
     * public boolean isShowing() { toolkit.lockAWT(); try { return
     * Component.this.isShowing(); } finally { toolkit.unlockAWT(); } } public
     * boolean isVisible() { toolkit.lockAWT(); try { return
     * Component.this.isVisible(); } finally { toolkit.unlockAWT(); } } public
     * void removeFocusListener(FocusListener l) {
     * Component.this.removeFocusListener(l); }
     * @Override public void removePropertyChangeListener(PropertyChangeListener
     * listener) { toolkit.lockAWT(); try {
     * super.removePropertyChangeListener(listener); listenersCount--; if
     * (listenersCount > 0) { return; } // if there are no more listeners,
     * remove handlers:
     * Component.this.removeFocusListener(accessibleAWTFocusHandler);
     * Component.this.removeComponentListener(accessibleAWTComponentHandler);
     * accessibleAWTComponentHandler = null; accessibleAWTFocusHandler = null; }
     * finally { toolkit.unlockAWT(); } } public void requestFocus() {
     * toolkit.lockAWT(); try { Component.this.requestFocus(); } finally {
     * toolkit.unlockAWT(); } } public void setBackground(Color color) {
     * toolkit.lockAWT(); try { Component.this.setBackground(color); } finally {
     * toolkit.unlockAWT(); } } public void setBounds(Rectangle r) {
     * toolkit.lockAWT(); try { Component.this.setBounds(r); } finally {
     * toolkit.unlockAWT(); } } public void setCursor(Cursor cursor) {
     * toolkit.lockAWT(); try { Component.this.setCursor(cursor); } finally {
     * toolkit.unlockAWT(); } } public void setEnabled(boolean enabled) {
     * toolkit.lockAWT(); try { Component.this.setEnabled(enabled); } finally {
     * toolkit.unlockAWT(); } } public void setFont(Font f) { toolkit.lockAWT();
     * try { Component.this.setFont(f); } finally { toolkit.unlockAWT(); } }
     * public void setForeground(Color color) { toolkit.lockAWT(); try {
     * Component.this.setForeground(color); } finally { toolkit.unlockAWT(); } }
     * public void setLocation(Point p) { toolkit.lockAWT(); try {
     * Component.this.setLocation(p); } finally { toolkit.unlockAWT(); } }
     * public void setSize(Dimension size) { toolkit.lockAWT(); try {
     * Component.this.setSize(size); } finally { toolkit.unlockAWT(); } } public
     * void setVisible(boolean visible) { toolkit.lockAWT(); try {
     * Component.this.setVisible(visible); } finally { toolkit.unlockAWT(); } }
     * @Override public Accessible getAccessibleParent() { toolkit.lockAWT();
     * try { Accessible aParent = super.getAccessibleParent(); if (aParent !=
     * null) { return aParent; } Container parent = getParent(); return (parent
     * instanceof Accessible ? (Accessible) parent : null); } finally {
     * toolkit.unlockAWT(); } }
     * @Override public Accessible getAccessibleChild(int i) {
     * toolkit.lockAWT(); try { return null; } finally { toolkit.unlockAWT(); }
     * }
     * @Override public int getAccessibleChildrenCount() { toolkit.lockAWT();
     * try { return 0; } finally { toolkit.unlockAWT(); } }
     * @Override public AccessibleComponent getAccessibleComponent() { return
     * this; }
     * @Override public String getAccessibleDescription() { return
     * super.getAccessibleDescription(); // why override? }
     * @Override public int getAccessibleIndexInParent() { toolkit.lockAWT();
     * try { if (getAccessibleParent() == null) { return -1; } int count = 0;
     * Container parent = getParent(); for (int i = 0; i <
     * parent.getComponentCount(); i++) { Component aComp =
     * parent.getComponent(i); if (aComp instanceof Accessible) { if (aComp ==
     * Component.this) { return count; } ++count; } } return -1; } finally {
     * toolkit.unlockAWT(); } }
     * @Override public AccessibleRole getAccessibleRole() { toolkit.lockAWT();
     * try { return AccessibleRole.AWT_COMPONENT; } finally {
     * toolkit.unlockAWT(); } }
     * @Override public AccessibleStateSet getAccessibleStateSet() {
     * toolkit.lockAWT(); try { AccessibleStateSet set = new
     * AccessibleStateSet(); if (isEnabled()) {
     * set.add(AccessibleState.ENABLED); } if (isFocusable()) {
     * set.add(AccessibleState.FOCUSABLE); } if (hasFocus()) {
     * set.add(AccessibleState.FOCUSED); } if (isOpaque()) {
     * set.add(AccessibleState.OPAQUE); } if (isShowing()) {
     * set.add(AccessibleState.SHOWING); } if (isVisible()) {
     * set.add(AccessibleState.VISIBLE); } return set; } finally {
     * toolkit.unlockAWT(); } }
     * @Override public Locale getLocale() throws IllegalComponentStateException
     * { toolkit.lockAWT(); try { return Component.this.getLocale(); } finally {
     * toolkit.unlockAWT(); } } }
     */
    /**
     * The BltBufferStrategy class provides opportunity of blitting offscreen
     * surfaces to a component. For more information on blitting, see <a
     * href="http://en.wikipedia.org/wiki/Bit_blit">Bit blit</a>.
     * 
     * @since Android 1.0
     */
    protected class BltBufferStrategy extends BufferStrategy {

        /**
         * The back buffers.
         */
        protected VolatileImage[] backBuffers;

        /**
         * The caps.
         */
        protected BufferCapabilities caps;

        /**
         * The width.
         */
        protected int width;

        /**
         * The height.
         */
        protected int height;

        /**
         * The validated contents.
         */
        protected boolean validatedContents;

        /**
         * Instantiates a new BltBufferStrategy buffer strategy.
         * 
         * @param numBuffers
         *            the number of buffers.
         * @param caps
         *            the BufferCapabilities.
         * @throws NotImplementedException
         *             the not implemented exception.
         */
        protected BltBufferStrategy(int numBuffers, BufferCapabilities caps)
                throws org.apache.harmony.luni.util.NotImplementedException {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
        }

        /**
         * Returns true if the drawing buffer has been lost since the last call
         * to getDrawGraphics.
         * 
         * @return true if the drawing buffer has been lost since the last call
         *         to getDrawGraphics, false otherwise.
         * @see java.awt.image.BufferStrategy#contentsLost()
         */
        @Override
        public boolean contentsLost() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
            return false;
        }

        /**
         * Returns true if the drawing buffer has been restored from a lost
         * state and reinitialized to the default background color.
         * 
         * @return true if the drawing buffer has been restored from a lost
         *         state and reinitialized to the default background color,
         *         false otherwise.
         * @see java.awt.image.BufferStrategy#contentsRestored()
         */
        @Override
        public boolean contentsRestored() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
            return false;
        }

        /**
         * Creates the back buffers.
         * 
         * @param numBuffers
         *            the number of buffers.
         */
        protected void createBackBuffers(int numBuffers) {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
        }

        /**
         * Returns the BufferCapabilities of the buffer strategy.
         * 
         * @return the BufferCapabilities.
         * @see java.awt.image.BufferStrategy#getCapabilities()
         */
        @Override
        public BufferCapabilities getCapabilities() {
            return (BufferCapabilities)caps.clone();
        }

        /**
         * Gets Graphics of current buffer strategy.
         * 
         * @return the Graphics of current buffer strategy.
         * @see java.awt.image.BufferStrategy#getDrawGraphics()
         */
        @Override
        public Graphics getDrawGraphics() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
            return null;
        }

        /**
         * Revalidates the lost drawing buffer.
         */
        protected void revalidate() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
        }

        /**
         * Shows the next available buffer.
         * 
         * @see java.awt.image.BufferStrategy#show()
         */
        @Override
        public void show() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
        }
    }

    /**
     * The FlipBufferStrategy class is for flipping buffers on a component.
     * 
     * @since Android 1.0
     */
    protected class FlipBufferStrategy extends BufferStrategy {

        /**
         * The Buffer Capabilities.
         */
        protected BufferCapabilities caps;

        /**
         * The drawing buffer.
         */
        protected Image drawBuffer;

        /**
         * The drawing VolatileImage buffer.
         */
        protected VolatileImage drawVBuffer;

        /**
         * The number of buffers.
         */
        protected int numBuffers;

        /**
         * The validated contents indicates if the drawing buffer is restored
         * from lost state.
         */
        protected boolean validatedContents;

        /**
         * Instantiates a new flip buffer strategy.
         * 
         * @param numBuffers
         *            the number of buffers.
         * @param caps
         *            the BufferCapabilities.
         * @throws AWTException
         *             if the capabilities supplied could not be supported or
         *             met.
         */
        protected FlipBufferStrategy(int numBuffers, BufferCapabilities caps) throws AWTException {
            // ???AWT
            /*
             * if (!(Component.this instanceof Window) && !(Component.this
             * instanceof Canvas)) { // awt.14B=Only Canvas or Window is allowed
             * throw new ClassCastException(Messages.getString("awt.14B"));
             * //$NON-NLS-1$ }
             */
            // TODO: throw new AWTException("Capabilities are not supported");
            this.numBuffers = numBuffers;
            this.caps = (BufferCapabilities)caps.clone();
        }

        /**
         * Returns true if the drawing buffer has been lost since the last call
         * to getDrawGraphics.
         * 
         * @return true if the drawing buffer has been lost since the last call
         *         to getDrawGraphics, false otherwise.
         * @see java.awt.image.BufferStrategy#contentsLost()
         */
        @Override
        public boolean contentsLost() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
            return false;
        }

        /**
         * Returns true if the drawing buffer has been restored from a lost
         * state and reinitialized to the default background color.
         * 
         * @return true if the drawing buffer has been restored from a lost
         *         state and reinitialized to the default background color,
         *         false otherwise.
         * @see java.awt.image.BufferStrategy#contentsRestored()
         */
        @Override
        public boolean contentsRestored() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
            return false;
        }

        /**
         * Creates flipping buffers with the specified buffer capabilities.
         * 
         * @param numBuffers
         *            the number of buffers.
         * @param caps
         *            the BufferCapabilities.
         * @throws AWTException
         *             if the capabilities could not be supported or met.
         */
        protected void createBuffers(int numBuffers, BufferCapabilities caps) throws AWTException {
            if (numBuffers < 2) {
                // awt.14C=Number of buffers must be greater than one
                throw new IllegalArgumentException(Messages.getString("awt.14C")); //$NON-NLS-1$
            }
            if (!caps.isPageFlipping()) {
                // awt.14D=Buffer capabilities should support flipping
                throw new IllegalArgumentException(Messages.getString("awt.14D")); //$NON-NLS-1$
            }
            if (!Component.this.behaviour.isDisplayable()) {
                // awt.14E=Component should be displayable
                throw new IllegalStateException(Messages.getString("awt.14E")); //$NON-NLS-1$
            }
            // TODO: throw new AWTException("Capabilities are not supported");
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
        }

        /**
         * Destroy buffers.
         */
        protected void destroyBuffers() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
        }

        /**
         * Flips the contents of the back buffer to the front buffer.
         * 
         * @param flipAction
         *            the flip action.
         */
        protected void flip(BufferCapabilities.FlipContents flipAction) {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
        }

        /**
         * Gets the back buffer as Image.
         * 
         * @return the back buffer as Image.
         */
        protected Image getBackBuffer() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
            return null;
        }

        /**
         * Returns the BufferCapabilities of the buffer strategy.
         * 
         * @return the BufferCapabilities.
         * @see java.awt.image.BufferStrategy#getCapabilities()
         */
        @Override
        public BufferCapabilities getCapabilities() {
            return (BufferCapabilities)caps.clone();
        }

        /**
         * Gets Graphics of current buffer strategy.
         * 
         * @return the Graphics of current buffer strategy.
         * @see java.awt.image.BufferStrategy#getDrawGraphics()
         */
        @Override
        public Graphics getDrawGraphics() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
            return null;
        }

        /**
         * Revalidates the lost drawing buffer.
         */
        protected void revalidate() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
        }

        /**
         * Shows the next available buffer.
         * 
         * @see java.awt.image.BufferStrategy#show()
         */
        @Override
        public void show() {
            if (true) {
                throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
            }
        }
    }

    /**
     * The internal component's state utilized by the visual theme.
     */
    class ComponentState implements State {

        /**
         * The default minimum size.
         */
        private Dimension defaultMinimumSize = new Dimension();

        /**
         * Checks if the component is enabled.
         * 
         * @return true, if the component is enabled.
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Checks if the component is visible.
         * 
         * @return true, if the component is visible.
         */
        public boolean isVisible() {
            return visible;
        }

        /**
         * Checks if is focused.
         * 
         * @return true, if is focused.
         */
        public boolean isFocused() {
            // ???AWT: return isFocusOwner();
            return false;
        }

        /**
         * Gets the font.
         * 
         * @return the font.
         */
        public Font getFont() {
            return Component.this.getFont();
        }

        /**
         * Checks if the font has been set.
         * 
         * @return true, if the font has been set.
         */
        public boolean isFontSet() {
            return font != null;
        }

        /**
         * Gets the background color.
         * 
         * @return the background color.
         */
        public Color getBackground() {
            Color c = Component.this.getBackground();
            return (c != null) ? c : getDefaultBackground();
        }

        /**
         * Checks if the background is set.
         * 
         * @return true, if the background is set.
         */
        public boolean isBackgroundSet() {
            return backColor != null;
        }

        /**
         * Gets the text color.
         * 
         * @return the text color.
         */
        public Color getTextColor() {
            Color c = getForeground();
            return (c != null) ? c : getDefaultForeground();
        }

        /**
         * Checks if the text color is set.
         * 
         * @return true, if the text color is set.
         */
        public boolean isTextColorSet() {
            return foreColor != null;
        }

        /**
         * Gets the font metrics.
         * 
         * @return the font metrics.
         */
        @SuppressWarnings("deprecation")
        public FontMetrics getFontMetrics() {
            return toolkit.getFontMetrics(Component.this.getFont());
        }

        /**
         * Gets the bounding rectangle.
         * 
         * @return the bounding rectangle.
         */
        public Rectangle getBounds() {
            return new Rectangle(x, y, w, h);
        }

        /**
         * Gets the size of the bounding rectangle.
         * 
         * @return the size of the bounding rectangle.
         */
        public Dimension getSize() {
            return new Dimension(w, h);
        }

        /**
         * Gets the window id.
         * 
         * @return the window id.
         */
        public long getWindowId() {
            NativeWindow win = getNativeWindow();
            return (win != null) ? win.getId() : 0;
        }

        /**
         * Gets the default minimum size.
         * 
         * @return the default minimum size.
         */
        public Dimension getDefaultMinimumSize() {
            if (defaultMinimumSize == null) {
                calculate();
            }
            return defaultMinimumSize;
        }

        /**
         * Sets the default minimum size.
         * 
         * @param size
         *            the new default minimum size.
         */
        public void setDefaultMinimumSize(Dimension size) {
            defaultMinimumSize = size;
        }

        /**
         * Reset the default minimum size to null.
         */
        public void reset() {
            defaultMinimumSize = null;
        }

        /**
         * Calculate the default minimum size: to be overridden.
         */
        public void calculate() {
            // to be overridden
        }
    }

    // ???AWT: private transient AccessibleContext accessibleContext;

    /**
     * The behaviour.
     */
    final transient ComponentBehavior behaviour;

    // ???AWT: Container parent;

    /**
     * The name.
     */
    private String name;

    /**
     * The auto name.
     */
    private boolean autoName = true;

    /**
     * The font.
     */
    private Font font;

    /**
     * The back color.
     */
    private Color backColor;

    /**
     * The fore color.
     */
    private Color foreColor;

    /**
     * The deprecated event handler.
     */
    boolean deprecatedEventHandler = true;

    /**
     * The enabled events.
     */
    private long enabledEvents;

    /**
     * The enabled AWT events.
     */
    private long enabledAWTEvents;

    /**
     * The component listeners.
     */
    private final AWTListenerList<ComponentListener> componentListeners = new AWTListenerList<ComponentListener>(
            this);

    /**
     * The focus listeners.
     */
    private final AWTListenerList<FocusListener> focusListeners = new AWTListenerList<FocusListener>(
            this);

    /**
     * The hierarchy listeners.
     */
    private final AWTListenerList<HierarchyListener> hierarchyListeners = new AWTListenerList<HierarchyListener>(
            this);

    /**
     * The hierarchy bounds listeners.
     */
    private final AWTListenerList<HierarchyBoundsListener> hierarchyBoundsListeners = new AWTListenerList<HierarchyBoundsListener>(
            this);

    /**
     * The key listeners.
     */
    private final AWTListenerList<KeyListener> keyListeners = new AWTListenerList<KeyListener>(this);

    /**
     * The mouse listeners.
     */
    private final AWTListenerList<MouseListener> mouseListeners = new AWTListenerList<MouseListener>(
            this);

    /**
     * The mouse motion listeners.
     */
    private final AWTListenerList<MouseMotionListener> mouseMotionListeners = new AWTListenerList<MouseMotionListener>(
            this);

    /**
     * The mouse wheel listeners.
     */
    private final AWTListenerList<MouseWheelListener> mouseWheelListeners = new AWTListenerList<MouseWheelListener>(
            this);

    /**
     * The input method listeners.
     */
    private final AWTListenerList<InputMethodListener> inputMethodListeners = new AWTListenerList<InputMethodListener>(
            this);

    /**
     * The x.
     */
    int x;

    /**
     * The y.
     */
    int y;

    /**
     * The w.
     */
    int w;

    /**
     * The h.
     */
    int h;

    /**
     * The maximum size.
     */
    private Dimension maximumSize;

    /**
     * The minimum size.
     */
    private Dimension minimumSize;

    /**
     * The preferred size.
     */
    private Dimension preferredSize;

    /**
     * The bounds mask param.
     */
    private int boundsMaskParam;

    /**
     * The ignore repaint.
     */
    private boolean ignoreRepaint;

    /**
     * The enabled.
     */
    private boolean enabled = true;

    /**
     * The input methods enabled.
     */
    private boolean inputMethodsEnabled = true;

    /**
     * The dispatch to im.
     */
    transient boolean dispatchToIM = true;

    /**
     * The focusable.
     */
    private boolean focusable = true; // By default, all Components return

    // true from isFocusable() method
    /**
     * The visible.
     */
    boolean visible = true;

    /**
     * The called set focusable.
     */
    private boolean calledSetFocusable;

    /**
     * The overridden is focusable.
     */
    private boolean overridenIsFocusable = true;

    /**
     * The focus traversal keys enabled.
     */
    private boolean focusTraversalKeysEnabled = true;

    /**
     * Possible keys are: FORWARD_TRAVERSAL_KEYS, BACKWARD_TRAVERSAL_KEYS,
     * UP_CYCLE_TRAVERSAL_KEYS.
     */
    private final Map<Integer, Set<? extends AWTKeyStroke>> traversalKeys = new HashMap<Integer, Set<? extends AWTKeyStroke>>();

    /**
     * The traversal i ds.
     */
    int[] traversalIDs;

    /**
     * The locale.
     */
    private Locale locale;

    /**
     * The orientation.
     */
    private ComponentOrientation orientation;

    /**
     * The property change support.
     */
    private PropertyChangeSupport propertyChangeSupport;

    // ???AWT: private ArrayList<PopupMenu> popups;

    /**
     * The coalescer.
     */
    private boolean coalescer;

    /**
     * The events table.
     */
    private Hashtable<Integer, LinkedList<AWTEvent>> eventsTable;

    /**
     * Cashed reference used during EventQueue.postEvent()
     */
    private LinkedList<AWTEvent> eventsList;

    /**
     * The hierarchy changing counter.
     */
    private int hierarchyChangingCounter;

    /**
     * The was showing.
     */
    private boolean wasShowing;

    /**
     * The was displayable.
     */
    private boolean wasDisplayable;

    /**
     * The cursor.
     */
    Cursor cursor;

    // ???AWT: DropTarget dropTarget;

    /**
     * The mouse exited expected.
     */
    private boolean mouseExitedExpected;

    /**
     * The repaint region.
     */
    transient MultiRectArea repaintRegion;

    // ???AWT: transient RedrawManager redrawManager;
    /**
     * The redraw manager.
     */
    transient Object redrawManager;

    /**
     * The valid.
     */
    private boolean valid;

    /**
     * The updated images.
     */
    private HashMap<Image, ImageParameters> updatedImages;

    /**
     * The lock object for private component's data which don't affect the
     * component hierarchy.
     */
    private class ComponentLock {
    }

    /**
     * The component lock.
     */
    private final transient Object componentLock = new ComponentLock();
    static {
        PrivilegedAction<String[]> action = new PrivilegedAction<String[]>() {
            public String[] run() {
                String properties[] = new String[2];
                properties[0] = System.getProperty("awt.image.redrawrate", "100"); //$NON-NLS-1$ //$NON-NLS-2$
                properties[1] = System.getProperty("awt.image.incrementaldraw", "true"); //$NON-NLS-1$ //$NON-NLS-2$
                return properties;
            }
        };
        String properties[] = AccessController.doPrivileged(action);
        // FIXME: rate is never used, can this code and the get property above
        // be removed?
        // int rate;
        //
        // try {
        // rate = Integer.decode(properties[0]).intValue();
        // } catch (NumberFormatException e) {
        // rate = 100;
        // }
        incrementalImageUpdate = properties[1].equals("true"); //$NON-NLS-1$
    }

    /**
     * Instantiates a new component.
     */
    protected Component() {
        toolkit.lockAWT();
        try {
            orientation = ComponentOrientation.UNKNOWN;
            redrawManager = null;
            // ???AWT
            /*
             * traversalIDs = this instanceof Container ?
             * KeyboardFocusManager.contTraversalIDs :
             * KeyboardFocusManager.compTraversalIDs; for (int element :
             * traversalIDs) { traversalKeys.put(new Integer(element), null); }
             * behaviour = createBehavior();
             */
            behaviour = null;

            deriveCoalescerFlag();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Determine that the class inherited from Component declares the method
     * coalesceEvents(), and put the results to the childClassesFlags map.
     */
    private void deriveCoalescerFlag() {
        Class<?> thisClass = getClass();
        boolean flag = true;
        synchronized (childClassesFlags) {
            Boolean flagWrapper = childClassesFlags.get(thisClass);
            if (flagWrapper == null) {
                Method coalesceMethod = null;
                for (Class<?> c = thisClass; c != Component.class; c = c.getSuperclass()) {
                    try {
                        coalesceMethod = c.getDeclaredMethod("coalesceEvents", new Class[] { //$NON-NLS-1$
                                        Class.forName("java.awt.AWTEvent"), //$NON-NLS-1$
                                        Class.forName("java.awt.AWTEvent")}); //$NON-NLS-1$
                    } catch (Exception e) {
                    }
                    if (coalesceMethod != null) {
                        break;
                    }
                }
                flag = (coalesceMethod != null);
                childClassesFlags.put(thisClass, Boolean.valueOf(flag));
            } else {
                flag = flagWrapper.booleanValue();
            }
        }
        coalescer = flag;
        if (flag) {
            eventsTable = new Hashtable<Integer, LinkedList<AWTEvent>>();
        } else {
            eventsTable = null;
        }
    }

    /**
     * Sets the name of the Component.
     * 
     * @param name
     *            the new name of the Component.
     */
    public void setName(String name) {
        String oldName;
        toolkit.lockAWT();
        try {
            autoName = false;
            oldName = this.name;
            this.name = name;
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("name", oldName, name); //$NON-NLS-1$
    }

    /**
     * Gets the name of this Component.
     * 
     * @return the name of this Component.
     */
    public String getName() {
        toolkit.lockAWT();
        try {
            if ((name == null) && autoName) {
                name = autoName();
            }
            return name;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Auto name.
     * 
     * @return the string.
     */
    String autoName() {
        String name = getClass().getName();
        if (name.indexOf("$") != -1) { //$NON-NLS-1$
            return null;
        }
        // ???AWT
        // int number = toolkit.autoNumber.nextComponent++;
        int number = 0;
        name = name.substring(name.lastIndexOf(".") + 1) + Integer.toString(number); //$NON-NLS-1$
        return name;
    }

    /**
     * Returns the string representation of the Component.
     * 
     * @return the string representation of the Component.
     */
    @Override
    public String toString() {
        /*
         * The format is based on 1.5 release behavior which can be revealed by
         * the following code: Component c = new Component(){};
         * c.setVisible(false); System.out.println(c);
         */
        toolkit.lockAWT();
        try {
            return getClass().getName() + "[" + paramString() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            toolkit.unlockAWT();
        }
    }

    // ???AWT
    /*
     * public void add(PopupMenu popup) { toolkit.lockAWT(); try { if
     * (popup.getParent() == this) { return; } if (popups == null) { popups =
     * new ArrayList<PopupMenu>(); } popup.setParent(this); popups.add(popup); }
     * finally { toolkit.unlockAWT(); } }
     */

    /**
     * Returns true, if the component contains the specified Point.
     * 
     * @param p
     *            the Point.
     * @return true, if the component contains the specified Point, false
     *         otherwise.
     */
    public boolean contains(Point p) {
        toolkit.lockAWT();
        try {
            return contains(p.x, p.y);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Returns true, if the component contains the point with the specified
     * coordinates.
     * 
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @return true, if the component contains the point with the specified
     *         coordinates, false otherwise.
     */
    public boolean contains(int x, int y) {
        toolkit.lockAWT();
        try {
            return inside(x, y);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by replaced by getSize() method.
     * 
     * @return the dimension.
     * @deprecated Replaced by getSize() method.
     */
    @Deprecated
    public Dimension size() {
        toolkit.lockAWT();
        try {
            return new Dimension(w, h);
        } finally {
            toolkit.unlockAWT();
        }
    }

    // ???AWT
    /*
     * public Container getParent() { toolkit.lockAWT(); try { return parent; }
     * finally { toolkit.unlockAWT(); } }
     */

    /**
     * List.
     * 
     * @param out
     *            the out.
     * @param indent
     *            the indent
     * @return the nearest heavyweight ancestor in hierarchy or
     *         <code>null</code> if not found.
     */
    // ???AWT
    /*
     * Component getHWAncestor() { return (parent != null ?
     * parent.getHWSurface() : null); }
     */

    /**
     * @return heavyweight component that is equal to or is a nearest
     *         heavyweight container of the current component, or
     *         <code>null</code> if not found.
     */
    // ???AWT
    /*
     * Component getHWSurface() { Component parent; for (parent = this; (parent
     * != null) && (parent.isLightweight()); parent = parent .getParent()) { ; }
     * return parent; } Window getWindowAncestor() { Component par; for (par =
     * this; par != null && !(par instanceof Window); par = par.getParent()) { ;
     * } return (Window) par; }
     */

    /**
     * To be called by container
     */
    // ???AWT
    /*
     * void setParent(Container parent) { this.parent = parent;
     * setRedrawManager(); } void setRedrawManager() { redrawManager =
     * getRedrawManager(); } public void remove(MenuComponent menu) {
     * toolkit.lockAWT(); try { if (menu.getParent() == this) {
     * menu.setParent(null); popups.remove(menu); } } finally {
     * toolkit.unlockAWT(); } }
     */
    /**
     * Prints a list of this component with the specified number of leading
     * whitespace characters to the specified PrintStream.
     * 
     * @param out
     *            the output PrintStream object.
     * @param indent
     *            how many leading whitespace characters to prepend.
     */
    public void list(PrintStream out, int indent) {
        toolkit.lockAWT();
        try {
            out.println(getIndentStr(indent) + this);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Prints a list of this component to the specified PrintWriter.
     * 
     * @param out
     *            the output PrintWriter object.
     */
    public void list(PrintWriter out) {
        toolkit.lockAWT();
        try {
            list(out, 1);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Prints a list of this component with the specified number of leading
     * whitespace characters to the specified PrintWriter.
     * 
     * @param out
     *            the output PrintWriter object.
     * @param indent
     *            how many leading whitespace characters to prepend.
     */
    public void list(PrintWriter out, int indent) {
        toolkit.lockAWT();
        try {
            out.println(getIndentStr(indent) + this);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets a string composed of the desired number of whitespace characters.
     * 
     * @param indent
     *            the length of the String to return.
     * @return the string composed of the desired number of whitespace
     *         characters.
     */
    String getIndentStr(int indent) {
        char[] ind = new char[indent];
        for (int i = 0; i < indent; ind[i++] = ' ') {
            ;
        }
        return new String(ind);
    }

    /**
     * Prints a list of this component to the specified PrintStream.
     * 
     * @param out
     *            the output PrintStream object.
     */
    public void list(PrintStream out) {
        toolkit.lockAWT();
        try {
            // default indent = 1
            list(out, 1);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Prints a list of this component to the standard system output stream.
     */
    public void list() {
        toolkit.lockAWT();
        try {
            list(System.out);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Prints this component.
     * 
     * @param g
     *            the Graphics to be used for painting.
     */
    public void print(Graphics g) {
        toolkit.lockAWT();
        try {
            paint(g);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Prints the component and all of its subcomponents.
     * 
     * @param g
     *            the Graphics to be used for painting.
     */
    public void printAll(Graphics g) {
        toolkit.lockAWT();
        try {
            paintAll(g);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the size of the Component specified by width and height parameters.
     * 
     * @param width
     *            the width of the Component.
     * @param height
     *            the height of the Component.
     */
    public void setSize(int width, int height) {
        toolkit.lockAWT();
        try {
            resize(width, height);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the size of the Component specified by Dimension object.
     * 
     * @param d
     *            the new size of the Component.
     */
    public void setSize(Dimension d) {
        toolkit.lockAWT();
        try {
            resize(d);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by setSize(int, int) method.
     * 
     * @param width
     *            the width.
     * @param height
     *            the height.
     * @deprecated Replaced by setSize(int, int) method.
     */
    @Deprecated
    public void resize(int width, int height) {
        toolkit.lockAWT();
        try {
            boundsMaskParam = NativeWindow.BOUNDS_NOMOVE;
            setBounds(x, y, width, height);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by setSize(int, int) method.
     * 
     * @param size
     *            the size.
     * @deprecated Replaced by setSize(int, int) method.
     */
    @Deprecated
    public void resize(Dimension size) {
        toolkit.lockAWT();
        try {
            setSize(size.width, size.height);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not this component is completely opaque.
     * 
     * @return true, if this component is completely opaque, false by default.
     */
    public boolean isOpaque() {
        toolkit.lockAWT();
        try {
            return behaviour.isOpaque();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Disables.
     * 
     * @deprecated Replaced by setEnabled(boolean) method.
     */
    @Deprecated
    public void disable() {
        toolkit.lockAWT();
        try {
            setEnabledImpl(false);
        } finally {
            toolkit.unlockAWT();
        }
        // ???AWT: fireAccessibleStateChange(AccessibleState.ENABLED, false);
    }

    /**
     * Enables this component.
     * 
     * @deprecated Replaced by setEnabled(boolean) method.
     */
    @Deprecated
    public void enable() {
        toolkit.lockAWT();
        try {
            setEnabledImpl(true);
        } finally {
            toolkit.unlockAWT();
        }
        // ???AWT: fireAccessibleStateChange(AccessibleState.ENABLED, true);
    }

    /**
     * Enables or disable this component.
     * 
     * @param b
     *            the boolean parameter.
     * @deprecated Replaced by setEnabled(boolean) method.
     */
    @Deprecated
    public void enable(boolean b) {
        toolkit.lockAWT();
        try {
            if (b) {
                enable();
            } else {
                disable();
            }
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Stores the location of this component to the specified Point object;
     * returns the point of the component's top-left corner.
     * 
     * @param rv
     *            the Point object where the component's top-left corner
     *            position will be stored.
     * @return the Point which specifies the component's top-left corner.
     */
    public Point getLocation(Point rv) {
        toolkit.lockAWT();
        try {
            if (rv == null) {
                rv = new Point();
            }
            rv.setLocation(getX(), getY());
            return rv;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the location of this component on the form; returns the point of the
     * component's top-left corner.
     * 
     * @return the Point which specifies the component's top-left corner.
     */
    public Point getLocation() {
        toolkit.lockAWT();
        try {
            return location();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the size of this Component.
     * 
     * @return the size of this Component.
     */
    public Dimension getSize() {
        toolkit.lockAWT();
        try {
            return size();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Stores the size of this Component to the specified Dimension object.
     * 
     * @param rv
     *            the Dimension object where the size of the Component will be
     *            stored.
     * @return the Dimension of this Component.
     */
    public Dimension getSize(Dimension rv) {
        toolkit.lockAWT();
        try {
            if (rv == null) {
                rv = new Dimension();
            }
            rv.setSize(getWidth(), getHeight());
            return rv;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not this Component is valid. A component is valid if it
     * is correctly sized and positioned within its parent container and all its
     * children are also valid.
     * 
     * @return true, if the Component is valid, false otherwise.
     */
    public boolean isValid() {
        toolkit.lockAWT();
        try {
            return valid && behaviour.isDisplayable();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by getComponentAt(int, int) method.
     * 
     * @return the Point.
     * @deprecated Replaced by getComponentAt(int, int) method.
     */
    @Deprecated
    public Point location() {
        toolkit.lockAWT();
        try {
            return new Point(x, y);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Connects this Component to a native screen resource and makes it
     * displayable. This method not be called directly by user applications.
     */
    public void addNotify() {
        toolkit.lockAWT();
        try {
            prepare4HierarchyChange();
            behaviour.addNotify();
            // ???AWT
            // finishHierarchyChange(this, parent, 0);
            // if (dropTarget != null) {
            // dropTarget.addNotify(peer);
            // }
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Map to display.
     * 
     * @param b
     *            the b.
     */
    void mapToDisplay(boolean b) {
        // ???AWT
        /*
         * if (b && !isDisplayable()) { if ((this instanceof Window) || ((parent
         * != null) && parent.isDisplayable())) { addNotify(); } } else if (!b
         * && isDisplayable()) { removeNotify(); }
         */
    }

    /**
     * Gets the toolkit.
     * 
     * @return accessible context specific for particular component.
     */
    // ???AWT
    /*
     * AccessibleContext createAccessibleContext() { return null; } public
     * AccessibleContext getAccessibleContext() { toolkit.lockAWT(); try { if
     * (accessibleContext == null) { accessibleContext =
     * createAccessibleContext(); } return accessibleContext; } finally {
     * toolkit.unlockAWT(); } }
     */

    /**
     * Gets Toolkit for the current Component.
     * 
     * @return the Toolkit of this Component.
     */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * Gets this component's locking object for AWT component tree and layout
     * operations.
     * 
     * @return the tree locking object.
     */
    public final Object getTreeLock() {
        return toolkit.awtTreeLock;
    }

    /**
     * Handles the event. Use ActionListener instead of this.
     * 
     * @param evt
     *            the Event.
     * @param what
     *            the event's key.
     * @return true, if successful.
     * @deprecated Use ActionListener class for registering event listener.
     */
    @Deprecated
    public boolean action(Event evt, Object what) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Gets the property change support.
     * 
     * @return the property change support.
     */
    private PropertyChangeSupport getPropertyChangeSupport() {
        synchronized (componentLock) {
            if (propertyChangeSupport == null) {
                propertyChangeSupport = new PropertyChangeSupport(this);
            }
            return propertyChangeSupport;
        }
    }

    // ???AWT
    /*
     * public void addPropertyChangeListener(PropertyChangeListener listener) {
     * getPropertyChangeSupport().addPropertyChangeListener(listener); } public
     * void addPropertyChangeListener(String propertyName,
     * PropertyChangeListener listener) {
     * getPropertyChangeSupport().addPropertyChangeListener(propertyName,
     * listener); } public void applyComponentOrientation(ComponentOrientation
     * orientation) { toolkit.lockAWT(); try {
     * setComponentOrientation(orientation); } finally { toolkit.unlockAWT(); }
     * }
     */

    /**
     * Returns true if the set of focus traversal keys for the given focus
     * traversal operation has been explicitly defined for this Component.
     * 
     * @param id
     *            the ID of traversal key.
     * @return true, if the set of focus traversal keys for the given focus.
     *         traversal operation has been explicitly defined for this
     *         Component, false otherwise.
     */
    public boolean areFocusTraversalKeysSet(int id) {
        toolkit.lockAWT();
        try {
            Integer Id = new Integer(id);
            if (traversalKeys.containsKey(Id)) {
                return traversalKeys.get(Id) != null;
            }
            // awt.14F=invalid focus traversal key identifier
            throw new IllegalArgumentException(Messages.getString("awt.14F")); //$NON-NLS-1$
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the bounds of the Component.
     * 
     * @return the rectangle bounds of the Component.
     * @deprecated Use getBounds() methood.
     */
    @Deprecated
    public Rectangle bounds() {
        toolkit.lockAWT();
        try {
            return new Rectangle(x, y, w, h);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Returns the construction status of a specified image with the specified
     * width and height that is being created.
     * 
     * @param image
     *            the image to be checked.
     * @param width
     *            the width of scaled image which status is being checked, or
     *            -1.
     * @param height
     *            the height of scaled image which status is being checked, or
     *            -1.
     * @param observer
     *            the ImageObserver object to be notified while the image is
     *            being prepared.
     * @return the ImageObserver flags of the current state of the image data.
     */
    public int checkImage(Image image, int width, int height, ImageObserver observer) {
        toolkit.lockAWT();
        try {
            return toolkit.checkImage(image, width, height, observer);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Returns the construction status of a specified image that is being
     * created.
     * 
     * @param image
     *            the image to be checked.
     * @param observer
     *            the ImageObserver object to be notified while the image is
     *            being prepared.
     * @return the ImageObserver flags of the current state of the image data.
     */
    public int checkImage(Image image, ImageObserver observer) {
        toolkit.lockAWT();
        try {
            return toolkit.checkImage(image, -1, -1, observer);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Coalesces the existed event with new event.
     * 
     * @param existingEvent
     *            the existing event in the EventQueue.
     * @param newEvent
     *            the new event to be posted to the EventQueue.
     * @return the coalesced AWTEvent, or null if there is no coalescing done.
     */
    protected AWTEvent coalesceEvents(AWTEvent existingEvent, AWTEvent newEvent) {
        toolkit.lockAWT();
        try {
            // Nothing to do:
            // 1. Mouse events coalesced at WTK level
            // 2. Paint events handled by RedrawManager
            // This method is for overriding only
            return null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks if this Component is a coalescer.
     * 
     * @return true, if is coalescer.
     */
    boolean isCoalescer() {
        return coalescer;
    }

    /**
     * Gets the relative event.
     * 
     * @param id
     *            the id.
     * @return the relative event.
     */
    AWTEvent getRelativeEvent(int id) {
        Integer idWrapper = new Integer(id);
        eventsList = eventsTable.get(idWrapper);
        if (eventsList == null) {
            eventsList = new LinkedList<AWTEvent>();
            eventsTable.put(idWrapper, eventsList);
            return null;
        }
        if (eventsList.isEmpty()) {
            return null;
        }
        return eventsList.getLast();
    }

    /**
     * Adds the new event.
     * 
     * @param event
     *            the event.
     */
    void addNewEvent(AWTEvent event) {
        eventsList.addLast(event);
    }

    /**
     * Removes the relative event.
     */
    void removeRelativeEvent() {
        eventsList.removeLast();
    }

    /**
     * Removes the next event.
     * 
     * @param id
     *            the id.
     */
    void removeNextEvent(int id) {
        eventsTable.get(new Integer(id)).removeFirst();
    }

    /**
     * Creates the image with the specified ImageProducer.
     * 
     * @param producer
     *            the ImageProducer to be used for image creation.
     * @return the image with the specified ImageProducer.
     */
    public Image createImage(ImageProducer producer) {
        toolkit.lockAWT();
        try {
            return toolkit.createImage(producer);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Creates an off-screen drawable image to be used for double buffering.
     * 
     * @param width
     *            the width of the image.
     * @param height
     *            the height of the image.
     * @return the off-screen drawable image or null if the component is not
     *         displayable or GraphicsEnvironment.isHeadless() method returns
     *         true.
     */
    public Image createImage(int width, int height) {
        toolkit.lockAWT();
        try {
            if (!isDisplayable()) {
                return null;
            }
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc == null) {
                return null;
            }
            ColorModel cm = gc.getColorModel(Transparency.OPAQUE);
            WritableRaster wr = cm.createCompatibleWritableRaster(width, height);
            Image image = new BufferedImage(cm, wr, cm.isAlphaPremultiplied(), null);
            fillImageBackground(image, width, height);
            return image;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Creates an off-screen drawable image with the specified width, height and
     * ImageCapabilities.
     * 
     * @param width
     *            the width.
     * @param height
     *            the height.
     * @param caps
     *            the ImageCapabilities.
     * @return the volatile image.
     * @throws AWTException
     *             if an image with the specified capabilities cannot be
     *             created.
     */
    public VolatileImage createVolatileImage(int width, int height, ImageCapabilities caps)
            throws AWTException {
        toolkit.lockAWT();
        try {
            if (!isDisplayable()) {
                return null;
            }
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc == null) {
                return null;
            }
            VolatileImage image = gc.createCompatibleVolatileImage(width, height, caps);
            fillImageBackground(image, width, height);
            return image;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Creates a volatile off-screen drawable image which is used for double
     * buffering.
     * 
     * @param width
     *            the width of image.
     * @param height
     *            the height of image.
     * @return the volatile image a volatile off-screen drawable image which is
     *         used for double buffering or null if the component is not
     *         displayable, or GraphicsEnvironment.isHeadless() method returns
     *         true.
     */
    public VolatileImage createVolatileImage(int width, int height) {
        toolkit.lockAWT();
        try {
            if (!isDisplayable()) {
                return null;
            }
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc == null) {
                return null;
            }
            VolatileImage image = gc.createCompatibleVolatileImage(width, height);
            fillImageBackground(image, width, height);
            return image;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Fill the image being created by createImage() or createVolatileImage()
     * with the component's background color to prepare it for double-buffered
     * painting.
     * 
     * @param image
     *            the image.
     * @param width
     *            the width.
     * @param height
     *            the height.
     */
    private void fillImageBackground(Image image, int width, int height) {
        Graphics gr = image.getGraphics();
        gr.setColor(getBackground());
        gr.fillRect(0, 0, width, height);
        gr.dispose();
    }

    /**
     * Delivers event.
     * 
     * @param evt
     *            the event.
     * @deprecated Replaced by dispatchEvent(AWTEvent e) method.
     */
    @Deprecated
    public void deliverEvent(Event evt) {
        postEvent(evt);
    }

    /**
     * Prompts the layout manager to lay out this component.
     */
    public void doLayout() {
        toolkit.lockAWT();
        try {
            layout();
        } finally {
            toolkit.unlockAWT();
        }
        // Implemented in Container
    }

    /**
     * Fire property change impl.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the old value.
     * @param newValue
     *            the new value.
     */
    private void firePropertyChangeImpl(String propertyName, Object oldValue, Object newValue) {
        PropertyChangeSupport pcs;
        synchronized (componentLock) {
            if (propertyChangeSupport == null) {
                return;
            }
            pcs = propertyChangeSupport;
        }
        pcs.firePropertyChange(propertyName, oldValue, newValue);
    }

    /**
     * Reports a bound property changes for int properties.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the old property's value.
     * @param newValue
     *            the new property's value.
     */
    protected void firePropertyChange(String propertyName, int oldValue, int newValue) {
        firePropertyChangeImpl(propertyName, new Integer(oldValue), new Integer(newValue));
    }

    /**
     * Report a bound property change for a boolean-valued property.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the property's old value.
     * @param newValue
     *            the property's new value.
     */
    protected void firePropertyChange(String propertyName, boolean oldValue, boolean newValue) {
        firePropertyChangeImpl(propertyName, Boolean.valueOf(oldValue), Boolean.valueOf(newValue));
    }

    /**
     * Reports a bound property change for an Object-valued property.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the property's old value.
     * @param newValue
     *            the property's new value.
     */
    protected void firePropertyChange(final String propertyName, final Object oldValue,
            final Object newValue) {
        firePropertyChangeImpl(propertyName, oldValue, newValue);
    }

    /**
     * Report a bound property change for a byte-valued property.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the property's old value.
     * @param newValue
     *            the property's new value.
     */
    public void firePropertyChange(String propertyName, byte oldValue, byte newValue) {
        firePropertyChangeImpl(propertyName, new Byte(oldValue), new Byte(newValue));
    }

    /**
     * Report a bound property change for a char-valued property.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the old property's value.
     * @param newValue
     *            the new property's value.
     */
    public void firePropertyChange(String propertyName, char oldValue, char newValue) {
        firePropertyChangeImpl(propertyName, new Character(oldValue), new Character(newValue));
    }

    /**
     * Report a bound property change for a short-valued property.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the old property's value.
     * @param newValue
     *            the new property's value.
     */
    public void firePropertyChange(String propertyName, short oldValue, short newValue) {
        firePropertyChangeImpl(propertyName, new Short(oldValue), new Short(newValue));
    }

    /**
     * Report a bound property change for a long-valued property.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the old property's value.
     * @param newValue
     *            the new property's value.
     */
    public void firePropertyChange(String propertyName, long oldValue, long newValue) {
        firePropertyChangeImpl(propertyName, new Long(oldValue), new Long(newValue));
    }

    /**
     * Report a bound property change for a float-valued property.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the old property's value.
     * @param newValue
     *            the new property's value.
     */
    public void firePropertyChange(String propertyName, float oldValue, float newValue) {
        firePropertyChangeImpl(propertyName, new Float(oldValue), new Float(newValue));
    }

    /**
     * Report a bound property change for a double-valued property.
     * 
     * @param propertyName
     *            the property name.
     * @param oldValue
     *            the old property's value.
     * @param newValue
     *            the new property's value.
     */
    public void firePropertyChange(String propertyName, double oldValue, double newValue) {
        firePropertyChangeImpl(propertyName, new Double(oldValue), new Double(newValue));
    }

    /**
     * Gets the alignment along the x axis.
     * 
     * @return the alignment along the x axis.
     */
    public float getAlignmentX() {
        toolkit.lockAWT();
        try {
            return CENTER_ALIGNMENT;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the alignment along the y axis.
     * 
     * @return the alignment along y axis.
     */
    public float getAlignmentY() {
        toolkit.lockAWT();
        try {
            return CENTER_ALIGNMENT;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the background color for this component.
     * 
     * @return the background color for this component.
     */
    public Color getBackground() {
        toolkit.lockAWT();
        try {
            // ???AWT
            /*
             * if ((backColor == null) && (parent != null)) { return
             * parent.getBackground(); }
             */
            return backColor;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the bounding rectangle of this component.
     * 
     * @return the bounding rectangle of this component.
     */
    public Rectangle getBounds() {
        toolkit.lockAWT();
        try {
            return bounds();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Writes the data of the bounding rectangle to the specified Rectangle
     * object.
     * 
     * @param rv
     *            the Rectangle object where the bounding rectangle's data is
     *            stored.
     * @return the bounding rectangle.
     */
    public Rectangle getBounds(Rectangle rv) {
        toolkit.lockAWT();
        try {
            if (rv == null) {
                rv = new Rectangle();
            }
            rv.setBounds(x, y, w, h);
            return rv;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the color model of the Component.
     * 
     * @return the color model of the Component.
     */
    public ColorModel getColorModel() {
        toolkit.lockAWT();
        try {
            return getToolkit().getColorModel();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the Component which contains the specified Point.
     * 
     * @param p
     *            the Point.
     * @return the Component which contains the specified Point.
     */
    public Component getComponentAt(Point p) {
        toolkit.lockAWT();
        try {
            return getComponentAt(p.x, p.y);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the Component which contains the point with the specified
     * coordinates.
     * 
     * @param x
     *            the x coordinate of the point.
     * @param y
     *            the y coordinate of the point.
     * @return the Component which contains the point with the specified
     *         coordinates.
     */
    public Component getComponentAt(int x, int y) {
        toolkit.lockAWT();
        try {
            return locate(x, y);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the component's orientation.
     * 
     * @return the component's orientation.
     */
    public ComponentOrientation getComponentOrientation() {
        toolkit.lockAWT();
        try {
            return orientation;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the cursor of the Component.
     * 
     * @return the Cursor.
     */
    public Cursor getCursor() {
        toolkit.lockAWT();
        try {
            if (cursor != null) {
                return cursor;
                // ???AWT
                /*
                 * } else if (parent != null) { return parent.getCursor();
                 */
            }
            return Cursor.getDefaultCursor();
        } finally {
            toolkit.unlockAWT();
        }
    }

    // ???AWT
    /*
     * public DropTarget getDropTarget() { toolkit.lockAWT(); try { return
     * dropTarget; } finally { toolkit.unlockAWT(); } } public Container
     * getFocusCycleRootAncestor() { toolkit.lockAWT(); try { for (Container c =
     * parent; c != null; c = c.getParent()) { if (c.isFocusCycleRoot()) {
     * return c; } } return null; } finally { toolkit.unlockAWT(); } }
     * @SuppressWarnings("unchecked") public Set<AWTKeyStroke>
     * getFocusTraversalKeys(int id) { toolkit.lockAWT(); try { Integer kId =
     * new Integer(id); KeyboardFocusManager.checkTraversalKeysID(traversalKeys,
     * kId); Set<? extends AWTKeyStroke> keys = traversalKeys.get(kId); if (keys
     * == null && parent != null) { keys = parent.getFocusTraversalKeys(id); }
     * if (keys == null) { keys =
     * KeyboardFocusManager.getCurrentKeyboardFocusManager()
     * .getDefaultFocusTraversalKeys(id); } return (Set<AWTKeyStroke>) keys; }
     * finally { toolkit.unlockAWT(); } }
     */

    /**
     * Checks if the the focus traversal keys are enabled for this component.
     * 
     * @return true, if the the focus traversal keys are enabled for this
     *         component, false otherwise.
     */
    public boolean getFocusTraversalKeysEnabled() {
        toolkit.lockAWT();
        try {
            return focusTraversalKeysEnabled;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the font metrics of the specified Font.
     * 
     * @param f
     *            the Font.
     * @return the FontMetrics of the specified Font.
     */
    @SuppressWarnings("deprecation")
    public FontMetrics getFontMetrics(Font f) {
        return toolkit.getFontMetrics(f);
    }

    /**
     * Gets the foreground color of the Component.
     * 
     * @return the foreground color of the Component.
     */
    public Color getForeground() {
        toolkit.lockAWT();
        try {
            // ???AWT
            /*
             * if (foreColor == null && parent != null) { return
             * parent.getForeground(); }
             */
            return foreColor;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the Graphics of the Component or null if this Component is not
     * displayable.
     * 
     * @return the Graphics of the Component or null if this Component is not
     *         displayable.
     */
    public Graphics getGraphics() {
        toolkit.lockAWT();
        try {
            if (!isDisplayable()) {
                return null;
            }
            Graphics g = behaviour.getGraphics(0, 0, w, h);
            g.setColor(foreColor);
            g.setFont(font);
            return g;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the GraphicsConfiguration associated with this Component.
     * 
     * @return the GraphicsConfiguration associated with this Component.
     */
    public GraphicsConfiguration getGraphicsConfiguration() {
        // ???AWT
        /*
         * toolkit.lockAWT(); try { Window win = getWindowAncestor(); if (win ==
         * null) { return null; } return win.getGraphicsConfiguration(); }
         * finally { toolkit.unlockAWT(); }
         */
        return null;
    }

    /**
     * Gets the height of the Component.
     * 
     * @return the height of the Component.
     */
    public int getHeight() {
        toolkit.lockAWT();
        try {
            return h;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Returns true if paint messages received from the operating system should
     * be ignored.
     * 
     * @return true if paint messages received from the operating system should
     *         be ignored, false otherwise.
     */
    public boolean getIgnoreRepaint() {
        toolkit.lockAWT();
        try {
            return ignoreRepaint;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the input context of this component for handling the communication
     * with input methods when text is entered in this component.
     * 
     * @return the InputContext used by this Component or null if no context is
     *         specifined.
     */
    public InputContext getInputContext() {
        toolkit.lockAWT();
        try {
            // ???AWT
            /*
             * Container parent = getParent(); if (parent != null) { return
             * parent.getInputContext(); }
             */
            return null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the input method request handler which supports requests from input
     * methods for this component, or null for default.
     * 
     * @return the input method request handler which supports requests from
     *         input methods for this component, or null for default.
     */
    public InputMethodRequests getInputMethodRequests() {
        return null;
    }

    /**
     * Gets the locale of this Component.
     * 
     * @return the locale of this Component.
     */
    public Locale getLocale() {
        toolkit.lockAWT();
        try {
            // ???AWT
            /*
             * if (locale == null) { if (parent == null) { if (this instanceof
             * Window) { return Locale.getDefault(); } // awt.150=no parent
             * throw new
             * IllegalComponentStateException(Messages.getString("awt.150"));
             * //$NON-NLS-1$ } return getParent().getLocale(); }
             */
            return locale;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the location of this component in the form of a point specifying the
     * component's top-left corner in the screen's coordinate space.
     * 
     * @return the Point giving the component's location in the screen's
     *         coordinate space.
     * @throws IllegalComponentStateException
     *             if the component is not shown on the screen.
     */
    public Point getLocationOnScreen() throws IllegalComponentStateException {
        toolkit.lockAWT();
        try {
            Point p = new Point();
            if (isShowing()) {
                // ???AWT
                /*
                 * Component comp; for (comp = this; comp != null && !(comp
                 * instanceof Window); comp = comp .getParent()) {
                 * p.translate(comp.getX(), comp.getY()); } if (comp instanceof
                 * Window) { p.translate(comp.getX(), comp.getY()); }
                 */
                return p;
            }
            // awt.151=component must be showing on the screen to determine its
            // location
            throw new IllegalComponentStateException(Messages.getString("awt.151")); //$NON-NLS-1$
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the peer. This method should not be called directly by user
     * applications.
     * 
     * @return the ComponentPeer.
     * @deprecated Replaced by isDisplayable().
     */
    @Deprecated
    public ComponentPeer getPeer() {
        toolkit.lockAWT();
        try {
            if (behaviour.isDisplayable()) {
                return peer;
            }
            return null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets an array of the property change listeners registered to this
     * Component.
     * 
     * @return an array of the PropertyChangeListeners registered to this
     *         Component.
     */
    public PropertyChangeListener[] getPropertyChangeListeners() {
        return getPropertyChangeSupport().getPropertyChangeListeners();
    }

    /**
     * Gets an array of PropertyChangeListener objects registered to this
     * Component for the specified property.
     * 
     * @param propertyName
     *            the property name.
     * @return an array of PropertyChangeListener objects registered to this
     *         Component for the specified property.
     */
    public PropertyChangeListener[] getPropertyChangeListeners(String propertyName) {
        return getPropertyChangeSupport().getPropertyChangeListeners(propertyName);
    }

    /**
     * Gets the width of the Component.
     * 
     * @return the width of the Component.
     */
    public int getWidth() {
        toolkit.lockAWT();
        try {
            return w;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the x coordinate of the component's top-left corner.
     * 
     * @return the x coordinate of the component's top-left corner.
     */
    public int getX() {
        toolkit.lockAWT();
        try {
            return x;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the y coordinate of the component's top-left corner.
     * 
     * @return the y coordinate of the component's top-left corner.
     */
    public int getY() {
        toolkit.lockAWT();
        try {
            return y;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Got the focus.
     * 
     * @param evt
     *            the Event.
     * @param what
     *            the Object.
     * @return true, if successful.
     * @deprecated Replaced by processFocusEvent(FocusEvent) method.
     */
    @Deprecated
    public boolean gotFocus(Event evt, Object what) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Handles event.
     * 
     * @param evt
     *            the Event.
     * @return true, if successful.
     * @deprecated Replaced by processEvent(AWTEvent) method.
     */
    @Deprecated
    public boolean handleEvent(Event evt) {
        switch (evt.id) {
            case Event.ACTION_EVENT:
                return action(evt, evt.arg);
            case Event.GOT_FOCUS:
                return gotFocus(evt, null);
            case Event.LOST_FOCUS:
                return lostFocus(evt, null);
            case Event.MOUSE_DOWN:
                return mouseDown(evt, evt.x, evt.y);
            case Event.MOUSE_DRAG:
                return mouseDrag(evt, evt.x, evt.y);
            case Event.MOUSE_ENTER:
                return mouseEnter(evt, evt.x, evt.y);
            case Event.MOUSE_EXIT:
                return mouseExit(evt, evt.x, evt.y);
            case Event.MOUSE_MOVE:
                return mouseMove(evt, evt.x, evt.y);
            case Event.MOUSE_UP:
                return mouseUp(evt, evt.x, evt.y);
            case Event.KEY_ACTION:
            case Event.KEY_PRESS:
                return keyDown(evt, evt.key);
            case Event.KEY_ACTION_RELEASE:
            case Event.KEY_RELEASE:
                return keyUp(evt, evt.key);
        }
        return false;// event not handled
    }

    /**
     * Checks whether the Component is the focus owner or not.
     * 
     * @return true, if the Component is the focus owner, false otherwise.
     */
    public boolean hasFocus() {
        toolkit.lockAWT();
        try {
            // ???AWT: return isFocusOwner();
            return false;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Hides the Component.
     * 
     * @deprecated Replaced by setVisible(boolean) method.
     */
    @Deprecated
    public void hide() {
        toolkit.lockAWT();
        try {
            if (!visible) {
                return;
            }
            prepare4HierarchyChange();
            visible = false;
            moveFocusOnHide();
            behaviour.setVisible(false);
            postEvent(new ComponentEvent(this, ComponentEvent.COMPONENT_HIDDEN));
            // ???AWT: finishHierarchyChange(this, parent, 0);
            notifyInputMethod(null);
            // ???AWT: invalidateRealParent();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not the point with the specified coordinates belongs to
     * the Commponent.
     * 
     * @param x
     *            the x coordinate of the Point.
     * @param y
     *            the y coordinate of the Point.
     * @return true, if the point with the specified coordinates belongs to the
     *         Commponent, false otherwise.
     * @deprecated Replaced by contains(int, int) method.
     */
    @Deprecated
    public boolean inside(int x, int y) {
        toolkit.lockAWT();
        try {
            return x >= 0 && x < getWidth() && y >= 0 && y < getHeight();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Invalidates the component, this component and all parents above it are
     * marked as needing to be laid out.
     */
    public void invalidate() {
        toolkit.lockAWT();
        try {
            valid = false;
            resetDefaultSize();
            // ???AWT: invalidateRealParent();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not the background color is set to this Component.
     * 
     * @return true, if the background color is set to this Component, false
     *         otherwise.
     */
    public boolean isBackgroundSet() {
        toolkit.lockAWT();
        try {
            return backColor != null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not a cursor is set for the Component.
     * 
     * @return true, if a cursor is set for the Component, false otherwise.
     */
    public boolean isCursorSet() {
        toolkit.lockAWT();
        try {
            return cursor != null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not this Component is displayable.
     * 
     * @return true, if this Component is displayable, false otherwise.
     */
    public boolean isDisplayable() {
        toolkit.lockAWT();
        try {
            return behaviour.isDisplayable();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not this component is painted to an buffer which is
     * copied to the screen later.
     * 
     * @return true, if this component is painted to an buffer which is copied
     *         to the screen later, false otherwise.
     */
    public boolean isDoubleBuffered() {
        toolkit.lockAWT();
        try {
            // false by default
            return false;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not this Component is enabled.
     * 
     * @return true, if this Component is enabled, false otherwise.
     */
    public boolean isEnabled() {
        toolkit.lockAWT();
        try {
            return enabled;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * "Recursive" isEnabled().
     * 
     * @return true if not only component itself is enabled but its heavyweight
     *         parent is also "indirectly" enabled.
     */
    boolean isIndirectlyEnabled() {
        Component comp = this;
        while (comp != null) {
            if (!comp.isLightweight() && !comp.isEnabled()) {
                return false;
            }
            // ???AWT: comp = comp.getRealParent();
        }
        return true;
    }

    /**
     * Checks if the component is key enabled.
     * 
     * @return true, if the component is enabled and indirectly enabled.
     */
    boolean isKeyEnabled() {
        if (!isEnabled()) {
            return false;
        }
        return isIndirectlyEnabled();
    }

    /**
     * Gets only parent of a child component, but not owner of a window.
     * 
     * @return parent of child component, null if component is a top-level
     *         (Window instance).
     */
    // ???AWT
    /*
     * Container getRealParent() { return (!(this instanceof Window) ?
     * getParent() : null); } public boolean isFocusCycleRoot(Container
     * container) { toolkit.lockAWT(); try { return getFocusCycleRootAncestor()
     * == container; } finally { toolkit.unlockAWT(); } } public boolean
     * isFocusOwner() { toolkit.lockAWT(); try { return
     * KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() ==
     * this; } finally { toolkit.unlockAWT(); } }
     */

    /**
     * Checks whether or not this Component can be focusable.
     * 
     * @return true, if this Component can be focusable, false otherwise.
     * @deprecated Replaced by isFocusable().
     */
    @Deprecated
    public boolean isFocusTraversable() {
        toolkit.lockAWT();
        try {
            overridenIsFocusable = false;
            return focusable; // a Component must either be both focusable and
            // focus traversable, or neither
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks if this Component can be focusable or not.
     * 
     * @return true, if this Component can be focusable, false otherwise.
     */
    public boolean isFocusable() {
        toolkit.lockAWT();
        try {
            return isFocusTraversable();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks if the Font is set for this Component or not.
     * 
     * @return true, if the Font is set, false otherwise.
     */
    public boolean isFontSet() {
        toolkit.lockAWT();
        try {
            return font != null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks if foreground color is set for the Component or not.
     * 
     * @return true, if is foreground color is set for the Component, false
     *         otherwise.
     */
    public boolean isForegroundSet() {
        toolkit.lockAWT();
        try {
            return foreColor != null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Returns true if this component has a lightweight peer.
     * 
     * @return true, if this component has a lightweight peer, false if it has a
     *         native peer or no peer.
     */
    public boolean isLightweight() {
        toolkit.lockAWT();
        try {
            return behaviour.isLightweight();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not this Component is shown.
     * 
     * @return true, if this Component is shown, false otherwise.
     */
    public boolean isShowing() {
        // ???AWT
        /*
         * toolkit.lockAWT(); try { return (isVisible() && isDisplayable() &&
         * (parent != null) && parent.isShowing()); } finally {
         * toolkit.unlockAWT(); }
         */
        return false;
    }

    /**
     * Checks whether or not this Component is visible.
     * 
     * @return true, if the Component is visible, false otherwise.
     */
    public boolean isVisible() {
        toolkit.lockAWT();
        try {
            return visible;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by processKeyEvent(KeyEvent) method.
     * 
     * @param evt
     *            the Event.
     * @param key
     *            the key code.
     * @return true, if successful.
     * @deprecated Replaced by replaced by processKeyEvent(KeyEvent) method.
     */
    @Deprecated
    public boolean keyDown(Event evt, int key) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Deprecated: replaced by processKeyEvent(KeyEvent) method.
     * 
     * @param evt
     *            the Event.
     * @param key
     *            the key code.
     * @return true, if successful.
     * @deprecated Replaced by processKeyEvent(KeyEvent) method.
     */
    @Deprecated
    public boolean keyUp(Event evt, int key) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Deprecated: Replaced by doLayout() method.
     * 
     * @deprecated Replaced by doLayout() method.
     */
    @Deprecated
    public void layout() {
        toolkit.lockAWT();
        try {
            // Implemented in Container
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by getComponentAt(int, int) method.
     * 
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @return The component.
     * @deprecated Replaced by getComponentAt(int, int) method.
     */
    @Deprecated
    public Component locate(int x, int y) {
        toolkit.lockAWT();
        try {
            if (contains(x, y)) {
                return this;
            }
            return null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by processFocusEvent(FocusEvent).
     * 
     * @param evt
     *            the Event.
     * @param what
     *            the Object.
     * @return true, if successful.
     * @deprecated Replaced by processFocusEvent(FocusEvent).
     */
    @Deprecated
    public boolean lostFocus(Event evt, Object what) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Deprecated: replaced by processMouseEvent(MouseEvent) method.
     * 
     * @param evt
     *            the MouseEvent.
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @return true, if successful.
     * @deprecated Replaced by processMouseEvent(MouseEvent) method.
     */
    @Deprecated
    public boolean mouseDown(Event evt, int x, int y) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Deprecated: replaced by getMinimumSize() method.
     * 
     * @param evt
     *            the Event.
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @return true, if successful.
     * @deprecated Replaced by getMinimumSize() method.
     */
    @Deprecated
    public boolean mouseDrag(Event evt, int x, int y) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Replaced by processMouseEvent(MouseEvent) method.
     * 
     * @param evt
     *            the Event.
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @return true, if successful.
     * @deprecated replaced by processMouseEvent(MouseEvent) method.
     */
    @Deprecated
    public boolean mouseEnter(Event evt, int x, int y) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Replaced by processMouseEvent(MouseEvent) method.
     * 
     * @param evt
     *            the Event.
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @return true, if successful.
     * @deprecated Replaced by processMouseEvent(MouseEvent) method.
     */
    @Deprecated
    public boolean mouseExit(Event evt, int x, int y) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Replaced by processMouseEvent(MouseEvent) method.
     * 
     * @param evt
     *            the Event.
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @deprecated Replaced by processMouseEvent(MouseEvent) method.
     * @return true, if successful.
     */
    @Deprecated
    public boolean mouseMove(Event evt, int x, int y) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Replaced by processMouseEvent(MouseEvent) method.
     * 
     * @param evt
     *            the Event.
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @return true, if successful.
     * @deprecated Replaced by processMouseEvent(MouseEvent) method.
     */
    @Deprecated
    public boolean mouseUp(Event evt, int x, int y) {
        // to be overridden: do nothing,
        // just return false to propagate event up to the parent container
        return false;
    }

    /**
     * Deprecated: replaced by setLocation(int, int) method.
     * 
     * @param x
     *            the x coordinates.
     * @param y
     *            the y coordinates.
     * @deprecated Replaced by setLocation(int, int) method.
     */
    @Deprecated
    public void move(int x, int y) {
        toolkit.lockAWT();
        try {
            boundsMaskParam = NativeWindow.BOUNDS_NOSIZE;
            setBounds(x, y, w, h);
        } finally {
            toolkit.unlockAWT();
        }
    }

    // ???AWT
    /*
     * @Deprecated public void nextFocus() { toolkit.lockAWT(); try {
     * transferFocus(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS); } finally {
     * toolkit.unlockAWT(); } }
     */

    /**
     * Returns a string representation of the component's state.
     * 
     * @return the string representation of the component's state.
     */
    protected String paramString() {
        /*
         * The format is based on 1.5 release behavior which can be revealed by
         * the following code: Component c = new Component(){};
         * c.setVisible(false); System.out.println(c);
         */
        toolkit.lockAWT();
        try {
            return getName() + "," + getX() + "," + getY() + "," + getWidth() + "x" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    + getHeight() + (!isVisible() ? ",hidden" : ""); //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            toolkit.unlockAWT();
        }
    }

    @Deprecated
    @SuppressWarnings("deprecation")
    public boolean postEvent(Event evt) {
        boolean handled = handleEvent(evt);
        if (handled) {
            return true;
        }
        // ???AWT
        /*
         * // propagate non-handled events up to parent Component par = parent;
         * // try to call postEvent only on components which // override any of
         * deprecated method handlers // while (par != null &&
         * !par.deprecatedEventHandler) { // par = par.parent; // } // translate
         * event coordinates before posting it to parent if (par != null) {
         * evt.translate(x, y); par.postEvent(evt); }
         */
        return false;
    }

    /**
     * Prepares an image for rendering on the Component.
     * 
     * @param image
     *            the Image to be prepared.
     * @param observer
     *            the ImageObserver object to be notified as soon as the image
     *            is prepared.
     * @return true if the image has been fully prepared, false otherwise.
     */
    public boolean prepareImage(Image image, ImageObserver observer) {
        toolkit.lockAWT();
        try {
            return toolkit.prepareImage(image, -1, -1, observer);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Prepares an image for rendering on the Component with the specified
     * width, height, and ImageObserver.
     * 
     * @param image
     *            the Image to be prepared.
     * @param width
     *            the width of scaled image.
     * @param height
     *            the height of scaled height.
     * @param observer
     *            the ImageObserver object to be notified as soon as the image
     *            is prepared.
     * @return true if the image is been fully prepared, false otherwise.
     */
    public boolean prepareImage(Image image, int width, int height, ImageObserver observer) {
        toolkit.lockAWT();
        try {
            return toolkit.prepareImage(image, width, height, observer);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Makes this Component undisplayable.
     */
    public void removeNotify() {
        toolkit.lockAWT();
        try {
            // ???AWT
            /*
             * if (dropTarget != null) { dropTarget.removeNotify(peer); }
             */
            prepare4HierarchyChange();
            // /???AWT: moveFocus();
            behaviour.removeNotify();
            // ???AWT: finishHierarchyChange(this, parent, 0);
            removeNotifyInputContext();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Calls InputContext.removeNotify.
     */
    private void removeNotifyInputContext() {
        if (!inputMethodsEnabled) {
            return;
        }
        InputContext ic = getInputContext();
        if (ic != null) {
            // ???AWT: ic.removeNotify(this);
        }
    }

    /**
     * This method is called when some property of a component changes, making
     * it unfocusable, e. g. hide(), removeNotify(), setEnabled(false),
     * setFocusable(false) is called, and therefore automatic forward focus
     * traversal is necessary
     */
    // ???AWT
    /*
     * void moveFocus() { // don't use transferFocus(), but query focus
     * traversal policy directly // and if it returns null, transfer focus up
     * cycle // and find next focusable component there KeyboardFocusManager kfm
     * = KeyboardFocusManager.getCurrentKeyboardFocusManager(); Container root =
     * kfm.getCurrentFocusCycleRoot(); Component nextComp = this; boolean
     * success = !isFocusOwner(); while (!success) { if (root !=
     * nextComp.getFocusCycleRootAncestor()) { // component was probably removed
     * from container // so focus will be lost in some time return; } nextComp =
     * root.getFocusTraversalPolicy().getComponentAfter(root, nextComp); if
     * (nextComp == this) { nextComp = null; // avoid looping } if (nextComp !=
     * null) { success = nextComp.requestFocusInWindow(); } else { nextComp =
     * root; root = root.getFocusCycleRootAncestor(); // if no acceptable
     * component is found at all - clear global // focus owner if (root == null)
     * { if (nextComp instanceof Window) { Window wnd = (Window) nextComp;
     * wnd.setFocusOwner(null); wnd.setRequestedFocus(null); }
     * kfm.clearGlobalFocusOwner(); return; } } } }
     */

    /**
     * For Container there's a difference between moving focus when being made
     * invisible or made unfocusable in some other way, because when container
     * is made invisible, component still remains visible, i. e. its hide() or
     * setVisible() is not called.
     */
    void moveFocusOnHide() {
        // ???AWT: moveFocus();
    }

    /**
     * Removes the property change listener registered for this component.
     * 
     * @param listener
     *            the PropertyChangeListener.
     */
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        getPropertyChangeSupport().removePropertyChangeListener(listener);
    }

    /**
     * Removes the property change listener registered fot this component for
     * the specified propertyy.
     * 
     * @param propertyName
     *            the property name.
     * @param listener
     *            the PropertyChangeListener.
     */
    public void removePropertyChangeListener(String propertyName, PropertyChangeListener listener) {
        getPropertyChangeSupport().removePropertyChangeListener(propertyName, listener);
    }

    /**
     * Repaints the specified rectangle of this component within tm
     * milliseconds.
     * 
     * @param tm
     *            the time in milliseconds before updating.
     * @param x
     *            the x coordinate of Rectangle.
     * @param y
     *            the y coordinate of Rectangle.
     * @param width
     *            the width of Rectangle.
     * @param height
     *            the height of Rectangle.
     */
    public void repaint(long tm, int x, int y, int width, int height) {
        // ???AWT
        /*
         * toolkit.lockAWT(); try { if (width <= 0 || height <= 0 ||
         * (redrawManager == null) || !isShowing()) { return; } if (behaviour
         * instanceof LWBehavior) { if (parent == null || !parent.visible ||
         * !parent.behaviour.isDisplayable()) { return; } if (repaintRegion ==
         * null) { repaintRegion = new MultiRectArea(new Rectangle(x, y, width,
         * height)); } repaintRegion.intersect(new Rectangle(0, 0, this.w,
         * this.h)); repaintRegion.translate(this.x, this.y);
         * parent.repaintRegion = repaintRegion; repaintRegion = null;
         * parent.repaint(tm, x + this.x, y + this.y, width, height); } else {
         * if (repaintRegion != null) { redrawManager.addUpdateRegion(this,
         * repaintRegion); repaintRegion = null; } else {
         * redrawManager.addUpdateRegion(this, new Rectangle(x, y, width,
         * height)); }
         * toolkit.getSystemEventQueueCore().notifyEventMonitor(toolkit); } }
         * finally { toolkit.unlockAWT(); }
         */
    }

    /**
     * Post event.
     * 
     * @param e
     *            the e.
     */
    void postEvent(AWTEvent e) {
        getToolkit().getSystemEventQueueImpl().postEvent(e);
    }

    /**
     * Repaints the specified Rectangle of this Component.
     * 
     * @param x
     *            the x coordinate of Rectangle.
     * @param y
     *            the y coordinate of Rectangle.
     * @param width
     *            the width of Rectangle.
     * @param height
     *            the height of Rectangle.
     */
    public void repaint(int x, int y, int width, int height) {
        toolkit.lockAWT();
        try {
            repaint(0, x, y, width, height);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Repaints this component.
     */
    public void repaint() {
        toolkit.lockAWT();
        try {
            if (w > 0 && h > 0) {
                repaint(0, 0, 0, w, h);
            }
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Repaints the component within tm milliseconds.
     * 
     * @param tm
     *            the time in milliseconds before updating.
     */
    public void repaint(long tm) {
        toolkit.lockAWT();
        try {
            repaint(tm, 0, 0, w, h);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Requests that this Component get the input focus temporarily. This
     * component must be displayable, visible, and focusable.
     * 
     * @param temporary
     *            this parameter is true if the focus change is temporary, when
     *            the window loses the focus.
     * @return true if the focus change request is succeeded, false otherwise.
     */
    protected boolean requestFocus(boolean temporary) {
        toolkit.lockAWT();
        try {
            // ???AWT: return requestFocusImpl(temporary, true, false);
        } finally {
            toolkit.unlockAWT();
        }
        // ???AWT
        return false;
    }

    /**
     * Requests that this Component get the input focus. This component must be
     * displayable, visible, and focusable.
     */
    public void requestFocus() {
        toolkit.lockAWT();
        try {
            requestFocus(false);
        } finally {
            toolkit.unlockAWT();
        }
    }

    // ???AWT
    /*
     * protected boolean requestFocusInWindow(boolean temporary) {
     * toolkit.lockAWT(); try { Window wnd = getWindowAncestor(); if ((wnd ==
     * null) || !wnd.isFocused()) { return false; } return
     * requestFocusImpl(temporary, false, false); } finally {
     * toolkit.unlockAWT(); } } boolean requestFocusImpl(boolean temporary,
     * boolean crossWindow, boolean rejectionRecovery) { if (!rejectionRecovery
     * && isFocusOwner()) { return true; } Window wnd = getWindowAncestor();
     * Container par = getRealParent(); if ((par != null) && par.isRemoved) {
     * return false; } if (!isShowing() || !isFocusable() ||
     * !wnd.isFocusableWindow()) { return false; } return
     * KeyboardFocusManager.getCurrentKeyboardFocusManager().requestFocus(this,
     * temporary, crossWindow, true); } public boolean requestFocusInWindow() {
     * toolkit.lockAWT(); try { return requestFocusInWindow(false); } finally {
     * toolkit.unlockAWT(); } }
     */

    /**
     * Deprecated: replaced by setBounds(int, int, int, int) method.
     * 
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @param w
     *            the width.
     * @param h
     *            the height.
     * @deprecated Replaced by setBounds(int, int, int, int) method.
     */
    @Deprecated
    public void reshape(int x, int y, int w, int h) {
        toolkit.lockAWT();
        try {
            setBounds(x, y, w, h, boundsMaskParam, true);
            boundsMaskParam = 0;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets rectangle for this Component to be the rectangle with the specified
     * x,y coordinates of the top-left corner and the width and height.
     * 
     * @param x
     *            the x coordinate of the rectangle's top-left corner.
     * @param y
     *            the y coordinate of the rectangle's top-left corner.
     * @param w
     *            the width of rectangle.
     * @param h
     *            the height of rectangle.
     */
    public void setBounds(int x, int y, int w, int h) {
        toolkit.lockAWT();
        try {
            reshape(x, y, w, h);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets rectangle for this Component to be the rectangle with the specified
     * x,y coordinates of the top-left corner and the width and height and posts
     * the appropriate events.
     * 
     * @param x
     *            the x coordinate of the rectangle's top-left corner.
     * @param y
     *            the y coordinate of the rectangle's top-left corner.
     * @param w
     *            the width of rectangle.
     * @param h
     *            the height of rectangle.
     * @param bMask
     *            the bitmask of bounds options.
     * @param updateBehavior
     *            the whether to update the behavoir's bounds as well.
     */
    void setBounds(int x, int y, int w, int h, int bMask, boolean updateBehavior) {
        int oldX = this.x;
        int oldY = this.y;
        int oldW = this.w;
        int oldH = this.h;
        setBoundsFields(x, y, w, h, bMask);
        // Moved
        if ((oldX != this.x) || (oldY != this.y)) {
            // ???AWT: invalidateRealParent();
            postEvent(new ComponentEvent(this, ComponentEvent.COMPONENT_MOVED));
            spreadHierarchyBoundsEvents(this, HierarchyEvent.ANCESTOR_MOVED);
        }
        // Resized
        if ((oldW != this.w) || (oldH != this.h)) {
            invalidate();
            postEvent(new ComponentEvent(this, ComponentEvent.COMPONENT_RESIZED));
            spreadHierarchyBoundsEvents(this, HierarchyEvent.ANCESTOR_RESIZED);
        }
        if (updateBehavior) {
            behaviour.setBounds(this.x, this.y, this.w, this.h, bMask);
        }
        notifyInputMethod(new Rectangle(x, y, w, h));
    }

    /**
     * Calls InputContextImpl.notifyClientWindowChanged.
     * 
     * @param bounds
     *            the bounds.
     */
    void notifyInputMethod(Rectangle bounds) {
        // only Window actually notifies IM of bounds change
    }

    /**
     * Sets the bounds fields.
     * 
     * @param x
     *            the x.
     * @param y
     *            the y.
     * @param w
     *            the w.
     * @param h
     *            the h.
     * @param bMask
     *            the b mask.
     */
    private void setBoundsFields(int x, int y, int w, int h, int bMask) {
        if ((bMask & NativeWindow.BOUNDS_NOSIZE) == 0) {
            this.w = w;
            this.h = h;
        }
        if ((bMask & NativeWindow.BOUNDS_NOMOVE) == 0) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Gets the native insets.
     * 
     * @return the native insets.
     */
    Insets getNativeInsets() {
        return new Insets(0, 0, 0, 0);
    }

    /**
     * Gets the insets.
     * 
     * @return the insets.
     */
    Insets getInsets() {
        return new Insets(0, 0, 0, 0);
    }

    /**
     * Checks if is mouse exited expected.
     * 
     * @return true, if is mouse exited expected.
     */
    boolean isMouseExitedExpected() {
        return mouseExitedExpected;
    }

    /**
     * Sets the mouse exited expected.
     * 
     * @param expected
     *            the new mouse exited expected.
     */
    void setMouseExitedExpected(boolean expected) {
        mouseExitedExpected = expected;
    }

    /**
     * Sets the new bounding rectangle for this Component.
     * 
     * @param r
     *            the new bounding rectangle.
     */
    public void setBounds(Rectangle r) {
        toolkit.lockAWT();
        try {
            setBounds(r.x, r.y, r.width, r.height);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the component orientation which affects the component's elements and
     * text within this component.
     * 
     * @param o
     *            the ComponentOrientation object.
     */
    public void setComponentOrientation(ComponentOrientation o) {
        ComponentOrientation oldOrientation;
        toolkit.lockAWT();
        try {
            oldOrientation = orientation;
            orientation = o;
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("componentOrientation", oldOrientation, orientation); //$NON-NLS-1$
        invalidate();
    }

    /**
     * Sets the specified cursor for this Component.
     * 
     * @param cursor
     *            the new Cursor.
     */
    public void setCursor(Cursor cursor) {
        toolkit.lockAWT();
        try {
            this.cursor = cursor;
            setCursor();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Set current cursor shape to Component's Cursor.
     */
    void setCursor() {
        if (isDisplayable() && isShowing()) {
            Rectangle absRect = new Rectangle(getLocationOnScreen(), getSize());
            Point absPointerPos = toolkit.dispatcher.mouseDispatcher.getPointerPos();
            // ???AWT
            /*
             * if (absRect.contains(absPointerPos)) { // set Cursor only on
             * top-level Windows(on X11) Window topLevelWnd =
             * getWindowAncestor(); if (topLevelWnd != null) { Point pointerPos
             * = MouseDispatcher.convertPoint(null, absPointerPos, topLevelWnd);
             * Component compUnderCursor =
             * topLevelWnd.findComponentAt(pointerPos); // if (compUnderCursor
             * == this || // compUnderCursor.getCursorAncestor() == this) {
             * NativeWindow wnd = topLevelWnd.getNativeWindow(); if
             * (compUnderCursor != null && wnd != null) {
             * compUnderCursor.getRealCursor().getNativeCursor()
             * .setCursor(wnd.getId()); } // } } }
             */
        }
    }

    /**
     * Gets the ancestor Cursor if Component is disabled (directly or via an
     * ancestor) even if Cursor is explicitly set.
     * 
     * @param value
     *            the value.
     * @return the actual Cursor to be displayed.
     */
    // ???AWT
    /*
     * Cursor getRealCursor() { Component cursorAncestor = getCursorAncestor();
     * return cursorAncestor != null ? cursorAncestor.getCursor() :
     * Cursor.getDefaultCursor(); }
     */

    /**
     * Gets the ancestor(or component itself) whose cursor is set when pointer
     * is inside component
     * 
     * @return the actual Cursor to be displayed.
     */
    // ???AWT
    /*
     * Component getCursorAncestor() { Component comp; for (comp = this; comp !=
     * null; comp = comp.getParent()) { if (comp instanceof Window ||
     * comp.isCursorSet() && comp.isKeyEnabled()) { return comp; } } return
     * null; } public void setDropTarget(DropTarget dt) { toolkit.lockAWT(); try
     * { if (dropTarget == dt) { return; } DropTarget oldDropTarget =
     * dropTarget; dropTarget = dt; if (oldDropTarget != null) { if
     * (behaviour.isDisplayable()) { oldDropTarget.removeNotify(peer); }
     * oldDropTarget.setComponent(null); } if (dt != null) {
     * dt.setComponent(this); if (behaviour.isDisplayable()) {
     * dt.addNotify(peer); } } } finally { toolkit.unlockAWT(); } }
     */

    /**
     * Sets this component to the "enabled" or "disabled" state depending on the
     * specified boolean parameter.
     * 
     * @param value
     *            true if this component should be enabled; false if this
     *            component should be disabled.
     */
    public void setEnabled(boolean value) {
        toolkit.lockAWT();
        try {
            enable(value);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the enabled impl.
     * 
     * @param value
     *            the new enabled impl.
     */
    void setEnabledImpl(boolean value) {
        if (enabled != value) {
            enabled = value;
            setCursor();
            if (!enabled) {
                moveFocusOnHide();
            }
            behaviour.setEnabled(value);
        }
    }

    // ???AWT
    /*
     * private void fireAccessibleStateChange(AccessibleState state, boolean
     * value) { if (behaviour.isLightweight()) { return; } AccessibleContext ac
     * = getAccessibleContext(); if (ac != null) { AccessibleState oldValue =
     * null; AccessibleState newValue = null; if (value) { newValue = state; }
     * else { oldValue = state; }
     * ac.firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY,
     * oldValue, newValue); } }
     */

    // ???AWT
    /*
     * public void setFocusTraversalKeys(int id, Set<? extends AWTKeyStroke>
     * keystrokes) { Set<? extends AWTKeyStroke> oldTraversalKeys; String
     * propName = "FocusTraversalKeys"; //$NON-NLS-1$ toolkit.lockAWT(); try {
     * Integer kId = new Integer(id);
     * KeyboardFocusManager.checkTraversalKeysID(traversalKeys, kId);
     * Map<Integer, Set<? extends AWTKeyStroke>> keys = new HashMap<Integer,
     * Set<? extends AWTKeyStroke>>(); for (int kid : traversalIDs) { Integer
     * key = new Integer(kid); keys.put(key, getFocusTraversalKeys(kid)); }
     * KeyboardFocusManager.checkKeyStrokes(traversalIDs, keys, kId,
     * keystrokes); oldTraversalKeys = traversalKeys.get(new Integer(id)); //
     * put a copy of keystrokes object into map: Set<? extends AWTKeyStroke>
     * newKeys = keystrokes; if (keystrokes != null) { newKeys = new
     * HashSet<AWTKeyStroke>(keystrokes); } traversalKeys.put(kId, newKeys);
     * String direction = ""; //$NON-NLS-1$ switch (id) { case
     * KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS: direction = "forward";
     * //$NON-NLS-1$ break; case KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS:
     * direction = "backward"; //$NON-NLS-1$ break; case
     * KeyboardFocusManager.UP_CYCLE_TRAVERSAL_KEYS: direction = "upCycle";
     * //$NON-NLS-1$ break; case KeyboardFocusManager.DOWN_CYCLE_TRAVERSAL_KEYS:
     * direction = "downCycle"; //$NON-NLS-1$ break; } propName = direction +
     * propName; } finally { toolkit.unlockAWT(); } firePropertyChange(propName,
     * oldTraversalKeys, keystrokes); }
     */

    /**
     * Sets the focus traversal keys state for this component.
     * 
     * @param value
     *            true if the focus traversal keys state is enabled, false if
     *            the focus traversal keys state is disabled.
     */
    public void setFocusTraversalKeysEnabled(boolean value) {
        boolean oldFocusTraversalKeysEnabled;
        toolkit.lockAWT();
        try {
            oldFocusTraversalKeysEnabled = focusTraversalKeysEnabled;
            focusTraversalKeysEnabled = value;
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("focusTraversalKeysEnabled", oldFocusTraversalKeysEnabled, //$NON-NLS-1$
                focusTraversalKeysEnabled);
    }

    // ???AWT
    /*
     * public void setFocusable(boolean focusable) { boolean oldFocusable;
     * toolkit.lockAWT(); try { calledSetFocusable = true; oldFocusable =
     * this.focusable; this.focusable = focusable; if (!focusable) {
     * moveFocus(); } } finally { toolkit.unlockAWT(); }
     * firePropertyChange("focusable", oldFocusable, focusable); //$NON-NLS-1$ }
     * public Font getFont() { toolkit.lockAWT(); try { return (font == null) &&
     * (parent != null) ? parent.getFont() : font; } finally {
     * toolkit.unlockAWT(); } }
     */

    /**
     * Sets the font for this Component.
     * 
     * @param f
     *            the new font of the Component.
     */
    public void setFont(Font f) {
        Font oldFont;
        toolkit.lockAWT();
        try {
            oldFont = font;
            setFontImpl(f);
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("font", oldFont, font); //$NON-NLS-1$
    }

    /**
     * Sets the font impl.
     * 
     * @param f
     *            the new font impl.
     */
    void setFontImpl(Font f) {
        font = f;
        invalidate();
        if (isShowing()) {
            repaint();
        }
    }

    /**
     * Invalidate the component if it inherits the font from the parent. This
     * method is overridden in Container.
     * 
     * @return true if the component was invalidated, false otherwise.
     */
    boolean propagateFont() {
        if (font == null) {
            invalidate();
            return true;
        }
        return false;
    }

    /**
     * Sets the foreground color for this Component.
     * 
     * @param c
     *            the new foreground color.
     */
    public void setForeground(Color c) {
        Color oldFgColor;
        toolkit.lockAWT();
        try {
            oldFgColor = foreColor;
            foreColor = c;
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("foreground", oldFgColor, foreColor); //$NON-NLS-1$
        repaint();
    }

    /**
     * Sets the background color for the Component.
     * 
     * @param c
     *            the new background color for this component.
     */
    public void setBackground(Color c) {
        Color oldBkColor;
        toolkit.lockAWT();
        try {
            oldBkColor = backColor;
            backColor = c;
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("background", oldBkColor, backColor); //$NON-NLS-1$
        repaint();
    }

    /**
     * Sets the flag for whether paint messages received from the operating
     * system should be ignored or not.
     * 
     * @param value
     *            true if paint messages received from the operating system
     *            should be ignored, false otherwise.
     */
    public void setIgnoreRepaint(boolean value) {
        toolkit.lockAWT();
        try {
            ignoreRepaint = value;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the locale of the component.
     * 
     * @param locale
     *            the new Locale.
     */
    public void setLocale(Locale locale) {
        Locale oldLocale;
        toolkit.lockAWT();
        try {
            oldLocale = this.locale;
            this.locale = locale;
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("locale", oldLocale, locale); //$NON-NLS-1$
    }

    /**
     * Sets the location of the Component to the specified point.
     * 
     * @param p
     *            the new location of the Component.
     */
    public void setLocation(Point p) {
        toolkit.lockAWT();
        try {
            setLocation(p.x, p.y);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the location of the Component to the specified x, y coordinates.
     * 
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     */
    public void setLocation(int x, int y) {
        toolkit.lockAWT();
        try {
            move(x, y);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the visibility state of the component.
     * 
     * @param b
     *            true if the component is visible, false if the component is
     *            not shown.
     */
    public void setVisible(boolean b) {
        // show() & hide() are not deprecated for Window,
        // so have to call them from setVisible()
        show(b);
    }

    /**
     * Deprecated: replaced by setVisible(boolean) method.
     * 
     * @deprecated Replaced by setVisible(boolean) method.
     */
    @Deprecated
    public void show() {
        toolkit.lockAWT();
        try {
            if (visible) {
                return;
            }
            prepare4HierarchyChange();
            mapToDisplay(true);
            validate();
            visible = true;
            behaviour.setVisible(true);
            postEvent(new ComponentEvent(this, ComponentEvent.COMPONENT_SHOWN));
            // ???AWT: finishHierarchyChange(this, parent, 0);
            notifyInputMethod(new Rectangle(x, y, w, h));
            // ???AWT: invalidateRealParent();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by setVisible(boolean) method.
     * 
     * @param b
     *            the visibility's state.
     * @deprecated Replaced by setVisible(boolean) method.
     */
    @Deprecated
    public void show(boolean b) {
        if (b) {
            show();
        } else {
            hide();
        }
    }

    // ???AWT
    /*
     * void transferFocus(int dir) { Container root = null; if (this instanceof
     * Container) { Container cont = (Container) this; if
     * (cont.isFocusCycleRoot()) { root = cont.getFocusTraversalRoot(); } } if
     * (root == null) { root = getFocusCycleRootAncestor(); } // transfer focus
     * up cycle if root is unreachable Component comp = this; while ((root !=
     * null) && !(root.isFocusCycleRoot() && root.isShowing() &&
     * root.isEnabled() && root .isFocusable())) { comp = root; root =
     * root.getFocusCycleRootAncestor(); } if (root == null) { return; }
     * FocusTraversalPolicy policy = root.getFocusTraversalPolicy(); Component
     * nextComp = null; switch (dir) { case
     * KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS: nextComp =
     * policy.getComponentAfter(root, comp); break; case
     * KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS: nextComp =
     * policy.getComponentBefore(root, comp); break; } if (nextComp != null) {
     * nextComp.requestFocus(false); } } public void transferFocus() {
     * toolkit.lockAWT(); try { nextFocus(); } finally { toolkit.unlockAWT(); }
     * } public void transferFocusBackward() { toolkit.lockAWT(); try {
     * transferFocus(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS); } finally {
     * toolkit.unlockAWT(); } } public void transferFocusUpCycle() {
     * toolkit.lockAWT(); try { KeyboardFocusManager kfm =
     * KeyboardFocusManager.getCurrentKeyboardFocusManager(); Container root =
     * kfm.getCurrentFocusCycleRoot(); if(root == null) { return; } boolean
     * success = false; Component nextComp = null; Container newRoot = root; do
     * { nextComp = newRoot instanceof Window ?
     * newRoot.getFocusTraversalPolicy() .getDefaultComponent(newRoot) :
     * newRoot; newRoot = newRoot.getFocusCycleRootAncestor(); if (nextComp ==
     * null) { break; } success = nextComp.requestFocusInWindow(); if (newRoot
     * == null) { break; } kfm.setGlobalCurrentFocusCycleRoot(newRoot); } while
     * (!success); if (!success && root != newRoot) {
     * kfm.setGlobalCurrentFocusCycleRoot(root); } } finally {
     * toolkit.unlockAWT(); } }
     */

    /**
     * Validates that this component has a valid layout.
     */
    public void validate() {
        toolkit.lockAWT();
        try {
            if (!behaviour.isDisplayable()) {
                return;
            }
            validateImpl();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Validate impl.
     */
    void validateImpl() {
        valid = true;
    }

    /**
     * Gets the native window.
     * 
     * @return the native window.
     */
    NativeWindow getNativeWindow() {
        return behaviour.getNativeWindow();
    }

    /**
     * Checks whether or not a maximum size is set for the Component.
     * 
     * @return true, if the maximum size is set for the Component, false
     *         otherwise.
     */
    public boolean isMaximumSizeSet() {
        toolkit.lockAWT();
        try {
            return maximumSize != null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not the minimum size is set for the component.
     * 
     * @return true, if the minimum size is set for the component, false
     *         otherwise.
     */
    public boolean isMinimumSizeSet() {
        toolkit.lockAWT();
        try {
            return minimumSize != null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks whether or not the preferred size is set for the Component.
     * 
     * @return true, if the preferred size is set for the Component, false
     *         otherwise.
     */
    public boolean isPreferredSizeSet() {
        toolkit.lockAWT();
        try {
            return preferredSize != null;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the maximum size of the Component.
     * 
     * @return the maximum size of the Component.
     */
    public Dimension getMaximumSize() {
        toolkit.lockAWT();
        try {
            return isMaximumSizeSet() ? new Dimension(maximumSize) : new Dimension(Short.MAX_VALUE,
                    Short.MAX_VALUE);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the minimum size of the Component.
     * 
     * @return the minimum size of the Component.
     */
    public Dimension getMinimumSize() {
        toolkit.lockAWT();
        try {
            return minimumSize();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by getMinimumSize() method.
     * 
     * @return the Dimension.
     * @deprecated Replaced by getMinimumSize() method.
     */
    @Deprecated
    public Dimension minimumSize() {
        toolkit.lockAWT();
        try {
            if (isMinimumSizeSet()) {
                return (Dimension)minimumSize.clone();
            }
            Dimension defSize = getDefaultMinimumSize();
            if (defSize != null) {
                return (Dimension)defSize.clone();
            }
            return isDisplayable() ? new Dimension(1, 1) : new Dimension(w, h);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the preferred size of the Component.
     * 
     * @return the preferred size of the Component.
     */
    public Dimension getPreferredSize() {
        toolkit.lockAWT();
        try {
            return preferredSize();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Deprecated: replaced by getPreferredSize() method.
     * 
     * @return the Dimension.
     * @deprecated Replaced by getPreferredSize() method.
     */
    @Deprecated
    public Dimension preferredSize() {
        toolkit.lockAWT();
        try {
            if (isPreferredSizeSet()) {
                return new Dimension(preferredSize);
            }
            Dimension defSize = getDefaultPreferredSize();
            if (defSize != null) {
                return new Dimension(defSize);
            }
            return new Dimension(getMinimumSize());
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the maximum size of the Component.
     * 
     * @param maximumSize
     *            the new maximum size of the Component.
     */
    public void setMaximumSize(Dimension maximumSize) {
        Dimension oldMaximumSize;
        toolkit.lockAWT();
        try {
            oldMaximumSize = this.maximumSize;
            if (oldMaximumSize != null) {
                oldMaximumSize = oldMaximumSize.getSize();
            }
            if (this.maximumSize == null) {
                if (maximumSize != null) {
                    this.maximumSize = new Dimension(maximumSize);
                }
            } else {
                if (maximumSize != null) {
                    this.maximumSize.setSize(maximumSize);
                } else {
                    this.maximumSize = null;
                }
            }
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("maximumSize", oldMaximumSize, this.maximumSize); //$NON-NLS-1$
        toolkit.lockAWT();
        try {
            // ???AWT: invalidateRealParent();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the minimum size of the Component.
     * 
     * @param minimumSize
     *            the new minimum size of the Component.
     */
    public void setMinimumSize(Dimension minimumSize) {
        Dimension oldMinimumSize;
        toolkit.lockAWT();
        try {
            oldMinimumSize = this.minimumSize;
            if (oldMinimumSize != null) {
                oldMinimumSize = oldMinimumSize.getSize();
            }
            if (this.minimumSize == null) {
                if (minimumSize != null) {
                    this.minimumSize = new Dimension(minimumSize);
                }
            } else {
                if (minimumSize != null) {
                    this.minimumSize.setSize(minimumSize);
                } else {
                    this.minimumSize = null;
                }
            }
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("minimumSize", oldMinimumSize, this.minimumSize); //$NON-NLS-1$
        toolkit.lockAWT();
        try {
            // ???AWT: invalidateRealParent();
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the preferred size of the Component.
     * 
     * @param preferredSize
     *            the new preferred size of the Component.
     */
    public void setPreferredSize(Dimension preferredSize) {
        Dimension oldPreferredSize;
        toolkit.lockAWT();
        try {
            oldPreferredSize = this.preferredSize;
            if (oldPreferredSize != null) {
                oldPreferredSize = oldPreferredSize.getSize();
            }
            if (this.preferredSize == null) {
                if (preferredSize != null) {
                    this.preferredSize = new Dimension(preferredSize);
                }
            } else {
                if (preferredSize != null) {
                    this.preferredSize.setSize(preferredSize);
                } else {
                    this.preferredSize = null;
                }
            }
        } finally {
            toolkit.unlockAWT();
        }
        firePropertyChange("preferredSize", oldPreferredSize, this.preferredSize); //$NON-NLS-1$
        toolkit.lockAWT();
        try {
            // ???AWT: invalidateRealParent();
        } finally {
            toolkit.unlockAWT();
        }
    }

    // ???AWT
    /*
     * RedrawManager getRedrawManager() { if (parent == null) { return null; }
     * return parent.getRedrawManager(); }
     */

    /**
     * Checks if is focusability explicitly set.
     * 
     * @return true if component has a focusable peer.
     */
    // ???AWT
    /*
     * boolean isPeerFocusable() { // The recommendations for Windows and Unix
     * are that // Canvases, Labels, Panels, Scrollbars, ScrollPanes, Windows,
     * // and lightweight Components have non-focusable peers, // and all other
     * Components have focusable peers. if (this instanceof Canvas || this
     * instanceof Label || this instanceof Panel || this instanceof Scrollbar ||
     * this instanceof ScrollPane || this instanceof Window || isLightweight())
     * { return false; } return true; }
     */

    /**
     * @return true if focusability was explicitly set via a call to
     *         setFocusable() or via overriding isFocusable() or
     *         isFocusTraversable().
     */
    boolean isFocusabilityExplicitlySet() {
        return calledSetFocusable || overridenIsFocusable;
    }

    /**
     * Paints the component and all of its subcomponents.
     * 
     * @param g
     *            the Graphics to be used for painting.
     */
    public void paintAll(Graphics g) {
        toolkit.lockAWT();
        try {
            paint(g);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Updates this Component.
     * 
     * @param g
     *            the Graphics to be used for updating.
     */
    public void update(Graphics g) {
        toolkit.lockAWT();
        try {
            if (!isLightweight() && !isPrepainter()) {
                g.setColor(getBackground());
                g.fillRect(0, 0, w, h);
                g.setColor(getForeground());
            }
            paint(g);
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Paints this component.
     * 
     * @param g
     *            the Graphics to be used for painting.
     */
    public void paint(Graphics g) {
        toolkit.lockAWT();
        try {
            // Just to nothing
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Prepares the component to be painted.
     * 
     * @param g
     *            the Graphics to be used for painting.
     */
    void prepaint(Graphics g) {
        // Just to nothing. For overriding.
    }

    /**
     * Checks if is prepainter.
     * 
     * @return true, if is prepainter.
     */
    boolean isPrepainter() {
        return false;
    }

    /**
     * Prepare4 hierarchy change.
     */
    void prepare4HierarchyChange() {
        if (hierarchyChangingCounter++ == 0) {
            wasShowing = isShowing();
            wasDisplayable = isDisplayable();
            prepareChildren4HierarchyChange();
        }
    }

    /**
     * Prepare children4 hierarchy change.
     */
    void prepareChildren4HierarchyChange() {
        // To be inherited by Container
    }

    // ???AWT
    /*
     * void finishHierarchyChange(Component changed, Container changedParent,
     * int ancestorFlags) { if (--hierarchyChangingCounter == 0) { int
     * changeFlags = ancestorFlags; if (wasShowing != isShowing()) { changeFlags
     * |= HierarchyEvent.SHOWING_CHANGED; } if (wasDisplayable !=
     * isDisplayable()) { changeFlags |= HierarchyEvent.DISPLAYABILITY_CHANGED;
     * } if (changeFlags > 0) { postEvent(new HierarchyEvent(this,
     * HierarchyEvent.HIERARCHY_CHANGED, changed, changedParent, changeFlags));
     * } finishChildrenHierarchyChange(changed, changedParent, ancestorFlags); }
     * } void finishChildrenHierarchyChange(Component changed, Container
     * changedParent, int ancestorFlags) { // To be inherited by Container }
     * void postHierarchyBoundsEvents(Component changed, int id) { postEvent(new
     * HierarchyEvent(this, id, changed, null, 0)); }
     */

    /**
     * Spread hierarchy bounds events.
     * 
     * @param changed
     *            the changed.
     * @param id
     *            the id.
     */
    void spreadHierarchyBoundsEvents(Component changed, int id) {
        // To be inherited by Container
    }

    /**
     * Dispatches an event to this component.
     * 
     * @param e
     *            the Event.
     */
    public final void dispatchEvent(AWTEvent e) {
        // ???AWT
        /*
         * if (e.isConsumed()) { return; } if (e instanceof PaintEvent) {
         * toolkit.dispatchAWTEvent(e); processPaintEvent((PaintEvent) e);
         * return; } KeyboardFocusManager kfm =
         * KeyboardFocusManager.getCurrentKeyboardFocusManager(); if
         * (!e.dispatchedByKFM && kfm.dispatchEvent(e)) { return; } if (e
         * instanceof KeyEvent) { KeyEvent ke = (KeyEvent) e; // consumes
         * KeyEvent which represents a focus traversal key if
         * (getFocusTraversalKeysEnabled()) { kfm.processKeyEvent(this, ke); if
         * (ke.isConsumed()) { return; } } } if (inputMethodsEnabled &&
         * dispatchToIM && e.isPosted && dispatchEventToIM(e)) { return; } if
         * (e.getID() == WindowEvent.WINDOW_ICONIFIED) {
         * notifyInputMethod(null); } AWTEvent.EventDescriptor descriptor =
         * toolkit.eventTypeLookup.getEventDescriptor(e);
         * toolkit.dispatchAWTEvent(e); if (descriptor != null) { if
         * (isEventEnabled(descriptor.eventMask) ||
         * (getListeners(descriptor.listenerType).length > 0)) {
         * processEvent(e); } // input events can be consumed by user listeners:
         * if (!e.isConsumed() && ((enabledAWTEvents & descriptor.eventMask) !=
         * 0)) { postprocessEvent(e, descriptor.eventMask); } }
         * postDeprecatedEvent(e);
         */
    }

    /**
     * Post deprecated event.
     * 
     * @param e
     *            the e.
     */
    private void postDeprecatedEvent(AWTEvent e) {
        if (deprecatedEventHandler) {
            Event evt = e.getEvent();
            if (evt != null) {
                postEvent(evt);
            }
        }
    }

    /**
     * Postprocess event.
     * 
     * @param e
     *            the e.
     * @param eventMask
     *            the event mask.
     */
    void postprocessEvent(AWTEvent e, long eventMask) {
        toolkit.lockAWT();
        try {
            // call system listeners under AWT lock
            if (eventMask == AWTEvent.FOCUS_EVENT_MASK) {
                preprocessFocusEvent((FocusEvent)e);
            } else if (eventMask == AWTEvent.KEY_EVENT_MASK) {
                preprocessKeyEvent((KeyEvent)e);
            } else if (eventMask == AWTEvent.MOUSE_EVENT_MASK) {
                preprocessMouseEvent((MouseEvent)e);
            } else if (eventMask == AWTEvent.MOUSE_MOTION_EVENT_MASK) {
                preprocessMouseMotionEvent((MouseEvent)e);
            } else if (eventMask == AWTEvent.COMPONENT_EVENT_MASK) {
                preprocessComponentEvent((ComponentEvent)e);
            } else if (eventMask == AWTEvent.MOUSE_WHEEL_EVENT_MASK) {
                preprocessMouseWheelEvent((MouseWheelEvent)e);
            } else if (eventMask == AWTEvent.INPUT_METHOD_EVENT_MASK) {
                preprocessInputMethodEvent((InputMethodEvent)e);
            }
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Preprocess input method event.
     * 
     * @param e
     *            the e.
     */
    private void preprocessInputMethodEvent(InputMethodEvent e) {
        processInputMethodEventImpl(e, inputMethodListeners.getSystemListeners());
    }

    /**
     * Preprocess mouse wheel event.
     * 
     * @param e
     *            the e.
     */
    private void preprocessMouseWheelEvent(MouseWheelEvent e) {
        processMouseWheelEventImpl(e, mouseWheelListeners.getSystemListeners());
    }

    /**
     * Process mouse wheel event impl.
     * 
     * @param e
     *            the e.
     * @param c
     *            the c.
     */
    private void processMouseWheelEventImpl(MouseWheelEvent e, Collection<MouseWheelListener> c) {
        for (MouseWheelListener listener : c) {
            switch (e.getID()) {
                case MouseEvent.MOUSE_WHEEL:
                    listener.mouseWheelMoved(e);
                    break;
            }
        }
    }

    /**
     * Preprocess component event.
     * 
     * @param e
     *            the e.
     */
    private void preprocessComponentEvent(ComponentEvent e) {
        processComponentEventImpl(e, componentListeners.getSystemListeners());
    }

    /**
     * Preprocess mouse motion event.
     * 
     * @param e
     *            the e.
     */
    void preprocessMouseMotionEvent(MouseEvent e) {
        processMouseMotionEventImpl(e, mouseMotionListeners.getSystemListeners());
    }

    /**
     * Preprocess mouse event.
     * 
     * @param e
     *            the e
     */
    void preprocessMouseEvent(MouseEvent e) {
        processMouseEventImpl(e, mouseListeners.getSystemListeners());
    }

    /**
     * Preprocess key event.
     * 
     * @param e
     *            the e.
     */
    void preprocessKeyEvent(KeyEvent e) {
        processKeyEventImpl(e, keyListeners.getSystemListeners());
    }

    /**
     * Preprocess focus event.
     * 
     * @param e
     *            the e.
     */
    void preprocessFocusEvent(FocusEvent e) {
        processFocusEventImpl(e, focusListeners.getSystemListeners());
    }

    /**
     * Processes AWTEvent occurred on this component.
     * 
     * @param e
     *            the AWTEvent.
     */
    protected void processEvent(AWTEvent e) {
        long eventMask = toolkit.eventTypeLookup.getEventMask(e);
        if (eventMask == AWTEvent.COMPONENT_EVENT_MASK) {
            processComponentEvent((ComponentEvent)e);
        } else if (eventMask == AWTEvent.FOCUS_EVENT_MASK) {
            processFocusEvent((FocusEvent)e);
        } else if (eventMask == AWTEvent.KEY_EVENT_MASK) {
            processKeyEvent((KeyEvent)e);
        } else if (eventMask == AWTEvent.MOUSE_EVENT_MASK) {
            processMouseEvent((MouseEvent)e);
        } else if (eventMask == AWTEvent.MOUSE_WHEEL_EVENT_MASK) {
            processMouseWheelEvent((MouseWheelEvent)e);
        } else if (eventMask == AWTEvent.MOUSE_MOTION_EVENT_MASK) {
            processMouseMotionEvent((MouseEvent)e);
        } else if (eventMask == AWTEvent.HIERARCHY_EVENT_MASK) {
            processHierarchyEvent((HierarchyEvent)e);
        } else if (eventMask == AWTEvent.HIERARCHY_BOUNDS_EVENT_MASK) {
            processHierarchyBoundsEvent((HierarchyEvent)e);
        } else if (eventMask == AWTEvent.INPUT_METHOD_EVENT_MASK) {
            processInputMethodEvent((InputMethodEvent)e);
        }
    }

    /**
     * Gets an array of all listener's objects based on the specified listener
     * type and registered to this Component.
     * 
     * @param listenerType
     *            the listener type.
     * @return an array of all listener's objects based on the specified
     *         listener type and registered to this Component.
     */
    @SuppressWarnings("unchecked")
    public <T extends EventListener> T[] getListeners(Class<T> listenerType) {
        if (ComponentListener.class.isAssignableFrom(listenerType)) {
            return (T[])getComponentListeners();
        } else if (FocusListener.class.isAssignableFrom(listenerType)) {
            return (T[])getFocusListeners();
        } else if (HierarchyBoundsListener.class.isAssignableFrom(listenerType)) {
            return (T[])getHierarchyBoundsListeners();
        } else if (HierarchyListener.class.isAssignableFrom(listenerType)) {
            return (T[])getHierarchyListeners();
        } else if (InputMethodListener.class.isAssignableFrom(listenerType)) {
            return (T[])getInputMethodListeners();
        } else if (KeyListener.class.isAssignableFrom(listenerType)) {
            return (T[])getKeyListeners();
        } else if (MouseWheelListener.class.isAssignableFrom(listenerType)) {
            return (T[])getMouseWheelListeners();
        } else if (MouseMotionListener.class.isAssignableFrom(listenerType)) {
            return (T[])getMouseMotionListeners();
        } else if (MouseListener.class.isAssignableFrom(listenerType)) {
            return (T[])getMouseListeners();
        } else if (PropertyChangeListener.class.isAssignableFrom(listenerType)) {
            return (T[])getPropertyChangeListeners();
        }
        return (T[])Array.newInstance(listenerType, 0);
    }

    /**
     * Process paint event.
     * 
     * @param event
     *            the event.
     */
    private void processPaintEvent(PaintEvent event) {
        if (redrawManager == null) {
            return;
        }
        Rectangle clipRect = event.getUpdateRect();
        if ((clipRect.width <= 0) || (clipRect.height <= 0)) {
            return;
        }
        Graphics g = getGraphics();
        if (g == null) {
            return;
        }
        initGraphics(g, event);
        if (!getIgnoreRepaint()) {
            if (event.getID() == PaintEvent.PAINT) {
                paint(g);
            } else {
                update(g);
            }
        }
        g.dispose();
    }

    /**
     * Inits the graphics.
     * 
     * @param g
     *            the g.
     * @param e
     *            the e.
     */
    void initGraphics(Graphics g, PaintEvent e) {
        Rectangle clip = e.getUpdateRect();
        if (clip instanceof ClipRegion) {
            g.setClip(((ClipRegion)clip).getClip());
        } else {
            g.setClip(clip);
        }
        if (isPrepainter()) {
            prepaint(g);
        } else if (!isLightweight() && (e.getID() == PaintEvent.PAINT)) {
            g.setColor(getBackground());
            g.fillRect(0, 0, w, h);
        }
        g.setFont(getFont());
        g.setColor(getForeground());
    }

    /**
     * Enables the events with the specified event mask to be delivered to this
     * component.
     * 
     * @param eventsToEnable
     *            the events mask which specifies the types of events to enable.
     */
    protected final void enableEvents(long eventsToEnable) {
        toolkit.lockAWT();
        try {
            enabledEvents |= eventsToEnable;
            deprecatedEventHandler = false;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Enable awt events.
     * 
     * @param eventsToEnable
     *            the events to enable.
     */
    private void enableAWTEvents(long eventsToEnable) {
        enabledAWTEvents |= eventsToEnable;
    }

    /**
     * Disables the events with types specified by the specified event mask from
     * being delivered to this component.
     * 
     * @param eventsToDisable
     *            the event mask specifying the event types.
     */
    protected final void disableEvents(long eventsToDisable) {
        toolkit.lockAWT();
        try {
            enabledEvents &= ~eventsToDisable;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /*
     * For use in MouseDispatcher only. Really it checks not only mouse events.
     */
    /**
     * Checks if is mouse event enabled.
     * 
     * @param eventMask
     *            the event mask.
     * @return true, if is mouse event enabled.
     */
    boolean isMouseEventEnabled(long eventMask) {
        return (isEventEnabled(eventMask) || (enabledAWTEvents & eventMask) != 0);
    }

    /**
     * Checks if is event enabled.
     * 
     * @param eventMask
     *            the event mask.
     * @return true, if is event enabled.
     */
    boolean isEventEnabled(long eventMask) {
        return ((enabledEvents & eventMask) != 0);
    }

    /**
     * Enables or disables input method support for this component.
     * 
     * @param enable
     *            true to enable input method support, false to disable it.
     */
    public void enableInputMethods(boolean enable) {
        toolkit.lockAWT();
        try {
            if (!enable) {
                removeNotifyInputContext();
            }
            inputMethodsEnabled = enable;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets an array of all component's listeners registered for this component.
     * 
     * @return an array of all component's listeners registered for this
     *         component.
     */
    public ComponentListener[] getComponentListeners() {
        return componentListeners.getUserListeners(new ComponentListener[0]);
    }

    /**
     * Adds the specified component listener to the Component for receiving
     * component's event.
     * 
     * @param l
     *            the ComponentListener.
     */
    public void addComponentListener(ComponentListener l) {
        componentListeners.addUserListener(l);
    }

    /**
     * Removes the component listener registered for this Component.
     * 
     * @param l
     *            the ComponentListener.
     */
    public void removeComponentListener(ComponentListener l) {
        componentListeners.removeUserListener(l);
    }

    /**
     * Processes a component event that has occurred on this component by
     * dispatching them to any registered ComponentListener objects.
     * 
     * @param e
     *            the ComponentEvent.
     */
    protected void processComponentEvent(ComponentEvent e) {
        processComponentEventImpl(e, componentListeners.getUserListeners());
    }

    /**
     * Process component event impl.
     * 
     * @param e
     *            the e.
     * @param c
     *            the c.
     */
    private void processComponentEventImpl(ComponentEvent e, Collection<ComponentListener> c) {
        for (ComponentListener listener : c) {
            switch (e.getID()) {
                case ComponentEvent.COMPONENT_HIDDEN:
                    listener.componentHidden(e);
                    break;
                case ComponentEvent.COMPONENT_MOVED:
                    listener.componentMoved(e);
                    break;
                case ComponentEvent.COMPONENT_RESIZED:
                    listener.componentResized(e);
                    break;
                case ComponentEvent.COMPONENT_SHOWN:
                    listener.componentShown(e);
                    break;
            }
        }
    }

    /**
     * Gets an array of focus listeners registered for this Component.
     * 
     * @return the array of focus listeners registered for this Component.
     */
    public FocusListener[] getFocusListeners() {
        return focusListeners.getUserListeners(new FocusListener[0]);
    }

    /**
     * Adds the specified focus listener to the Component for receiving focus
     * events.
     * 
     * @param l
     *            the FocusListener.
     */
    public void addFocusListener(FocusListener l) {
        focusListeners.addUserListener(l);
    }

    /**
     * Adds the awt focus listener.
     * 
     * @param l
     *            the l.
     */
    void addAWTFocusListener(FocusListener l) {
        enableAWTEvents(AWTEvent.FOCUS_EVENT_MASK);
        focusListeners.addSystemListener(l);
    }

    /**
     * Removes the focus listener registered for this Component.
     * 
     * @param l
     *            the FocusListener.
     */
    public void removeFocusListener(FocusListener l) {
        focusListeners.removeUserListener(l);
    }

    /**
     * Processes a FocusEvent that has occurred on this component by dispatching
     * it to the registered listeners.
     * 
     * @param e
     *            the FocusEvent.
     */
    protected void processFocusEvent(FocusEvent e) {
        processFocusEventImpl(e, focusListeners.getUserListeners());
    }

    /**
     * Process focus event impl.
     * 
     * @param e
     *            the e.
     * @param c
     *            the c.
     */
    private void processFocusEventImpl(FocusEvent e, Collection<FocusListener> c) {
        for (FocusListener listener : c) {
            switch (e.getID()) {
                case FocusEvent.FOCUS_GAINED:
                    listener.focusGained(e);
                    break;
                case FocusEvent.FOCUS_LOST:
                    listener.focusLost(e);
                    break;
            }
        }
    }

    /**
     * Gets an array of registered HierarchyListeners for this Component.
     * 
     * @return an array of registered HierarchyListeners for this Component.
     */
    public HierarchyListener[] getHierarchyListeners() {
        return hierarchyListeners.getUserListeners(new HierarchyListener[0]);
    }

    /**
     * Adds the specified hierarchy listener.
     * 
     * @param l
     *            the HierarchyListener.
     */
    public void addHierarchyListener(HierarchyListener l) {
        hierarchyListeners.addUserListener(l);
    }

    /**
     * Removes the hierarchy listener registered for this component.
     * 
     * @param l
     *            the HierarchyListener.
     */
    public void removeHierarchyListener(HierarchyListener l) {
        hierarchyListeners.removeUserListener(l);
    }

    /**
     * Processes a hierarchy event that has occurred on this component by
     * dispatching it to the registered listeners.
     * 
     * @param e
     *            the HierarchyEvent.
     */
    protected void processHierarchyEvent(HierarchyEvent e) {
        for (HierarchyListener listener : hierarchyListeners.getUserListeners()) {
            switch (e.getID()) {
                case HierarchyEvent.HIERARCHY_CHANGED:
                    listener.hierarchyChanged(e);
                    break;
            }
        }
    }

    /**
     * Gets an array of HierarchyBoundsListener objects registered to this
     * Component.
     * 
     * @return an array of HierarchyBoundsListener objects.
     */
    public HierarchyBoundsListener[] getHierarchyBoundsListeners() {
        return hierarchyBoundsListeners.getUserListeners(new HierarchyBoundsListener[0]);
    }

    /**
     * Adds the specified hierarchy bounds listener.
     * 
     * @param l
     *            the HierarchyBoundsListener.
     */
    public void addHierarchyBoundsListener(HierarchyBoundsListener l) {
        hierarchyBoundsListeners.addUserListener(l);
    }

    /**
     * Removes the hierarchy bounds listener registered for this Component.
     * 
     * @param l
     *            the HierarchyBoundsListener.
     */
    public void removeHierarchyBoundsListener(HierarchyBoundsListener l) {
        hierarchyBoundsListeners.removeUserListener(l);
    }

    /**
     * Processes a hierarchy bounds event that has occurred on this component by
     * dispatching it to the registered listeners.
     * 
     * @param e
     *            the HierarchyBoundsEvent.
     */
    protected void processHierarchyBoundsEvent(HierarchyEvent e) {
        for (HierarchyBoundsListener listener : hierarchyBoundsListeners.getUserListeners()) {
            switch (e.getID()) {
                case HierarchyEvent.ANCESTOR_MOVED:
                    listener.ancestorMoved(e);
                    break;
                case HierarchyEvent.ANCESTOR_RESIZED:
                    listener.ancestorResized(e);
                    break;
            }
        }
    }

    /**
     * Gets an array of the key listeners registered to the Component.
     * 
     * @return an array of the key listeners registered to the Component.
     */
    public KeyListener[] getKeyListeners() {
        return keyListeners.getUserListeners(new KeyListener[0]);
    }

    /**
     * Adds the specified key listener.
     * 
     * @param l
     *            the KeyListener.
     */
    public void addKeyListener(KeyListener l) {
        keyListeners.addUserListener(l);
    }

    /**
     * Adds the awt key listener.
     * 
     * @param l
     *            the l.
     */
    void addAWTKeyListener(KeyListener l) {
        enableAWTEvents(AWTEvent.KEY_EVENT_MASK);
        keyListeners.addSystemListener(l);
    }

    /**
     * Removes the key listener registered for this Component.
     * 
     * @param l
     *            the KeyListener.
     */
    public void removeKeyListener(KeyListener l) {
        keyListeners.removeUserListener(l);
    }

    /**
     * Processes a key event that has occurred on this component by dispatching
     * it to the registered listeners.
     * 
     * @param e
     *            the KeyEvent.
     */
    protected void processKeyEvent(KeyEvent e) {
        processKeyEventImpl(e, keyListeners.getUserListeners());
    }

    /**
     * Process key event impl.
     * 
     * @param e
     *            the e.
     * @param c
     *            the c.
     */
    private void processKeyEventImpl(KeyEvent e, Collection<KeyListener> c) {
        for (KeyListener listener : c) {
            switch (e.getID()) {
                case KeyEvent.KEY_PRESSED:
                    listener.keyPressed(e);
                    break;
                case KeyEvent.KEY_RELEASED:
                    listener.keyReleased(e);
                    break;
                case KeyEvent.KEY_TYPED:
                    listener.keyTyped(e);
                    break;
            }
        }
    }

    /**
     * Gets an array of the mouse listeners registered to the Component.
     * 
     * @return an array of the mouse listeners registered to the Component.
     */
    public MouseListener[] getMouseListeners() {
        return mouseListeners.getUserListeners(new MouseListener[0]);
    }

    /**
     * Adds the specified mouse listener.
     * 
     * @param l
     *            the MouseListener.
     */
    public void addMouseListener(MouseListener l) {
        mouseListeners.addUserListener(l);
    }

    /**
     * Adds the awt mouse listener.
     * 
     * @param l
     *            the l.
     */
    void addAWTMouseListener(MouseListener l) {
        enableAWTEvents(AWTEvent.MOUSE_EVENT_MASK);
        mouseListeners.addSystemListener(l);
    }

    /**
     * Adds the awt mouse motion listener.
     * 
     * @param l
     *            the l.
     */
    void addAWTMouseMotionListener(MouseMotionListener l) {
        enableAWTEvents(AWTEvent.MOUSE_MOTION_EVENT_MASK);
        mouseMotionListeners.addSystemListener(l);
    }

    /**
     * Adds the awt component listener.
     * 
     * @param l
     *            the l.
     */
    void addAWTComponentListener(ComponentListener l) {
        enableAWTEvents(AWTEvent.COMPONENT_EVENT_MASK);
        componentListeners.addSystemListener(l);
    }

    /**
     * Adds the awt input method listener.
     * 
     * @param l
     *            the l.
     */
    void addAWTInputMethodListener(InputMethodListener l) {
        enableAWTEvents(AWTEvent.INPUT_METHOD_EVENT_MASK);
        inputMethodListeners.addSystemListener(l);
    }

    /**
     * Adds the awt mouse wheel listener.
     * 
     * @param l
     *            the l.
     */
    void addAWTMouseWheelListener(MouseWheelListener l) {
        enableAWTEvents(AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        mouseWheelListeners.addSystemListener(l);
    }

    /**
     * Removes the mouse listener registered for this Component.
     * 
     * @param l
     *            the MouseListener.
     */
    public void removeMouseListener(MouseListener l) {
        mouseListeners.removeUserListener(l);
    }

    /**
     * Processes a mouse event that has occurred on this component by
     * dispatching it to the registered listeners.
     * 
     * @param e
     *            the MouseEvent.
     */
    protected void processMouseEvent(MouseEvent e) {
        processMouseEventImpl(e, mouseListeners.getUserListeners());
    }

    /**
     * Process mouse event impl.
     * 
     * @param e
     *            the e.
     * @param c
     *            the c.
     */
    private void processMouseEventImpl(MouseEvent e, Collection<MouseListener> c) {
        for (MouseListener listener : c) {
            switch (e.getID()) {
                case MouseEvent.MOUSE_CLICKED:
                    listener.mouseClicked(e);
                    break;
                case MouseEvent.MOUSE_ENTERED:
                    listener.mouseEntered(e);
                    break;
                case MouseEvent.MOUSE_EXITED:
                    listener.mouseExited(e);
                    break;
                case MouseEvent.MOUSE_PRESSED:
                    listener.mousePressed(e);
                    break;
                case MouseEvent.MOUSE_RELEASED:
                    listener.mouseReleased(e);
                    break;
            }
        }
    }

    /**
     * Process mouse motion event impl.
     * 
     * @param e
     *            the e.
     * @param c
     *            the c.
     */
    private void processMouseMotionEventImpl(MouseEvent e, Collection<MouseMotionListener> c) {
        for (MouseMotionListener listener : c) {
            switch (e.getID()) {
                case MouseEvent.MOUSE_DRAGGED:
                    listener.mouseDragged(e);
                    break;
                case MouseEvent.MOUSE_MOVED:
                    listener.mouseMoved(e);
                    break;
            }
        }
    }

    /**
     * Gets an array of the mouse motion listeners registered to the Component.
     * 
     * @return an array of the MouseMotionListeners registered to the Component.
     */
    public MouseMotionListener[] getMouseMotionListeners() {
        return mouseMotionListeners.getUserListeners(new MouseMotionListener[0]);
    }

    /**
     * Adds the specified mouse motion listener.
     * 
     * @param l
     *            the MouseMotionListener.
     */
    public void addMouseMotionListener(MouseMotionListener l) {
        mouseMotionListeners.addUserListener(l);
    }

    /**
     * Removes the mouse motion listener registered for this component.
     * 
     * @param l
     *            the MouseMotionListener.
     */
    public void removeMouseMotionListener(MouseMotionListener l) {
        mouseMotionListeners.removeUserListener(l);
    }

    /**
     * Processes a mouse motion event that has occurred on this component by
     * dispatching it to the registered listeners.
     * 
     * @param e
     *            the MouseEvent.
     */
    protected void processMouseMotionEvent(MouseEvent e) {
        processMouseMotionEventImpl(e, mouseMotionListeners.getUserListeners());
    }

    /**
     * Gets an array of the mouse wheel listeners registered to the Component.
     * 
     * @return an array of the MouseWheelListeners registered to the Component.
     */
    public MouseWheelListener[] getMouseWheelListeners() {
        return mouseWheelListeners.getUserListeners(new MouseWheelListener[0]);
    }

    /**
     * Adds the specified mouse wheel listener.
     * 
     * @param l
     *            the MouseWheelListener.
     */
    public void addMouseWheelListener(MouseWheelListener l) {
        mouseWheelListeners.addUserListener(l);
    }

    /**
     * Removes the mouse wheel listener registered for this component.
     * 
     * @param l
     *            the MouseWheelListener.
     */
    public void removeMouseWheelListener(MouseWheelListener l) {
        mouseWheelListeners.removeUserListener(l);
    }

    /**
     * Processes a mouse wheel event that has occurred on this component by
     * dispatching it to the registered listeners.
     * 
     * @param e
     *            the MouseWheelEvent.
     */
    protected void processMouseWheelEvent(MouseWheelEvent e) {
        processMouseWheelEventImpl(e, mouseWheelListeners.getUserListeners());
    }

    /**
     * Gets an array of the InputMethodListener listeners registered to the
     * Component.
     * 
     * @return an array of the InputMethodListener listeners registered to the
     *         Component.
     */
    public InputMethodListener[] getInputMethodListeners() {
        return inputMethodListeners.getUserListeners(new InputMethodListener[0]);
    }

    /**
     * Adds the specified input method listener.
     * 
     * @param l
     *            the InputMethodListener.
     */
    public void addInputMethodListener(InputMethodListener l) {
        inputMethodListeners.addUserListener(l);
    }

    /**
     * Removes the input method listener registered for this component.
     * 
     * @param l
     *            the InputMethodListener.
     */
    public void removeInputMethodListener(InputMethodListener l) {
        inputMethodListeners.removeUserListener(l);
    }

    /**
     * Processes an input method event that has occurred on this component by
     * dispatching it to the registered listeners.
     * 
     * @param e
     *            the InputMethodEvent.
     */
    protected void processInputMethodEvent(InputMethodEvent e) {
        processInputMethodEventImpl(e, inputMethodListeners.getUserListeners());
    }

    /**
     * Process input method event impl.
     * 
     * @param e
     *            the e.
     * @param c
     *            the c.
     */
    private void processInputMethodEventImpl(InputMethodEvent e, Collection<InputMethodListener> c) {
        for (InputMethodListener listener : c) {
            switch (e.getID()) {
                case InputMethodEvent.CARET_POSITION_CHANGED:
                    listener.caretPositionChanged(e);
                    break;
                case InputMethodEvent.INPUT_METHOD_TEXT_CHANGED:
                    listener.inputMethodTextChanged(e);
                    break;
            }
        }
    }

    // ???AWT
    /*
     * public Point getMousePosition() throws HeadlessException { Point
     * absPointerPos = MouseInfo.getPointerInfo().getLocation(); Window
     * winUnderPtr =
     * toolkit.dispatcher.mouseDispatcher.findWindowAt(absPointerPos); Point
     * pointerPos = MouseDispatcher.convertPoint(null, absPointerPos,
     * winUnderPtr); boolean isUnderPointer = false; if (winUnderPtr == null) {
     * return null; } isUnderPointer = winUnderPtr.isComponentAt(this,
     * pointerPos); if (isUnderPointer) { return
     * MouseDispatcher.convertPoint(null, absPointerPos, this); } return null; }
     */

    /**
     * Set native caret at the given position <br>
     * Note: this method takes AWT lock inside because it walks through the
     * component hierarchy.
     * 
     * @param x
     *            the x.
     * @param y
     *            the y.
     */
    void setCaretPos(final int x, final int y) {
        Runnable r = new Runnable() {
            public void run() {
                toolkit.lockAWT();
                try {
                    setCaretPosImpl(x, y);
                } finally {
                    toolkit.unlockAWT();
                }
            }
        };
        if (Thread.currentThread() instanceof EventDispatchThread) {
            r.run();
        } else {
            toolkit.getSystemEventQueueImpl().postEvent(new InvocationEvent(this, r));
        }
    }

    /**
     * This method should be called only at event dispatch thread.
     * 
     * @param x
     *            the x.
     * @param y
     *            the y.
     */
    void setCaretPosImpl(int x, int y) {
        Component c = this;
        while ((c != null) && c.behaviour.isLightweight()) {
            x += c.x;
            y += c.y;
            // ???AWT: c = c.getParent();
        }
        if (c == null) {
            return;
        }
        // ???AWT
        /*
         * if (c instanceof Window) { Insets insets = c.getNativeInsets(); x -=
         * insets.left; y -= insets.top; }
         * toolkit.getWindowFactory().setCaretPosition(x, y);
         */
    }

    // to be overridden in standard components such as Button and List
    /**
     * Gets the default minimum size.
     * 
     * @return the default minimum size.
     */
    Dimension getDefaultMinimumSize() {
        return null;
    }

    // to be overridden in standard components such as Button and List
    /**
     * Gets the default preferred size.
     * 
     * @return the default preferred size.
     */
    Dimension getDefaultPreferredSize() {
        return null;
    }

    // to be overridden in standard components such as Button and List
    /**
     * Reset default size.
     */
    void resetDefaultSize() {
    }

    // ???AWT
    /*
     * ComponentBehavior createBehavior() { return new LWBehavior(this); }
     */

    /**
     * Gets the default background.
     * 
     * @return the default background.
     */
    Color getDefaultBackground() {
        // ???AWT: return getWindowAncestor().getDefaultBackground();
        return getBackground();
    }

    /**
     * Gets the default foreground.
     * 
     * @return the default foreground.
     */
    Color getDefaultForeground() {
        // ???AWT return getWindowAncestor().getDefaultForeground();
        return getForeground();
    }

    /**
     * Called when native resource for this component is created (for
     * heavyweights only).
     * 
     * @param win
     *            the win.
     */
    void nativeWindowCreated(NativeWindow win) {
        // to be overridden
    }

    /**
     * Determine the component's area hidden behind the windows that have higher
     * Z-order, including windows of other applications.
     * 
     * @param image
     *            the image.
     * @param destLocation
     *            the dest location.
     * @param destSize
     *            the dest size.
     * @param source
     *            the source.
     * @return the calculated region, or null if it cannot be determined.
     */
    // ???AWT
    /*
     * MultiRectArea getObscuredRegion(Rectangle part) { if (!visible || parent
     * == null || !parent.visible) { return null; } Rectangle r = new
     * Rectangle(0, 0, w, h); if (part != null) { r = r.intersection(part); } if
     * (r.isEmpty()) { return null; } r.translate(x, y); MultiRectArea ret =
     * parent.getObscuredRegion(r); if (ret != null) {
     * parent.addObscuredRegions(ret, this); ret.translate(-x, -y);
     * ret.intersect(new Rectangle(0, 0, w, h)); } return ret; }
     */

    // ???AWT
    /*
     * private void readObject(ObjectInputStream stream) throws IOException,
     * ClassNotFoundException { stream.defaultReadObject(); FieldsAccessor
     * accessor = new FieldsAccessor(Component.class, this);
     * accessor.set("toolkit", Toolkit.getDefaultToolkit()); //$NON-NLS-1$
     * accessor.set("behaviour", createBehavior()); //$NON-NLS-1$
     * accessor.set("componentLock", new Object()); // $NON-LOCK-1$
     * //$NON-NLS-1$ }
     */

    final void onDrawImage(Image image, Point destLocation, Dimension destSize, Rectangle source) {
        ImageParameters imageParams;
        if (updatedImages == null) {
            updatedImages = new HashMap<Image, ImageParameters>();
        }
        imageParams = updatedImages.get(image);
        if (imageParams == null) {
            imageParams = new ImageParameters();
            updatedImages.put(image, imageParams);
        }
        imageParams.addDrawing(destLocation, destSize, source);
    }

    public boolean imageUpdate(Image img, int infoflags, int x, int y, int w, int h) {
        toolkit.lockAWT();
        try {
            boolean done = false;
            if ((infoflags & (ALLBITS | FRAMEBITS)) != 0) {
                done = true;
            } else if ((infoflags & SOMEBITS) != 0 && incrementalImageUpdate) {
                done = true;
            }
            if (done) {
                repaint();
            }
            return (infoflags & (ABORT | ALLBITS)) == 0;
        } finally {
            toolkit.unlockAWT();
        }
    }

    // ???AWT
    /*
     * private void invalidateRealParent() { Container realParent =
     * getRealParent(); if ((realParent != null) && realParent.isValid()) {
     * realParent.invalidate(); } }
     */

    /**
     * The Class ImageParameters.
     */
    private class ImageParameters {

        /**
         * The drawing params.
         */
        private final LinkedList<DrawingParameters> drawingParams = new LinkedList<DrawingParameters>();

        /**
         * The size.
         */
        Dimension size = new Dimension(Component.this.w, Component.this.h);

        /**
         * Adds the drawing.
         * 
         * @param destLocation
         *            the dest location.
         * @param destSize
         *            the dest size.
         * @param source
         *            the source.
         */
        void addDrawing(Point destLocation, Dimension destSize, Rectangle source) {
            drawingParams.add(new DrawingParameters(destLocation, destSize, source));
        }

        /**
         * Drawing parameters iterator.
         * 
         * @return the iterator< drawing parameters>.
         */
        Iterator<DrawingParameters> drawingParametersIterator() {
            return drawingParams.iterator();
        }

        /**
         * The Class DrawingParameters.
         */
        class DrawingParameters {

            /**
             * The dest location.
             */
            Point destLocation;

            /**
             * The dest size.
             */
            Dimension destSize;

            /**
             * The source.
             */
            Rectangle source;

            /**
             * Instantiates a new drawing parameters.
             * 
             * @param destLocation
             *            the dest location.
             * @param destSize
             *            the dest size.
             * @param source
             *            the source.
             */
            DrawingParameters(Point destLocation, Dimension destSize, Rectangle source) {
                this.destLocation = new Point(destLocation);
                if (destSize != null) {
                    this.destSize = new Dimension(destSize);
                } else {
                    this.destSize = null;
                }
                if (source != null) {
                    this.source = new Rectangle(source);
                } else {
                    this.source = null;
                }
            }
        }
    }

    /**
     * TextComponent support.
     * 
     * @param e
     *            the e.
     * @return true, if dispatch event to im.
     */
    // ???AWT
    /*
     * private TextKit textKit = null; TextKit getTextKit() { return textKit; }
     * void setTextKit(TextKit kit) { textKit = kit; }
     */

    /**
     * TextField support.
     */
    // ???AWT
    /*
     * private TextFieldKit textFieldKit = null; TextFieldKit getTextFieldKit()
     * { return textFieldKit; } void setTextFieldKit(TextFieldKit kit) {
     * textFieldKit = kit; }
     */

    /**
     * Dispatches input & focus events to input method context.
     * 
     * @param e
     *            event to pass to InputContext.dispatchEvent().
     * @return true if event was consumed by IM, false otherwise.
     */
    private boolean dispatchEventToIM(AWTEvent e) {
        InputContext ic = getInputContext();
        if (ic == null) {
            return false;
        }
        int id = e.getID();
        boolean isInputEvent = ((id >= KeyEvent.KEY_FIRST) && (id <= KeyEvent.KEY_LAST))
                || ((id >= MouseEvent.MOUSE_FIRST) && (id <= MouseEvent.MOUSE_LAST));
        if (((id >= FocusEvent.FOCUS_FIRST) && (id <= FocusEvent.FOCUS_LAST)) || isInputEvent) {
            ic.dispatchEvent(e);
        }
        return e.isConsumed();
    }
}
