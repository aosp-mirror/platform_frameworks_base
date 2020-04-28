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

package android.hardware.display;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;

import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

/** @hide */
@SystemApi
@TestApi
public final class BrightnessConfiguration implements Parcelable {
    private static final String TAG_BRIGHTNESS_CURVE = "brightness-curve";
    private static final String TAG_BRIGHTNESS_POINT = "brightness-point";
    private static final String TAG_BRIGHTNESS_CORRECTIONS = "brightness-corrections";
    private static final String TAG_BRIGHTNESS_CORRECTION = "brightness-correction";
    private static final String ATTR_LUX = "lux";
    private static final String ATTR_NITS = "nits";
    private static final String ATTR_DESCRIPTION = "description";
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_CATEGORY = "category";

    private final float[] mLux;
    private final float[] mNits;
    private final Map<String, BrightnessCorrection> mCorrectionsByPackageName;
    private final Map<Integer, BrightnessCorrection> mCorrectionsByCategory;
    private final String mDescription;

    private BrightnessConfiguration(float[] lux, float[] nits,
            Map<String, BrightnessCorrection> correctionsByPackageName,
            Map<Integer, BrightnessCorrection> correctionsByCategory, String description) {
        mLux = lux;
        mNits = nits;
        mCorrectionsByPackageName = correctionsByPackageName;
        mCorrectionsByCategory = correctionsByCategory;
        mDescription = description;
    }

    /**
     * Gets the base brightness as curve.
     *
     * The curve is returned as a pair of float arrays, the first representing all of the lux
     * points of the brightness curve and the second representing all of the nits values of the
     * brightness curve.
     *
     * @return the control points for the brightness curve.
     */
    public Pair<float[], float[]> getCurve() {
        return Pair.create(Arrays.copyOf(mLux, mLux.length), Arrays.copyOf(mNits, mNits.length));
    }

    /**
     * Returns a brightness correction by app, or null.
     *
     * @param packageName
     *      The app's package name.
     *
     * @return The matching brightness correction, or null.
     *
     */
    @Nullable
    public BrightnessCorrection getCorrectionByPackageName(@NonNull String packageName) {
        return mCorrectionsByPackageName.get(packageName);
    }

    /**
     * Returns a brightness correction by app category, or null.
     *
     * @param category
     *      The app category.
     *
     * @return The matching brightness correction, or null.
     */
    @Nullable
    public BrightnessCorrection getCorrectionByCategory(@ApplicationInfo.Category int category) {
        return mCorrectionsByCategory.get(category);
    }

