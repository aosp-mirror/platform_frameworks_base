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

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.VolatileImage;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The GraphicsConfiguration class contains the characteristics of graphics
 * devices such as a printer or monitor, and represents device's capabilities
 * and modes. Many GraphicsConfiguration objects can be associated with single
 * graphics device.
 * 
 * @since Android 1.0
 */
public abstract class GraphicsConfiguration {

    /**
     * Constructor could not be used directly and should be obtained in extended
     * classes.
     */
    protected GraphicsConfiguration() {
    }

    /**
     * Creates BufferedImage image object with a data layout and color model
     * compatible with this GraphicsConfiguration with specified width and
     * height parameters.
     * 
     * @param width
     *            the width of BufferedImage.
     * @param height
     *            the height of BufferedImage.
     * @return the BufferedImage object with specified width and height
     *         parameters.
     */
    public abstract BufferedImage createCompatibleImage(int width, int height);

    /**
     * Creates a BufferedImage that has the specified width, height,
     * transparency and has a data layout and color model compatible with this
     * GraphicsConfiguration.
     * 
     * @param width
     *            the width of image.
     * @param height
     *            the height of image.
     * @param transparency
     *            the transparency mode.
     * @return the BufferedImage object.
     */
    public abstract BufferedImage createCompatibleImage(int width, int height, int transparency);

    /**
     * Creates a VolatileImage that has the specified width and height and has a
     * data layout and color model compatible with this GraphicsConfiguration.
     * 
     * @param width
     *            the width of image.
     * @param height
     *            the height of image.
     * @return the VolatileImage object.
     */
    public abstract VolatileImage createCompatibleVolatileImage(int width, int height);

    /**
     * Creates a VolatileImage that supports the specified width, height,
     * transparency and has a data layout and color model compatible with this
     * GraphicsConfiguration.
     * 
     * @param width
     *            the width of image.
     * @param height
     *            the height of image.
     * @param transparency
     *            the transparency mode.
     * @return the VolatileImage object.
     */
    public abstract VolatileImage createCompatibleVolatileImage(int width, int height,
            int transparency);

    /**
     * Gets the bounds of area covered by the GraphicsConfiguration in the
     * device coordinates space.
     * 
     * @return the Rectangle of GraphicsConfiguration's bounds.
     */
    public abstract Rectangle getBounds();

    /**
     * Gets the ColorModel of the GraphicsConfiguration.
     * 
     * @return the ColorModel object of the GraphicsConfiguration.
     */
    public abstract ColorModel getColorModel();

    /**
     * Gets the ColorModel of the GraphicsConfiguration which supports specified
     * Transparency.
     * 
     * @param transparency
     *            the Transparency mode: OPAQUE, BITMASK, or TRANSLUCENT.
     * @return the ColorModel of the GraphicsConfiguration which supports
     *         specified Transparency.
     */
    public abstract ColorModel getColorModel(int transparency);

    /**
     * Gets the default AffineTransform of the GraphicsConfiguration. This
     * method translates user coordinates to device coordinates.
     * 
     * @return the default AffineTransform of the GraphicsConfiguration.
     */
    public abstract AffineTransform getDefaultTransform();

    /**
     * Gets the GraphicsDevice of the GraphicsConfiguration.
     * 
     * @return the GraphicsDevice of the GraphicsConfiguration.
     */
    public abstract GraphicsDevice getDevice();

    /**
     * Gets the normalizing AffineTransform of the GraphicsConfiguration.
     * 
     * @return the normalizing AffineTransform of the GraphicsConfiguration.
     */
    public abstract AffineTransform getNormalizingTransform();

    /**
     * Creates VolatileImage with specified width, height, ImageCapabilities; a
     * data layout and color model compatible with this GraphicsConfiguration.
     * 
     * @param width
     *            the width of image.
     * @param height
     *            the height of image.
     * @param caps
     *            the ImageCapabilities object.
     * @return the VolatileImage which data layout and color model compatible
     *         with this GraphicsConfiguration.
     * @throws AWTException
     *             if ImageCapabilities is not supported by the
     *             GraphicsConfiguration.
     */
    public VolatileImage createCompatibleVolatileImage(int width, int height, ImageCapabilities caps)
            throws AWTException {
        VolatileImage res = createCompatibleVolatileImage(width, height);
        if (!res.getCapabilities().equals(caps)) {
            // awt.14A=Can not create VolatileImage with specified capabilities
            throw new AWTException(Messages.getString("awt.14A")); //$NON-NLS-1$
        }
        return res;
    }

    /**
     * Creates a VolatileImage with specified width, height, transparency and
     * ImageCapabilities; a data layout and color model compatible with this
     * GraphicsConfiguration.
     * 
     * @param width
     *            the width of image.
     * @param height
     *            the height of image.
     * @param caps
     *            the ImageCapabilities object.
     * @param transparency
     *            the Transparency mode: OPAQUE, BITMASK, or TRANSLUCENT.
     * @return the VolatileImage which data layout and color model compatible
     *         with this GraphicsConfiguration.
     * @throws AWTException
     *             if ImageCapabilities is not supported by the
     *             GraphicsConfiguration.
     */
    public VolatileImage createCompatibleVolatileImage(int width, int height,
            ImageCapabilities caps, int transparency) throws AWTException {
        VolatileImage res = createCompatibleVolatileImage(width, height, transparency);
        if (!res.getCapabilities().equals(caps)) {
            // awt.14A=Can not create VolatileImage with specified capabilities
            throw new AWTException(Messages.getString("awt.14A")); //$NON-NLS-1$
        }
        return res;
    }

    /**
     * Gets the buffering capabilities of the GraphicsConfiguration.
     * 
     * @return the BufferCapabilities object.
     */
    public BufferCapabilities getBufferCapabilities() {
        return new BufferCapabilities(new ImageCapabilities(false), new ImageCapabilities(false),
                BufferCapabilities.FlipContents.UNDEFINED);
    }

    /**
     * Gets the image capabilities of the GraphicsConfiguration.
     * 
     * @return the ImageCapabilities object.
     */
    public ImageCapabilities getImageCapabilities() {
        return new ImageCapabilities(false);
    }
}
