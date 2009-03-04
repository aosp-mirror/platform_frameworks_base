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

import java.awt.Rectangle;
import java.util.Vector;

/**
 * The RenderedImage interface should be implemented by all objects which
 * contains image data. The image data is represented as a single tile or an
 * array of tiles.
 * 
 * @since Android 1.0
 */
public interface RenderedImage {

    /**
     * Gets the property with the specified name from the property set of this
     * RenderedImage.
     * 
     * @param name
     *            the property's name.
     * @return the property value corresponded to this property's name.
     */
    public Object getProperty(String name);

    /**
     * Copies the region of this RenderedImage to the specified WritableRaster.
     * The bounds of the region are the bounds of the WritableRaster.
     * 
     * @param raster
     *            the WritableRaster.
     * @return the created WritableRaster.
     */
    public WritableRaster copyData(WritableRaster raster);

    /**
     * Gets the image data of the image's region as one tile.
     * 
     * @param rect
     *            the rectangular region of RenderedImage.
     * @return the image data of the image's region as one tile.
     */
    public Raster getData(Rectangle rect);

    /**
     * Gets all RenderedImage objects which are the source of this RenderedImage
     * object.
     * 
     * @return a Vector of RenderedImage objects which are the source of this
     *         RenderedImage object or null, if there is no information about
     *         them.
     */
    public Vector<RenderedImage> getSources();

    /**
     * Gets the set of all property names for this RenderedImage.
     * 
     * @return the array of all property names for this RenderedImage.
     */
    public String[] getPropertyNames();

    /**
     * Gets the SampleModel of this RenderedImage.
     * 
     * @return the SampleModel of this RenderedImage.
     */
    public SampleModel getSampleModel();

    /**
     * Gets the tile corresponded to the specified indices in the tile array.
     * 
     * @param tileX
     *            the X index of the tile.
     * @param tileY
     *            the Y index of the tile.
     * @return the tile corresponded to the specified indices in the tile array.
     */
    public Raster getTile(int tileX, int tileY);

    /**
     * Gets the image data of this image as one tile.
     * 
     * @return the image data of this image as one tile.
     */
    public Raster getData();

    /**
     * Gets the ColorModel of this RenderedImage.
     * 
     * @return the ColorModel of this RenderedImage.
     */
    public ColorModel getColorModel();

    /**
     * Gets the width of the RenderedImage.
     * 
     * @return the width of the RenderedImage.
     */
    public int getWidth();

    /**
     * Gets the tile width.
     * 
     * @return the tile width in pixels.
     */
    public int getTileWidth();

    /**
     * Gets the tile height.
     * 
     * @return the tile height in pixels.
     */
    public int getTileHeight();

    /**
     * Gets the Y offset of the tile grid.
     * 
     * @return the Y offset of the tile grid.
     */
    public int getTileGridYOffset();

    /**
     * Gets the X offset of the tile grid.
     * 
     * @return the X offset of the tile grid.
     */
    public int getTileGridXOffset();

    /**
     * Gets the number of tiles along Y direction.
     * 
     * @return the number of tiles along Y direction.
     */
    public int getNumYTiles();

    /**
     * Gets the number of tiles along X direction.
     * 
     * @return the number of tiles along X direction.
     */
    public int getNumXTiles();

    /**
     * Gets the minimum Y coordinate of this RenderedImage.
     * 
     * @return the minimum Y coordinate of this RenderedImage.
     */
    public int getMinY();

    /**
     * Gets the minimum X coordinate of this RenderedImage.
     * 
     * @return the minimum X coordinate of this RenderedImage.
     */
    public int getMinX();

    /**
     * Gets the minimum tile's index along the Y direction.
     * 
     * @return the minimum tile's index along the Y direction.
     */
    public int getMinTileY();

    /**
     * Gets the minimum tile's index along the X direction.
     * 
     * @return the minimum tile's index along the X direction.
     */
    public int getMinTileX();

    /**
     * Gets the height of the RenderedImage.
     * 
     * @return the height of the RenderedImage.
     */
    public int getHeight();

}
