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

import java.awt.event.AWTEventListener;
import java.awt.event.AWTEventListenerProxy;
import java.awt.event.InputEvent;
import java.awt.im.InputMethodHighlight;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;
import java.awt.peer.FontPeer;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;

import org.apache.harmony.awt.ChoiceStyle;
import org.apache.harmony.awt.ComponentInternals;
import org.apache.harmony.awt.ContextStorage;
import org.apache.harmony.awt.ReadOnlyIterator;
import org.apache.harmony.awt.internal.nls.Messages;
import org.apache.harmony.awt.wtk.CreationParams;
import org.apache.harmony.awt.wtk.GraphicsFactory;
import org.apache.harmony.awt.wtk.NativeCursor;

import org.apache.harmony.awt.wtk.NativeEventQueue;
import org.apache.harmony.awt.wtk.NativeEventThread;
import org.apache.harmony.awt.wtk.ShutdownWatchdog;
import org.apache.harmony.awt.wtk.Synchronizer;
import org.apache.harmony.awt.wtk.WTK;
import org.apache.harmony.luni.util.NotImplementedException;

/**
 * The Toolkit class is the representation of the platform-specific Abstract
 * Window Toolkit implementation. Toolkit's subclasses are used to bind the
 * various components to particular native toolkit implementations.
 * 
 * @since Android 1.0
 */
public abstract class Toolkit {

    /**
     * The Constant RECOURCE_PATH.
     */
    private static final String RECOURCE_PATH = "org.apache.harmony.awt.resources.AWTProperties"; //$NON-NLS-1$

    /**
     * The Constant properties.
     */
    private static final ResourceBundle properties = loadResources(RECOURCE_PATH);

    /**
     * The dispatcher.
     */
    Dispatcher dispatcher;

    /**
     * The system event queue core.
     */
    private EventQueueCore systemEventQueueCore;

    /**
     * The dispatch thread.
     */
    EventDispatchThread dispatchThread;

    /**
     * The native thread.
     */
    NativeEventThread nativeThread;

    /**
     * The AWT events manager.
     */
    protected AWTEventsManager awtEventsManager;

    /**
     * The Class AWTTreeLock.
     */
    private class AWTTreeLock {
    }

    /**
     * The AWT tree lock.
     */
    final Object awtTreeLock = new AWTTreeLock();

    /**
     * The synchronizer.
     */
    private final Synchronizer synchronizer = ContextStorage.getSynchronizer();

    /**
     * The shutdown watchdog.
     */
    final ShutdownWatchdog shutdownWatchdog = new ShutdownWatchdog();

    /**
     * The auto number.
     */
    final AutoNumber autoNumber = new AutoNumber();

    /**
     * The event type lookup.
     */
    final AWTEvent.EventTypeLookup eventTypeLookup = new AWTEvent.EventTypeLookup();

    /**
     * The b dynamic layout set.
     */
    private boolean bDynamicLayoutSet = true;

    /**
     * The set of desktop properties that user set directly.
     */
    private final HashSet<String> userPropSet = new HashSet<String>();

    /**
     * The desktop properties.
     */
    protected Map<String, Object> desktopProperties;

    /**
     * The desktop props support.
     */
    protected PropertyChangeSupport desktopPropsSupport;

    /**
     * For this component the native window is being created It is used in the
     * callback-driven window creation (e.g. on Windows in the handler of
     * WM_CREATE event) to establish the connection between this component and
     * its native window.
     */
    private Object recentNativeWindowComponent;

    /**
     * The wtk.
     */
    private WTK wtk;

    /**
     * The Class ComponentInternalsImpl.
     * 
     * @since Android 1.0
     */
    protected final class ComponentInternalsImpl extends ComponentInternals {

        /**
         * Shutdown.
         */
        @Override
        public void shutdown() {
            dispatchThread.shutdown();
        }

        /**
         * Sets the desktop property to the specified value and fires a property
         * change event.
         * 
         * @param name
         *            the name of property.
         * @param value
         *            the new value of property.
         */
        @Override
        public void setDesktopProperty(String name, Object value) {
            Toolkit.this.setDesktopProperty(name, value);
        }
    }

    /**
     * A lot of methods must throw HeadlessException if
     * <code>GraphicsEnvironment.isHeadless()</code> returns <code>true</code>.
     * 
     * @throws HeadlessException
     *             the headless exception.
     */
    static void checkHeadless() throws HeadlessException {
        if (GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance())
            throw new HeadlessException();
    }

