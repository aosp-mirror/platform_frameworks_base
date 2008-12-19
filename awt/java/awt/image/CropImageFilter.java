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

/**
 * The CropImageFilter class crops a rectangular region of an source Image and
 * provides a source for a new image containing the extracted region.
 * 
 * @since Android 1.0
 */
public class CropImageFilter extends ImageFilter {

    /**
     * The HEIGHT.
     */
    private final int X, Y, WIDTH, HEIGHT;

    /**
     * Instantiates a new CropImageFilter object with the specified rectangular
     * area.
     * 
     * @param x
     *            the X coordinate of rectangular area.
     * @param y
     *            the Y coordinate of rectangular area.
     * @param w
     *            the width of rectangular area.
     * @param h
     *            the height of rectangular area.
     */
    public CropImageFilter(int x, int y, int w, int h) {
        X = x;
        Y = y;
        WIDTH = w;
        HEIGHT = h;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setProperties(Hashtable<?, ?> props) {
        Hashtable<Object, Object> fprops;
        if (props == null) {
            fprops = new Hashtable<Object, Object>();
        } else {
            fprops = (Hashtable<Object, Object>)props.clone();
        }
        String propName = "Crop Filters"; //$NON-NLS-1$
        String prop = "x=" + X + "; y=" + Y + "; width=" + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                WIDTH + "; height=" + HEIGHT; //$NON-NLS-1$
        Object o = fprops.get(propName);
        if (o != null) {
            if (o instanceof String) {
                prop = (String)o + "; " + prop; //$NON-NLS-1$
            } else {
                prop = o.toString() + "; " + prop; //$NON-NLS-1$
            }
        }
        fprops.put(propName, prop);
        consumer.setProperties(fprops);
    }

    @Override
    public void setPixels(int x, int y, int w, int h, ColorModel model, int[] pixels, int off,
            int scansize) {

        if (x + w < X || X + WIDTH < x || y + h < Y || Y + HEIGHT < y) {
            return;
        }

        int destX, destY, destWidth, destHeight, endX, endY, srcEndX, srcEndY;

        int newOffset = off;

        endX = X + WIDTH;
        endY = Y + HEIGHT;

        srcEndX = x + w;
        srcEndY = y + h;

        if (x <= X) {
            destX = 0;
            newOffset += X;
            if (endX >= srcEndX) {
                destWidth = srcEndX - X;
            } else {
                destWidth = WIDTH;
            }
        } else {
            destX = x - X;
            if (endX >= srcEndX) {
                destWidth = w;
            } else {
                destWidth = endX - x;
            }
        }

        if (y <= Y) {
            newOffset += scansize * (Y - y);
            destY = 0;
            if (endY >= srcEndY) {
                destHeight = srcEndY - Y;
            } else {
                destHeight = HEIGHT;
            }
        } else {
            destY = y - Y;
            if (endY >= srcEndY) {
                destHeight = h;
            } else {
                destHeight = endY - y;
            }
        }
        consumer.setPixels(destX, destY, destWidth, destHeight, model, pixels, newOffset, scansize);
    }

    @Override
    public void setPixels(int x, int y, int w, int h, ColorModel model, byte[] pixels, int off,
            int scansize) {

        if (x + w < X || X + WIDTH < x || y + h < Y || Y + HEIGHT < y) {
            return;
        }

        int destX, destY, destWidth, destHeight, endX, endY, srcEndX, srcEndY;

        int newOffset = off;

        endX = X + WIDTH;
        endY = Y + HEIGHT;

        srcEndX = x + w;
        srcEndY = y + h;

        if (x <= X) {
            destX = 0;
            newOffset += X;
            if (endX >= srcEndX) {
                destWidth = srcEndX - X;
            } else {
                destWidth = WIDTH;
            }
        } else {
            destX = x - X;
            if (endX >= srcEndX) {
                destWidth = w;
            } else {
                destWidth = endX - x;
            }
        }

        if (y <= Y) {
            newOffset += scansize * (Y - y);
            destY = 0;
            if (endY >= srcEndY) {
                destHeight = srcEndY - Y;
            } else {
                destHeight = HEIGHT;
            }
        } else {
            destY = y - Y;
            if (endY >= srcEndY) {
                destHeight = h;
            } else {
                destHeight = endY - y;
            }
        }
        consumer.setPixels(destX, destY, destWidth, destHeight, model, pixels, newOffset, scansize);
    }

    @Override
    public void setDimensions(int w, int h) {
        consumer.setDimensions(WIDTH, HEIGHT);
    }

}
