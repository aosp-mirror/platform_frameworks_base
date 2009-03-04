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

package java.awt.image;

import java.util.Hashtable;

/**
 * The ImageConsumer interface provides the data about the image and about how
 * its data is delivered. A ImageProducer provides all of the information about
 * the image using the methods defined in this interface.
 * 
 * @since Android 1.0
 */
public interface ImageConsumer {

    /**
     * The Constant RANDOMPIXELORDER indicates that the pixels are delivered in
     * a random order.
     */
    public static final int RANDOMPIXELORDER = 1;

    /**
     * The Constant TOPDOWNLEFTRIGHT indicates that the pixels are delivered in
     * top-down, left-to-right order.
     */
    public static final int TOPDOWNLEFTRIGHT = 2;

    /**
     * The Constant COMPLETESCANLINES indicates that the pixels are delivered in
     * complete scanline.
     */
    public static final int COMPLETESCANLINES = 4;

    /**
     * The Constant SINGLEPASS indicates that pixels are delivered in a single
     * pass.
     */
    public static final int SINGLEPASS = 8;

    /**
     * The Constant SINGLEFRAME indicates that image consists of single frame.
     */
    public static final int SINGLEFRAME = 16;

    /**
     * The Constant IMAGEERROR indicates an image error during image producing.
     */
    public static final int IMAGEERROR = 1;

    /**
     * The Constant SINGLEFRAMEDONE indicates that only one of the image's
     * frames is completed.
     */
    public static final int SINGLEFRAMEDONE = 2;

    /**
     * The Constant STATICIMAGEDONE indicates that the image is completed.
     */
    public static final int STATICIMAGEDONE = 3;

    /**
     * The Constant IMAGEABORTED indicates that the image producing process is
     * aborted.
     */
    public static final int IMAGEABORTED = 4;

    /**
     * Sets the properties for the image associated with this ImageConsumer.
     * 
     * @param props
     *            the properties for the image associated with this
     *            ImageConsumer.
     */
    public void setProperties(Hashtable<?, ?> props);

    /**
     * Sets the ColorModel object.
     * 
     * @param model
     *            the new ColorModel.
     */
    public void setColorModel(ColorModel model);

    /**
     * Sets the pixels for the specified rectangular area of the image.
     * 
     * @param x
     *            the X coordinate of rectangular area.
     * @param y
     *            the Y coordinate of rectangular area.
     * @param w
     *            the width of rectangular area.
     * @param h
     *            the height of rectangular area.
     * @param model
     *            the specified ColorModel to be used for pixels converting.
     * @param pixels
     *            the array of pixels.
     * @param off
     *            the offset of pixels array.
     * @param scansize
     *            the distance from the one row of pixels to the next row in the
     *            specified array.
     */
    public void setPixels(int x, int y, int w, int h, ColorModel model, int[] pixels, int off,
            int scansize);

    /**
     * Sets the pixels for the specified rectangular area of the image.
     * 
     * @param x
     *            the X coordinate of rectangular area.
     * @param y
     *            the Y coordinate of rectangular area.
     * @param w
     *            the width of rectangular area.
     * @param h
     *            the height of rectangular area.
     * @param model
     *            the specified ColorModel to be used for pixels converting.
     * @param pixels
     *            the array of pixels.
     * @param off
     *            the offset of pixels array.
     * @param scansize
     *            the distance from the one row of pixels to the next row in the
     *            specified array.
     */
    public void setPixels(int x, int y, int w, int h, ColorModel model, byte[] pixels, int off,
            int scansize);

    /**
     * Sets the dimensions of a source image.
     * 
     * @param width
     *            the width of the image.
     * @param height
     *            the height of the image.
     */
    public void setDimensions(int width, int height);

    /**
     * Sets the hint flags of pixels order, which is used by the ImageConsumer
     * for obtaining pixels from the ImageProducer for which this ImageConsumer
     * is added.
     * 
     * @param hintflags
     *            the mask of hint flags.
     */
    public void setHints(int hintflags);

    /**
     * THis method is called in the one of the following cases:
     * <ul>
     * <li>The ImageProducer (for which this ImageConsumer is added) has been
     * delivered all pixels of the source image.</li>
     * <li>A one frame of an animation has been completed.</li>
     * <li>An error while loading or producing of the image has occurred.
     * </ul>
     * 
     * @param status
     *            the status of image producing.
     */
    public void imageComplete(int status);

}