    /**
     * Lock AWT.
     */
    final void lockAWT() {
        synchronizer.lock();
    }

    /**
     * Static lock AWT.
     */
    static final void staticLockAWT() {
        ContextStorage.getSynchronizer().lock();
    }

    /**
     * Unlock AWT.
     */
    final void unlockAWT() {
        synchronizer.unlock();
    }

    /**
     * Static unlock AWT.
     */
    static final void staticUnlockAWT() {
        ContextStorage.getSynchronizer().unlock();
    }

    /**
     * InvokeAndWait under AWT lock. W/o this method system can hang up. Added
     * to support modality (Dialog.show() & PopupMenu.show()) from not event
     * dispatch thread. Use in other cases is not recommended. Still can be
     * called only for whole API methods that cannot be called from other
     * classes API methods. Examples: show() for modal dialogs - correct, only
     * user can call it, directly or through setVisible(true) setBounds() for
     * components - incorrect, setBounds() can be called from layoutContainer()
     * for layout managers
     * 
     * @param runnable
     *            the runnable.
     * @throws InterruptedException
     *             the interrupted exception.
     * @throws InvocationTargetException
     *             the invocation target exception.
     */
    final void unsafeInvokeAndWait(Runnable runnable) throws InterruptedException,
            InvocationTargetException {
        synchronizer.storeStateAndFree();
        try {
            EventQueue.invokeAndWait(runnable);
        } finally {
            synchronizer.lockAndRestoreState();
        }
    }

    /**
     * Gets the synchronizer.
     * 
     * @return the synchronizer.
     */
    final Synchronizer getSynchronizer() {
        return synchronizer;
    }

    /**
     * Gets the wTK.
     * 
     * @return the wTK.
     */
    final WTK getWTK() {
        return wtk;
    }

    /**
     * Gets the property with the specified key and default value. This method
     * returns the defValue if the property is not found.
     * 
     * @param propName
     *            the name of property.
     * @param defVal
     *            the default value.
     * @return the property value.
     */
    public static String getProperty(String propName, String defVal) {
        if (propName == null) {
            // awt.7D=Property name is null
            throw new NullPointerException(Messages.getString("awt.7D")); //$NON-NLS-1$
        }
        staticLockAWT();
        try {
            String retVal = null;
            if (properties != null) {
                try {
                    retVal = properties.getString(propName);
                } catch (MissingResourceException e) {
                } catch (ClassCastException e) {
                }
            }
            return (retVal == null) ? defVal : retVal;
        } finally {
            staticUnlockAWT();
        }
    }

    /**
     * Gets the default Toolkit.
     * 
     * @return the default Toolkit.
     */
    public static Toolkit getDefaultToolkit() {
        synchronized (ContextStorage.getContextLock()) {
            if (ContextStorage.shutdownPending()) {
                return null;
            }
            Toolkit defToolkit = ContextStorage.getDefaultToolkit();
            if (defToolkit != null) {
                return defToolkit;
            }
            staticLockAWT();
            try {
                defToolkit = GraphicsEnvironment.isHeadless() ? new HeadlessToolkit()
                        : new ToolkitImpl();
                ContextStorage.setDefaultToolkit(defToolkit);
                return defToolkit;
            } finally {
                staticUnlockAWT();
            }
            // TODO: read system property named awt.toolkit
            // and create an instance of the specified class,
            // by default use ToolkitImpl
        }
    }

    /**
     * Gets the default Font.
     * 
     * @return the default Font for Toolkit.
     */
    Font getDefaultFont() {
        return wtk.getSystemProperties().getDefaultFont();
    }

    /**
     * Load resources.
     * 
     * @param path
     *            the path.
     * @return the resource bundle.
     */
    private static ResourceBundle loadResources(String path) {
        try {
            return ResourceBundle.getBundle(path);
        } catch (MissingResourceException e) {
            return null;
        }
    }

    /**
     * Gets the wTK class name.
     * 
     * @return the wTK class name.
     */
    private static String getWTKClassName() {
        return "com.android.internal.awt.AndroidWTK";
    }

    /**
     * Gets the component by id.
     * 
     * @param id
     *            the id.
     * @return the component by id.
     */
    Component getComponentById(long id) {
        if (id == 0) {
            return null;
        }
        return null;
    }

    /**
     * Gets the GraphicsFactory.
     * 
     * @return the GraphicsFactory object.
     */
    public GraphicsFactory getGraphicsFactory() {
        return wtk.getGraphicsFactory();
    }

