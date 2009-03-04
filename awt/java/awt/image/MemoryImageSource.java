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
import java.util.Vector;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The MemoryImageSource class is used to produces pixels of an image from an
 * array. This class can manage a memory image which contains an animation or
 * custom rendering.
 * 
 * @since Android 1.0
 */
public class MemoryImageSource implements ImageProducer {

    /**
     * The width.
     */
    int width;

    /**
     * The height.
     */
    int height;

    /**
     * The cm.
     */
    ColorModel cm;

    /**
     * The b data.
     */
    byte bData[];

    /**
     * The i data.
     */
    int iData[];

    /**
     * The offset.
     */
    int offset;

    /**
     * The scanline.
     */
    int scanline;

    /**
     * The properties.
     */
    Hashtable<?, ?> properties;

    /**
     * The consumers.
     */
    Vector<ImageConsumer> consumers;

    /**
     * The animated.
     */
    boolean animated;

    /**
     * The fullbuffers.
     */
    boolean fullbuffers;

    /**
     * The data type.
     */
    int dataType;

    /**
     * The Constant DATA_TYPE_BYTE.
     */
    static final int DATA_TYPE_BYTE = 0;

    /**
     * The Constant DATA_TYPE_INT.
     */
    static final int DATA_TYPE_INT = 1;

    /**
     * Instantiates a new MemoryImageSource with the specified parameters.
     * 
     * @param w
     *            the width of the rectangular area of pixels.
     * @param h
     *            the height of the rectangular area of pixels.
     * @param cm
     *            the specified ColorModel.
     * @param pix
     *            the pixel array.
     * @param off
     *            the offset in the pixel array.
     * @param scan
     *            the distance from one pixel's row to the next in the pixel
     *            array.
     * @param props
     *            the set of properties to be used for image processing.
     */
    public MemoryImageSource(int w, int h, ColorModel cm, int pix[], int off, int scan,
            Hashtable<?, ?> props) {
        init(w, h, cm, pix, off, scan, props);
    }

    /**
     * Instantiates a new MemoryImageSource with the specified parameters.
     * 
     * @param w
     *            the width of the rectangular area of pixels.
     * @param h
     *            the height of the rectangular area of pixels.
     * @param cm
     *            the specified ColorModel.
     * @param pix
     *            the pixel array.
     * @param off
     *            the offset in the pixel array.
     * @param scan
     *            the distance from one pixel's row to the next in the pixel
     *            array.
     * @param props
     *            the set of properties to be used for image processing.
     */
    public MemoryImageSource(int w, int h, ColorModel cm, byte pix[], int off, int scan,
            Hashtable<?, ?> props) {
        init(w, h, cm, pix, off, scan, props);
    }

    /**
     * Instantiates a new MemoryImageSource with the specified parameters and
     * default RGB ColorModel.
     * 
     * @param w
     *            the width of the rectangular area of pixels.
     * @param h
     *            the height of the rectangular area of pixels.
     * @param pix
     *            the pixel array.
     * @param off
     *            the offset in the pixel array.
     * @param scan
     *            the distance from one pixel's row to the next in the pixel
     *            array.
     * @param props
     *            the set of properties to be used for image processing.
     */
    public MemoryImageSource(int w, int h, int pix[], int off, int scan, Hashtable<?, ?> props) {
        init(w, h, ColorModel.getRGBdefault(), pix, off, scan, props);
    }

    /**
     * Instantiates a new MemoryImageSource with the specified parameters.
     * 
     * @param w
     *            the width of the rectangular area of pixels.
     * @param h
     *            the height of the rectangular area of pixels.
     * @param cm
     *            the specified ColorModel.
     * @param pix
     *            the pixel array.
     * @param off
     *            the offset in the pixel array.
     * @param scan
     *            the distance from one pixel's row to the next in the pixel
     *            array.
     */
    public MemoryImageSource(int w, int h, ColorModel cm, int pix[], int off, int scan) {
        init(w, h, cm, pix, off, scan, null);
    }

