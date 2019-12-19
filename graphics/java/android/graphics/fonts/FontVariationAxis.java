/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Class that holds information about single font variation axis.
 */
public final class FontVariationAxis {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int mTag;
    private final String mTagString;
    @UnsupportedAppUsage
    private final float mStyleValue;

    /**
     * Construct FontVariationAxis.
     *
     * The axis tag must contain four ASCII characters. Tag string that are longer or shorter than
     * four characters, or contains characters outside of U+0020..U+007E are invalid.
     *
     * @throws IllegalArgumentException If given tag string is invalid.
     */
    public FontVariationAxis(@NonNull String tagString, float styleValue) {
        if (!isValidTag(tagString)) {
            throw new IllegalArgumentException("Illegal tag pattern: " + tagString);
        }
        mTag = makeTag(tagString);
        mTagString = tagString;
        mStyleValue = styleValue;
    }

    /**
     * Returns the OpenType style tag value.
     * @hide
     */
    public int getOpenTypeTagValue() {
        return mTag;
    }

    /**
     * Returns the variable font axis tag associated to this axis.
     */
    public String getTag() {
        return mTagString;
    }

    /**
     * Returns the style value associated to the given axis for this font.
     */
    public float getStyleValue() {
        return mStyleValue;
    }

    /**
     * Returns a valid font variation setting string for this object.
     */
    @Override
    public @NonNull String toString() {
        return "'" + mTagString + "' " + Float.toString(mStyleValue);
    }

    /**
     * The 'tag' attribute value is read as four character values between U+0020 and U+007E
     * inclusive.
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("[\u0020-\u007E]{4}");

    /**
     * Returns true if 'tagString' is valid for font variation axis tag.
     */
    private static boolean isValidTag(String tagString) {
        return tagString != null && TAG_PATTERN.matcher(tagString).matches();
    }

    /**
     * The 'styleValue' attribute has an optional leading '-', followed by '<digits>',
     * '<digits>.<digits>', or '.<digits>' where '<digits>' is one or more of [0-9].
     */
    private static final Pattern STYLE_VALUE_PATTERN =
            Pattern.compile("-?(([0-9]+(\\.[0-9]+)?)|(\\.[0-9]+))");

    private static boolean isValidValueFormat(String valueString) {
        return valueString != null && STYLE_VALUE_PATTERN.matcher(valueString).matches();
    }

    /** @hide */
    public static int makeTag(String tagString) {
        final char c1 = tagString.charAt(0);
        final char c2 = tagString.charAt(1);
        final char c3 = tagString.charAt(2);
        final char c4 = tagString.charAt(3);
        return (c1 << 24) | (c2 << 16) | (c3 << 8) | c4;
    }

    /**
     * Construct FontVariationAxis array from font variation settings.
     *
     * The settings string is constructed from multiple pairs of axis tag and style values. The axis
     * tag must contain four ASCII characters and must be wrapped with single quotes (U+0027) or
     * double quotes (U+0022). Axis strings that are longer or shorter than four characters, or
     * contain characters outside of U+0020..U+007E are invalid. If a specified axis name is not
     * defined in the font, the settings will be ignored.
     *
     * <pre>
     *   FontVariationAxis.fromFontVariationSettings("'wdth' 1.0");
     *   FontVariationAxis.fromFontVariationSettings("'AX  ' 1.0, 'FB  ' 2.0");
     * </pre>
     *
     * @param settings font variation settings.
     * @return FontVariationAxis[] the array of parsed font variation axis. {@code null} if settings
     *                             has no font variation settings.
     * @throws IllegalArgumentException If given string is not a valid font variation settings
     *                                  format.
     */
    public static @Nullable FontVariationAxis[] fromFontVariationSettings(
            @Nullable String settings) {
        if (settings == null || settings.isEmpty()) {
            return null;
        }
        final ArrayList<FontVariationAxis> axisList = new ArrayList<>();
        final int length = settings.length();
        for (int i = 0; i < length; i++) {
            final char c = settings.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (!(c == '\'' || c == '"') || length < i + 6 || settings.charAt(i + 5) != c) {
                throw new IllegalArgumentException(
                        "Tag should be wrapped with double or single quote: " + settings);
            }
            final String tagString = settings.substring(i + 1, i + 5);

            i += 6;  // Move to end of tag.
            int endOfValueString = settings.indexOf(',', i);
            if (endOfValueString == -1) {
                endOfValueString = length;
            }
            final float value;
            try {
                // Float.parseFloat ignores leading/trailing whitespaces.
                value = Float.parseFloat(settings.substring(i, endOfValueString));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Failed to parse float string: " + e.getMessage());
            }
            axisList.add(new FontVariationAxis(tagString, value));
            i = endOfValueString;
        }
        if (axisList.isEmpty()) {
            return null;
        }
        return axisList.toArray(new FontVariationAxis[0]);
    }

    /**
     * Stringify the array of FontVariationAxis.
     *
     * @param axes an array of FontVariationAxis.
     * @return String a valid font variation settings string.
     */
    public static @NonNull String toFontVariationSettings(@Nullable FontVariationAxis[] axes) {
        if (axes == null) {
            return "";
        }
        return TextUtils.join(",", axes);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (o == null || !(o instanceof FontVariationAxis)) {
            return false;
        }
        FontVariationAxis axis = (FontVariationAxis) o;
        return axis.mTag == mTag && axis.mStyleValue == mStyleValue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTag, mStyleValue);
    }
}