    /**
     * Instantiates a new toolkit.
     */
    public Toolkit() {
        init();
    }

    /**
     * Initiates AWT.
     */
    protected void init() {
        lockAWT();
        try {
            ComponentInternals.setComponentInternals(new ComponentInternalsImpl());
            new EventQueue(this); // create the system EventQueue
            dispatcher = new Dispatcher(this);
            final String className = getWTKClassName();
            desktopProperties = new HashMap<String, Object>();
            desktopPropsSupport = new PropertyChangeSupport(this);
            awtEventsManager = new AWTEventsManager();
            dispatchThread = new EventDispatchThread(this, dispatcher);
            nativeThread = new NativeEventThread();
            NativeEventThread.Init init = new NativeEventThread.Init() {
                public WTK init() {
                    wtk = createWTK(className);
                    wtk.getNativeEventQueue().setShutdownWatchdog(shutdownWatchdog);
                    synchronizer.setEnvironment(wtk, dispatchThread);
                    ContextStorage.setWTK(wtk);
                    return wtk;
                }
            };
            nativeThread.start(init);
            dispatchThread.start();
            wtk.getNativeEventQueue().awake();
        } finally {
            unlockAWT();
        }
    }

    /**
     * Synchronizes this toolkit's graphics.
     */
    public abstract void sync();

    /**
     * Returns the construction status of a specified image that is being
     * created.
     * 
     * @param a0
     *            the image to be checked.
     * @param a1
     *            the width of scaled image for which the status is being
     *            checked or -1.
     * @param a2
     *            the height of scaled image for which the status is being
     *            checked or -1.
     * @param a3
     *            the ImageObserver object to be notified while the image is
     *            being prepared.
     * @return the ImageObserver flags which give the current state of the image
     *         data.
     */
    public abstract int checkImage(Image a0, int a1, int a2, ImageObserver a3);

    /**
     * Creates the image with the specified ImageProducer.
     * 
     * @param a0
     *            the ImageProducer to be used for image creation.
     * @return the image with the specified ImageProducer.
     */
    public abstract Image createImage(ImageProducer a0);

    /**
     * Creates the image from the specified byte array, offset and length. The
     * byte array should contain data with image format supported by Toolkit
     * such as JPEG, GIF, or PNG.
     * 
     * @param a0
     *            the byte array with the image data.
     * @param a1
     *            the offset of the beginning the image data in the byte array.
     * @param a2
     *            the length of the image data in the byte array.
     * @return the created Image.
     */
    public abstract Image createImage(byte[] a0, int a1, int a2);

    /**
     * Creates the image using image data from the specified URL.
     * 
     * @param a0
     *            the URL for extracting image data.
     * @return the Image.
     */
    public abstract Image createImage(URL a0);

    /**
     * Creates the image using image data from the specified file.
     * 
     * @param a0
     *            the file name which contains image data of supported format.
     * @return the Image.
     */
    public abstract Image createImage(String a0);

    /**
     * Gets the color model.
     * 
     * @return the ColorModel of Toolkit's screen.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public abstract ColorModel getColorModel() throws HeadlessException;

    /**
     * Gets the screen device metrics for the specified font.
     * 
     * @param font
     *            the Font.
     * @return the FontMetrics for the specified Font.
     * @deprecated Use getLineMetrics method from Font class.
     */

    @Deprecated
    public abstract FontMetrics getFontMetrics(Font font);

    /**
     * Prepares the specified image for rendering on the screen with the
     * specified size.
     * 
     * @param a0
     *            the Image to be prepared.
     * @param a1
     *            the width of the screen representation or -1 for the current
     *            screen.
     * @param a2
     *            the height of the screen representation or -1 for the current
     *            screen.
     * @param a3
     *            the ImageObserver object to be notified as soon as the image
     *            is prepared.
     * @return true, if image is fully prepared, false otherwise.
     */
    public abstract boolean prepareImage(Image a0, int a1, int a2, ImageObserver a3);

    /**
     * Creates an audio beep.
     */
    public abstract void beep();

    /**
     * Returns the array of font names which are available in this Toolkit.
     * 
     * @return the array of font names which are available in this Toolkit.
     * @deprecated use GraphicsEnvironment.getAvailableFontFamilyNames() method.
     */
    @Deprecated
    public abstract String[] getFontList();

