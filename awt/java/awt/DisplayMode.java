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

package java.awt;

/**
 * The DisplayMode class contains the bit depth, height, width and refresh rate
 * of a GraphicsDevice.
 * 
 * @since Android 1.0
 */
public final class DisplayMode {

    /**
     * The width.
     */
    private final int width;

    /**
     * The height.
     */
    private final int height;

    /**
     * The bit depth.
     */
    private final int bitDepth;

    /**
     * The refresh rate.
     */
    private final int refreshRate;

    /**
     * The Constant Value BIT_DEPTH_MULTI indicates the bit depth
     */

    public static final int BIT_DEPTH_MULTI = -1;

    /**
     * The Constant REFRESH_RATE_UNKNOWN indicates the refresh rate.
     */
    public static final int REFRESH_RATE_UNKNOWN = 0;

    /**
     * Creates a new DisplayMode object with the specified parameters.
     * 
     * @param width
     *            the width of the display.
     * @param height
     *            the height of the display.
     * @param bitDepth
     *            the bit depth of the display.
     * @param refreshRate
     *            the refresh rate of the display.
     */

    public DisplayMode(int width, int height, int bitDepth, int refreshRate) {
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.refreshRate = refreshRate;
    }

    /**
     * Compares if this DisplayMode is equal to the specified object or not.
     * 
     * @param dm
     *            the Object to be compared.
     * @return true, if the specified object is a DisplayMode with the same data
     *         values as this DisplayMode, false otherwise.
     */

    @Override
    public boolean equals(Object dm) {
        if (dm instanceof DisplayMode) {
            return equals((DisplayMode)dm);
        }
        return false;
    }

    /**
     * Compares if this DisplayMode is equal to the specified DisplayMode object
     * or not.
     * 
     * @param dm
     *            the DisplayMode to be compared.
     * @return true, if all of the data values of this DisplayMode are equal to
     *         the values of the specified DisplayMode object, false otherwise.
     */
    public boolean equals(DisplayMode dm) {
        if (dm == null) {
            return false;
        }
        if (dm.bitDepth != bitDepth) {
            return false;
        }
        if (dm.refreshRate != refreshRate) {
            return false;
        }
        if (dm.width != width) {
            return false;
        }
        if (dm.height != height) {
            return false;
        }
        return true;
    }

    /**
     * Gets the bit depth of the DisplayMode, returns BIT_DEPTH_MULTI value if
     * multiple bit depths are supported in this display mode.
     * 
     * @return the bit depth of the DisplayMode.
     */
    public int getBitDepth() {
        return bitDepth;
    }

    /**
     * Gets the height of the DisplayMode.
     * 
     * @return the height of the DisplayMode.
     */
    public int getHeight() {
        return height;
    }

    /**
     * Gets the refresh rate of the DisplayMode, returns REFRESH_RATE_UNKNOWN
     * value if the information is not available.
     * 
     * @return the refresh rate of the DisplayMode.
     */
    public int getRefreshRate() {
        return refreshRate;
    }

    /**
     * Gets the width of the DisplayMode.
     * 
     * @return the width of the DisplayMode.
     */
    public int getWidth() {
        return width;
    }
}
