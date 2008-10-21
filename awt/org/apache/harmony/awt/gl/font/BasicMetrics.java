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
 */

package org.apache.harmony.awt.gl.font;

import java.awt.font.LineMetrics;
import java.awt.font.GraphicAttribute;
import java.awt.*;

/**
 * Date: May 14, 2005
 * Time: 7:44:13 PM
 *
 * This class incapsulates text metrics specific for the text layout or
 * for the separate text segment. Text segment is a text run with the constant direction
 * and attributes like font, decorations, etc. BasicMetrics is also used to store
 * calculated text metrics like advance, ascent or descent. this class is very similar to
 * LineMetrics, but provides some additional info, constructors and is more transparent.
 */
public class BasicMetrics {
    int baseLineIndex;

    float ascent;   // Ascent of the font
    float descent;  // Descent of the font
    float leading;  // External leading
    float advance;

    float italicAngle;
    float superScriptOffset;

    float underlineOffset;
    float underlineThickness;

    float strikethroughOffset;
    float strikethroughThickness;

    /**
     * Constructs BasicMetrics from LineMetrics and font
     * @param lm
     * @param font
     */
    BasicMetrics(LineMetrics lm, Font font) {
        ascent = lm.getAscent();
        descent = lm.getDescent();
        leading = lm.getLeading();

        underlineOffset = lm.getUnderlineOffset();
        underlineThickness = lm.getUnderlineThickness();

        strikethroughOffset = lm.getStrikethroughOffset();
        strikethroughThickness = lm.getStrikethroughThickness();

        baseLineIndex = lm.getBaselineIndex();

        italicAngle = font.getItalicAngle();
        superScriptOffset = (float) font.getTransform().getTranslateY();
    }

    /**
     * Constructs BasicMetrics from GraphicAttribute.
     * It gets ascent and descent from the graphic attribute and
     * computes reasonable defaults for other metrics.
     * @param ga - graphic attribute
     */
    BasicMetrics(GraphicAttribute ga) {
        ascent = ga.getAscent();
        descent = ga.getDescent();
        leading = 2;

        baseLineIndex = ga.getAlignment();

        italicAngle = 0;
        superScriptOffset = 0;

        underlineOffset = Math.max(descent/2, 1);

        // Just suggested, should be cap_stem_width or something like that
        underlineThickness = Math.max(ascent/13, 1);

        strikethroughOffset = -ascent/2; // Something like middle of the line
        strikethroughThickness = underlineThickness;
    }

    /**
     * Copies metrics from the TextMetricsCalculator object.
     * @param tmc - TextMetricsCalculator object
     */
    BasicMetrics(TextMetricsCalculator tmc) {
        ascent = tmc.ascent;
        descent = tmc.descent;
        leading = tmc.leading;
        advance = tmc.advance;
        baseLineIndex = tmc.baselineIndex;
    }

    public float getAscent() {
        return ascent;
    }

    public float getDescent() {
        return descent;
    }

    public float getLeading() {
        return leading;
    }

    public float getAdvance() {
        return advance;
    }

    public int getBaseLineIndex() {
        return baseLineIndex;
    }
}