    /**
     * Gets the the Font implementation using the specified peer interface.
     * 
     * @param a0
     *            the Font name to be implemented.
     * @param a1
     *            the the font style: PLAIN, BOLD, ITALIC.
     * @return the FontPeer implementation of the specified Font.
     * @deprecated use java.awt.GraphicsEnvironment.getAllFonts method.
     */

    @Deprecated
    protected abstract FontPeer getFontPeer(String a0, int a1);

    /**
     * Gets the image from the specified file which contains image data in a
     * supported image format (such as JPEG, GIF, or PNG); this method should
     * return the same Image for multiple calls of this method with the same
     * image file name.
     * 
     * @param a0
     *            the file name which contains image data in a supported image
     *            format (such as JPEG, GIF, or PNG).
     * @return the Image.
     */
    public abstract Image getImage(String a0);

    /**
     * Gets the image from the specified URL which contains image data in a
     * supported image format (such as JPEG, GIF, or PNG); this method should
     * return the same Image for multiple calls of this method with the same
     * image URL.
     * 
     * @param a0
     *            the URL which contains image data in a supported image format
     *            (such as JPEG, GIF, or PNG).
     * @return the Image.
     */
    public abstract Image getImage(URL a0);

    /**
     * Gets the screen resolution.
     * 
     * @return the screen resolution.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public abstract int getScreenResolution() throws HeadlessException;

    /**
     * Gets the screen size.
     * 
     * @return a Dimension object containing the width and height of the screen.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public abstract Dimension getScreenSize() throws HeadlessException;

    /**
     * Gets the EventQueue instance without checking access.
     * 
     * @return the system EventQueue.
     */
    protected abstract EventQueue getSystemEventQueueImpl();

    /**
     * Returns a map of text attributes for the abstract level description of
     * the specified input method highlight, or null if no mapping is found.
     * 
     * @param highlight
     *            the InputMethodHighlight.
     * @return the Map<java.awt.font. text attribute,?>.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public abstract Map<java.awt.font.TextAttribute, ?> mapInputMethodHighlight(
            InputMethodHighlight highlight) throws HeadlessException;

    /**
     * Map input method highlight impl.
     * 
     * @param highlight
     *            the highlight.
     * @return the map<java.awt.font. text attribute,?>.
     * @throws HeadlessException
     *             the headless exception.
     */
    Map<java.awt.font.TextAttribute, ?> mapInputMethodHighlightImpl(InputMethodHighlight highlight)
            throws HeadlessException {
        HashMap<java.awt.font.TextAttribute, ?> map = new HashMap<java.awt.font.TextAttribute, Object>();
        wtk.getSystemProperties().mapInputMethodHighlight(highlight, map);
        return Collections.<java.awt.font.TextAttribute, Object> unmodifiableMap(map);
    }

    /**
     * Adds the specified PropertyChangeListener listener for the specified
     * property.
     * 
     * @param propName
     *            the property name for which the specified
     *            PropertyChangeListener will be added.
     * @param l
     *            the PropertyChangeListener object.
     */
    public void addPropertyChangeListener(String propName, PropertyChangeListener l) {
        lockAWT();
        try {
            if (desktopProperties.isEmpty()) {
                initializeDesktopProperties();
            }
        } finally {
            unlockAWT();
        }
        if (l != null) { // there is no guarantee that null listener will not be
            // added
            desktopPropsSupport.addPropertyChangeListener(propName, l);
        }
    }

    /**
     * Returns an array of the property change listeners registered with this
     * Toolkit.
     * 
     * @return an array of the property change listeners registered with this
     *         Toolkit.
     */
    public PropertyChangeListener[] getPropertyChangeListeners() {
        return desktopPropsSupport.getPropertyChangeListeners();
    }

    /**
     * Returns an array of the property change listeners registered with this
     * Toolkit for notification regarding the specified property.
     * 
     * @param propName
     *            the property name for which the PropertyChangeListener was
     *            registered.
     * @return the array of PropertyChangeListeners registered for the specified
     *         property name.
     */
    public PropertyChangeListener[] getPropertyChangeListeners(String propName) {
        return desktopPropsSupport.getPropertyChangeListeners(propName);
    }

    /**
     * Removes the specified property change listener registered for the
     * specified property name.
     * 
     * @param propName
     *            the property name.
     * @param l
     *            the PropertyChangeListener registered for the specified
     *            property name.
     */
    public void removePropertyChangeListener(String propName, PropertyChangeListener l) {
        desktopPropsSupport.removePropertyChangeListener(propName, l);
    }

