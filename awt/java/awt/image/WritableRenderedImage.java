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

import java.awt.Point;

/**
 * The WriteableRenderedImage interface is interface for objects which contains
 * Raster data of one or several tiles. This interface provides notification
 * mechanism for obtaining tile's writing status.
 * 
 * @since Android 1.0
 */
public interface WritableRenderedImage extends RenderedImage {

    /**
     * Gets and checks out the writable tile for writing.
     * 
     * @param tileX
     *            the X index of the tile.
     * @param tileY
     *            the Y index of the tile.
     * @return the WritableRaster.
     */
    public WritableRaster getWritableTile(int tileX, int tileY);

    /**
     * Removes the registered TileObserver.
     * 
     * @param to
     *            the TileObserver which is registered for this
     *            WritableRenderedImage.
     */
    public void removeTileObserver(TileObserver to);

    /**
     * Adds the specified TileObserver to this WritableRenderedImage.
     * 
     * @param to
     *            the TileObserver object to be added.
     */
    public void addTileObserver(TileObserver to);

    /**
     * Sets this image to the contents of the specified Raster.
     * 
     * @param r
     *            the specified Raster.
     */
    public void setData(Raster r);

    /**
     * Gets the array of points which represent indices of tiles which are check
     * out for writing.
     * 
     * @return the array of points.
     */
    public Point[] getWritableTileIndices();

    /**
     * Checks if the specified tile is writable or not.
     * 
     * @param tileX
     *            the X index of tile.
     * @param tileY
     *            the Y index of tile.
     * @return true, if the specified tile is writable, false otherwise.
     */
    public boolean isTileWritable(int tileX, int tileY);

    /**
     * Release the specified writable tile. This method removes the writer from
     * the tile.
     * 
     * @param tileX
     *            the X index of the tile.
     * @param tileY
     *            the Y index of the tile.
     */
    public void releaseWritableTile(int tileX, int tileY);

    /**
     * Checks if there is a tile which is checked out for writing.
     * 
     * @return true, if any tile is checked out for writing, false if there is
     *         no such tile.
     */
    public boolean hasTileWriters();

}
