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

import java.awt.font.FontRenderContext;
import org.apache.harmony.awt.gl.font.LineMetricsImpl;


/**
 *
 * Linux implementation of LineMetrics class
 */
public class AndroidLineMetrics extends LineMetricsImpl {
    
    /**
     * Constructor
     */
    public AndroidLineMetrics(    AndroidFont fnt,
                                FontRenderContext frc,
                                String str){
        numChars = str.length();
        baseLineIndex = 0;

        ascent = fnt.ascent;    // Ascent of the font
        descent = -fnt.descent;  // Descent of the font
        leading = fnt.leading;  // External leading

        height = ascent + descent + leading;    // Height of the font ( == (ascent + descent + leading))
        underlineThickness = 0.0f;
        underlineOffset = 0.0f;
        strikethroughThickness = 0.0f;
        strikethroughOffset = 0.0f;
        maxCharWidth = 0.0f;

        //    TODO: Find out pixel metrics
        /*
         * positive metrics rounded to the smallest int that is bigger than value
         * negative metrics rounded to the smallest int that is lesser than value
         * thicknesses rounded to int ((int)round(value + 0.5))
         *
         */

        lAscent = (int)Math.ceil(fnt.ascent);//   // Ascent of the font
        lDescent = -(int)Math.ceil(fnt.descent);// Descent of the font
        lLeading = (int)Math.ceil(leading);  // External leading

        lHeight = lAscent + lDescent + lLeading;    // Height of the font ( == (ascent + descent + leading))

        lUnderlineThickness = Math.round(underlineThickness);//(int)metrics[11];

        if (underlineOffset >= 0){
            lUnderlineOffset = (int)Math.ceil(underlineOffset);
        } else {
            lUnderlineOffset = (int)Math.floor(underlineOffset);
        }

        lStrikethroughThickness = Math.round(strikethroughThickness); //(int)metrics[13];

        if (strikethroughOffset >= 0){
            lStrikethroughOffset = (int)Math.ceil(strikethroughOffset);
        } else {
            lStrikethroughOffset = (int)Math.floor(strikethroughOffset);
        }

        lMaxCharWidth = (int)Math.ceil(maxCharWidth); //(int)metrics[15];
        units_per_EM = 0;

    }

    public float[] getBaselineOffsets() {
        // TODO: implement baseline offsets for TrueType fonts
        if (baselineOffsets == null){
            float[] baselineData = null;

            // Temporary workaround:
            // Commented out native data initialization, since it can 
            // cause failures with opening files in multithreaded applications.
            //
            // TODO: support work with truetype data in multithreaded
            // applications.

            // If font TrueType data is taken from BASE table
//            if ((this.font.getFontHandle() != 0) && (font.getFontType() == FontManager.FONT_TYPE_TT)){
//                baselineData = LinuxNativeFont.getBaselineOffsetsNative(font.getFontHandle(), font.getSize(), ascent, descent, units_per_EM);
//            }
//
                baseLineIndex = 0;
                baselineOffsets = new float[]{0, (-ascent+descent)/2, -ascent};
        }

        return baselineOffsets;
    }

    public int getBaselineIndex() {
        if (baselineOffsets == null){
            // get offsets and set correct index
            getBaselineOffsets();
        }
        return baseLineIndex;
    }

}
