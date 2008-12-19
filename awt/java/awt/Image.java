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
 * @author Igor V. Stolyarov
 * @version $Revision$
 */

package java.awt;

import java.awt.image.AreaAveragingScaleFilter;
import java.awt.image.FilteredImageSource;
import java.awt.image.ImageFilter;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;
import java.awt.image.ReplicateScaleFilter;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Image abstract class represents the graphic images.
 * 
 * @since Android 1.0
 */
public abstract class Image {

    /**
     * The UndefinedProperty object should be returned if property is not
     * defined for a particular image.
     */
    public static final Object UndefinedProperty = new Object(); // $NON-LOCK-1$

    /**
     * The Constant SCALE_DEFAULT indicates the default image scaling algorithm.
     */
    public static final int SCALE_DEFAULT = 1;

    /**
     * The Constant SCALE_FAST indicates an image scaling algorithm which places
     * a higher priority on scaling speed than on the image's smoothness.
     */
    public static final int SCALE_FAST = 2;

    /**
     * The Constant SCALE_SMOOTH indicates an image scaling algorithm which
     * places a higher priority on image smoothness than on scaling speed.
     */
    public static final int SCALE_SMOOTH = 4;

    /**
     * The Constant SCALE_REPLICATE indicates the image scaling algorithm in the
     * ReplicateScaleFilter class.
     */
    public static final int SCALE_REPLICATE = 8;

    /**
     * The Constant SCALE_AREA_AVERAGING indicates the area averaging image
     * scaling algorithm.
     */
    public static final int SCALE_AREA_AVERAGING = 16;

    /**
     * The acceleration priority indicates image acceleration.
     */
    protected float accelerationPriority = 0.5f;

    /**
     * The Constant capabilities.
     */
    private static final ImageCapabilities capabilities = new ImageCapabilities(false);

    /**
     * Gets the image property with the specified name. The UndefinedProperty
     * object should be return if the property is not specified for this image.
     * The return value should be null if the property is currently unknown yet
     * and the specified ImageObserver is to be notified later.
     * 
     * @param name
     *            the name of image's property.
     * @param observer
     *            the ImageObserver.
     * @return the Object which represents value of the specified property.
     */
    public abstract Object getProperty(String name, ImageObserver observer);

    /**
     * Gets the ImageProducer object which represents data of this Image.
     * 
     * @return the ImageProducer object which represents data of this Image.
     */
    public abstract ImageProducer getSource();

    /**
     * Gets the width of this image. The specified ImageObserver object is
     * notified when the width of this image is available.
     * 
     * @param observer
     *            the ImageObserver object which is is notified when the width
     *            of this image is available.
     * @return the width of image, or -1 if the width of this image is not
     *         available.
     */
    public abstract int getWidth(ImageObserver observer);

    /**
     * Gets the height of this image. The specified ImageObserver object is
     * notified when the height of this image is available.
     * 
     * @param observer
     *            the ImageObserver object which is is notified when the height
     *            of this image is available.
     * @return the height of image, or -1 if the height of this image is not
     *         available.
     */
    public abstract int getHeight(ImageObserver observer);

    /**
     * Gets the scaled instance of this Image. This method returns an Image
     * object constructed from the source of this image with the specified
     * width, height, and applied scaling algorithm.
     * 
     * @param width
     *            the width of scaled Image.
     * @param height
     *            the height of scaled Image.
     * @param hints
     *            the constant which indicates scaling algorithm.
     * @return the scaled Image.
     */
    public Image getScaledInstance(int width, int height, int hints) {
        ImageFilter filter;
        if ((hints & (SCALE_SMOOTH | SCALE_AREA_AVERAGING)) != 0) {
            filter = new AreaAveragingScaleFilter(width, height);
        } else {
            filter = new ReplicateScaleFilter(width, height);
        }
        ImageProducer producer = new FilteredImageSource(getSource(), filter);
        return Toolkit.getDefaultToolkit().createImage(producer);
    }

    /**
     * Gets a Graphics object for rendering this image. This method can be used
     * for off-screen images.
     * 
     * @return a Graphics object for rendering to this image.
     */
    public abstract Graphics getGraphics();

    /**
     * Flushes resources which are used by this Image object. This method resets
     * the image to the reconstructed state from the image's source.
     */
    public abstract void flush();

    /**
     * Gets the acceleration priority of this image.
     * 
     * @return the acceleration priority of this image.
     */
    public float getAccelerationPriority() {
        return accelerationPriority;
    }

    /**
     * Sets the acceleration priority for this image.
     * 
     * @param priority
     *            the new acceleration priority (value in the range 0-1).
     */
    public void setAccelerationPriority(float priority) {
        if (priority < 0 || priority > 1) {
            // awt.10A=Priority must be a value between 0 and 1, inclusive
            throw new IllegalArgumentException(Messages.getString("awt.10A")); //$NON-NLS-1$
        }
        accelerationPriority = priority;
    }

    /**
     * Gets an ImageCapabilities object of this Image object for the specified
     * GraphicsConfiguration.
     * 
     * @param gc
     *            the specified GraphicsConfiguration object (null value means
     *            default GraphicsConfiguration).
     * @return an ImageCapabilities object of this Image object for the
     *         specified GraphicsConfiguration.
     */
    public ImageCapabilities getCapabilities(GraphicsConfiguration gc) {
        // Note: common image is not accelerated.
        return capabilities;
    }
}
