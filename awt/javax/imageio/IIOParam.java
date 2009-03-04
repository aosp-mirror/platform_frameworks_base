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
 * @author Rustem V. Rafikov
 * @version $Revision: 1.3 $
 */

package javax.imageio;

import java.awt.*;

/**
 * The IIOParam abstract class is superclass for ImageReadParam and
 * ImageWriteParam classes and provides methods and variables which they share.
 * 
 * @since Android 1.0
 */
public abstract class IIOParam {

    /**
     * The source region.
     */
    protected Rectangle sourceRegion;

    /**
     * The source x subsampling.
     */
    protected int sourceXSubsampling = 1;

    /**
     * The source y subsampling.
     */
    protected int sourceYSubsampling = 1;

    /**
     * The subsampling x offset.
     */
    protected int subsamplingXOffset;

    /**
     * The subsampling y offset.
     */
    protected int subsamplingYOffset;

    /**
     * The source bands.
     */
    protected int[] sourceBands;

    /**
     * The destination type.
     */
    protected ImageTypeSpecifier destinationType;

    /**
     * The destination offset.
     */
    protected Point destinationOffset = new Point(0, 0);

    /**
     * The default controller.
     */
    protected IIOParamController defaultController;

    /**
     * The controller.
     */
    protected IIOParamController controller;

    /**
     * Instantiates a new IIOParam.
     */
    protected IIOParam() {
    }

    /**
     * Sets the source region as a Rectangle object.
     * 
     * @param sourceRegion
     *            the Rectangle which specifies the source region.
     */
    public void setSourceRegion(Rectangle sourceRegion) {
        if (sourceRegion != null) {
            if (sourceRegion.x < 0) {
                throw new IllegalArgumentException("x < 0");
            }
            if (sourceRegion.y < 0) {
                throw new IllegalArgumentException("y < 0");
            }
            if (sourceRegion.width <= 0) {
                throw new IllegalArgumentException("width <= 0");
            }
            if (sourceRegion.height <= 0) {
                throw new IllegalArgumentException("height <= 0");
            }

            if (sourceRegion.width <= subsamplingXOffset) {
                throw new IllegalArgumentException("width <= subsamplingXOffset");
            }

            if (sourceRegion.height <= subsamplingYOffset) {
                throw new IllegalArgumentException("height <= subsamplingXOffset");
            }
            // -- clone it to avoid unexpected modifications
            this.sourceRegion = (Rectangle)sourceRegion.clone();
        } else {
            this.sourceRegion = null;
        }
    }

    /**
     * Gets the source region.
     * 
     * @return the source region as Rectangle.
     */
    public Rectangle getSourceRegion() {
        if (sourceRegion == null) {
            return null;
        }
        // -- clone it to avoid unexpected modifications
        return (Rectangle)sourceRegion.clone();
    }

    /**
     * Sets the source subsampling. The sourceXSubsampling and
     * sourceYSubsampling parameters specify the number of rows and columns to
     * advance after every source pixel.
     * 
     * @param sourceXSubsampling
     *            the source X subsampling.
     * @param sourceYSubsampling
     *            the source Y subsampling.
     * @param subsamplingXOffset
     *            the subsampling X offset.
     * @param subsamplingYOffset
     *            the subsampling Y offset.
     */
    public void setSourceSubsampling(int sourceXSubsampling, int sourceYSubsampling,
            int subsamplingXOffset, int subsamplingYOffset) {

        if (sourceXSubsampling <= 0) {
            throw new IllegalArgumentException("sourceXSubsampling <= 0");
        }
        if (sourceYSubsampling <= 0) {
            throw new IllegalArgumentException("sourceYSubsampling <= 0");
        }

        if (subsamplingXOffset <= 0 || subsamplingXOffset >= sourceXSubsampling) {
            throw new IllegalArgumentException("subsamplingXOffset is wrong");
        }

        if (subsamplingYOffset <= 0 || subsamplingYOffset >= sourceYSubsampling) {
            throw new IllegalArgumentException("subsamplingYOffset is wrong");
        }

        // -- does region contain pixels
        if (sourceRegion != null) {
            if (sourceRegion.width <= subsamplingXOffset
                    || sourceRegion.height <= subsamplingYOffset) {
                throw new IllegalArgumentException("there are no pixels in region");
            }
        }

        this.sourceXSubsampling = sourceXSubsampling;
        this.sourceYSubsampling = sourceYSubsampling;
        this.subsamplingXOffset = subsamplingXOffset;
        this.subsamplingYOffset = subsamplingYOffset;
    }

