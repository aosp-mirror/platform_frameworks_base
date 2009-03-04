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
 * The RasterOp interface provides methods for performing transformations from
 * source data to destination data for Raster objects. The source and
 * destination objects should contain the appropriate number of bands for the
 * particular classes which implement this interface.
 * 
 * @since Android 1.0
 */
public interface RasterOp {

    /**
     * Creates a destination WritableRaster with the specified Raster; this
     * destination image data is empty and has the correct size and number of
     * bands.
     * 
     * @param src
     *            the source Raster.
     * @return the WritableRaster.
     */
    public WritableRaster createCompatibleDestRaster(Raster src);

    /**
     * Performs a filter operation on the source Raster and stores the resulting
     * image data to the destination WritableRaster.
     * 
     * @param src
     *            the source Raster.
     * @param dst
     *            the destination WritableRaster, where the result is stored.
     * @return the filtered WritableRaster.
     */
    public WritableRaster filter(Raster src, WritableRaster dst);

    /**
     * Gets the bounds of the filtered Raster.
     * 
     * @param src
     *            the source Raster to be filtered.
     * @return the rectangle bounds of the filtered Raster.
     */
    public Rectangle2D getBounds2D(Raster src);

    /**
     * Gets the point of the destination image which corresponds to the
     * specified point in the source raster.
     * 
     * @param srcPoint
     *            the point of the source raster.
     * @param dstPoint
     *            the point where the result will be stored.
     * @return the destination point.
     */
    public Point2D getPoint2D(Point2D srcPoint, Point2D dstPoint);

    /**
     * Gets the RenderingHints of the RasterOp.
     * 
     * @return the RenderingHints of the RasterOp.
     */
    public RenderingHints getRenderingHints();
}
