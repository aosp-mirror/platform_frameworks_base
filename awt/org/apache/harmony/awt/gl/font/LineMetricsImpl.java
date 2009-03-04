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
package org.apache.harmony.awt.gl.font;

import java.awt.font.LineMetrics;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 *
 * LineMetrics implementation class.
 */

public class LineMetricsImpl extends LineMetrics implements Cloneable{

    // array of baseline offsets
    float[] baselineOffsets;

    // the number of characters to measure
    int numChars;

    // baseline index of the font corresponding to this line metrics
    int baseLineIndex;

    // underline thickness
    float underlineThickness;

    // underline offset
    float underlineOffset;

    // strikethrough thickness
    float strikethroughThickness;

    // strikethrough offset
    float strikethroughOffset;

    // External leading
    float leading;

    // Height of the font ( == (ascent+descent+leading))
    float height;

    // Ascent of the font
    float ascent;

    // Descent of the font
    float descent;

    // Width of the widest char in the font
    float maxCharWidth;

    // underline thickness (in pixels)
    int lUnderlineThickness;

    // underline offset (in pixels)
    int lUnderlineOffset;

    // strikethrough thickness (in pixels)
    int lStrikethroughThickness;

    // strikethrough offset (in pixels)
    int lStrikethroughOffset;

    // External leading (in pixels)
    int lLeading;

    // Height of the font ( == (ascent+descent+leading)) (in pixels)
    int lHeight;

    // Ascent of the font (in pixels)
    int lAscent;
    
    // Descent of the font (in pixels)
    int lDescent;

    //  Width of the widest char in the font (in pixels)
    int lMaxCharWidth;

    // units per EM square in font value
    int units_per_EM = 0;

    /**
     * Creates LineMetricsImpl object from specified parameters. If baseline data parameter
     * is null than {0, (-ascent+descent)/2, -ascent} values are used for baseline offsets.
     *  
     * @param len a number of characters 
     * @param metrics an array of 16 elements with metrics values that can be 
     * initialized in native code.<p>
     * metrics[0] - ascent<p>
     * metrics[1] - descent<p>
     * metrics[2] - external leading<p>
     * metrics[3] - underline thickness<p>
     * -metrics[4] - underline offset<p>
     * metrics[5] - strikethrough thickness<p>
     * -metrics[6] - strikethrough offset<p>
     * metrics[7] - maximum char width<p>
     * metrics[8] - ascent in pixels<p>
     * metrics[9] - descent in pixles<p>
     * metrics[10] - external leading in pixels<p>
     * metrics[11] - underline thickness in pixels<p>
     * -metrics[12] - underline offset in pixels<p>
     * metrics[13] - strikethrough thickness in pixels<p>
     * -metrics[14] - strikethrough offset in pixels<p>
     * metrics[15] - maximum char width in pixels<p>

     * @param _baselineData an array of 3 elements with baseline offsets metrics<p>
     * _baselineData[0] - roman baseline offset<p> 
     * _baselineData[1] - center baseline offset<p>
     * _baselineData[2] - hanging baseline offset<p>
     */
    public LineMetricsImpl(int len, float[] metrics, float[] _baselineData){
        numChars = len;

        ascent = metrics[0];    // Ascent of the font
        descent = metrics[1];   // Descent of the font
        leading = metrics[2];  // External leading
        height = metrics[0] + metrics[1] + metrics[2];  // Height of the font ( == (ascent + descent + leading))
    }

    /**
     * Creates LineMetricsImpl object from specified parameters. If baseline data parameter
     * is null than {0, (-ascent+descent)/2, -ascent} values are used for baseline offsets.
     *  
     * @param _numChars number of chars 
     * @param _baseLineIndex index of the baseline offset
     * @param _baselineOffsets an array of baseline offsets
     * @param _underlineThickness underline thickness
     * @param _underlineOffset underline offset
     * @param _strikethroughThickness strikethrough thickness
     * @param _strikethroughOffset strinkethrough offset
     * @param _leading leading of the font
     * @param _height font height
     * @param _ascent ascent of the font
     * @param _descent descent of the font
     * @param _maxCharWidth max char width
     */
    public LineMetricsImpl(int _numChars, int _baseLineIndex,
            float[] _baselineOffsets, float _underlineThickness,
            float _underlineOffset, float _strikethroughThickness,
            float _strikethroughOffset, float _leading, float _height,
            float _ascent, float _descent, float _maxCharWidth) {

        numChars = _numChars;
        baseLineIndex = _baseLineIndex;
        underlineThickness = _underlineThickness;
        underlineOffset = _underlineOffset;
        strikethroughThickness = _strikethroughThickness;
        strikethroughOffset = _strikethroughOffset;
        leading = _leading;
        height = _height;
        ascent = _ascent;
        descent = _descent;
        baselineOffsets = _baselineOffsets;
        lUnderlineThickness = (int) underlineThickness;
        lUnderlineOffset = (int) underlineOffset;
        lStrikethroughThickness = (int) strikethroughThickness;
        lStrikethroughOffset = (int) strikethroughOffset;
        lLeading = (int) leading;
        lHeight = (int) height;
        lAscent = (int) ascent;
        lDescent = (int) descent;
        maxCharWidth = _maxCharWidth;
    }

