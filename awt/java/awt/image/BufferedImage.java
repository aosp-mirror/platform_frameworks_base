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

import com.android.internal.awt.AndroidGraphics2D;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import org.apache.harmony.awt.gl.ImageSurface;
import org.apache.harmony.awt.gl.Surface;
import org.apache.harmony.awt.gl.image.BufferedImageSource;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The BufferedImage class describes an Image which contains a buffer of image
 * data and includes a ColorModel and a Raster for this data. This class
 * provides methods for obtaining and setting the Raster and for manipulating
 * the ColorModel parameters.
 * 
 * @since Android 1.0
 */
public class BufferedImage extends Image implements WritableRenderedImage, Transparency {

    /**
     * The Constant TYPE_CUSTOM indicates that Image type is unknown.
     */
    public static final int TYPE_CUSTOM = 0;

    /**
     * The Constant TYPE_INT_RGB indicates an image with 8 bit RGB color
     * components, it has a DirectColorModel without alpha.
     */
    public static final int TYPE_INT_RGB = 1;

    /**
     * The Constant TYPE_INT_ARGB indicates an image with 8 bit RGBA color
     * components, it has a DirectColorModel with alpha.
     */
    public static final int TYPE_INT_ARGB = 2;

    /**
     * The Constant TYPE_INT_ARGB_PRE indicates an image with 8 bit RGBA color
     * components, it has a DirectColorModel with alpha, and image data is
     * pre-multiplied by alpha.
     */
    public static final int TYPE_INT_ARGB_PRE = 3;

    /**
     * The Constant TYPE_INT_BGR indicates an image with 8 bit RGB color
     * components, BGR color model (with the colors Blue, Green, and Red). There
     * is no alpha. The image has a DirectColorModel.
     */
    public static final int TYPE_INT_BGR = 4;

    /**
     * The Constant TYPE_3BYTE_BGR indicates an image with 8 bit RGB color
     * components, BGR color model (with the colors Blue, Green, and Red stored
     * in 3 bytes). There is no alpha. The image has a ComponentColorModel.
     */
    public static final int TYPE_3BYTE_BGR = 5;

    /**
     * The Constant TYPE_4BYTE_ABGR indicates an image with 8 bit RGBA color
     * components stored in 3 bytes and 1 byte of alpha. It has a
     * ComponentColorModel with alpha.
     */
    public static final int TYPE_4BYTE_ABGR = 6;

    /**
     * The Constant TYPE_4BYTE_ABGR_PRE indicates an image with 8 bit RGBA color
     * components stored in 3 bytes and 1 byte for alpha. The image has a
     * ComponentColorModel with alpha. The color data is pre-multiplied with
     * alpha.
     */
    public static final int TYPE_4BYTE_ABGR_PRE = 7;

    /**
     * The Constant TYPE_USHORT_565_RGB indicates an image with 565 RGB color
     * components (5-bits red, 6-bits green, 5-bits blue) with no alpha. This
     * image has a DirectColorModel.
     */
    public static final int TYPE_USHORT_565_RGB = 8;

    /**
     * The Constant TYPE_USHORT_555_RGB indicates an image with 555 RGB color
     * components (5-bits red, 5-bits green, 5-bits blue) with no alpha. This
     * image has a DirectColorModel.
     */
    public static final int TYPE_USHORT_555_RGB = 9;

    /**
     * The Constant TYPE_BYTE_GRAY indicates a unsigned byte image. This image
     * has a ComponentColorModel with a CS_GRAY ColorSpace.
     */
    public static final int TYPE_BYTE_GRAY = 10;

    /**
     * The Constant TYPE_USHORT_GRAY indicates an unsigned short image. This
     * image has a ComponentColorModel with a CS_GRAY ColorSpace.
     */
    public static final int TYPE_USHORT_GRAY = 11;

    /**
     * The Constant TYPE_BYTE_BINARY indicates an opaque byte-packed 1, 2 or 4
     * bit image. The image has an IndexColorModel without alpha.
     */
    public static final int TYPE_BYTE_BINARY = 12;

    /**
     * The Constant TYPE_BYTE_INDEXED indicates an indexed byte image.
     */
    public static final int TYPE_BYTE_INDEXED = 13;

