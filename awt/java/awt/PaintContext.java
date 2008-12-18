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

import java.awt.image.ColorModel;
import java.awt.image.Raster;

/**
 * The PaintContext interface determines the specific environment for generating
 * color patterns in device space for fill, draw, or stroke rendering operations
 * using Graphics2D. This interface provides colors through the Raster object
 * associated with the specific ColorModel for Graphics2D rendering operations.
 * 
 * @since Android 1.0
 */
public interface PaintContext {

    /**
     * Releases the resources allocated for the operation.
     */
    void dispose();

    /**
     * Gets the color model.
     * 
     * @return the ColorModel object.
     */
    ColorModel getColorModel();

    /**
     * Gets the Raster which defines the colors of the specified rectangular
     * area for Graphics2D rendering operations.
     * 
     * @param x
     *            the X coordinate of the device space area for which colors are
     *            generated.
     * @param y
     *            the Y coordinate of the device space area for which colors are
     *            generated.
     * @param w
     *            the width of the device space area for which colors are
     *            generated.
     * @param h
     *            the height of the device space area for which colors are
     *            generated.
     * @return the Raster object which contains the colors of the specified
     *         rectangular area for Graphics2D rendering operations.
     */
    Raster getRaster(int x, int y, int w, int h);
}
