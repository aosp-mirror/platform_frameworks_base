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

package java.awt.image;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.ImageCapabilities;
import java.awt.Transparency;

/**
 * The VolatileImage abstract class represents an image which can lose its
 * contents at any point. VolatileImage objects are device specific. This class
 * provides methods for checking if operation of this image are compatible for
 * the GraphicsConfiguration.
 * 
 * @since Android 1.0
 */
public abstract class VolatileImage extends Image
// Volatile image implements Transparency since 1.5
        implements Transparency {

    /**
     * The Constant IMAGE_INCOMPATIBLE indicates that this VolatileImage is not
     * applicable for the GraphicsConfiguration object.
     */
    public static final int IMAGE_INCOMPATIBLE = 2;

    /**
     * The Constant IMAGE_OK indicates that VolatileImage is ready for using.
     */
    public static final int IMAGE_OK = 0;

    /**
     * The Constant IMAGE_RESTORED indicates that VolatileImage will be ready to
     * use after restoring.
     */
    public static final int IMAGE_RESTORED = 1;

    /**
     * The transparency value of this image.
     */
    protected int transparency = OPAQUE;

    /**
     * Instantiates a new VolatileImage object.
     */
    public VolatileImage() {
        super();
    }

    /**
     * Returns true if rendering data is lost during validating. This method
     * should be called after rendering operation of image.
     * 
     * @return true, if contents lost during validating, false otherwise.
     */

    public abstract boolean contentsLost();

    /**
     * Creates a Graphics2D used to draw in this VolatileImage.
     * 
     * @return the Graphics2D object.
     */
    public abstract Graphics2D createGraphics();

    /**
     * Gets the ImageCapabilities of this VolatileImage.
     * 
     * @return the ImageCapabilities of this VolatileImage.
     */
    public abstract ImageCapabilities getCapabilities();

    /**
     * Gets the height of this VolatileImage.
     * 
     * @return the height of this VolatileImage.
     */
    public abstract int getHeight();

    /**
     * Gets a BufferedImage representation of current VolatileImage that won't
     * be affected by any changes to this VolatileImage.
     * 
     * @return a BufferedImage representation of current VolatileImage.
     */
    public abstract BufferedImage getSnapshot();

    /**
     * Gets the width of this VolatileImage.
     * 
     * @return the width of this VolatileImage.
     */
    public abstract int getWidth();

    /**
     * Validates the drawing surface of the image if the surface had been lost
     * and if the specified GraphicsConfiguration object is applicable to this
     * image.
     * 
     * @param gc
     *            the GraphicsConfiguration object.
     * @return one of the image status constants: IMAGE_OK, IMAGE_RESTORED or
     *         IMAGE_INCOMPATIBLE.
     */
    public abstract int validate(GraphicsConfiguration gc);

    @Override
    public void flush() {
    }

    @Override
    public Graphics getGraphics() {
        return createGraphics();
    }

    @Override
    public ImageProducer getSource() {
        return getSnapshot().getSource();
    }

    public int getTransparency() {
        return transparency;
    }
}