    /**
     * Creates a custom cursor with the specified Image, hot spot, and cursor
     * description.
     * 
     * @param img
     *            the image of activated cursor.
     * @param hotSpot
     *            the Point giving the coordinates of the cursor's hot spot.
     * @param name
     *            the cursor description.
     * @return the cursor with the specified Image, hot spot, and cursor
     *         description.
     * @throws IndexOutOfBoundsException
     *             if the hot spot values are outside the bounds of the cursor.
     * @throws HeadlessException
     *             if isHeadless() method of GraphicsEnvironment class returns
     *             true.
     */
    public Cursor createCustomCursor(Image img, Point hotSpot, String name)
            throws IndexOutOfBoundsException, HeadlessException {
        lockAWT();
        try {
            int w = img.getWidth(null), x = hotSpot.x;
            int h = img.getHeight(null), y = hotSpot.y;
            if (x < 0 || x >= w || y < 0 || y >= h) {
                // awt.7E=invalid hotSpot
                throw new IndexOutOfBoundsException(Messages.getString("awt.7E")); //$NON-NLS-1$
            }
            return new Cursor(name, img, hotSpot);
        } finally {
            unlockAWT();
        }
    }

    /**
     * Returns the supported cursor dimension which is closest to the specified
     * width and height. If the Toolkit only supports a single cursor size, this
     * method should return the supported cursor size. If custom cursor is not
     * supported, a dimension of 0, 0 should be returned.
     * 
     * @param prefWidth
     *            the preferred cursor width.
     * @param prefHeight
     *            the preferred cursor height.
     * @return the supported cursor dimension which is closest to the specified
     *         width and height.
     * @throws HeadlessException
     *             if GraphicsEnvironment.isHeadless() returns true.
     */
    public Dimension getBestCursorSize(int prefWidth, int prefHeight) throws HeadlessException {
        lockAWT();
        try {
            return wtk.getCursorFactory().getBestCursorSize(prefWidth, prefHeight);
        } finally {
            unlockAWT();
        }
    }

    /**
     * Gets the value for the specified desktop property.
     * 
     * @param propName
     *            the property name.
     * @return the Object that is the property's value.
     */
    public final Object getDesktopProperty(String propName) {
        lockAWT();
        try {
            if (desktopProperties.isEmpty()) {
                initializeDesktopProperties();
            }
            if (propName.equals("awt.dynamicLayoutSupported")) { //$NON-NLS-1$
                // dynamicLayoutSupported is special case
                return Boolean.valueOf(isDynamicLayoutActive());
            }
            Object val = desktopProperties.get(propName);
            if (val == null) {
                // try to lazily load prop value
                // just for compatibility, our lazilyLoad is empty
                val = lazilyLoadDesktopProperty(propName);
            }
            return val;
        } finally {
            unlockAWT();
        }
    }

    /**
     * Returns the locking key state for the specified key.
     * 
     * @param a0
     *            the key code: VK_CAPS_LOCK, VK_NUM_LOCK, VK_SCROLL_LOCK, or
     *            VK_KANA_LOCK.
     * @return true if the specified key code is in the locked state, false
     *         otherwise.
     * @throws UnsupportedOperationException
     *             if the state of this key can't be retrieved, or if the
     *             keyboard doesn't have this key.
     * @throws NotImplementedException
     *             if this method is not implemented.
     */
    public boolean getLockingKeyState(int a0) throws UnsupportedOperationException,
            org.apache.harmony.luni.util.NotImplementedException {
        lockAWT();
        try {
        } finally {
            unlockAWT();
        }
        if (true) {
            throw new RuntimeException("Method is not implemented"); //TODO: implement //$NON-NLS-1$
        }
        return true;
    }

