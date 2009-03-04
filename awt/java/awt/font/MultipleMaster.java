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

import java.awt.Font;

/**
 * The MultipleMaster interface provides methods to manipulate MultipleMaster
 * type fonts and retrieve graphical and design data from them.
 * 
 * @since Android 1.0
 */
public interface MultipleMaster {

    /**
     * Derives a new multiple master font based on the specified parameters.
     * 
     * @param glyphWidths
     *            float array which represents width of each glyph in font
     *            space.
     * @param avgStemWidth
     *            the average stem width in font space.
     * @param typicalCapHeight
     *            the typical upper case char height.
     * @param typicalXHeight
     *            the typical lower case char height.
     * @param italicAngle
     *            the slope angle for italics.
     * @return a MultipleMaster font.
     */
    public Font deriveMMFont(float[] glyphWidths, float avgStemWidth, float typicalCapHeight,
            float typicalXHeight, float italicAngle);

    /**
     * Derives a new multiple master font based on the design axis values
     * contained in the specified array.
     * 
     * @param axes
     *            an float array which contains axis values.
     * @return a MultipleMaster font.
     */
    public Font deriveMMFont(float[] axes);

    /**
     * Gets default design values for the axes.
     * 
     * @return the default design values for the axes.
     */
    public float[] getDesignAxisDefaults();

    /**
     * Gets the array of design axis names.
     * 
     * @return the array of design axis names.
     */
    public String[] getDesignAxisNames();

    /**
     * Gets the array of design axis ranges.
     * 
     * @return the array of design axis ranges.
     */
    public float[] getDesignAxisRanges();

    /**
     * Gets the number of multiple master design controls.
     * 
     * @return the number of multiple master design controls.
     */
    public int getNumDesignAxes();

}
