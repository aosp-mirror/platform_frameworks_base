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
 * @author Alexey A. Petrenko
 * @version $Revision$
 */

package java.awt;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The GraphicsDevice class describes the graphics devices (such as screens or
 * printers) which are available in a particular graphics environment. Many
 * GraphicsDevice instances can be associated with a single GraphicsEnvironment.
 * Each GraphicsDevice has one or more GraphicsConfiguration objects which
 * specify the different configurations and modes of GraphicsDevice.
 * 
 * @since Android 1.0
 */
public abstract class GraphicsDevice {

    /**
     * The display mode.
     */
    private DisplayMode displayMode;

    // ???AWT
    // private Window fullScreenWindow = null;

    /**
     * The Constant TYPE_IMAGE_BUFFER indicates a image buffer device.
     */

    public static final int TYPE_IMAGE_BUFFER = 2;

    /**
     * The Constant TYPE_PRINTER indicates a printer device.
     */
    public static final int TYPE_PRINTER = 1;

    /**
     * The Constant TYPE_RASTER_SCREEN indicates a raster screen device.
     */
    public static final int TYPE_RASTER_SCREEN = 0;

    /**
     * Constructor is not to be used directly as this class is abstract.
     */
    protected GraphicsDevice() {
        displayMode = new DisplayMode(0, 0, DisplayMode.BIT_DEPTH_MULTI,
                DisplayMode.REFRESH_RATE_UNKNOWN);
    }

    /**
     * Returns an array of GraphicsConfiguration objects associated with the
     * GraphicsDevice.
     * 
     * @return an array of GraphicsConfiguration objects associated with the
     *         GraphicsDevice.
     */
    public abstract GraphicsConfiguration[] getConfigurations();

    /**
     * Gets the default configuration for the GraphicsDevice.
     * 
     * @return the default GraphicsConfiguration object for the GraphicsDevice.
     */
    public abstract GraphicsConfiguration getDefaultConfiguration();

    /**
     * Gets the String identifier which associated with the GraphicsDevice in
     * the GraphicsEnvironment.
     * 
     * @return the String identifier of the GraphicsDevice in the
     *         GraphicsEnvironment.
     */
    public abstract String getIDstring();

    /**
     * Gets the type of this GraphicsDevice: TYPE_IMAGE_BUFFER, TYPE_PRINTER or
     * TYPE_RASTER_SCREEN.
     * 
     * @return the type of this GraphicsDevice: TYPE_IMAGE_BUFFER, TYPE_PRINTER
     *         or TYPE_RASTER_SCREEN.
     */
    public abstract int getType();

    /**
     * Returns the number of bytes available in accelerated memory on this
     * device.
     * 
     * @return the number of bytes available accelerated memory.
     */
    public int getAvailableAcceleratedMemory() {
        return 0;
    }

    /*
     * ???AWT public GraphicsConfiguration
     * getBestConfiguration(GraphicsConfigTemplate gct) { return
     * gct.getBestConfiguration(getConfigurations()); }
     */

    /**
     * Gets the current display mode of the GraphicsDevice.
     * 
     * @return the current display mode of the GraphicsDevice.
     */
    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    /**
     * Gets an array of display modes available in this GraphicsDevice.
     * 
     * @return an array of display modes available in this GraphicsDevice.
     */
    public DisplayMode[] getDisplayModes() {
        DisplayMode[] dms = {
            displayMode
        };
        return dms;
    }

    /*
     * ???AWT public Window getFullScreenWindow() { return fullScreenWindow; }
     */

    /**
     * Returns true if this GraphicsDevice supports low-level display changes.
     * 
     * @return true, if this GraphicsDevice supports low-level display changes;
     *         false otherwise.
     */
    public boolean isDisplayChangeSupported() {
        return false;
    }

    /**
     * Returns true if this GraphicsDevice supports full screen mode.
     * 
     * @return true, if this GraphicsDevice supports full screen mode, false
     *         otherwise.
     */
    public boolean isFullScreenSupported() {
        return false;
    }

    // an array of display modes available in this GraphicsDevice.

    /**
     * Sets the display mode of this GraphicsDevice.
     * 
     * @param dm
     *            the new display mode of this GraphicsDevice.
     */
    public void setDisplayMode(DisplayMode dm) {
        if (!isDisplayChangeSupported()) {
            // awt.122=Does not support display mode changes
            throw new UnsupportedOperationException(Messages.getString("awt.122")); //$NON-NLS-1$
        }

        DisplayMode[] dms = getDisplayModes();
        for (DisplayMode element : dms) {
            if (element.equals(dm)) {
                displayMode = dm;
                return;
            }
        }
        // awt.123=Unsupported display mode: {0}
        throw new IllegalArgumentException(Messages.getString("awt.123", dm)); //$NON-NLS-1$
    }

    /*
     * ???AWT public void setFullScreenWindow(Window w) { if (w == null) {
     * fullScreenWindow = null; return; } fullScreenWindow = w; if
     * (isFullScreenSupported()) { w.enableInputMethods(false); } else {
     * w.setSize(displayMode.getWidth(), displayMode.getHeight());
     * w.setLocation(0, 0); } w.setVisible(true); w.setAlwaysOnTop(true); }
     */
}
