/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.graphics.palette;

/**
 * Helper methods for determining contrast between two colors, either via the colors themselves
 * or components in different color spaces.
 */
public class Contrast {
    /**
     *
     * @param y Y in XYZ that contrasts with the returned Y value
     * @param contrast contrast ratio between color argument and returned Y value. Must be >= 1
     *    or an exception will be thrown
     * @return the lower Y coordinate in XYZ space that contrasts with color, or -1 if reaching
     *    no Y coordinate reaches contrast with color.
     */
    public static float lighterY(float y, float contrast) {
        assert (contrast >= 1);
        float answer = -5 + contrast * (5 + y);
        if (answer > 100.0) {
            return -1;
        }
        return answer;
    }


    /**
     * @param y Y in XYZ that contrasts with the returned Y value
     * @param contrast contrast ratio between color argument and returned Y value. Must be >= 1
     *    or an exception will be thrown
     * @return the lower Y coordinate in XYZ space that contrasts with color, or -1 if reaching
     *    no Y coordinate reaches contrast with color.
     */
    public static float darkerY(float y, float contrast) {
        assert (contrast >= 1);
        float answer = (5 - 5 * contrast + y) / contrast;
        if (answer < 0.0) {
            return -1;
        }
        return answer;
    }

    /**
     * Convert L* in L*a*b* to Y in XYZ.
     *
     * @param lstar L* in L*a*b*
     * @return Y in XYZ
     */
    public static float lstarToY(float lstar) {
        // http://www.brucelindbloom.com/index.html?Eqn_Lab_to_XYZ.html
        float ke = 8.0f;
        if (lstar > ke) {
            return (float) (Math.pow(((lstar + 16.0) / 116.0), 3) * 100.0);
        } else {
            return (float) (lstar / (24389 / 27) * 100.0);
        }
    }

    /**
     * Convert Y in XYZ to L* in L*a*b*.
     *
     * @param y Y in XYZ
     * @return L* in L*a*b*
     */
    public static float yToLstar(float y) {
        y = y / 100.0f;
        float e = 216.0f / 24389.0f;
        float y_intermediate;
        if (y <= e) {
            y_intermediate = (24389.f / 27.f) * y;
            // If y < e, can skip consecutive steps of / 116 + 16 followed by * 116 - 16.
            return y_intermediate;
        } else {
            y_intermediate = (float) Math.cbrt(y);
        }
        return 116.f * y_intermediate - 16.f;
    }


    /**
     * @return Contrast ratio between two Y values in XYZ space.
     */
    public static float contrastYs(float y1, float y2) {
        final float lighter = Math.max(y1, y2);
        final float darker = (lighter == y1) ? y2 : y1;
        return (lighter + 5) / (darker + 5);
    }
}
