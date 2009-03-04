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
 * @author Oleg V. Khaschansky
 * @version $Revision$
 *
 * @date: Sep 28, 2005
 */

package java.awt.image;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Kernel class provides a matrix. This matrix is stored as a float array
 * which describes how a specified pixel affects the value calculated for the
 * pixel's position in the output image of a filtering operation. The X, Y
 * origins indicate the kernel matrix element which corresponds to the pixel
 * position for which an output value is being calculated.
 * 
 * @since Android 1.0
 */
public class Kernel implements Cloneable {

    /**
     * The x origin.
     */
    private final int xOrigin;

    /**
     * The y origin.
     */
    private final int yOrigin;

    /**
     * The width.
     */
    private int width;

    /**
     * The height.
     */
    private int height;

    /**
     * The data.
     */
    float data[];

    /**
     * Instantiates a new Kernel with the specified float array. The
     * width*height elements of the data array are copied.
     * 
     * @param width
     *            the width of the Kernel.
     * @param height
     *            the height of the Kernel.
     * @param data
     *            the data of Kernel.
     */
    public Kernel(int width, int height, float[] data) {
        int dataLength = width * height;
        if (data.length < dataLength) {
            // awt.22B=Length of data should not be less than width*height
            throw new IllegalArgumentException(Messages.getString("awt.22B")); //$NON-NLS-1$
        }

        this.width = width;
        this.height = height;

        this.data = new float[dataLength];
        System.arraycopy(data, 0, this.data, 0, dataLength);

        xOrigin = (width - 1) / 2;
        yOrigin = (height - 1) / 2;
    }

    /**
     * Gets the width of this Kernel.
     * 
     * @return the width of this Kernel.
     */
    public final int getWidth() {
        return width;
    }

    /**
     * Gets the height of this Kernel.
     * 
     * @return the height of this Kernel.
     */
    public final int getHeight() {
        return height;
    }

    /**
     * Gets the float data array of this Kernel.
     * 
     * @param data
     *            the float array where the resulted data will be stored.
     * @return the float data array of this Kernel.
     */
    public final float[] getKernelData(float[] data) {
        if (data == null) {
            data = new float[this.data.length];
        }
        System.arraycopy(this.data, 0, data, 0, this.data.length);

        return data;
    }

    /**
     * Gets the X origin of this Kernel.
     * 
     * @return the X origin of this Kernel.
     */
    public final int getXOrigin() {
        return xOrigin;
    }

    /**
     * Gets the Y origin of this Kernel.
     * 
     * @return the Y origin of this Kernel.
     */
    public final int getYOrigin() {
        return yOrigin;
    }

    /**
     * Returns a copy of this Kernel object.
     * 
     * @return the copy of this Kernel object.
     */
    @Override
    public Object clone() {
        return new Kernel(width, height, data);
    }
}
