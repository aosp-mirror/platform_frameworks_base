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

import javax.imageio.metadata.IIOMetadata;
import java.awt.image.RenderedImage;
import java.awt.image.Raster;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The IIOImage class combines the image, image's thumbnail and image's
 * metadata. The image can be presented as RenderedImage or Raster object.
 * 
 * @since Android 1.0
 */
public class IIOImage {

    /**
     * The image of this IIOImage.
     */
    protected RenderedImage image;

    /**
     * The raster of this IIOImage.
     */
    protected Raster raster;

    /**
     * The list with thumbnails associated with the image.
     */
    protected List<? extends BufferedImage> thumbnails;

    /**
     * The metadata associated with the image.
     */
    protected IIOMetadata metadata;

    /**
     * Instantiates a new IIOImage with the specified RenderedImage, list of
     * thumbnails and metadata.
     * 
     * @param image
     *            the image specified by RenderedImage.
     * @param thumbnails
     *            the list of BufferedImage objects which represent the
     *            thumbnails of the image.
     * @param metadata
     *            the metadata of the image.
     */
    public IIOImage(RenderedImage image, List<? extends BufferedImage> thumbnails,
            IIOMetadata metadata) {
        if (image == null) {
            throw new IllegalArgumentException("image should not be NULL");
        }
        this.raster = null;
        this.image = image;
        this.thumbnails = thumbnails;
        this.metadata = metadata;
    }

    /**
     * Instantiates a new IIOImage with the specified Raster, list of thumbnails
     * and metadata.
     * 
     * @param raster
     *            the Raster.
     * @param thumbnails
     *            the list of BufferedImage objects which represent the
     *            thumbnails of Raster data.
     * @param metadata
     *            the metadata.
     */
    public IIOImage(Raster raster, List<? extends BufferedImage> thumbnails, IIOMetadata metadata) {
        if (raster == null) {
            throw new IllegalArgumentException("raster should not be NULL");
        }
        this.image = null;
        this.raster = raster;
        this.thumbnails = thumbnails;
        this.metadata = metadata;
    }

    /**
     * Gets the RenderedImage object or returns null if this IIOImage object is
     * associated with a Raster.
     * 
     * @return the RenderedImage object or null if this IIOImage object is
     *         associated with a Raster.
     */
    public RenderedImage getRenderedImage() {
        return image;
    }

    /**
     * Sets the RenderedImage to this IIOImage object.
     * 
     * @param image
     *            the RenderedImage to be set to this IIOImage.
     */
    public void setRenderedImage(RenderedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image should not be NULL");
        }
        raster = null;
        this.image = image;
    }

    /**
     * Returns true if the IIOImage object associated with a Raster, or false if
     * it's associated with a RenderedImage.
     * 
     * @return true, if the IIOImage object associated with a Raster, or false
     *         if it's associated with a RenderedImage.
     */
    public boolean hasRaster() {
        return raster != null;
    }

    /**
     * Gets the Raster object or returns null if this IIOImage object is
     * associated with a RenderedImage.
     * 
     * @return the Raster or null if this IIOImage object is associated with a
     *         RenderedImage.
     */
    public Raster getRaster() {
        return raster;
    }

    /**
     * Sets the Raster to the IIOImage.
     * 
     * @param raster
     *            the new Raster to the IIOImage.
     */
    public void setRaster(Raster raster) {
        if (raster == null) {
            throw new IllegalArgumentException("raster should not be NULL");
        }
        image = null;
        this.raster = raster;
    }

    /**
     * Gets the number of thumbnails for this IIOImage.
     * 
     * @return the number of thumbnails for this IIOImage.
     */
    public int getNumThumbnails() {
        return thumbnails != null ? thumbnails.size() : 0;
    }

    /**
     * Gets the thumbnail with the specified index in the list.
     * 
     * @param index
     *            the index of the thumbnail in the list.
     * @return the thumbnail with the specified index in the list.
     */
    public BufferedImage getThumbnail(int index) {
        if (thumbnails != null) {
            return thumbnails.get(index);
        }
        throw new IndexOutOfBoundsException("no thumbnails were set");
    }

    /**
     * Gets the list of thumbnails.
     * 
     * @return the list of thumbnails.
     */
    public List<? extends BufferedImage> getThumbnails() {
        return thumbnails;
    }

    /**
     * Sets the list of thumbnails images to this IIOImage object.
     * 
     * @param thumbnails
     *            the list of BufferedImage which represent thumbnails.
     */
    public void setThumbnails(List<? extends BufferedImage> thumbnails) {
        this.thumbnails = thumbnails;
    }

    /**
     * Gets the metadata of this IIOImage.
     * 
     * @return the metadata of this IIOImage.
     */
    public IIOMetadata getMetadata() {
        return metadata;
    }

    /**
     * Sets the metadata to this IIOImage object.
     * 
     * @param metadata
     *            the IIOMetadata, or null.
     */
    public void setMetadata(IIOMetadata metadata) {
        this.metadata = metadata;
    }
}
