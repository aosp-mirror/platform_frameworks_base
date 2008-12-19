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

/**
 * The LineMetrics class provides information such as concerning how the text is
 * positioned with respect to the base line, such as ascent, descent, and
 * leading.
 * 
 * @since Android 1.0
 */
public abstract class LineMetrics {

    /**
     * Gets the baseline offsets of the text according to the the baseline of
     * this text.
     * 
     * @return the baseline offsets of the text according to the the baseline of
     *         this text.
     */
    public abstract float[] getBaselineOffsets();

    /**
     * Gets the number of characters of the text.
     * 
     * @return the number of characters of the text.
     */
    public abstract int getNumChars();

    /**
     * Gets the baseline index, returns one of the following index:
     * ROMAN_BASELINE, CENTER_BASELINE, HANGING_BASELINE.
     * 
     * @return the baseline index: ROMAN_BASELINE, CENTER_BASELINE or
     *         HANGING_BASELINE.
     */
    public abstract int getBaselineIndex();

    /**
     * Gets the thickness of the underline.
     * 
     * @return the thickness of the underline.
     */
    public abstract float getUnderlineThickness();

    /**
     * Gets the offset of the underline.
     * 
     * @return the offset of the underline.
     */
    public abstract float getUnderlineOffset();

    /**
     * Gets the thickness of strike through line.
     * 
     * @return the thickness of strike through line.
     */
    public abstract float getStrikethroughThickness();

    /**
     * Gets the offset of the strike through line.
     * 
     * @return the offset of the strike through line.
     */
    public abstract float getStrikethroughOffset();

    /**
     * Gets the leading of the text.
     * 
     * @return the leading of the text.
     */
    public abstract float getLeading();

    /**
     * Gets the height of the text as a sum of the ascent, the descent and the
     * leading.
     * 
     * @return the height of the text as a sum of the ascent, the descent and
     *         the leading.
     */
    public abstract float getHeight();

    /**
     * Gets the descent of the text.
     * 
     * @return the descent of the text.
     */
    public abstract float getDescent();

    /**
     * Gets the ascent of the text.
     * 
     * @return the ascent of the text.
     */
    public abstract float getAscent();

}
