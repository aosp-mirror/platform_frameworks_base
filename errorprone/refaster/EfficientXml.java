/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.util.XmlUtils;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;

/**
 * Refaster templates that migrate callers to equivalent and more efficient
 * {@link TypedXmlSerializer} and {@link TypedXmlPullParser} methods.
 */
public class EfficientXml {
    class IntToString {
        @BeforeTemplate
        void beforeToString(TypedXmlSerializer out, String n, int v) throws Exception {
            out.attribute(null, n, Integer.toString(v));
        }

        @BeforeTemplate
        void beforeValueOf(TypedXmlSerializer out, String n, int v) throws Exception {
            out.attribute(null, n, String.valueOf(v));
        }

        @BeforeTemplate
        void beforeUtils(TypedXmlSerializer out, String n, int v) throws Exception {
            XmlUtils.writeIntAttribute(out, n, v);
        }

        @BeforeTemplate
        void beforeRadix(TypedXmlSerializer out, String n, int v) throws Exception {
            out.attribute(null, n, Integer.toString(v, 10));
        }

        @AfterTemplate
        void after(TypedXmlSerializer out, String n, int v) throws Exception {
            out.attributeInt(null, n, v);
        }
    }

    class IntToStringHex {
        @BeforeTemplate
        void beforeToHexString(TypedXmlSerializer out, String n, int v) throws Exception {
            out.attribute(null, n, Integer.toHexString(v));
        }

        @BeforeTemplate
        void beforeRadix(TypedXmlSerializer out, String n, int v) throws Exception {
            out.attribute(null, n, Integer.toString(v, 16));
        }

        @AfterTemplate
        void after(TypedXmlSerializer out, String n, int v) throws Exception {
            out.attributeIntHex(null, n, v);
        }
    }

    class IntFromString {
        @BeforeTemplate
        int beforeParse(TypedXmlPullParser in, String n) throws Exception {
            return Integer.parseInt(in.getAttributeValue(null, n));
        }

        @BeforeTemplate
        int beforeUtils(TypedXmlPullParser in, String n) throws Exception {
            return XmlUtils.readIntAttribute(in, n);
        }

        @BeforeTemplate
        int beforeRadix(TypedXmlPullParser in, String n) throws Exception {
            return Integer.parseInt(in.getAttributeValue(null, n), 10);
        }

        @AfterTemplate
        int after(TypedXmlPullParser in, String n) throws Exception {
            return in.getAttributeInt(null, n);
        }
    }

    class IntFromStringDefault {
        @BeforeTemplate
        int before(TypedXmlPullParser in, String n, int d) throws Exception {
            return XmlUtils.readIntAttribute(in, n, d);
        }

        @AfterTemplate
        int after(TypedXmlPullParser in, String n, int d) throws Exception {
            return in.getAttributeInt(null, n, d);
        }
    }

    class IntFromStringHex {
        @BeforeTemplate
        int beforeParse(TypedXmlPullParser in, String n) throws Exception {
            return Integer.parseInt(in.getAttributeValue(null, n), 16);
        }

        @AfterTemplate
        int after(TypedXmlPullParser in, String n) throws Exception {
            return in.getAttributeIntHex(null, n);
        }
    }

    class LongToString {
        @BeforeTemplate
        void beforeToString(TypedXmlSerializer out, String n, long v) throws Exception {
            out.attribute(null, n, Long.toString(v));
        }

        @BeforeTemplate
        void beforeValueOf(TypedXmlSerializer out, String n, long v) throws Exception {
            out.attribute(null, n, String.valueOf(v));
        }

        @BeforeTemplate
        void beforeUtils(TypedXmlSerializer out, String n, long v) throws Exception {
            XmlUtils.writeLongAttribute(out, n, v);
        }

        @BeforeTemplate
        void beforeRadix(TypedXmlSerializer out, String n, long v) throws Exception {
            out.attribute(null, n, Long.toString(v, 10));
        }

        @AfterTemplate
        void after(TypedXmlSerializer out, String n, long v) throws Exception {
            out.attributeLong(null, n, v);
        }
    }

    class LongToStringHex {
        @BeforeTemplate
        void beforeToHexString(TypedXmlSerializer out, String n, long v) throws Exception {
            out.attribute(null, n, Long.toHexString(v));
        }

        @BeforeTemplate
        void beforeRadix(TypedXmlSerializer out, String n, long v) throws Exception {
            out.attribute(null, n, Long.toString(v, 16));
        }

        @AfterTemplate
        void after(TypedXmlSerializer out, String n, long v) throws Exception {
            out.attributeLongHex(null, n, v);
        }
    }

    class LongFromString {
        @BeforeTemplate
        long beforeParse(TypedXmlPullParser in, String n) throws Exception {
            return Long.parseLong(in.getAttributeValue(null, n));
        }

        @BeforeTemplate
        long beforeUtils(TypedXmlPullParser in, String n) throws Exception {
            return XmlUtils.readLongAttribute(in, n);
        }

        @BeforeTemplate
        long beforeRadix(TypedXmlPullParser in, String n) throws Exception {
            return Long.parseLong(in.getAttributeValue(null, n), 10);
        }