    /**
     * Instantiates a new MemoryImageSource with the specified parameters.
     * 
     * @param w
     *            the width of the rectangular area of pixels.
     * @param h
     *            the height of the rectangular area of pixels.
     * @param cm
     *            the specified ColorModel.
     * @param pix
     *            the pixel array.
     * @param off
     *            the offset in the pixel array.
     * @param scan
     *            the distance from one pixel's row to the next in the pixel
     *            array.
     */
    public MemoryImageSource(int w, int h, ColorModel cm, byte pix[], int off, int scan) {
        init(w, h, cm, pix, off, scan, null);
    }

    /**
     * Instantiates a new MemoryImageSource with the specified parameters and
     * default RGB ColorModel.
     * 
     * @param w
     *            the width of the rectangular area of pixels.
     * @param h
     *            the height of the rectangular area of pixels.
     * @param pix
     *            the pixels array.
     * @param off
     *            the offset in the pixel array.
     * @param scan
     *            the distance from one pixel's row to the next in the pixel
     *            array.
     */
    public MemoryImageSource(int w, int h, int pix[], int off, int scan) {
        init(w, h, ColorModel.getRGBdefault(), pix, off, scan, null);
    }

    public synchronized boolean isConsumer(ImageConsumer ic) {
        return consumers.contains(ic);
    }

    public void startProduction(ImageConsumer ic) {
        if (!isConsumer(ic) && ic != null) {
            consumers.addElement(ic);
        }
        try {
            setHeader(ic);
            setPixels(ic, 0, 0, width, height);
            if (animated) {
                ic.imageComplete(ImageConsumer.SINGLEFRAMEDONE);
            } else {
                ic.imageComplete(ImageConsumer.STATICIMAGEDONE);
                if (isConsumer(ic)) {
                    removeConsumer(ic);
                }
            }
        } catch (Exception e) {
            if (isConsumer(ic)) {
                ic.imageComplete(ImageConsumer.IMAGEERROR);
            }
            if (isConsumer(ic)) {
                removeConsumer(ic);
            }
        }
    }

    public void requestTopDownLeftRightResend(ImageConsumer ic) {
    }

    public synchronized void removeConsumer(ImageConsumer ic) {
        consumers.removeElement(ic);
    }

    public synchronized void addConsumer(ImageConsumer ic) {
        if (ic == null || consumers.contains(ic)) {
            return;
        }
        consumers.addElement(ic);
    }

    /**
     * Replaces the pixel data with a new pixel array for holding the pixels for
     * this image. If an animation flag is set to true value by the
     * setAnimated() method, the new pixels will be immediately delivered to the
     * ImageConsumers.
     * 
     * @param newpix
     *            the new pixel array.
     * @param newmodel
     *            the new ColorModel.
     * @param offset
     *            the offset in the array.
     * @param scansize
     *            the distance from one row of pixels to the next row in the
     *            pixel array.
     */
    public synchronized void newPixels(int newpix[], ColorModel newmodel, int offset, int scansize) {
        this.dataType = DATA_TYPE_INT;
        this.iData = newpix;
        this.cm = newmodel;
        this.offset = offset;
        this.scanline = scansize;
        newPixels();
    }

    /**
     * Replaces the pixel data with a new pixel array for holding the pixels for
     * this image. If an animation flag is set to true value by the
     * setAnimated() method, the new pixels will be immediately delivered to the
     * ImageConsumers.
     * 
     * @param newpix
     *            the new pixel array.
     * @param newmodel
     *            the new ColorModel.
     * @param offset
     *            the offset in the array.
     * @param scansize
     *            the distance from one row of pixels to the next row in the
     *            pixel array.
     */
    public synchronized void newPixels(byte newpix[], ColorModel newmodel, int offset, int scansize) {
        this.dataType = DATA_TYPE_BYTE;
        this.bData = newpix;
        this.cm = newmodel;
        this.offset = offset;
        this.scanline = scansize;
        newPixels();
    }

