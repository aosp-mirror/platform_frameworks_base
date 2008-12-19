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
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */

package java.awt;

import java.awt.image.BufferedImage;
import java.util.Locale;

import org.apache.harmony.awt.ContextStorage;
import org.apache.harmony.awt.gl.CommonGraphics2DFactory;

/**
 * The GraphicsEnvironment class defines a collection of GraphicsDevice objects
 * and Font objects which are available for Java application on current
 * platform.
 * 
 * @since Android 1.0
 */
public abstract class GraphicsEnvironment {

    /**
     * Constructor could not be used directly and should be obtained in extended
     * classes.
     */
    protected GraphicsEnvironment() {
    }

    /**
     * Gets the local GraphicsEnvironment.
     * 
     * @return the local GraphicsEnvironment.
     */
    public static GraphicsEnvironment getLocalGraphicsEnvironment() {
        synchronized (ContextStorage.getContextLock()) {
            if (ContextStorage.getGraphicsEnvironment() == null) {
                if (isHeadless()) {
                    ContextStorage.setGraphicsEnvironment(new HeadlessGraphicsEnvironment());
                } else {
                    CommonGraphics2DFactory g2df = (CommonGraphics2DFactory)Toolkit
                            .getDefaultToolkit().getGraphicsFactory();

                    ContextStorage.setGraphicsEnvironment(g2df
                            .createGraphicsEnvironment(ContextStorage.getWindowFactory()));
                }
            }

            return ContextStorage.getGraphicsEnvironment();
        }
    }

    /**
     * Returns whether or not a display, keyboard, and mouse are supported in
     * this graphics environment.
     * 
     * @return true, if HeadlessException will be thrown from areas of the
     *         graphics environment that are dependent on a display, keyboard,
     *         or mouse, false otherwise.
     */
    public boolean isHeadlessInstance() {
        return false;
    }

    /**
     * Checks whether or not a display, keyboard, and mouse are supported in
     * this environment.
     * 
     * @return true, if a HeadlessException is thrown from areas of the Toolkit
     *         and GraphicsEnvironment that are dependent on a display,
     *         keyboard, or mouse, false otherwise.
     */
    public static boolean isHeadless() {
        return "true".equals(System.getProperty("java.awt.headless"));
    }

    /**
     * Gets the maximum bounds of system centered windows.
     * 
     * @return the maximum bounds of system centered windows.
     * @throws HeadlessException
     *             if isHeadless() method returns true.
     */
    public Rectangle getMaximumWindowBounds() throws HeadlessException {
        return getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    /**
     * Gets the Point which should defines the center of system window.
     * 
     * @return the Point where the system window should be centered.
     * @throws HeadlessException
     *             if isHeadless() method returns true.
     */
    public Point getCenterPoint() throws HeadlessException {
        Rectangle mwb = getMaximumWindowBounds();
        return new Point(mwb.width >> 1, mwb.height >> 1);
    }

    /**
     * Indicates that the primary font should be used. Primary font is specified
     * by initial system locale or default encoding).
     */
    public void preferLocaleFonts() {
        // Note: API specification says following:
        // "The actual change in font rendering behavior resulting
        // from a call to this method is implementation dependent;
        // it may have no effect at all." So, doing nothing is an
        // acceptable behavior for this method.

        // For now FontManager uses 1.4 font.properties scheme for font mapping,
        // so
        // this method doesn't make any sense. The implementation of this method
        // which will influence font mapping is postponed until
        // 1.5 mapping scheme not implemented.

        // todo - Implement non-default behavior with 1.5 font mapping scheme
    }

    /**
     * Indicates that a proportional preference of the font should be used.
     */
    public void preferProportionalFonts() {
        // Note: API specification says following:
        // "The actual change in font rendering behavior resulting
        // from a call to this method is implementation dependent;
        // it may have no effect at all." So, doing nothing is an
        // acceptable behavior for this method.

        // For now FontManager uses 1.4 font.properties scheme for font mapping,
        // so
        // this method doesn't make any sense. The implementation of this method
        // which will influence font mapping is postponed until
        // 1.5 mapping scheme not implemented.

        // todo - Implement non-default behavior with 1.5 font mapping scheme
    }

    /**
     * Creates the Graphics2D object for rendering to the specified
     * BufferedImage.
     * 
     * @param bufferedImage
     *            the BufferedImage object.
     * @return the Graphics2D object which allows to render to the specified
     *         BufferedImage.
     */
    public abstract Graphics2D createGraphics(BufferedImage bufferedImage);

    /**
     * Gets the array of all available fonts instances in this
     * GraphicsEnviroments.
     * 
     * @return the array of all available fonts instances in this
     *         GraphicsEnviroments.
     */
    public abstract Font[] getAllFonts();

    /**
     * Gets the array of all available font family names.
     * 
     * @return the array of all available font family names.
     */
    public abstract String[] getAvailableFontFamilyNames();

    /**
     * Gets the array of all available font family names for the specified
     * locale.
     * 
     * @param locale
     *            the Locale object which represents geographical region. The
     *            default locale is used if locale is null.
     * @return the array of available font family names for the specified
     *         locale.
     */
    public abstract String[] getAvailableFontFamilyNames(Locale locale);

    /**
     * Gets the default screen device as GraphicDevice object.
     * 
     * @return the GraphicDevice object which represents default screen device.
     * @throws HeadlessException
     *             if isHeadless() returns true.
     */
    public abstract GraphicsDevice getDefaultScreenDevice() throws HeadlessException;

    /**
     * Gets an array of all available screen devices.
     * 
     * @return the array of GraphicsDevice objects which represents all
     *         available screen devices.
     * @throws HeadlessException
     *             if isHeadless() returns true.
     */
    public abstract GraphicsDevice[] getScreenDevices() throws HeadlessException;
}