        @AfterTemplate
        long after(TypedXmlPullParser in, String n) throws Exception {
            return in.getAttributeLong(null, n);
        }
    }

    class LongFromStringDefault {
        @BeforeTemplate
        long before(TypedXmlPullParser in, String n, long d) throws Exception {
            return XmlUtils.readLongAttribute(in, n, d);
        }

        @AfterTemplate
        long after(TypedXmlPullParser in, String n, long d) throws Exception {
            return in.getAttributeLong(null, n, d);
        }
    }

    class LongFromStringHex {
        @BeforeTemplate
        long beforeParse(TypedXmlPullParser in, String n) throws Exception {
            return Long.parseLong(in.getAttributeValue(null, n), 16);
        }

        @AfterTemplate
        long after(TypedXmlPullParser in, String n) throws Exception {
            return in.getAttributeLongHex(null, n);
        }
    }

    class FloatToString {
        @BeforeTemplate
        void beforeToString(TypedXmlSerializer out, String n, float v) throws Exception {
            out.attribute(null, n, Float.toString(v));
        }

        @BeforeTemplate
        void beforeValueOf(TypedXmlSerializer out, String n, float v) throws Exception {
            out.attribute(null, n, String.valueOf(v));
        }

        @BeforeTemplate
        void beforeUtils(TypedXmlSerializer out, String n, float v) throws Exception {
            XmlUtils.writeFloatAttribute(out, n, v);
        }

        @AfterTemplate
        void after(TypedXmlSerializer out, String n, float v) throws Exception {
            out.attributeFloat(null, n, v);
        }
    }

    class FloatFromString {
        @BeforeTemplate
        float beforeParse(TypedXmlPullParser in, String n) throws Exception {
            return Float.parseFloat(in.getAttributeValue(null, n));
        }

        @BeforeTemplate
        float beforeUtils(TypedXmlPullParser in, String n) throws Exception {
            return XmlUtils.readFloatAttribute(in, n);
        }

        @AfterTemplate
        float after(TypedXmlPullParser in, String n) throws Exception {
            return in.getAttributeFloat(null, n);
        }
    }

    class DoubleToString {
        @BeforeTemplate
        void beforeToString(TypedXmlSerializer out, String n, double v) throws Exception {
            out.attribute(null, n, Double.toString(v));
        }

        @BeforeTemplate
        void beforeValueOf(TypedXmlSerializer out, String n, double v) throws Exception {
            out.attribute(null, n, String.valueOf(v));
        }

        @AfterTemplate
        void after(TypedXmlSerializer out, String n, double v) throws Exception {
            out.attributeDouble(null, n, v);
        }
    }

    class DoubleFromString {
        @BeforeTemplate
        double beforeParse(TypedXmlPullParser in, String n) throws Exception {
            return Double.parseDouble(in.getAttributeValue(null, n));
        }

        @AfterTemplate
        double after(TypedXmlPullParser in, String n) throws Exception {
            return in.getAttributeDouble(null, n);
        }
    }

    class BooleanToString {
        @BeforeTemplate
        void beforeToString(TypedXmlSerializer out, String n, boolean v) throws Exception {
            out.attribute(null, n, Boolean.toString(v));
        }

        @BeforeTemplate
        void beforeValueOf(TypedXmlSerializer out, String n, boolean v) throws Exception {
            out.attribute(null, n, String.valueOf(v));
        }

        @AfterTemplate
        void after(TypedXmlSerializer out, String n, boolean v) throws Exception {
            out.attributeBoolean(null, n, v);
        }
    }

    class BooleanToStringTrue {
        @BeforeTemplate
        void before(TypedXmlSerializer out, String n) throws Exception {
            out.attribute(null, n, "true");
        }

        @AfterTemplate
        void after(TypedXmlSerializer out, String n) throws Exception {
            out.attributeBoolean(null, n, true);
        }
    }

    class BooleanToStringFalse {
        @BeforeTemplate
        void before(TypedXmlSerializer out, String n) throws Exception {
            out.attribute(null, n, "false");
        }

        @AfterTemplate
        void after(TypedXmlSerializer out, String n) throws Exception {
            out.attributeBoolean(null, n, false);
        }
    }

    class BooleanFromString {
        @BeforeTemplate
        boolean beforeParse(TypedXmlPullParser in, String n) throws Exception {
            return Boolean.parseBoolean(in.getAttributeValue(null, n));
        }

        @BeforeTemplate
        boolean beforeUtils(TypedXmlPullParser in, String n) throws Exception {
            return XmlUtils.readBooleanAttribute(in, n);
        }

        @AfterTemplate
        boolean after(TypedXmlPullParser in, String n) throws Exception {
            return in.getAttributeBoolean(null, n, false);
        }
    }

    class BooleanFromStringDefault {
        @BeforeTemplate
        boolean before(TypedXmlPullParser in, String n, boolean d) throws Exception {
            return XmlUtils.readBooleanAttribute(in, n, d);
        }

        @AfterTemplate
        boolean after(TypedXmlPullParser in, String n, boolean d) throws Exception {
            return in.getAttributeBoolean(null, n, d);
        }
    }
}