    /**
     * Sets the full buffer updates flag to true. If this is an animated image,
     * the image consumers hints are updated accordingly.
     * 
     * @param fullbuffers
     *            the true if the pixel buffer should be sent always.
     */
    public synchronized void setFullBufferUpdates(boolean fullbuffers) {
        if (this.fullbuffers == fullbuffers) {
            return;
        }
        this.fullbuffers = fullbuffers;
        if (animated) {
            Object consAr[] = consumers.toArray();
            for (Object element : consAr) {
                ImageConsumer con = (ImageConsumer)element;
                try {
                    if (fullbuffers) {
                        con.setHints(ImageConsumer.TOPDOWNLEFTRIGHT
                                | ImageConsumer.COMPLETESCANLINES);
                    } else {
                        con.setHints(ImageConsumer.RANDOMPIXELORDER);
                    }
                } catch (Exception e) {
                    if (isConsumer(con)) {
                        con.imageComplete(ImageConsumer.IMAGEERROR);
                    }
                    if (isConsumer(con)) {
                        removeConsumer(con);
                    }
                }
            }
        }
    }

    /**
     * Sets the flag that tells whether this memory image has more than one
     * frame (for animation): true for multiple frames, false if this class
     * represents a single frame image.
     * 
     * @param animated
     *            whether this image represents an animation.
     */
    public synchronized void setAnimated(boolean animated) {
        if (this.animated == animated) {
            return;
        }
        Object consAr[] = consumers.toArray();
        for (Object element : consAr) {
            ImageConsumer con = (ImageConsumer)element;
            try {
                con.imageComplete(ImageConsumer.STATICIMAGEDONE);
            } catch (Exception e) {
                if (isConsumer(con)) {
                    con.imageComplete(ImageConsumer.IMAGEERROR);
                }
            }
            if (isConsumer(con)) {
                removeConsumer(con);
            }
        }
        this.animated = animated;
    }

    /**
     * Sends the specified rectangular area of the buffer to ImageConsumers and
     * notifies them that an animation frame is completed only if the {@code
     * framenotify} parameter is true. That works only if the animated flag has
     * been set to true by the setAnimated() method. If the full buffer update
     * flag has been set to true by the setFullBufferUpdates() method, then the
     * entire buffer will always be sent ignoring parameters.
     * 
     * @param x
     *            the X coordinate of the rectangular area.
     * @param y
     *            the Y coordinate of the rectangular area.
     * @param w
     *            the width of the rectangular area.
     * @param h
     *            the height of the rectangular area.
     * @param framenotify
     *            true if a SINGLEFRAMEDONE notification should be sent to the
     *            registered consumers, false otherwise.
     */
    public synchronized void newPixels(int x, int y, int w, int h, boolean framenotify) {
        if (animated) {
            if (fullbuffers) {
                x = 0;
                y = 0;
                w = width;
                h = height;
            } else {
                if (x < 0) {
                    w += x;
                    x = 0;
                }
                if (w > width) {
                    w = width - x;
                }
                if (y < 0) {
                    h += y;
                    y = 0;
                }
            }
            if (h > height) {
                h = height - y;
            }
            Object consAr[] = consumers.toArray();
            for (Object element : consAr) {
                ImageConsumer con = (ImageConsumer)element;
                try {
                    if (w > 0 && h > 0) {
                        setPixels(con, x, y, w, h);
                    }
                    if (framenotify) {
                        con.imageComplete(ImageConsumer.SINGLEFRAMEDONE);
                    }
                } catch (Exception ex) {
                    if (isConsumer(con)) {
                        con.imageComplete(ImageConsumer.IMAGEERROR);
                    }
                    if (isConsumer(con)) {
                        removeConsumer(con);
                    }
                }
            }
        }
    }

