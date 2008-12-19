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
 * @author Sergey I. Salishev
 * @version $Revision: 1.2 $
 */

package javax.imageio;

import java.awt.Dimension;
import java.awt.image.BufferedImage;

/*
 * @author Sergey I. Salishev
 * @version $Revision: 1.2 $
 */

/**
 * The ImageReadParam class provides information to the ImageReader about how an
 * image is to be decoded.
 * 
 * @since Android 1.0
 */
public class ImageReadParam extends IIOParam {

    /**
     * This flag indicates if this ImageReadParam supports setting the source
     * rendering size.
     */
    protected boolean canSetSourceRenderSize;

    /**
     * The destination BufferedImage.
     */
    protected BufferedImage destination;

    /**
     * The destination bands.
     */
    protected int[] destinationBands;

    /**
     * The minimum progressive pass.
     */
    protected int minProgressivePass;

    /**
     * The number of progressive passes.
     */
    protected int numProgressivePasses;

    /**
     * The source render size.
     */
    protected Dimension sourceRenderSize;

    /**
     * Returns true if this ImageReaderParam supports rendering a source image
     * at an arbitrary size.
     * 
     * @return true, if this ImageReaderParam supports rendering a source image
     *         at an arbitrary size, false otherwise.
     */
    public boolean canSetSourceRenderSize() {
        return canSetSourceRenderSize;
    }

    /**
     * Gets the current destination image as BufferedImage.
     * 
     * @return the BufferedImage which represents the destination.
     */
    public BufferedImage getDestination() {
        return destination;
    }

    /**
     * Gets the indices of destination bands.
     * 
     * @return the array of destination bands.
     */
    public int[] getDestinationBands() {
        return destinationBands;
    }

    /**
     * Gets the index of the maximum pass to be decoded. This method returns
     * Integer.MAX_VALUE, if getSourceNumProgressivePasses() method returns
     * value that is equal to Integer.MAX_VALUE. Otherwise this method returns
     * getSourceMinProgressivePass() + getSourceNumProgressivePasses() - 1.
     * 
     * @return the index of the maximum pass to be decoded.
     */
    public int getSourceMaxProgressivePass() {
        if (getSourceNumProgressivePasses() == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return getSourceMinProgressivePass() + getSourceNumProgressivePasses() - 1;
    }

    /**
     * Gets the index of the minimum progressive pass that is decoded, default
     * is 0.
     * 
     * @return the index of the minimum progressive pass that is decoded,
     *         default is 0.
     */
    public int getSourceMinProgressivePass() {
        return minProgressivePass;
    }

    /**
     * Gets the number of progressive passes. The default value is
     * Integer.MAX_VALUE.
     * 
     * @return the number of progressive passes.
     */
    public int getSourceNumProgressivePasses() {
        return numProgressivePasses;
    }

    /**
     * Gets the dimension of source image which will be rendered during decoding
     * process.
     * 
     * @return the source render size.
     */
    public Dimension getSourceRenderSize() {
        return sourceRenderSize;
    }

    /**
     * Sets the specified destination image. This image will be used by read,
     * readAll, and readRaster methods, and a reference to it will be returned
     * by those methods.
     * 
     * @param destination
     *            the destination image.
     */
    public void setDestination(BufferedImage destination) {
        this.destination = destination;
    }

    /**
     * Sets the indices of the destination bands.
     * 
     * @param destinationBands
     *            the indices of the destination bands.
     */
    public void setDestinationBands(int[] destinationBands) {
        this.destinationBands = destinationBands;
    }

    @Override
    public void setDestinationType(ImageTypeSpecifier destinationType) {
        this.destinationType = destinationType;
    }

    /**
     * Sets the source progressive passes.
     * 
     * @param minPass
     *            the index of the minimum pass to be decoded.
     * @param numPasses
     *            the number of passes to be decoded.
     */
    public void setSourceProgressivePasses(int minPass, int numPasses) {
        minProgressivePass = minPass;
        numProgressivePasses = numPasses;
    }

    /**
     * Sets the dimension size of source image if an image can be rendered at an
     * arbitrary size.
     * 
     * @param size
     *            the size of rendered image.
     * @throws UnsupportedOperationException
     *             the unsupported operation exception.
     */
    public void setSourceRenderSize(Dimension size) throws UnsupportedOperationException {
        if (!canSetSourceRenderSize) {
            throw new UnsupportedOperationException("can't set source renderer size");
        }
        sourceRenderSize = size;
    }
}
