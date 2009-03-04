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

import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * The BufferedImageOp interface provides methods for performing transformations
 * from source data to destination data for BufferedImage objects. An object
 * implementing this interface can be passed into a BufferedImageFilter to
 * operate on a BufferedImage.
 * 
 * @since Android 1.0
 */
public interface BufferedImageOp {

    /**
     * Creates a destination image with the specified BufferedImage and
     * ColorModel; this destination image is empty and has the correct size and
     * number of bands.
     * 
     * @param src
     *            the source BufferedImage.
     * @param destCM
     *            the destination ColorModel.
     * @return the BufferedImage.
     */
    public BufferedImage createCompatibleDestImage(BufferedImage src, ColorModel destCM);

    /**
     * Performs a filter operation on the source BufferedImage and stores the
     * resulting BufferedImage to the destination BufferedImage. If the
     * destination BufferedImage is null, a BufferedImage with an appropriate
     * ColorModel is created.
     * 
     * @param src
     *            the source BufferedImage.
     * @param dest
     *            the destination BufferedImage, where the result is stored.
     * @return the filtered BufferedImage.
     */
    public BufferedImage filter(BufferedImage src, BufferedImage dest);

    /**
     * Gets the bounds of filtered image.
     * 
     * @param src
     *            the source BufferedImage to be filtered.
     * @return the rectangle bounds of filtered image.
     */
    public Rectangle2D getBounds2D(BufferedImage src);

    /**
     * Gets the point of the destination image which corresponds to the
     * specified point in the source image.
     * 
     * @param srcPt
     *            the point of the source image.
     * @param dstPt
     *            the point where the result will be stored.
     * @return the destination point.
     */
    public Point2D getPoint2D(Point2D srcPt, Point2D dstPt);

    /**
     * Gets the RenderingHints of the BufferedImageOp.
     * 
     * @return the RenderingHints of the BufferedImageOp.
     */
    public RenderingHints getRenderingHints();
}