    /**
     * Sends the specified rectangular area of the buffer to the ImageConsumers
     * and notifies them that an animation frame is completed if the animated
     * flag has been set to true by the setAnimated() method. If the full buffer
     * update flag has been set to true by the setFullBufferUpdates() method,
     * then the entire buffer will always be sent ignoring parameters.
     * 
     * @param x
     *            the X coordinate of the rectangular area.
     * @param y
     *            the Y coordinate of the rectangular area.
     * @param w
     *            the width of the rectangular area.
     * @param h
     *            the height of the rectangular area.
     */
    public synchronized void newPixels(int x, int y, int w, int h) {
        newPixels(x, y, w, h, true);
    }

    /**
     * Sends a new buffer of pixels to the ImageConsumers and notifies them that
     * an animation frame is completed if the animated flag has been set to true
     * by the setAnimated() method.
     */
    public void newPixels() {
        newPixels(0, 0, width, height, true);
    }

    /**
     * Inits the.
     * 
     * @param width
     *            the width.
     * @param height
     *            the height.
     * @param model
     *            the model.
     * @param pixels
     *            the pixels.
     * @param off
     *            the off.
     * @param scan
     *            the scan.
     * @param prop
     *            the prop.
     */
    private void init(int width, int height, ColorModel model, byte pixels[], int off, int scan,
            Hashtable<?, ?> prop) {

        this.width = width;
        this.height = height;
        this.cm = model;
        this.bData = pixels;
        this.offset = off;
        this.scanline = scan;
        this.properties = prop;
        this.dataType = DATA_TYPE_BYTE;
        this.consumers = new Vector<ImageConsumer>();

    }

    /**
     * Inits the.
     * 
     * @param width
     *            the width.
     * @param height
     *            the height.
     * @param model
     *            the model.
     * @param pixels
     *            the pixels.
     * @param off
     *            the off.
     * @param scan
     *            the scan.
     * @param prop
     *            the prop.
     */
    private void init(int width, int height, ColorModel model, int pixels[], int off, int scan,
            Hashtable<?, ?> prop) {

        this.width = width;
        this.height = height;
        this.cm = model;
        this.iData = pixels;
        this.offset = off;
        this.scanline = scan;
        this.properties = prop;
        this.dataType = DATA_TYPE_INT;
        this.consumers = new Vector<ImageConsumer>();
    }

    /**
     * Sets the pixels.
     * 
     * @param con
     *            the con.
     * @param x
     *            the x.
     * @param y
     *            the y.
     * @param w
     *            the w.
     * @param h
     *            the h.
     */
    private void setPixels(ImageConsumer con, int x, int y, int w, int h) {
        int pixelOff = scanline * y + offset + x;

        switch (dataType) {
            case DATA_TYPE_BYTE:
                con.setPixels(x, y, w, h, cm, bData, pixelOff, scanline);
                break;
            case DATA_TYPE_INT:
                con.setPixels(x, y, w, h, cm, iData, pixelOff, scanline);
                break;
            default:
                // awt.22A=Wrong type of pixels array
                throw new IllegalArgumentException(Messages.getString("awt.22A")); //$NON-NLS-1$
        }
    }

    /**
     * Sets the header.
     * 
     * @param con
     *            the new header.
     */
    private synchronized void setHeader(ImageConsumer con) {
        con.setDimensions(width, height);
        con.setProperties(properties);
        con.setColorModel(cm);
        con
                .setHints(animated ? (fullbuffers ? (ImageConsumer.TOPDOWNLEFTRIGHT | ImageConsumer.COMPLETESCANLINES)
                        : ImageConsumer.RANDOMPIXELORDER)
                        : (ImageConsumer.TOPDOWNLEFTRIGHT | ImageConsumer.COMPLETESCANLINES
                                | ImageConsumer.SINGLEPASS | ImageConsumer.SINGLEFRAME));
    }

}