    /**
     * Returns description string.
     * @hide
     */
    public String getDescription() {
        return mDescription;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloatArray(mLux);
        dest.writeFloatArray(mNits);
        dest.writeInt(mCorrectionsByPackageName.size());
        for (Entry<String, BrightnessCorrection> entry : mCorrectionsByPackageName.entrySet()) {
            final String packageName = entry.getKey();
            final BrightnessCorrection correction = entry.getValue();
            dest.writeString(packageName);
            correction.writeToParcel(dest, flags);
        }
        dest.writeInt(mCorrectionsByCategory.size());
        for (Entry<Integer, BrightnessCorrection> entry : mCorrectionsByCategory.entrySet()) {
            final int category = entry.getKey();
            final BrightnessCorrection correction = entry.getValue();
            dest.writeInt(category);
            correction.writeToParcel(dest, flags);
        }
        dest.writeString(mDescription);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BrightnessConfiguration{[");
        final int size = mLux.length;
        for (int i = 0; i < size; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append("(").append(mLux[i]).append(", ").append(mNits[i]).append(")");
        }
        sb.append("], {");
        for (Entry<String, BrightnessCorrection> entry : mCorrectionsByPackageName.entrySet()) {
            sb.append("'" + entry.getKey() + "': " + entry.getValue() + ", ");
        }
        for (Entry<Integer, BrightnessCorrection> entry : mCorrectionsByCategory.entrySet()) {
            sb.append(entry.getKey() + ": " + entry.getValue() + ", ");
        }
        sb.append("}, '");
        if (mDescription != null) {
            sb.append(mDescription);
        }
        sb.append("'}");
        return sb.toString();
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = result * 31 + Arrays.hashCode(mLux);
        result = result * 31 + Arrays.hashCode(mNits);
        result = result * 31 + mCorrectionsByPackageName.hashCode();
        result = result * 31 + mCorrectionsByCategory.hashCode();
        if (mDescription != null) {
            result = result * 31 + mDescription.hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof BrightnessConfiguration)) {
            return false;
        }
        final BrightnessConfiguration other = (BrightnessConfiguration) o;
        return Arrays.equals(mLux, other.mLux) && Arrays.equals(mNits, other.mNits)
                && mCorrectionsByPackageName.equals(other.mCorrectionsByPackageName)
                && mCorrectionsByCategory.equals(other.mCorrectionsByCategory)
                && Objects.equals(mDescription, other.mDescription);
    }

    public static final @android.annotation.NonNull Creator<BrightnessConfiguration> CREATOR =
            new Creator<BrightnessConfiguration>() {
        public BrightnessConfiguration createFromParcel(Parcel in) {
            float[] lux = in.createFloatArray();
            float[] nits = in.createFloatArray();
            Builder builder = new Builder(lux, nits);

            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                final String packageName = in.readString();
                final BrightnessCorrection correction =
                        BrightnessCorrection.CREATOR.createFromParcel(in);
                builder.addCorrectionByPackageName(packageName, correction);
            }

            n = in.readInt();
            for (int i = 0; i < n; i++) {
                final int category = in.readInt();
                final BrightnessCorrection correction =
                        BrightnessCorrection.CREATOR.createFromParcel(in);
                builder.addCorrectionByCategory(category, correction);
            }

            final String description = in.readString();
            builder.setDescription(description);
            return builder.build();
        }

        public BrightnessConfiguration[] newArray(int size) {
            return new BrightnessConfiguration[size];
        }
    };

    /**
     * Writes the configuration to an XML serializer.
     *
     * @param serializer
     *      The XML serializer.
     *
     * @hide
     */
    public void saveToXml(@NonNull XmlSerializer serializer) throws IOException {
        serializer.startTag(null, TAG_BRIGHTNESS_CURVE);
        if (mDescription != null) {
            serializer.attribute(null, ATTR_DESCRIPTION, mDescription);
        }
        for (int i = 0; i < mLux.length; i++) {
            serializer.startTag(null, TAG_BRIGHTNESS_POINT);
            serializer.attribute(null, ATTR_LUX, Float.toString(mLux[i]));
            serializer.attribute(null, ATTR_NITS, Float.toString(mNits[i]));
            serializer.endTag(null, TAG_BRIGHTNESS_POINT);
        }
        serializer.endTag(null, TAG_BRIGHTNESS_CURVE);
        serializer.startTag(null, TAG_BRIGHTNESS_CORRECTIONS);
        for (Map.Entry<String, BrightnessCorrection> entry :
                mCorrectionsByPackageName.entrySet()) {
            final String packageName = entry.getKey();
            final BrightnessCorrection correction = entry.getValue();
            serializer.startTag(null, TAG_BRIGHTNESS_CORRECTION);
            serializer.attribute(null, ATTR_PACKAGE_NAME, packageName);
            correction.saveToXml(serializer);
            serializer.endTag(null, TAG_BRIGHTNESS_CORRECTION);
        }
        for (Map.Entry<Integer, BrightnessCorrection> entry : mCorrectionsByCategory.entrySet()) {
            final int category = entry.getKey();
            final BrightnessCorrection correction = entry.getValue();
            serializer.startTag(null, TAG_BRIGHTNESS_CORRECTION);
            serializer.attribute(null, ATTR_CATEGORY, Integer.toString(category));
            correction.saveToXml(serializer);
            serializer.endTag(null, TAG_BRIGHTNESS_CORRECTION);
        }
        serializer.endTag(null, TAG_BRIGHTNESS_CORRECTIONS);
    }