    public LineMetricsImpl(){

    }

    /**
     * All metrics are scaled according to scaleX and scaleY values. 
     * This function helps to recompute metrics according to the scale factors
     * of desired AffineTransform.
     * 
     * @param scaleX scale X factor
     * @param scaleY scale Y factor
     */
    public void scale(float scaleX, float scaleY){
        float absScaleX = Math.abs(scaleX);
        float absScaleY = Math.abs(scaleY);

        underlineThickness *= absScaleY;
        underlineOffset *= scaleY;
        strikethroughThickness *= absScaleY;
        strikethroughOffset *= scaleY;
        leading *= absScaleY;
        height *= absScaleY;
        ascent *= absScaleY;
        descent *= absScaleY;

        if(baselineOffsets == null) {
            getBaselineOffsets();
        }

        for (int i=0; i< baselineOffsets.length; i++){
            baselineOffsets[i] *= scaleY;
        }

        lUnderlineThickness *= absScaleY;
        lUnderlineOffset *= scaleY;
        lStrikethroughThickness *= absScaleY;
        lStrikethroughOffset *= scaleY;
        lLeading  *= absScaleY;
        lHeight *= absScaleY;
        lAscent *= absScaleY;
        lDescent *= absScaleY;
        maxCharWidth *= absScaleX;

    }


    /**
     * Returns offset of the baseline.
     */
    @Override
    public float[] getBaselineOffsets() {
        // XXX: at the moment there only horizontal metrics are taken into
        // account. If there is no baseline information in TrueType font
        // file default values used: {0, -ascent, (-ascent+descent)/2}

        return baselineOffsets;
    }

    /**
     * Returns a number of chars in specified text
     */
    @Override
    public int getNumChars() {
        return numChars;
    }

    /**
     * Returns index of the baseline, one of predefined constants.
     */
    @Override
    public int getBaselineIndex() {
        // Baseline index is the deafult baseline index value
        // taken from the TrueType table "BASE".
        return baseLineIndex;
    }

    /**
     * Returns thickness of the Underline.
     */
    @Override
    public float getUnderlineThickness() {
        return underlineThickness;
    }

    /**
     * Returns offset of the Underline.
     */
    @Override
    public float getUnderlineOffset() {
        return underlineOffset;
    }

    /**
     * Returns thickness of the Strikethrough line.
     */
    @Override
    public float getStrikethroughThickness() {
        return strikethroughThickness;
    }

    /**
     * Returns offset of the Strikethrough line.
     */
    @Override
    public float getStrikethroughOffset() {
        return strikethroughOffset;
    }

    /**
     * Returns the leading.
     */
    @Override
    public float getLeading() {
        return leading;
    }

    /**
     * Returns the height of the font.
     */
    @Override
    public float getHeight() {
        //return height; // equals to (ascent + descent + leading);
    	return ascent + descent + leading;
    }

    /**
     * Returns the descent.
     */
    @Override
    public float getDescent() {
        return descent;
    }

    /**
     * Returns the ascent.
     */
    @Override
    public float getAscent() {
        return ascent;
    }

    /**
     * Returns logical thickness of the Underline.
     */
    public int getLogicalUnderlineThickness() {
        return lUnderlineThickness;
    }

    /**
     * Returns logical offset of the Underline.
     */
    public int getLogicalUnderlineOffset() {
        return lUnderlineOffset;
    }

    /**
     * Returns logical thickness of the Strikethrough line.
     */
    public int getLogicalStrikethroughThickness() {
        return lStrikethroughThickness;
    }

    /**
     * Returns logical offset of the Strikethrough line.
     */
    public int getLogicalStrikethroughOffset() {
        return lStrikethroughOffset;
    }

    /**
     * Returns the logical leading.
     */
    public int getLogicalLeading() {
        return lLeading;
    }

    /**
     * Returns the logical height of the font.
     */
    public int getLogicalHeight() {
        return lHeight; // equals to (ascent + descent + leading);
    }

    /**
     * Returns the logical descent.
     */
    public int getLogicalDescent() {
        return lDescent;
    }

    /**
     * Returns the logical ascent.
     */
    public int getLogicalAscent() {
        return lAscent;
    }

    /**
     * Returns the logical size of the widest char.
     */
    public int getLogicalMaxCharWidth() {
        return lMaxCharWidth;
    }

    /**
     * Returns the size of the widest char.
     */
    public float getMaxCharWidth() {
        return maxCharWidth;
    }

    /**
     * Set num chars to the desired value.
     * 
     * @param num specified number of chars
     */
    public void setNumChars(int num){
        numChars = num;
    }

    @Override
    public Object clone(){
        try{
            return super.clone();
        }catch (CloneNotSupportedException e){
            return null;
        }
    }

}