    /**
     * The Constant ALPHA_MASK.
     */
    private static final int ALPHA_MASK = 0xff000000;

    /**
     * The Constant RED_MASK.
     */
    private static final int RED_MASK = 0x00ff0000;

    /**
     * The Constant GREEN_MASK.
     */
    private static final int GREEN_MASK = 0x0000ff00;

    /**
     * The Constant BLUE_MASK.
     */
    private static final int BLUE_MASK = 0x000000ff;

    /**
     * The Constant RED_BGR_MASK.
     */
    private static final int RED_BGR_MASK = 0x000000ff;

    /**
     * The Constant GREEN_BGR_MASK.
     */
    private static final int GREEN_BGR_MASK = 0x0000ff00;

    /**
     * The Constant BLUE_BGR_MASK.
     */
    private static final int BLUE_BGR_MASK = 0x00ff0000;

    /**
     * The Constant RED_565_MASK.
     */
    private static final int RED_565_MASK = 0xf800;

    /**
     * The Constant GREEN_565_MASK.
     */
    private static final int GREEN_565_MASK = 0x07e0;

    /**
     * The Constant BLUE_565_MASK.
     */
    private static final int BLUE_565_MASK = 0x001f;

    /**
     * The Constant RED_555_MASK.
     */
    private static final int RED_555_MASK = 0x7c00;

    /**
     * The Constant GREEN_555_MASK.
     */
    private static final int GREEN_555_MASK = 0x03e0;

    /**
     * The Constant BLUE_555_MASK.
     */
    private static final int BLUE_555_MASK = 0x001f;

    /**
     * The cm.
     */
    private ColorModel cm;

    /**
     * The raster.
     */
    private final WritableRaster raster;

    /**
     * The image type.
     */
    private final int imageType;

    /**
     * The properties.
     */
    private Hashtable<?, ?> properties;

    // Surface of the Buffered Image - used for blitting one Buffered Image
    // on the other one or on the Component
    /**
     * The image surf.
     */
    private final ImageSurface imageSurf;

    /**
     * Instantiates a new BufferedImage with the specified ColorModel, and
     * WritableRaster objects. The Raster data can be be divided or multiplied
     * by alpha. It depends on the alphaPremultiplied state in the ColorModel.
     * 
     * @param cm
     *            the ColorModel of the new image.
     * @param raster
     *            the WritableRaster of the new image.
     * @param isRasterPremultiplied
     *            if true the data of the specified Raster is pre-multiplied by
     *            alpha.
     * @param properties
     *            the properties of new Image.
     */
    public BufferedImage(ColorModel cm, WritableRaster raster, boolean isRasterPremultiplied,
            Hashtable<?, ?> properties) {
        if (!cm.isCompatibleRaster(raster)) {
            // awt.4D=The raster is incompatible with this ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.4D")); //$NON-NLS-1$
        }

        if (raster.getMinX() != 0 || raster.getMinY() != 0) {
            // awt.228=minX or minY of this raster not equal to zero
            throw new IllegalArgumentException(Messages.getString("awt.228")); //$NON-NLS-1$
        }

        this.cm = cm;
        this.raster = raster;
        this.properties = properties;

        coerceData(isRasterPremultiplied);

        imageType = Surface.getType(cm, raster);

        imageSurf = createImageSurface(imageType);
    }

    /**
     * Instantiates a new BufferedImage with the specified width, height
     * predefined image type (TYPE_BYTE_BINARY or TYPE_BYTE_INDEXED) and the
     * specified IndexColorModel.
     * 
     * @param width
     *            the width of new image.
     * @param height
     *            the height of new image.
     * @param imageType
     *            the predefined image type.
     * @param cm
     *            the specified IndexColorModel.
     */
    public BufferedImage(int width, int height, int imageType, IndexColorModel cm) {
        switch (imageType) {
            case TYPE_BYTE_BINARY:
                if (cm.hasAlpha()) {
                    // awt.227=This image type can't have alpha
                    throw new IllegalArgumentException(Messages.getString("awt.227")); //$NON-NLS-1$
                }
                int pixel_bits = 0;
                int mapSize = cm.getMapSize();
                if (mapSize <= 2) {
                    pixel_bits = 1;
                } else if (mapSize <= 4) {
                    pixel_bits = 2;
                } else if (mapSize <= 16) {
                    pixel_bits = 4;
                } else {
                    // awt.221=The imageType is TYPE_BYTE_BINARY and the color
                    // map has more than 16 entries
                    throw new IllegalArgumentException(Messages.getString("awt.221")); //$NON-NLS-1$
                }

                raster = Raster.createPackedRaster(DataBuffer.TYPE_BYTE, width, height, 1,
                        pixel_bits, null);
                break;

            case TYPE_BYTE_INDEXED:
                raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 1,
                        null);
                break;

            default:
                // awt.222=The imageType is not TYPE_BYTE_BINARY or
                // TYPE_BYTE_INDEXED
                throw new IllegalArgumentException(Messages.getString("awt.222")); //$NON-NLS-1$

        }

