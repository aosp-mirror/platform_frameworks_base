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
 * @author Ilya S. Okomin
 * @version $Revision$
 */

package java.awt.font;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.Rectangle2D;

import org.apache.harmony.misc.HashCode;

/**
 * The ImageGraphicAttribute class provides an opportunity to insert images to a
 * text.
 * 
 * @since Android 1.0
 */
public final class ImageGraphicAttribute extends GraphicAttribute {

    // Image object rendered by this ImageGraphicAttribute
    /**
     * The image.
     */
    private Image fImage;

    // X coordinate of the origin point
    /**
     * The origin x.
     */
    private float fOriginX;

    // Y coordinate of the origin point
    /**
     * The origin y.
     */
    private float fOriginY;

    // the width of the image object
    /**
     * The img width.
     */
    private float fImgWidth;

    // the height of the image object
    /**
     * The img height.
     */
    private float fImgHeight;

    /**
     * Instantiates a new ImageGraphicAttribute with the specified image,
     * alignment and origins.
     * 
     * @param image
     *            the Image to be rendered by ImageGraphicAttribute.
     * @param alignment
     *            the alignment of the ImageGraphicAttribute.
     * @param originX
     *            the origin X coordinate in the image of ImageGraphicAttribute.
     * @param originY
     *            the origin Y coordinate in the image of ImageGraphicAttribute.
     */
    public ImageGraphicAttribute(Image image, int alignment, float originX, float originY) {
        super(alignment);

        this.fImage = image;
        this.fOriginX = originX;
        this.fOriginY = originY;

        this.fImgWidth = fImage.getWidth(null);
        this.fImgHeight = fImage.getHeight(null);

    }

    /**
     * Instantiates a new ImageGraphicAttribute with the specified image and
     * alignment.
     * 
     * @param image
     *            the Image to be rendered by ImageGraphicAttribute.
     * @param alignment
     *            the alignment of the ImageGraphicAttribute.
     */
    public ImageGraphicAttribute(Image image, int alignment) {
        this(image, alignment, 0, 0);
    }

    /**
     * Returns a hash code of this ImageGraphicAttribute object.
     * 
     * @return the hash code of this ImageGraphicAttribute object.
     */
    @Override
    public int hashCode() {
        HashCode hash = new HashCode();

        hash.append(fImage.hashCode());
        hash.append(getAlignment());
        return hash.hashCode();
    }

    /**
     * Compares the specified ImageGraphicAttribute object with this
     * ImageGraphicAttribute object.
     * 
     * @param iga
     *            the ImageGraphicAttribute object to be compared.
     * @return true, if the specified ImageGraphicAttribute object is equal to
     *         this ImageGraphicAttribute object, false otherwise.
     */
    public boolean equals(ImageGraphicAttribute iga) {
        if (iga == null) {
            return false;
        }

        if (iga == this) {
            return true;
        }

        return (fOriginX == iga.fOriginX && fOriginY == iga.fOriginY
                && getAlignment() == iga.getAlignment() && fImage.equals(iga.fImage));
    }

    /**
     * Compares the specified Object with this ImageGraphicAttribute object.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if the specified Object is equal to this
     *         ImageGraphicAttribute object, false otherwise.
     */
    @Override
    public boolean equals(Object obj) {
        try {
            return equals((ImageGraphicAttribute)obj);
        } catch (ClassCastException e) {
            return false;
        }

    }

    @Override
    public void draw(Graphics2D g2, float x, float y) {
        g2.drawImage(fImage, (int)(x - fOriginX), (int)(y - fOriginY), null);
    }

    @Override
    public float getAdvance() {
        return Math.max(0, fImgWidth - fOriginX);
    }

    @Override
    public float getAscent() {
        return Math.max(0, fOriginY);
    }

    @Override
    public Rectangle2D getBounds() {
        return new Rectangle2D.Float(-fOriginX, -fOriginY, fImgWidth, fImgHeight);
    }

    @Override
    public float getDescent() {
        return Math.max(0, fImgHeight - fOriginY);
    }

}
