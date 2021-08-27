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

package android.hardware.display;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.MathUtils;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * BrightnessCorrection encapsulates a correction to the brightness, without comitting to the
 * actual correction scheme.
 * It is used by the BrightnessConfiguration, which maps context (e.g. the foreground app's package
 * name and category) to corrections that need to be applied to the brightness within that context.
 * Corrections are currently done by the app that has the top activity of the focused stack, either
 * by its package name, or (if its package name is not mapped to any correction) by its category.
 *
 * @hide
 */
@SystemApi
public final class BrightnessCorrection implements Parcelable {

    private static final int SCALE_AND_TRANSLATE_LOG = 1;

    private static final String TAG_SCALE_AND_TRANSLATE_LOG = "scale-and-translate-log";

    private BrightnessCorrectionImplementation mImplementation;

    // Parcelable classes must be final, and protected methods are not allowed in APIs, so we can't
    // make this class abstract and use composition instead of inheritence.
    private BrightnessCorrection(BrightnessCorrectionImplementation implementation) {
        mImplementation = implementation;
    }

    /**
     * Creates a BrightnessCorrection that given {@code brightness}, corrects it to be
     * {@code exp(scale * ln(brightness) + translate)}.
     *
     * @param scale
     *      How much to scale the log (base e) brightness.
     * @param translate
     *      How much to translate the log (base e) brightness.
     *
     * @return A BrightnessCorrection that given {@code brightness}, corrects it to be
     * {@code exp(scale * ln(brightness) + translate)}.
     *
     * @throws IllegalArgumentException
     *      - scale or translate are NaN.
     */
    @NonNull
    public static BrightnessCorrection createScaleAndTranslateLog(float scale, float translate) {
        BrightnessCorrectionImplementation implementation =
                new ScaleAndTranslateLog(scale, translate);
        return new BrightnessCorrection(implementation);
    }

    /**
     * Applies the brightness correction to a given brightness.
     *
     * @param brightness
     *      The brightness.
     *
     * @return The corrected brightness.
     */
    @FloatRange(from = 0.0)
    public float apply(@FloatRange(from = 0.0) float brightness) {
        return mImplementation.apply(brightness);
    }

    /**
     * Returns a string representation.
     *
     * @return A string representation.
     */
    @NonNull
    public String toString() {
        return mImplementation.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof BrightnessCorrection)) {
            return false;
        }
        BrightnessCorrection other = (BrightnessCorrection) o;
        return other.mImplementation.equals(mImplementation);
    }

    @Override
    public int hashCode() {
        return mImplementation.hashCode();
    }

    public static final @android.annotation.NonNull Creator<BrightnessCorrection> CREATOR =
            new Creator<BrightnessCorrection>() {
                public BrightnessCorrection createFromParcel(Parcel in) {
                    final int type = in.readInt();
                    switch (type) {
                        case SCALE_AND_TRANSLATE_LOG:
                            return ScaleAndTranslateLog.readFromParcel(in);
                    }
                    return null;
                }

                public BrightnessCorrection[] newArray(int size) {
                    return new BrightnessCorrection[size];
                }
            };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mImplementation.writeToParcel(dest);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Writes the correction to an XML serializer.
     *
     * @param serializer
     *      The XML serializer.
     *
     * @hide
     */
    public void saveToXml(TypedXmlSerializer serializer) throws IOException {
        mImplementation.saveToXml(serializer);
    }

    /**
     * Read a correction from an XML parser.
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
    public static BrightnessCorrection loadFromXml(TypedXmlPullParser parser) throws IOException,
            XmlPullParserException {
        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (TAG_SCALE_AND_TRANSLATE_LOG.equals(parser.getName())) {
                return ScaleAndTranslateLog.loadFromXml(parser);
            }
        }
        return null;
    }

    private static float loadFloatFromXml(TypedXmlPullParser parser, String attribute) {
        return parser.getAttributeFloat(null, attribute, Float.NaN);
    }

    private interface BrightnessCorrectionImplementation {
        float apply(float brightness);
        String toString();
        void writeToParcel(Parcel dest);
        void saveToXml(TypedXmlSerializer serializer) throws IOException;
        // Package-private static methods:
        // static BrightnessCorrection readFromParcel(Parcel in);
        // static BrightnessCorrection loadFromXml(XmlPullParser parser) throws IOException,
        //      XmlPullParserException;
    }

    /**
     * A BrightnessCorrection that given {@code brightness}, corrects it to be
     * {@code exp(scale * ln(brightness) + translate)}.
     */
    private static class ScaleAndTranslateLog implements BrightnessCorrectionImplementation {
        private static final float MIN_SCALE = 0.5f;
        private static final float MAX_SCALE = 2.0f;
        private static final float MIN_TRANSLATE = -0.6f;
        private static final float MAX_TRANSLATE = 0.7f;

        private static final String ATTR_SCALE = "scale";
        private static final String ATTR_TRANSLATE = "translate";

        private final float mScale;
        private final float mTranslate;

        ScaleAndTranslateLog(float scale, float translate) {
            if (Float.isNaN(scale) || Float.isNaN(translate)) {
                throw new IllegalArgumentException("scale and translate must be numbers");
            }
            mScale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);
            mTranslate = MathUtils.constrain(translate, MIN_TRANSLATE, MAX_TRANSLATE);
        }

        @Override
        public float apply(float brightness) {
            return MathUtils.exp(mScale * MathUtils.log(brightness) + mTranslate);
        }

        @Override
        public String toString() {
            return "ScaleAndTranslateLog(" + mScale + ", " + mTranslate + ")";
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof ScaleAndTranslateLog)) {
                return false;
            }
            ScaleAndTranslateLog other = (ScaleAndTranslateLog) o;
            return other.mScale == mScale && other.mTranslate == mTranslate;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = result * 31 + Float.hashCode(mScale);
            result = result * 31 + Float.hashCode(mTranslate);
            return result;
        }

        @Override
        public void writeToParcel(Parcel dest) {
            dest.writeInt(SCALE_AND_TRANSLATE_LOG);
            dest.writeFloat(mScale);
            dest.writeFloat(mTranslate);
        }

        @Override
        public void saveToXml(TypedXmlSerializer serializer) throws IOException {
            serializer.startTag(null, TAG_SCALE_AND_TRANSLATE_LOG);
            serializer.attributeFloat(null, ATTR_SCALE, mScale);
            serializer.attributeFloat(null, ATTR_TRANSLATE, mTranslate);
            serializer.endTag(null, TAG_SCALE_AND_TRANSLATE_LOG);
        }

        static BrightnessCorrection readFromParcel(Parcel in) {
            float scale = in.readFloat();
            float translate = in.readFloat();
            return BrightnessCorrection.createScaleAndTranslateLog(scale, translate);
        }

        static BrightnessCorrection loadFromXml(TypedXmlPullParser parser) throws IOException,
                XmlPullParserException {
            final float scale = loadFloatFromXml(parser, ATTR_SCALE);
            final float translate = loadFloatFromXml(parser, ATTR_TRANSLATE);
            return BrightnessCorrection.createScaleAndTranslateLog(scale, translate);
        }
    }
}