    /**
     * Gets the source X subsampling - the number of source columns to advance
     * for each pixel.
     * 
     * @return the source X subsampling.
     */
    public int getSourceXSubsampling() {
        return sourceXSubsampling;
    }

    /**
     * Gets the source Y subsampling - the number of source rows to advance for
     * each pixel.
     * 
     * @return the source Y subsampling.
     */
    public int getSourceYSubsampling() {
        return sourceYSubsampling;
    }

    /**
     * Gets the horizontal offset of the subsampling grid.
     * 
     * @return the horizontal offset of the subsampling grid.
     */
    public int getSubsamplingXOffset() {
        return subsamplingXOffset;
    }

    /**
     * Gets the vertical offset of the subsampling grid.
     * 
     * @return the vertical offset of the subsampling grid.
     */
    public int getSubsamplingYOffset() {
        return subsamplingYOffset;
    }

    /**
     * Sets the indices of the source bands.
     * 
     * @param sourceBands
     *            the indices of the source bands.
     */
    public void setSourceBands(int[] sourceBands) {
        // TODO implement
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Gets the array of source bands.
     * 
     * @return the array of source bands.
     */
    public int[] getSourceBands() {
        // TODO implement
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Sets the specified ImageTypeSpecifier for the destination image.
     * 
     * @param destinationType
     *            the ImageTypeSpecifier.
     */
    public void setDestinationType(ImageTypeSpecifier destinationType) {
        // TODO implement
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Gets the type of the destination image as an ImageTypeSpecifier. .
     * 
     * @return the ImageTypeSpecifier.
     */
    public ImageTypeSpecifier getDestinationType() {
        // TODO implement
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Sets the offset in the destination image where the decoded pixels are
     * placed as a result of reading, or specified an area to be written while
     * writing operation.
     * 
     * @param destinationOffset
     *            the destination offset.
     */
    public void setDestinationOffset(Point destinationOffset) {
        if (destinationOffset == null) {
            throw new IllegalArgumentException("destinationOffset == null!");
        }

        this.destinationOffset = (Point)destinationOffset.clone();
    }

    /**
     * Gets the offset in the destination image for placing pixels.
     * 
     * @return the offset in the destination image.
     */
    public Point getDestinationOffset() {
        return (Point)destinationOffset.clone();
    }

    /**
     * Sets the IIOParamController to this IIOParam object for providing
     * settings to this IIOParam.
     * 
     * @param controller
     *            the new IIOParamController.
     */
    public void setController(IIOParamController controller) {
        // TODO implement
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Gets the current IIOParamController controller for this IIOParam.
     * 
     * @return the current IIOParamController controller for this IIOParam.
     */
    public IIOParamController getController() {
        // TODO implement
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Gets the default IIOParamController controller for this IIOParam.
     * 
     * @return the default IIOParamController controller for this IIOParam, or
     *         null.
     */
    public IIOParamController getDefaultController() {
        // TODO implement
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Returns true if IIOParamController is installed for this IIOParam.
     * 
     * @return true, if IIOParamController is installed for this IIOParam, false
     *         otherwise.
     */
    public boolean hasController() {
        // TODO implement
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Activates the controller.
     * 
     * @return true, if successful, false otherwise.
     */
    public boolean activateController() {
        // TODO implement
        throw new UnsupportedOperationException("not implemented yet");
    }
}