    /**
     * Returns the maximum number of colors which the Toolkit supports for
     * custom cursor.
     * 
     * @return the maximum cursor colors.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public int getMaximumCursorColors() throws HeadlessException {
        lockAWT();
        try {
            return wtk.getCursorFactory().getMaximumCursorColors();
        } finally {
            unlockAWT();
        }
    }

    /**
     * Gets the menu shortcut key mask.
     * 
     * @return the menu shortcut key mask.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public int getMenuShortcutKeyMask() throws HeadlessException {
        lockAWT();
        try {
            return InputEvent.CTRL_MASK;
        } finally {
            unlockAWT();
        }
    }

    /**
     * Gets the screen insets.
     * 
     * @param gc
     *            the GraphicsConfiguration.
     * @return the insets of this toolkit.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public Insets getScreenInsets(GraphicsConfiguration gc) throws HeadlessException {
        if (gc == null) {
            throw new NullPointerException();
        }
        lockAWT();
        try {
            return new Insets(0, 0, 0, 0); // TODO: get real screen insets
        } finally {
            unlockAWT();
        }
    }

    /**
     * Gets the system EventQueue instance. If the default implementation of
     * checkAwtEventQueueAccess is used, then this results of a call to the
     * security manager's checkPermission method with an
     * AWTPermission("accessEventQueue") permission.
     * 
     * @return the system EventQueue instance.
     */
    public final EventQueue getSystemEventQueue() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkAwtEventQueueAccess();
        }
        return getSystemEventQueueImpl();
    }

    /**
     * Gets the system event queue core.
     * 
     * @return the system event queue core.
     */
    EventQueueCore getSystemEventQueueCore() {
        return systemEventQueueCore;
    }

    /**
     * Sets the system event queue core.
     * 
     * @param core
     *            the new system event queue core.
     */
    void setSystemEventQueueCore(EventQueueCore core) {
        systemEventQueueCore = core;
    }

    /**
     * Initialize the desktop properties.
     */
    protected void initializeDesktopProperties() {
        lockAWT();
        try {
            wtk.getSystemProperties().init(desktopProperties);
        } finally {
            unlockAWT();
        }
    }

    /**
     * Checks if dynamic layout of Containers is active or not.
     * 
     * @return true, if is dynamic layout of Containers is active, false
     *         otherwise.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public boolean isDynamicLayoutActive() throws HeadlessException {
        lockAWT();
        try {
            // always return true
            return true;
        } finally {
            unlockAWT();
        }
    }

    /**
     * Returns if the layout of Containers is checked dynamically during
     * resizing, or statically after resizing is completed.
     * 
     * @return true, if if the layout of Containers is checked dynamically
     *         during resizing; false, if the layout of Containers is checked
     *         statically after resizing is completed.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    protected boolean isDynamicLayoutSet() throws HeadlessException {
        lockAWT();
        try {
            return bDynamicLayoutSet;
        } finally {
            unlockAWT();
        }
    }

    /**
     * Checks if the specified frame state is supported by Toolkit or not.
     * 
     * @param state
     *            the frame state.
     * @return true, if frame state is supported, false otherwise.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public boolean isFrameStateSupported(int state) throws HeadlessException {
        lockAWT();
        try {
            return wtk.getWindowFactory().isWindowStateSupported(state);
        } finally {
            unlockAWT();
        }
    }

    /**
     * Loads the value of the desktop property with the specified property name.
     * 
     * @param propName
     *            the property name.
     * @return the desktop property values.
     */
    protected Object lazilyLoadDesktopProperty(String propName) {
        return null;
    }

    /**
     * Loads the current system color values to the specified array.
     * 
     * @param colors
     *            the array where the current system color values are written by
     *            this method.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    protected void loadSystemColors(int[] colors) throws HeadlessException {
        lockAWT();
        try {
        } finally {
            unlockAWT();
        }
    }

    /**
     * Sets the value of the desktop property with the specified name.
     * 
     * @param propName
     *            the property's name.
     * @param value
     *            the property's value.
     */
    protected final void setDesktopProperty(String propName, Object value) {
        Object oldVal;
        lockAWT();
        try {
            oldVal = getDesktopProperty(propName);
            userPropSet.add(propName);
            desktopProperties.put(propName, value);
        } finally {
            unlockAWT();
        }
        desktopPropsSupport.firePropertyChange(propName, oldVal, value);
    }

    /**
     * Sets the layout state, whether the Container layout is checked
     * dynamically during resizing, or statically after resizing is completed.
     * 
     * @param dynamic
     *            the new dynamic layout state - if true the layout of
     *            Containers is checked dynamically during resizing, if false -
     *            statically after resizing is completed.
     * @throws HeadlessException
     *             if the GraphicsEnvironment.isHeadless() method returns true.
     */
    public void setDynamicLayout(boolean dynamic) throws HeadlessException {
        lockAWT();
        try {
            bDynamicLayoutSet = dynamic;
        } finally {
            unlockAWT();
        }
    }

    /**
     * Sets the locking key state for the specified key code.
     * 
     * @param a0
     *            the key code: VK_CAPS_LOCK, VK_NUM_LOCK, VK_SCROLL_LOCK, or
     *            VK_KANA_LOCK.
     * @param a1
     *            the state - true to set the specified key code to the locked
     *            state, false - to unlock it.
     * @throws UnsupportedOperationException
     *             if the state of this key can't be set, or if the keyboard
     *             doesn't have this key.
     * @throws NotImplementedException
     *             if this method is not implemented.
     */
    public void setLockingKeyState(int a0, boolean a1) throws UnsupportedOperationException,
            org.apache.harmony.luni.util.NotImplementedException {
        lockAWT();
        try {
        } finally {
            unlockAWT();
        }
        if (true) {
            throw new RuntimeException("Method is not implemented"); //TODO: implement //$NON-NLS-1$
        }
        return;
    }

    /**
     * On queue empty.
     */
    void onQueueEmpty() {
        throw new RuntimeException("Not implemented!");
    }

    /**
     * Creates the wtk.
     * 
     * @param clsName
     *            the cls name.
     * @return the wTK.
     */
    private WTK createWTK(String clsName) {
        WTK newWTK = null;
        try {
            newWTK = (WTK)Class.forName(clsName).newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return newWTK;
    }

    /**
     * Connect the component to its native window
     * 
     * @param winId
     *            the id of native window just created.
     */
    boolean onWindowCreated(long winId) {
        return false;
    }

    /**
     * Gets the native event queue.
     * 
     * @return the native event queue.
     */
    NativeEventQueue getNativeEventQueue() {
        return wtk.getNativeEventQueue();
    }

    /**
     * Returns a shared instance of implementation of
     * org.apache.harmony.awt.wtk.NativeCursor for current platform for.
     * 
     * @param type
     *            the Java Cursor type.
     * @return new instance of implementation of NativeCursor.
     */
    NativeCursor createNativeCursor(int type) {
        return wtk.getCursorFactory().getCursor(type);
    }

    /**
     * Returns a shared instance of implementation of
     * org.apache.harmony.awt.wtk.NativeCursor for current platform for custom
     * cursor
     * 
     * @param img
     *            the img.
     * @param hotSpot
     *            the hot spot.
     * @param name
     *            the name.
     * @return new instance of implementation of NativeCursor.
     */
    NativeCursor createCustomNativeCursor(Image img, Point hotSpot, String name) {
        return wtk.getCursorFactory().createCustomCursor(img, hotSpot.x, hotSpot.y);
    }

    /**
     * Adds an AWTEventListener to the Toolkit to listen for events of types
     * corresponding to bits in the specified event mask. Event masks are
     * defined in AWTEvent class.
     * 
     * @param listener
     *            the AWTEventListener.
     * @param eventMask
     *            the bitmask of event types.
     */
    public void addAWTEventListener(AWTEventListener listener, long eventMask) {
        lockAWT();
        try {
            SecurityManager security = System.getSecurityManager();
            if (security != null) {
                security.checkPermission(awtEventsManager.permission);
            }
            awtEventsManager.addAWTEventListener(listener, eventMask);
        } finally {
            unlockAWT();
        }
    }

    /**
     * Removes the specified AWT event listener.
     * 
     * @param listener
     *            the AWTEventListener to be removed.
     */
    public void removeAWTEventListener(AWTEventListener listener) {
        lockAWT();
        try {
            SecurityManager security = System.getSecurityManager();
            if (security != null) {
                security.checkPermission(awtEventsManager.permission);
            }
            awtEventsManager.removeAWTEventListener(listener);
        } finally {
            unlockAWT();
        }
    }

    /**
     * Gets the array of all AWT event listeners registered with this Toolkit.
     * 
     * @return the array of all AWT event listeners registered with this
     *         Toolkit.
     */
    public AWTEventListener[] getAWTEventListeners() {
        lockAWT();
        try {
            SecurityManager security = System.getSecurityManager();
            if (security != null) {
                security.checkPermission(awtEventsManager.permission);
            }
            return awtEventsManager.getAWTEventListeners();
        } finally {
            unlockAWT();
        }
    }

    /**
     * Returns the array of the AWT event listeners registered with this Toolkit
     * for the event types corresponding to the specified event mask.
     * 
     * @param eventMask
     *            the bit mask of event type.
     * @return the array of the AWT event listeners registered in this Toolkit
     *         for the event types corresponding to the specified event mask.
     */
    public AWTEventListener[] getAWTEventListeners(long eventMask) {
        lockAWT();
        try {
            SecurityManager security = System.getSecurityManager();
            if (security != null) {
                security.checkPermission(awtEventsManager.permission);
            }
            return awtEventsManager.getAWTEventListeners(eventMask);
        } finally {
            unlockAWT();
        }
    }

    /**
     * Dispatch AWT event.
     * 
     * @param event
     *            the event.
     */
    void dispatchAWTEvent(AWTEvent event) {
        awtEventsManager.dispatchAWTEvent(event);
    }

    /**
     * The Class AWTEventsManager.
     */
    final class AWTEventsManager {

        /**
         * The permission.
         */
        AWTPermission permission = new AWTPermission("listenToAllAWTEvents"); //$NON-NLS-1$

        /**
         * The listeners.
         */
        private final AWTListenerList<AWTEventListenerProxy> listeners = new AWTListenerList<AWTEventListenerProxy>();

        /**
         * Adds the AWT event listener.
         * 
         * @param listener
         *            the listener.
         * @param eventMask
         *            the event mask.
         */
        void addAWTEventListener(AWTEventListener listener, long eventMask) {
            if (listener != null) {
                listeners.addUserListener(new AWTEventListenerProxy(eventMask, listener));
            }
        }

        /**
         * Removes the AWT event listener.
         * 
         * @param listener
         *            the listener.
         */
        void removeAWTEventListener(AWTEventListener listener) {
            if (listener != null) {
                for (AWTEventListenerProxy proxy : listeners.getUserListeners()) {
                    if (listener == proxy.getListener()) {
                        listeners.removeUserListener(proxy);
                        return;
                    }
                }
            }
        }

        /**
         * Gets the AWT event listeners.
         * 
         * @return the AWT event listeners.
         */
        AWTEventListener[] getAWTEventListeners() {
            HashSet<EventListener> listenersSet = new HashSet<EventListener>();
            for (AWTEventListenerProxy proxy : listeners.getUserListeners()) {
                listenersSet.add(proxy.getListener());
            }
            return listenersSet.toArray(new AWTEventListener[listenersSet.size()]);
        }

        /**
         * Gets the AWT event listeners.
         * 
         * @param eventMask
         *            the event mask.
         * @return the AWT event listeners.
         */
        AWTEventListener[] getAWTEventListeners(long eventMask) {
            HashSet<EventListener> listenersSet = new HashSet<EventListener>();
            for (AWTEventListenerProxy proxy : listeners.getUserListeners()) {
                if ((proxy.getEventMask() & eventMask) == eventMask) {
                    listenersSet.add(proxy.getListener());
                }
            }
            return listenersSet.toArray(new AWTEventListener[listenersSet.size()]);
        }

        /**
         * Dispatch AWT event.
         * 
         * @param event
         *            the event.
         */
        void dispatchAWTEvent(AWTEvent event) {
            AWTEvent.EventDescriptor descriptor = eventTypeLookup.getEventDescriptor(event);
            if (descriptor == null) {
                return;
            }
            for (AWTEventListenerProxy proxy : listeners.getUserListeners()) {
                if ((proxy.getEventMask() & descriptor.eventMask) != 0) {
                    proxy.eventDispatched(event);
                }
            }
        }
    }

    /**
     * The Class AutoNumber.
     */
    static final class AutoNumber {

        /**
         * The next component.
         */
        int nextComponent = 0;

        /**
         * The next canvas.
         */
        int nextCanvas = 0;

        /**
         * The next panel.
         */
        int nextPanel = 0;

        /**
         * The next window.
         */
        int nextWindow = 0;

        /**
         * The next frame.
         */
        int nextFrame = 0;

        /**
         * The next dialog.
         */
        int nextDialog = 0;

        /**
         * The next button.
         */
        int nextButton = 0;

        /**
         * The next menu component.
         */
        int nextMenuComponent = 0;

        /**
         * The next label.
         */
        int nextLabel = 0;

        /**
         * The next check box.
         */
        int nextCheckBox = 0;

        /**
         * The next scrollbar.
         */
        int nextScrollbar = 0;

        /**
         * The next scroll pane.
         */
        int nextScrollPane = 0;

        /**
         * The next list.
         */
        int nextList = 0;

        /**
         * The next choice.
         */
        int nextChoice = 0;

        /**
         * The next file dialog.
         */
        int nextFileDialog = 0;

        /**
         * The next text area.
         */
        int nextTextArea = 0;

        /**
         * The next text field.
         */
        int nextTextField = 0;
    }

    private class Lock {
    }

    /**
     * The lock.
     */
    private final Object lock = new Lock();

}