        if (!cm.isCompatibleRaster(raster)) {
            // awt.223=The imageType is not compatible with ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.223")); //$NON-NLS-1$
        }

        this.cm = cm;
        this.imageType = imageType;
        imageSurf = createImageSurface(imageType);

    }

    /**
     * Instantiates a new BufferedImage with the specified width, height and
     * predefined image type.
     * 
     * @param width
     *            the width of new image.
     * @param height
     *            the height of new image.
     * @param imageType
     *            the predefined image type.
     */
    public BufferedImage(int width, int height, int imageType) {

        switch (imageType) {
            case TYPE_INT_RGB:
                cm = new DirectColorModel(24, RED_MASK, GREEN_MASK, BLUE_MASK);
                raster = cm.createCompatibleWritableRaster(width, height);
                break;

            case TYPE_INT_ARGB:
                cm = ColorModel.getRGBdefault();
                raster = cm.createCompatibleWritableRaster(width, height);
                break;

            case TYPE_INT_ARGB_PRE:
                cm = new DirectColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), 32, RED_MASK,
                        GREEN_MASK, BLUE_MASK, ALPHA_MASK, true, DataBuffer.TYPE_INT);

                raster = cm.createCompatibleWritableRaster(width, height);
                break;

            case TYPE_INT_BGR:
                cm = new DirectColorModel(24, RED_BGR_MASK, GREEN_BGR_MASK, BLUE_BGR_MASK);

                raster = cm.createCompatibleWritableRaster(width, height);
                break;

            case TYPE_3BYTE_BGR: {
                int bits[] = {
                        8, 8, 8
                };
                int bandOffsets[] = {
                        2, 1, 0
                };
                cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), bits,
                        false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

                raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height,
                        width * 3, 3, bandOffsets, null);
            }
                break;

            case TYPE_4BYTE_ABGR: {
                int bits[] = {
                        8, 8, 8, 8
                };
                int bandOffsets[] = {
                        3, 2, 1, 0
                };
                cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), bits,
                        true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);

                raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height,
                        width * 4, 4, bandOffsets, null);
            }
                break;

            case TYPE_4BYTE_ABGR_PRE: {
                int bits[] = {
                        8, 8, 8, 8
                };
                int bandOffsets[] = {
                        3, 2, 1, 0
                };
                cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), bits,
                        true, true, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);

                raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height,
                        width * 4, 4, bandOffsets, null);
            }
                break;

            case TYPE_USHORT_565_RGB:
                cm = new DirectColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), 16,
                        RED_565_MASK, GREEN_565_MASK, BLUE_565_MASK, 0, false,
                        DataBuffer.TYPE_USHORT);

                raster = cm.createCompatibleWritableRaster(width, height);
                break;

            case TYPE_USHORT_555_RGB:
                cm = new DirectColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), 15,
                        RED_555_MASK, GREEN_555_MASK, BLUE_555_MASK, 0, false,
                        DataBuffer.TYPE_USHORT);

                raster = cm.createCompatibleWritableRaster(width, height);
                break;

            case TYPE_BYTE_GRAY: {
                int bits[] = {
                    8
                };
                cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), bits,
                        false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

                raster = cm.createCompatibleWritableRaster(width, height);
            }
                break;

            case TYPE_USHORT_GRAY: {
                int bits[] = {
                    16
                };
                cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), bits,
                        false, false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
                raster = cm.createCompatibleWritableRaster(width, height);
            }
                break;

            case TYPE_BYTE_BINARY: {
                int colorMap[] = {
                        0, 0xffffff
                };
                cm = new IndexColorModel(1, 2, colorMap, 0, false, -1, DataBuffer.TYPE_BYTE);

                raster = Raster.createPackedRaster(DataBuffer.TYPE_BYTE, width, height, 1, 1, null);
            }
                break;

            case TYPE_BYTE_INDEXED: {
                int colorMap[] = new int[256];
                int i = 0;
                for (int r = 0; r < 256; r += 51) {
                    for (int g = 0; g < 256; g += 51) {
                        for (int b = 0; b < 256; b += 51) {
                            colorMap[i] = (r << 16) | (g << 8) | b;
                            i++;
                        }
                    }
                }

                int gray = 0x12;
                for (; i < 256; i++, gray += 6) {
                    colorMap[i] = (gray << 16) | (gray << 8) | gray;
                }
                cm = new IndexColorModel(8, 256, colorMap, 0, false, -1, DataBuffer.TYPE_BYTE);
                raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 1,
                        null);

            }
                break;
            default:
                // awt.224=Unknown image type
                throw new IllegalArgumentException(Messages.getString("awt.224")); //$NON-NLS-1$
        }
        this.imageType = imageType;
        imageSurf = createImageSurface(imageType);
    }

    @Override
    public Object getProperty(String name, ImageObserver observer) {
        return getProperty(name);
    }

    public Object getProperty(String name) {
        if (name == null) {
            // awt.225=Property name is null
            throw new NullPointerException(Messages.getString("awt.225")); //$NON-NLS-1$
        }
        if (properties == null) {
            return Image.UndefinedProperty;
        }
        Object property = properties.get(name);
        if (property == null) {
            property = Image.UndefinedProperty;
        }
        return property;
    }

    public WritableRaster copyData(WritableRaster outRaster) {
        if (outRaster == null) {
            outRaster = Raster.createWritableRaster(raster.getSampleModel(), new Point(raster
                    .getSampleModelTranslateX(), raster.getSampleModelTranslateY()));
        }

        int w = outRaster.getWidth();
        int h = outRaster.getHeight();
        int minX = outRaster.getMinX();
        int minY = outRaster.getMinY();

        Object data = null;

        data = raster.getDataElements(minX, minY, w, h, data);
        outRaster.setDataElements(minX, minY, w, h, data);

        return outRaster;
    }

    public Raster getData(Rectangle rect) {
        int minX = rect.x;
        int minY = rect.y;
        int w = rect.width;
        int h = rect.height;

        SampleModel sm = raster.getSampleModel();
        SampleModel nsm = sm.createCompatibleSampleModel(w, h);
        WritableRaster outr = Raster.createWritableRaster(nsm, rect.getLocation());
        Object data = null;

        data = raster.getDataElements(minX, minY, w, h, data);
        outr.setDataElements(minX, minY, w, h, data);
        return outr;
    }

    public Vector<RenderedImage> getSources() {
        return null;
    }

    public String[] getPropertyNames() {
        if (properties == null) {
            return null;
        }
        Vector<String> v = new Vector<String>();
        for (Enumeration<?> e = properties.keys(); e.hasMoreElements();) {
            try {
                v.add((String)e.nextElement());
            } catch (ClassCastException ex) {
            }
        }
        int size = v.size();
        if (size > 0) {
            String names[] = new String[size];
            for (int i = 0; i < size; i++) {
                names[i] = v.elementAt(i);
            }
            return names;
        }
        return null;
    }

    /**
     * Returns the string representation of this BufferedImage object.
     * 
     * @return the string representation of this BufferedImage object.
     */
    @Override
    public String toString() {
        return "BufferedImage@" + Integer.toHexString(hashCode()) + //$NON-NLS-1$
                ": type = " + imageType + " " + cm + " " + raster; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    public WritableRaster getWritableTile(int tileX, int tileY) {
        return raster;
    }

    /**
     * Gets the WritableRaster of this BufferedImage.
     * 
     * @return the WritableRaster of this BufferedImage.
     */
    public WritableRaster getRaster() {
        return raster;
    }

    /**
     * Gets a WritableRaster object which contains the alpha channel of
     * BufferedImage object with ColorModel objects that supports a separate
     * alpha channel such as ComponentColorModel or DirectColorModel.
     * 
     * @return the WritableRaster object which contains the alpha channel of
     *         this BufferedImage.
     */
    public WritableRaster getAlphaRaster() {
        return cm.getAlphaRaster(raster);
    }

    public void removeTileObserver(TileObserver to) {
    }

    public void addTileObserver(TileObserver to) {
    }

    public SampleModel getSampleModel() {
        return raster.getSampleModel();
    }

    public void setData(Raster r) {

        Rectangle from = r.getBounds();
        Rectangle to = raster.getBounds();
        Rectangle intersection = to.intersection(from);

        int minX = intersection.x;
        int minY = intersection.y;
        int w = intersection.width;
        int h = intersection.height;

        Object data = null;

        data = r.getDataElements(minX, minY, w, h, data);
        raster.setDataElements(minX, minY, w, h, data);
    }

    public Raster getTile(int tileX, int tileY) {
        if (tileX == 0 && tileY == 0) {
            return raster;
        }
        // awt.226=Both tileX and tileY are not equal to 0
        throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.226")); //$NON-NLS-1$
    }

    public Raster getData() {
        int w = raster.getWidth();
        int h = raster.getHeight();
        int minX = raster.getMinX();
        int minY = raster.getMinY();

        WritableRaster outr = Raster.createWritableRaster(raster.getSampleModel(), new Point(raster
                .getSampleModelTranslateX(), raster.getSampleModelTranslateY()));

        Object data = null;

        data = raster.getDataElements(minX, minY, w, h, data);
        outr.setDataElements(minX, minY, w, h, data);

        return outr;
    }

    @Override
    public ImageProducer getSource() {
        return new BufferedImageSource(this, properties);
    }

    @Override
    public int getWidth(ImageObserver observer) {
        return raster.getWidth();
    }

    @Override
    public int getHeight(ImageObserver observer) {
        return raster.getHeight();
    }

    public ColorModel getColorModel() {
        return cm;
    }

    /**
     * Gets the rectangular area of this BufferedImage as a subimage.
     * 
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     * @param w
     *            the width of the subimage.
     * @param h
     *            the height of the subimage.
     * @return the BufferedImage.
     */
    public BufferedImage getSubimage(int x, int y, int w, int h) {
        WritableRaster wr = raster.createWritableChild(x, y, w, h, 0, 0, null);
        return new BufferedImage(cm, wr, cm.isAlphaPremultiplied(), properties);
    }

    public Point[] getWritableTileIndices() {
        Point points[] = new Point[1];
        points[0] = new Point(0, 0);
        return points;
    }

    /**
     * Creates the Graphics2D object which allows to draw into this
     * BufferedImage.
     * 
     * @return the graphics2D object.
     */
    public Graphics2D createGraphics() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        // return ge.createGraphics(this);
        // ???AWT hack, FIXME
        // return AndroidGraphics2D.getInstance();
        // throw new RuntimeException("Not implemented!");
        return null;
    }

    @Override
    public Graphics getGraphics() {
        return createGraphics();
    }

    /**
     * Coerces the data to achieve the state which is specified by the
     * isAlphaPremultiplied variable.
     * 
     * @param isAlphaPremultiplied
     *            the is alpha pre-multiplied state.
     */
    public void coerceData(boolean isAlphaPremultiplied) {
        if (cm.hasAlpha() && cm.isAlphaPremultiplied() != isAlphaPremultiplied) {
            cm = cm.coerceData(raster, isAlphaPremultiplied);
        }
    }

    /**
     * Gets an array of colors in the TYPE_INT_ARGB color model and default sRGB
     * color space of the specified area of this BufferedImage. The result array
     * is composed by the following algorithm:
     * <p>
     * pixel = rgbArray[offset + (y-startY)*scansize + (x-startX)]
     * </p>
     * 
     * @param startX
     *            the start X area coordinate.
     * @param startY
     *            the start Y area coordinate.
     * @param w
     *            the width of the area.
     * @param h
     *            the height of the area.
     * @param rgbArray
     *            the result array will be stored to this array.
     * @param offset
     *            the offset of the rgbArray array.
     * @param scansize
     *            the scanline stride for the rgbArray.
     * @return an array of colors for the specified area.
     */
    public int[] getRGB(int startX, int startY, int w, int h, int[] rgbArray, int offset,
            int scansize) {
        if (rgbArray == null) {
            rgbArray = new int[offset + h * scansize];
        }

        int off = offset;
        for (int y = startY; y < startY + h; y++, off += scansize) {
            int i = off;
            for (int x = startX; x < startX + w; x++, i++) {
                rgbArray[i] = cm.getRGB(raster.getDataElements(x, y, null));
            }
        }
        return rgbArray;
    }

    /**
     * Sets RGB values from the specified array to the specified BufferedImage
     * area. The pixels are in the default RGB color model (TYPE_INT_ARGB) and
     * default sRGB color space.
     * 
     * @param startX
     *            the start X coordinate.
     * @param startY
     *            the start Y coordinate.
     * @param w
     *            the width of the BufferedImage area.
     * @param h
     *            the height of the BufferedImage area.
     * @param rgbArray
     *            the array of RGB values.
     * @param offset
     *            the offset of the rgbArray array.
     * @param scansize
     *            the scanline stride for the rgbArray.
     */
    public void setRGB(int startX, int startY, int w, int h, int[] rgbArray, int offset,
            int scansize) {
        int off = offset;
        for (int y = startY; y < startY + h; y++, off += scansize) {
            int i = off;
            for (int x = startX; x < startX + w; x++, i++) {
                raster.setDataElements(x, y, cm.getDataElements(rgbArray[i], null));
            }
        }
    }

    /**
     * Sets a the specified RGB value to the specified pixel of this
     * BufferedImage. The pixel should be in the default RGB color model
     * (TYPE_INT_ARGB) and default sRGB color space.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param rgb
     *            the RGB value to be set.
     */
    public synchronized void setRGB(int x, int y, int rgb) {
        raster.setDataElements(x, y, cm.getDataElements(rgb, null));
    }

    public boolean isTileWritable(int tileX, int tileY) {
        if (tileX == 0 && tileY == 0) {
            return true;
        }
        // awt.226=Both tileX and tileY are not equal to 0
        throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.226")); //$NON-NLS-1$
    }

    public void releaseWritableTile(int tileX, int tileY) {
    }

    /**
     * Gets a color in the TYPE_INT_ARGB color model and default sRGB color
     * space of the specified pixel.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @return the color of the specified pixel in the TYPE_INT_ARGB color model
     *         and default sRGB color space.
     */
    public int getRGB(int x, int y) {
        return cm.getRGB(raster.getDataElements(x, y, null));
    }

    /**
     * Returns true if alpha is pre-multiplied, false if alpha is not
     * pre-multiplied or there is no alpha.
     * 
     * @return true if alpha is pre-multiplied, false if alpha is not
     *         pre-multiplied or there is no alpha.
     */
    public boolean isAlphaPremultiplied() {
        return cm.isAlphaPremultiplied();
    }

    public boolean hasTileWriters() {
        return true;
    }

    @Override
    public void flush() {
        imageSurf.dispose();
    }

    public int getWidth() {
        return raster.getWidth();
    }

    /**
     * Gets the image type.
     * 
     * @return the image type.
     */
    public int getType() {
        return imageType;
    }

    public int getTileWidth() {
        return raster.getWidth();
    }

    public int getTileHeight() {
        return raster.getHeight();
    }

    public int getTileGridYOffset() {
        return raster.getSampleModelTranslateY();
    }

    public int getTileGridXOffset() {
        return raster.getSampleModelTranslateX();
    }

    public int getNumYTiles() {
        return 1;
    }

    public int getNumXTiles() {
        return 1;
    }

    public int getMinY() {
        return raster.getMinY();
    }

    public int getMinX() {
        return raster.getMinX();
    }

    public int getMinTileY() {
        return 0;
    }

    public int getMinTileX() {
        return 0;
    }

    public int getHeight() {
        return raster.getHeight();
    }

    /**
     * Creates the image surface.
     * 
     * @param type
     *            the type.
     * @return the image surface.
     */
    private ImageSurface createImageSurface(int type) {
        return new ImageSurface(getColorModel(), getRaster(), type);
    }

    /**
     * Gets the image surface.
     * 
     * @return the image surface.
     */
    ImageSurface getImageSurface() {
        return imageSurf;
    }

    public int getTransparency() {
        return cm.getTransparency();
    }
}
