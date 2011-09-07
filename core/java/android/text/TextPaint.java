/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.text;

import android.graphics.Paint;

/**
 * TextPaint is an extension of Paint that leaves room for some extra
 * data used during text measuring and drawing.
 */
public class TextPaint extends Paint {

    private static final int DEFAULT_UNDERLINE_SIZE = 3;

    // Special value 0 means no background paint
    public int bgColor;
    public int baselineShift;
    public int linkColor;
    public int[] drawableState;
    public float density = 1.0f;
    /**
     * Special value 0 means no custom underline
     * @hide
     */
    public int[] underlineColors;
    /**
     * Defined as a multiplier of the default underline thickness. Use 1.0f for default thickness.
     * @hide
     */
    public float[] underlineThicknesses;
    /**
     * The number of underlines currently stored in the array. If 0, no underline is drawn.
     * @hide
     */
    public int underlineCount;

    public TextPaint() {
        super();
    }

    public TextPaint(int flags) {
        super(flags);
    }

    public TextPaint(Paint p) {
        super(p);
    }

    /**
     * Copy the fields from tp into this TextPaint, including the
     * fields inherited from Paint.
     */
    public void set(TextPaint tp) {
        super.set(tp);

        bgColor = tp.bgColor;
        baselineShift = tp.baselineShift;
        linkColor = tp.linkColor;
        drawableState = tp.drawableState;
        density = tp.density;

        if (tp.underlineColors != null) {
            if (underlineColors == null || underlineColors.length < tp.underlineCount) {
                underlineColors = new int[tp.underlineCount];
                underlineThicknesses = new float[tp.underlineCount];
            }
            System.arraycopy(tp.underlineColors, 0, underlineColors, 0, tp.underlineCount);
            System.arraycopy(tp.underlineThicknesses, 0, underlineThicknesses, 0, tp.underlineCount);
        }
        underlineCount = tp.underlineCount;
    }

    /**
     * Defines a custom underline for this Paint.
     * @param color underline solid color
     * @param thickness underline thickness
     * @hide
     */
    public void setUnderlineText(int color, float thickness) {
        if (color == 0) {
            // No underline
            return;
        }

        if (underlineCount == 0) {
            underlineColors = new int[DEFAULT_UNDERLINE_SIZE];
            underlineThicknesses = new float[DEFAULT_UNDERLINE_SIZE];
            underlineColors[underlineCount] = color;
            underlineThicknesses[underlineCount] = thickness;
            underlineCount++;
        } else {
            if (underlineCount == underlineColors.length) {
                int[] newColors = new int[underlineColors.length + DEFAULT_UNDERLINE_SIZE];
                float[] newThickness = new float[underlineThicknesses.length
                        + DEFAULT_UNDERLINE_SIZE];
                System.arraycopy(underlineColors, 0, newColors, 0, underlineColors.length);
                System.arraycopy(
                        underlineThicknesses, 0, newThickness, 0, underlineThicknesses.length);
                underlineColors = newColors;
                underlineThicknesses = newThickness;
            }
            underlineColors[underlineCount] = color;
            underlineThicknesses[underlineCount] = thickness;
            underlineCount++;
        }
    }
}
