/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.graphics.fonts;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A font style object.
 *
 * This class represents a single font style which is a pair of weight value and slant value.
 * Here are common font styles examples:
 * <p>
 * <pre>
 * <code>
 * final FontStyle NORMAL = new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT);
 * final FontStyle BOLD = new FontStyle(FONT_WEIGHT_BOLD, FONT_SLANT_UPRIGHT);
 * final FontStyle ITALIC = new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_ITALIC);
 * final FontStyle BOLD_ITALIC = new FontStyle(FONT_WEIGHT_BOLD, FONT_SLANT_ITALIC);
 * </code>
 * </pre>
 * </p>
 *
 */
public final class FontStyle {
    private static final String TAG = "FontStyle";

    /**
     * A minimum weight value for the font
     */
    public static final int FONT_WEIGHT_MIN = 1;

    /**
     * A font weight value for the thin weight
     */
    public static final int FONT_WEIGHT_THIN = 100;

    /**
     * A font weight value for the extra-light weight
     */
    public static final int FONT_WEIGHT_EXTRA_LIGHT = 200;

    /**
     * A font weight value for the light weight
     */
    public static final int FONT_WEIGHT_LIGHT = 300;

    /**
     * A font weight value for the normal weight
     */
    public static final int FONT_WEIGHT_NORMAL = 400;

    /**
     * A font weight value for the medium weight
     */
    public static final int FONT_WEIGHT_MEDIUM = 500;

    /**
     * A font weight value for the semi-bold weight
     */
    public static final int FONT_WEIGHT_SEMI_BOLD = 600;

    /**
     * A font weight value for the bold weight.
     */
    public static final int FONT_WEIGHT_BOLD = 700;

    /**
     * A font weight value for the extra-bold weight
     */
    public static final int FONT_WEIGHT_EXTRA_BOLD = 800;

    /**
     * A font weight value for the black weight
     */
    public static final int FONT_WEIGHT_BLACK = 900;

    /**
     * A maximum weight value for the font
     */
    public static final int FONT_WEIGHT_MAX = 1000;

    /**
     * A font slant value for upright
     */
    public static final int FONT_SLANT_UPRIGHT = 0;

    /**
     * A font slant value for italic
     */
    public static final int FONT_SLANT_ITALIC = 1;

    // TODO: Support FONT_SLANT_OBLIQUE

    /** @hide */
    @IntDef(prefix = { "FONT_SLANT_" }, value = {
            FONT_SLANT_UPRIGHT,
            FONT_SLANT_ITALIC
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FontSlant {}

    private final @IntRange(from = 0, to = 1000) int mWeight;
    private final @FontSlant int mSlant;
    // TODO: Support width

    public FontStyle() {
        mWeight = FONT_WEIGHT_NORMAL;
        mSlant = FONT_SLANT_UPRIGHT;
    }

    /**
     * Create FontStyle with specific weight and italic
     *
     * <p>
     *  <table>
     *  <thead>
     *  <tr>
     *  <th align="center">Value</th>
     *  <th align="center">Name</th>
     *  <th align="center">Android Definition</th>
     *  </tr>
     *  </thead>
     *  <tbody>
     *  <tr>
     *  <td align="center">100</td>
     *  <td align="center">Thin</td>
     *  <td align="center">{@link FontStyle#FONT_WEIGHT_THIN}</td>
     *  </tr>
     *  <tr>
     *  <td align="center">200</td>
     *  <td align="center">Extra Light (Ultra Light)</td>
     *  <td align="center">{@link FontStyle#FONT_WEIGHT_EXTRA_LIGHT}</td>
     *  </tr>
     *  <tr>
     *  <td align="center">300</td>
     *  <td align="center">Light</td>
     *  <td align="center">{@link FontStyle#FONT_WEIGHT_LIGHT}</td>
     *  </tr>
     *  <tr>
     *  <td align="center">400</td>
     *  <td align="center">Normal (Regular)</td>
     *  <td align="center">{@link FontStyle#FONT_WEIGHT_NORMAL}</td>
     *  </tr>
     *  <tr>
     *  <td align="center">500</td>
     *  <td align="center">Medium</td>
     *  <td align="center">{@link FontStyle#FONT_WEIGHT_MEDIUM}</td>
     *  </tr>
     *  <tr>
     *  <td align="center">600</td>
     *  <td align="center">Semi Bold (Demi Bold)</td>
     *  <td align="center">{@link FontStyle#FONT_WEIGHT_SEMI_BOLD}</td>
     *  </tr>
     *  <tr>
     *  <td align="center">700</td>
     *  <td align="center">Bold</td>
     *  <td align="center">{@link FontStyle#FONT_WEIGHT_BOLD}</td>
     *  </tr>
     *  <tr>
     *  <td align="center">800</td>
     *  <td align="center">Extra Bold (Ultra Bold)</td>
     *  <td align="center">{@link FontStyle#FONT_WEIGHT_EXTRA_BOLD}</td>
     *  </tr>
     *  <tr>
     *  <td align="center">900</td>
     *  <td align="center">Black (Heavy)</td>
     *  <td align="center">{@link FontStyle#FONT_WEIGHT_BLACK}</td>
     *  </tr>
     *  </tbody>
     * </p>
     *
     * @see FontStyle#FONT_WEIGHT_THIN
     * @see FontStyle#FONT_WEIGHT_EXTRA_LIGHT
     * @see FontStyle#FONT_WEIGHT_LIGHT
     * @see FontStyle#FONT_WEIGHT_NORMAL
     * @see FontStyle#FONT_WEIGHT_MEDIUM
     * @see FontStyle#FONT_WEIGHT_SEMI_BOLD
     * @see FontStyle#FONT_WEIGHT_BOLD
     * @see FontStyle#FONT_WEIGHT_EXTRA_BOLD
     * @see FontStyle#FONT_WEIGHT_BLACK
     * @param weight a weight value
     * @param slant a slant value
     */
    public FontStyle(int weight, @FontSlant int slant) {
        Preconditions.checkArgument(FONT_WEIGHT_MIN <= weight && weight <= FONT_WEIGHT_MAX,
                "weight value must be [" + FONT_WEIGHT_MIN + ", " + FONT_WEIGHT_MAX + "]");
        Preconditions.checkArgument(slant == FONT_SLANT_UPRIGHT || slant == FONT_SLANT_ITALIC,
                "slant value must be FONT_SLANT_UPRIGHT or FONT_SLANT_UPRIGHT");
        mWeight = weight;
        mSlant = slant;
    }


     /**
      * Gets the weight value
      *
      * @see #FontStyle(int, int)
      * @return a weight value
      */
    public @IntRange(from = 0, to = 1000) int getWeight() {
        return mWeight;
    }

    /**
     * Gets the slant value
     *
     * @return a slant value
     */
    public @FontSlant int getSlant() {
        return mSlant;
    }

    /**
     * Compute the matching score for another style.
     *
     * The smaller is better.
     * @hide
     */
    public int getMatchScore(@NonNull FontStyle o) {
        return Math.abs((getWeight() - o.getWeight())) / 100 + (getSlant() == o.getSlant() ? 0 : 2);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (o == null || !(o instanceof FontStyle)) {
            return false;
        }
        FontStyle fontStyle = (FontStyle) o;
        return fontStyle.mWeight == mWeight && fontStyle.mSlant == mSlant;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mWeight, mSlant);
    }

    @Override
    public String toString() {
        return "FontStyle { weight=" + mWeight + ", slant=" + mSlant + "}";
    }
}
