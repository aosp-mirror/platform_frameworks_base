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
import java.awt.Font;
import java.util.HashMap;
import java.util.ArrayList;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * This class operates with an arbitrary text string which can include
 * any number of style, font and direction runs. It is responsible for computation
 * of the text metrics, such as ascent, descent, leading and advance. Actually,
 * each text run segment contains logic which allows it to compute its own metrics and
 * responsibility of this class is to combine metrics for all segments included in the text,
 * managed by the associated TextRunBreaker object.
 */
public class TextMetricsCalculator {
    TextRunBreaker breaker; // Associated run breaker

    // Metrics
    float ascent = 0;
    float descent = 0;
    float leading = 0;
    float advance = 0;

    private float baselineOffsets[];
    int baselineIndex;

    public TextMetricsCalculator(TextRunBreaker breaker) {
        this.breaker = breaker;
        checkBaselines();
    }

    /**
     * Returns either values cached by checkBaselines method or reasonable
     * values for the TOP and BOTTOM alignments.
     * @param baselineIndex - baseline index
     * @return baseline offset
     */
    float getBaselineOffset(int baselineIndex) {
        if (baselineIndex >= 0) {
            return baselineOffsets[baselineIndex];
        } else if (baselineIndex == GraphicAttribute.BOTTOM_ALIGNMENT) {
            return descent;
        } else if (baselineIndex == GraphicAttribute.TOP_ALIGNMENT) {
            return -ascent;
        } else {
            // awt.3F=Invalid baseline index
            throw new IllegalArgumentException(Messages.getString("awt.3F")); //$NON-NLS-1$
        }
    }

    public float[] getBaselineOffsets() {
        float ret[] = new float[baselineOffsets.length];
        System.arraycopy(baselineOffsets, 0, ret, 0, baselineOffsets.length);
        return ret;
    }

    /**
     * Take baseline offsets from the first font or graphic attribute
     * and normalizes them, than caches the results.
     */
    public void checkBaselines() {
        // Take baseline offsets of the first font and normalize them
        HashMap<Integer, Font> fonts = breaker.fonts;

        Object val = fonts.get(new Integer(0));

        if (val instanceof Font) {
            Font firstFont = (Font) val;
            LineMetrics lm = firstFont.getLineMetrics(breaker.text, 0, 1, breaker.frc);
            baselineOffsets = lm.getBaselineOffsets();
            baselineIndex = lm.getBaselineIndex();
        } else if (val instanceof GraphicAttribute) {
            // Get first graphic attribute and use it
            GraphicAttribute ga = (GraphicAttribute) val;

            int align = ga.getAlignment();

            if (
                    align == GraphicAttribute.TOP_ALIGNMENT ||
                    align == GraphicAttribute.BOTTOM_ALIGNMENT
            ) {
                baselineIndex = GraphicAttribute.ROMAN_BASELINE;
            } else {
                baselineIndex = align;
            }

            baselineOffsets = new float[3];
            baselineOffsets[0] = 0;
            baselineOffsets[1] = (ga.getDescent() - ga.getAscent()) / 2.f;
            baselineOffsets[2] = -ga.getAscent();
        } else { // Use defaults - Roman baseline and zero offsets
            baselineIndex = GraphicAttribute.ROMAN_BASELINE;
            baselineOffsets = new float[3];
        }

        // Normalize offsets if needed
        if (baselineOffsets[baselineIndex] != 0) {
            float baseOffset = baselineOffsets[baselineIndex];
            for (int i = 0; i < baselineOffsets.length; i++) {
                baselineOffsets[i] -= baseOffset;
            }
        }
    }

    /**
     * Computes metrics for the text managed by the associated TextRunBreaker
     */
    void computeMetrics() {

        ArrayList<TextRunSegment> segments = breaker.runSegments;

        float maxHeight = 0;
        float maxHeightLeading = 0;

        for (int i = 0; i < segments.size(); i++) {
            TextRunSegment segment = segments.get(i);
            BasicMetrics metrics = segment.metrics;
            int baseline = metrics.baseLineIndex;

            if (baseline >= 0) {
                float baselineOffset = baselineOffsets[metrics.baseLineIndex];
                float fixedDescent = metrics.descent + baselineOffset;

                ascent = Math.max(ascent, metrics.ascent - baselineOffset);
                descent = Math.max(descent, fixedDescent);
                leading = Math.max(leading, fixedDescent + metrics.leading);
            } else { // Position is not fixed by the baseline, need sum of ascent and descent
                float height = metrics.ascent + metrics.descent;

                maxHeight = Math.max(maxHeight, height);
                maxHeightLeading = Math.max(maxHeightLeading, height + metrics.leading);
            }
        }

        // Need to increase sizes for graphics?
        if (maxHeightLeading != 0) {
            descent = Math.max(descent, maxHeight - ascent);
            leading = Math.max(leading, maxHeightLeading - ascent);
        }

        // Normalize leading
        leading -= descent;

        BasicMetrics currMetrics;
        float currAdvance = 0;

        for (int i = 0; i < segments.size(); i++) {
            TextRunSegment segment = segments.get(breaker.getSegmentFromVisualOrder(i));
            currMetrics = segment.metrics;

            segment.y = getBaselineOffset(currMetrics.baseLineIndex)
                    + currMetrics.superScriptOffset;
            segment.x = currAdvance;

            currAdvance += segment.getAdvance();
        }

        advance = currAdvance;
    }

    /**
     * Computes metrics and creates BasicMetrics object from them
     * @return basic metrics
     */
    public BasicMetrics createMetrics() {
        computeMetrics();
        return new BasicMetrics(this);
    }

    /**
     * Corrects advance after justification. Gets BasicMetrics object
     * and updates advance stored into it.
     * @param metrics - metrics with outdated advance which should be corrected 
     */
    public void correctAdvance(BasicMetrics metrics) {
        ArrayList<TextRunSegment> segments = breaker.runSegments;
        TextRunSegment segment = segments.get(breaker
                .getSegmentFromVisualOrder(segments.size() - 1));

        advance = segment.x + segment.getAdvance();
        metrics.advance = advance;
    }
}