    /**
     * Read a configuration from an XML parser.
     *
     * @param parser
     *      The XML parser.
     *
     * @throws IOException
     *      The parser failed to read the XML file.
     * @throws XmlPullParserException
     *      The parser failed to parse the XML file.
     *
     * @hide
     */
    public static BrightnessConfiguration loadFromXml(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String description = null;
        List<Float> luxList = new ArrayList<>();
        List<Float> nitsList = new ArrayList<>();
        Map<String, BrightnessCorrection> correctionsByPackageName = new HashMap<>();
        Map<Integer, BrightnessCorrection> correctionsByCategory = new HashMap<>();
        final int configDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, configDepth)) {
            if (TAG_BRIGHTNESS_CURVE.equals(parser.getName())) {
                description = parser.getAttributeValue(null, ATTR_DESCRIPTION);
                final int curveDepth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, curveDepth)) {
                    if (!TAG_BRIGHTNESS_POINT.equals(parser.getName())) {
                        continue;
                    }
                    final float lux = loadFloatFromXml(parser, ATTR_LUX);
                    final float nits = loadFloatFromXml(parser, ATTR_NITS);
                    luxList.add(lux);
                    nitsList.add(nits);
                }
            }
            if (TAG_BRIGHTNESS_CORRECTIONS.equals(parser.getName())) {
                final int correctionsDepth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, correctionsDepth)) {
                    if (!TAG_BRIGHTNESS_CORRECTION.equals(parser.getName())) {
                        continue;
                    }
                    final String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                    final String categoryText = parser.getAttributeValue(null, ATTR_CATEGORY);
                    BrightnessCorrection correction = BrightnessCorrection.loadFromXml(parser);
                    if (packageName != null) {
                        correctionsByPackageName.put(packageName, correction);
                    } else if (categoryText != null) {
                        try {
                            final int category = Integer.parseInt(categoryText);
                            correctionsByCategory.put(category, correction);
                        } catch (NullPointerException | NumberFormatException e) {
                            continue;
                        }
                    }
                }
            }
        }
        final int n = luxList.size();
        float[] lux = new float[n];
        float[] nits = new float[n];
        for (int i = 0; i < n; i++) {
            lux[i] = luxList.get(i);
            nits[i] = nitsList.get(i);
        }
        final BrightnessConfiguration.Builder builder = new BrightnessConfiguration.Builder(lux,
                nits);
        builder.setDescription(description);
        for (Map.Entry<String, BrightnessCorrection> entry : correctionsByPackageName.entrySet()) {
            final String packageName = entry.getKey();
            final BrightnessCorrection correction = entry.getValue();
            builder.addCorrectionByPackageName(packageName, correction);
        }
        for (Map.Entry<Integer, BrightnessCorrection> entry : correctionsByCategory.entrySet()) {
            final int category = entry.getKey();
            final BrightnessCorrection correction = entry.getValue();
            builder.addCorrectionByCategory(category, correction);
        }
        return builder.build();
    }

    private static float loadFloatFromXml(XmlPullParser parser, String attribute) {
        final String string = parser.getAttributeValue(null, attribute);
        try {
            return Float.parseFloat(string);
        } catch (NullPointerException | NumberFormatException e) {
            return Float.NaN;
        }
    }

    /**
     * A builder class for {@link BrightnessConfiguration}s.
     */
    public static class Builder {
        private static final int MAX_CORRECTIONS_BY_PACKAGE_NAME = 20;
        private static final int MAX_CORRECTIONS_BY_CATEGORY = 20;

        private float[] mCurveLux;
        private float[] mCurveNits;
        private Map<String, BrightnessCorrection> mCorrectionsByPackageName;
        private Map<Integer, BrightnessCorrection> mCorrectionsByCategory;
        private String mDescription;

        /**
         * Constructs the builder with the control points for the brightness curve.
         *
         * Brightness curves must have strictly increasing ambient brightness values in lux and
         * monotonically increasing display brightness values in nits. In addition, the initial
         * control point must be 0 lux.
         *
         * @throws IllegalArgumentException if the initial control point is not at 0 lux.
         * @throws IllegalArgumentException if the lux levels are not strictly increasing.
         * @throws IllegalArgumentException if the nit levels are not monotonically increasing.
         */
        public Builder(float[] lux, float[] nits) {
            Preconditions.checkNotNull(lux);
            Preconditions.checkNotNull(nits);
            if (lux.length == 0 || nits.length == 0) {
                throw new IllegalArgumentException("Lux and nits arrays must not be empty");
            }
            if (lux.length != nits.length) {
                throw new IllegalArgumentException("Lux and nits arrays must be the same length");
            }
            if (lux[0] != 0) {
                throw new IllegalArgumentException("Initial control point must be for 0 lux");
            }
            Preconditions.checkArrayElementsInRange(lux, 0, Float.MAX_VALUE, "lux");
            Preconditions.checkArrayElementsInRange(nits, 0, Float.MAX_VALUE, "nits");
            checkMonotonic(lux, true /*strictly increasing*/, "lux");
            checkMonotonic(nits, false /*strictly increasing*/, "nits");
            mCurveLux = lux;
            mCurveNits = nits;
            mCorrectionsByPackageName = new HashMap<>();
            mCorrectionsByCategory = new HashMap<>();
        }

        /**
         * Returns the maximum number of corrections by package name allowed.
         *
         * @return The maximum number of corrections by package name allowed.
         *
         */
        public int getMaxCorrectionsByPackageName() {
            return MAX_CORRECTIONS_BY_PACKAGE_NAME;
        }

        /**
         * Returns the maximum number of corrections by category allowed.
         *
         * @return The maximum number of corrections by category allowed.
         *
         */
        public int getMaxCorrectionsByCategory() {
            return MAX_CORRECTIONS_BY_CATEGORY;
        }

        /**
         * Add a brightness correction by app package name.
         * This correction is applied whenever an app with this package name has the top activity
         * of the focused stack.
         *
         * @param packageName
         *      The app's package name.
         * @param correction
         *      The brightness correction.
         *
         * @return The builder.
         *
         * @throws IllegalArgumentExceptions
         *      Maximum number of corrections by package name exceeded (see
         *      {@link #getMaxCorrectionsByPackageName}).
         *
         */
        @NonNull
        public Builder addCorrectionByPackageName(@NonNull String packageName,
                @NonNull BrightnessCorrection correction) {
            Objects.requireNonNull(packageName, "packageName must not be null");
            Objects.requireNonNull(correction, "correction must not be null");
            if (mCorrectionsByPackageName.size() >= getMaxCorrectionsByPackageName()) {
                throw new IllegalArgumentException("Too many corrections by package name");
            }
            mCorrectionsByPackageName.put(packageName, correction);
            return this;
        }

        /**
         * Add a brightness correction by app category.
         * This correction is applied whenever an app with this category has the top activity of
         * the focused stack, and only if a correction by package name has not been applied.
         *
         * @param category
         *      The {@link android.content.pm.ApplicationInfo#category app category}.
         * @param correction
         *      The brightness correction.
         *
         * @return The builder.
         *
         * @throws IllegalArgumentException
         *      Maximum number of corrections by category exceeded (see
         *      {@link #getMaxCorrectionsByCategory}).
         *
         */
        @NonNull
        public Builder addCorrectionByCategory(@ApplicationInfo.Category int category,
                @NonNull BrightnessCorrection correction) {
            Objects.requireNonNull(correction, "correction must not be null");
            if (mCorrectionsByCategory.size() >= getMaxCorrectionsByCategory()) {
                throw new IllegalArgumentException("Too many corrections by category");
            }
            mCorrectionsByCategory.put(category, correction);
            return this;
        }

        /**
         * Set description of the brightness curve.
         *
         * @param description brief text describing the curve pushed. It maybe truncated
         *                    and will not be displayed in the UI
         */
        @NonNull
        public Builder setDescription(@Nullable String description) {
            mDescription = description;
            return this;
        }

        /**
         * Builds the {@link BrightnessConfiguration}.
         */
        @NonNull
        public BrightnessConfiguration build() {
            if (mCurveLux == null || mCurveNits == null) {
                throw new IllegalStateException("A curve must be set!");
            }
            return new BrightnessConfiguration(mCurveLux, mCurveNits, mCorrectionsByPackageName,
                    mCorrectionsByCategory, mDescription);
        }

        private static void checkMonotonic(float[] vals, boolean strictlyIncreasing, String name) {
            if (vals.length <= 1) {
                return;
            }
            float prev = vals[0];
            for (int i = 1; i < vals.length; i++) {
                if (prev > vals[i] || prev == vals[i] && strictlyIncreasing) {
                    String condition = strictlyIncreasing ? "strictly increasing" : "monotonic";
                    throw new IllegalArgumentException(name + " values must be " + condition);
                }
                prev = vals[i];
            }
        }
    }
}
