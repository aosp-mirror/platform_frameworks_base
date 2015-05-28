/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Rational;
import android.util.SizeF;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Size;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.marshal.impl.MarshalQueryableEnum;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.HighSpeedVideoConfiguration;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.ReprocessFormatsMap;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.hardware.camera2.utils.TypeReference;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static com.android.mediaframeworktest.unit.ByteArrayHelpers.*;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * <pre>
 * adb shell am instrument \
 *      -e class 'com.android.mediaframeworktest.unit.CameraMetadataTest' \
 *      -w com.android.mediaframeworktest/.MediaFrameworkUnitTestRunner
 * </pre>
 */
public class CameraMetadataTest extends junit.framework.TestCase {

    private static final boolean VERBOSE = false;
    private static final String TAG = "CameraMetadataTest";


    CameraMetadataNative mMetadata;

    // Sections
    static final int ANDROID_COLOR_CORRECTION = 0;
    static final int ANDROID_CONTROL = 1;

    // Section starts
    static final int ANDROID_COLOR_CORRECTION_START = ANDROID_COLOR_CORRECTION << 16;
    static final int ANDROID_CONTROL_START = ANDROID_CONTROL << 16;

    // Tags
    static final int ANDROID_COLOR_CORRECTION_MODE = ANDROID_COLOR_CORRECTION_START;
    static final int ANDROID_COLOR_CORRECTION_TRANSFORM = ANDROID_COLOR_CORRECTION_START + 1;
    static final int ANDROID_COLOR_CORRECTION_GAINS = ANDROID_COLOR_CORRECTION_START + 2;

    static final int ANDROID_CONTROL_AE_ANTIBANDING_MODE = ANDROID_CONTROL_START;
    static final int ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION = ANDROID_CONTROL_START + 1;

    // From graphics.h
    private static final int HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED = 0x22;

    @Override
    public void setUp() {
        mMetadata = new CameraMetadataNative();
    }

    @Override
    public void tearDown() throws Exception {
        mMetadata = null;
    }

    @SmallTest
    public void testNew() {
        assertEquals(0, mMetadata.getEntryCount());
        assertTrue(mMetadata.isEmpty());
    }

    @SmallTest
    public void testGetTagFromKey() {

        // Test success

        assertEquals(ANDROID_COLOR_CORRECTION_MODE,
                CameraMetadataNative.getTag("android.colorCorrection.mode"));
        assertEquals(ANDROID_COLOR_CORRECTION_TRANSFORM,
                CameraMetadataNative.getTag("android.colorCorrection.transform"));
        assertEquals(ANDROID_CONTROL_AE_ANTIBANDING_MODE,
                CameraMetadataNative.getTag("android.control.aeAntibandingMode"));
        assertEquals(ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION,
                CameraMetadataNative.getTag("android.control.aeExposureCompensation"));

        // Test failures

        try {
            CameraMetadataNative.getTag(null);
            fail("A null key should throw NPE");
        } catch(NullPointerException e) {
        }

        try {
            CameraMetadataNative.getTag("android.control");
            fail("A section name only should not be a valid key");
        } catch(IllegalArgumentException e) {
        }

        try {
            CameraMetadataNative.getTag("android.control.thisTagNameIsFakeAndDoesNotExist");
            fail("A valid section with an invalid tag name should not be a valid key");
        } catch(IllegalArgumentException e) {
        }

        try {
            CameraMetadataNative.getTag("android");
            fail("A namespace name only should not be a valid key");
        } catch(IllegalArgumentException e) {
        }

        try {
            CameraMetadataNative.getTag("this.key.is.definitely.invalid");
            fail("A completely fake key name should not be valid");
        } catch(IllegalArgumentException e) {
        }
    }

    @SmallTest
    public void testGetTypeFromTag() {
        assertEquals(TYPE_BYTE,
                CameraMetadataNative.getNativeType(ANDROID_COLOR_CORRECTION_MODE));
        assertEquals(TYPE_RATIONAL,
                CameraMetadataNative.getNativeType(ANDROID_COLOR_CORRECTION_TRANSFORM));
        assertEquals(TYPE_FLOAT,
                CameraMetadataNative.getNativeType(ANDROID_COLOR_CORRECTION_GAINS));
        assertEquals(TYPE_BYTE,
                CameraMetadataNative.getNativeType(ANDROID_CONTROL_AE_ANTIBANDING_MODE));
        assertEquals(TYPE_INT32,
                CameraMetadataNative.getNativeType(ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION));

        try {
            CameraMetadataNative.getNativeType(0xDEADF00D);
            fail("No type should exist for invalid tag 0xDEADF00D");
        } catch(IllegalArgumentException e) {
        }
    }

    @SmallTest
    public void testReadWriteValues() {
        final byte ANDROID_COLOR_CORRECTION_MODE_HIGH_QUALITY = 2;
        byte[] valueResult;

        assertEquals(0, mMetadata.getEntryCount());
        assertEquals(true, mMetadata.isEmpty());

        //
        // android.colorCorrection.mode (single enum byte)
        //

        assertEquals(null, mMetadata.readValues(ANDROID_COLOR_CORRECTION_MODE));

        // Write/read null values
        mMetadata.writeValues(ANDROID_COLOR_CORRECTION_MODE, null);
        assertEquals(null, mMetadata.readValues(ANDROID_COLOR_CORRECTION_MODE));

        // Write 0 values
        mMetadata.writeValues(ANDROID_COLOR_CORRECTION_MODE, new byte[] {});

        // Read 0 values
        valueResult = mMetadata.readValues(ANDROID_COLOR_CORRECTION_MODE);
        assertNotNull(valueResult);
        assertEquals(0, valueResult.length);

        assertEquals(1, mMetadata.getEntryCount());
        assertEquals(false, mMetadata.isEmpty());

        // Write 1 value
        mMetadata.writeValues(ANDROID_COLOR_CORRECTION_MODE, new byte[] {
            ANDROID_COLOR_CORRECTION_MODE_HIGH_QUALITY
        });

        // Read 1 value
        valueResult = mMetadata.readValues(ANDROID_COLOR_CORRECTION_MODE);
        assertNotNull(valueResult);
        assertEquals(1, valueResult.length);
        assertEquals(ANDROID_COLOR_CORRECTION_MODE_HIGH_QUALITY, valueResult[0]);

        assertEquals(1, mMetadata.getEntryCount());
        assertEquals(false, mMetadata.isEmpty());

        //
        // android.colorCorrection.colorCorrectionGains (float x 4 array)
        //

        final float[] colorCorrectionGains = new float[] { 1.0f, 2.0f, 3.0f, 4.0f};
        byte[] colorCorrectionGainsAsByteArray = new byte[colorCorrectionGains.length * 4];
        ByteBuffer colorCorrectionGainsByteBuffer =
                ByteBuffer.wrap(colorCorrectionGainsAsByteArray).order(ByteOrder.nativeOrder());
        for (float f : colorCorrectionGains)
            colorCorrectionGainsByteBuffer.putFloat(f);

        // Read
        assertNull(mMetadata.readValues(ANDROID_COLOR_CORRECTION_GAINS));
        mMetadata.writeValues(ANDROID_COLOR_CORRECTION_GAINS, colorCorrectionGainsAsByteArray);

        // Write
        assertArrayEquals(colorCorrectionGainsAsByteArray,
                mMetadata.readValues(ANDROID_COLOR_CORRECTION_GAINS));

        assertEquals(2, mMetadata.getEntryCount());
        assertEquals(false, mMetadata.isEmpty());

        // Erase
        mMetadata.writeValues(ANDROID_COLOR_CORRECTION_GAINS, null);
        assertNull(mMetadata.readValues(ANDROID_COLOR_CORRECTION_GAINS));
        assertEquals(1, mMetadata.getEntryCount());
    }

    /**
     * Format an array into a string with the {@code badIndex} highlighted with {@code **}.
     *
     * <p>Numbers are printed as hexadecimal values.</p>
     *
     * <p>Example: {@code "[hello, **world**]"} for a {@code string[]},
     * or a {@code "[**0xFF**, 0xFF]"} for a {@code int[]}.</p>
     */
    private static <T> String formatArray(T array, int badIndex) {
        StringBuilder builder = new StringBuilder();

        builder.append("[");

        int len = Array.getLength(array);
        for (int i = 0; i < len; ++i) {

            Object elem = Array.get(array, i);

            if (i == badIndex) {
                builder.append("**");
            }

            if (elem instanceof Number) {
                builder.append(String.format("%x", elem));
            } else {
                builder.append(elem);
            }

            if (i == badIndex) {
                builder.append("**");
            }

            if (i != len - 1) {
                builder.append(", ");
            }
        }

        builder.append("]");

        return builder.toString();
    }

    private static <T> void assertArrayEquals(T expected, T actual) {
        if (!expected.getClass().isArray() || !actual.getClass().isArray()) {
            throw new IllegalArgumentException("expected, actual must both be arrays");
        }

        assertEquals("Array lengths must be equal",
                Array.getLength(expected), Array.getLength(actual));

        int len = Array.getLength(expected);
        for (int i = 0; i < len; ++i) {

            Object expectedElement = Array.get(expected, i);
            Object actualElement = Array.get(actual, i);

            if (!expectedElement.equals(actualElement)) {
                fail(String.format(
                        "element %d in array was not equal (expected %s, actual %s). "
                                + "Arrays were: (expected %s, actual %s).",
                                i, expectedElement, actualElement,
                                formatArray(expected, i),
                                formatArray(actual, i)));
            }
        }
    }

    private static <T, T2> void assertArrayContains(T needle, T2 array) {
        if (!array.getClass().isArray()) {
            throw new IllegalArgumentException("actual must be array");
        }

        int len = Array.getLength(array);
        for (int i = 0; i < len; ++i) {

            Object actualElement = Array.get(array, i);

            if (needle.equals(actualElement)) {
                return;
            }
        }

        fail(String.format(
                "could not find element in array (needle %s). "
                        + "Array was: %s.",
                        needle,
                        formatArray(array, len)));
    }

    private <T> void checkKeyGetAndSet(String keyStr, TypeReference<T> typeToken, T expected,
            boolean reuse) {
        Key<T> key = new Key<T>(keyStr, typeToken);
        assertNull(mMetadata.get(key));
        mMetadata.set(key, null);
        assertNull(mMetadata.get(key));
        mMetadata.set(key, expected);

        T actual = mMetadata.get(key);

        if (typeToken.getRawType().isArray()) {
            assertArrayEquals(expected, actual);
        } else {
            assertEquals(expected, actual);
        }

        if (reuse) {
            // reset the key incase we want to use it again
            mMetadata.set(key, null);
        }
    }

    private <T> void checkKeyGetAndSet(String keyStr, TypeReference<T> typeToken, T expected) {
        checkKeyGetAndSet(keyStr, typeToken, expected, /*reuse*/false);
    }

    private <T> void checkKeyGetAndSet(String keyStr, Class<T> type, T expected) {
        checkKeyGetAndSet(keyStr, TypeReference.createSpecializedTypeReference(type), expected);
    }

    /**
     * Ensure that the data survives a marshal/unmarshal round-trip;
     * it must also be equal to the {@code expectedNative} byte array.
     *
     * <p>As a side-effect, the metadata value corresponding to the key is now set to
     * {@code expected}.</p>
     *
     * @return key created with {@code keyName} and {@code T}
     */
    private <T> Key<T> checkKeyMarshal(String keyName, TypeReference<T> typeReference,
            T expected, byte[] expectedNative) {
        Key<T> key = new Key<T>(keyName, typeReference);

        mMetadata.set(key, null);
        assertNull(mMetadata.get(key));

        // Write managed value -> make sure native bytes are what we expect
        mMetadata.set(key, expected);

        byte[] actualValues = mMetadata.readValues(key.getTag());
        assertArrayEquals(expectedNative, actualValues);

        // Write managed value -> make sure read-out managed value is what we expect
        T actual = mMetadata.get(key);

        if (typeReference.getRawType().isArray()) {
            assertArrayEquals(expected, actual);
        } else {
            assertEquals(expected, actual);
        }

        // Write native bytes -> make sure read-out managed value is what we expect
        mMetadata.writeValues(key.getTag(), expectedNative);
        actual = mMetadata.get(key);

        if (typeReference.getRawType().isArray()) {
            assertArrayEquals(expected, actual);
        } else {
            assertEquals(expected, actual);
        }

        return key;
    }

    /**
     * Ensure that the data survives a marshal/unmarshal round-trip;
     * it must also be equal to the {@code expectedNative} byte array.
     *
     * <p>As a side-effect,
     * the metadata value corresponding to the key is now set to {@code expected}.</p>
     *
     * @return key created with {@code keyName} and {@code T}
     */
    private <T> Key<T> checkKeyMarshal(String keyName, T expected, byte[] expectedNative) {
        @SuppressWarnings("unchecked")
        Class<T> expectedClass = (Class<T>) expected.getClass();
        return checkKeyMarshal(keyName,
                TypeReference.createSpecializedTypeReference(expectedClass),
                expected,
                expectedNative);
    }

    @SmallTest
    public void testReadWritePrimitive() {
        // int32 (single)
        checkKeyGetAndSet("android.control.aeExposureCompensation", Integer.TYPE, 0xC0FFEE);

        // byte (single)
        checkKeyGetAndSet("android.flash.maxEnergy", Byte.TYPE, (byte)6);

        // int64 (single)
        checkKeyGetAndSet("android.flash.firingTime", Long.TYPE, 0xABCD12345678FFFFL);

        // float (single)
        checkKeyGetAndSet("android.lens.aperture", Float.TYPE, Float.MAX_VALUE);

        // double (single) -- technically double x 3, but we fake it
        checkKeyGetAndSet("android.jpeg.gpsCoordinates", Double.TYPE, Double.MAX_VALUE);

        // rational (single)
        checkKeyGetAndSet("android.sensor.baseGainFactor", Rational.class, new Rational(1, 2));

        /**
         * Weirder cases, that don't map 1:1 with the native types
         */

        // bool (single) -- with TYPE_BYTE
        checkKeyGetAndSet("android.control.aeLock", Boolean.TYPE, true);

        // integer (single) -- with TYPE_BYTE
        checkKeyGetAndSet("android.control.aePrecaptureTrigger", Integer.TYPE, 6);
    }

    @SmallTest
    public void testReadWritePrimitiveArray() {
        // int32 (n)
        checkKeyGetAndSet("android.sensor.info.sensitivityRange", int[].class,
                new int[] {
                        0xC0FFEE, 0xDEADF00D
                });

        // byte (n)
        checkKeyGetAndSet("android.statistics.faceScores", byte[].class, new byte[] {
                1, 2, 3, 4
        });

        // int64 (n)
        checkKeyGetAndSet("android.scaler.availableProcessedMinDurations", long[].class,
                new long[] {
                        0xABCD12345678FFFFL, 0x1234ABCD5678FFFFL, 0xFFFF12345678ABCDL
                });

        // float (n)
        checkKeyGetAndSet("android.lens.info.availableApertures", float[].class,
                new float[] {
                        Float.MAX_VALUE, Float.MIN_NORMAL, Float.MIN_VALUE
                });

        // double (n) -- in particular double x 3
        checkKeyGetAndSet("android.jpeg.gpsCoordinates", double[].class,
                new double[] {
                        Double.MAX_VALUE, Double.MIN_NORMAL, Double.MIN_VALUE
                });

        // rational (n) -- in particular rational x 9
        checkKeyGetAndSet("android.sensor.calibrationTransform1", Rational[].class,
                new Rational[] {
                        new Rational(1, 2), new Rational(3, 4), new Rational(5, 6),
                        new Rational(7, 8), new Rational(9, 10), new Rational(10, 11),
                        new Rational(12, 13), new Rational(14, 15), new Rational(15, 16)
                });

        /**
         * Weirder cases, that don't map 1:1 with the native types
         */

        // bool (n) -- with TYPE_BYTE
        checkKeyGetAndSet("android.control.aeLock", boolean[].class, new boolean[] {
                true, false, true
        });

        // integer (n) -- with TYPE_BYTE
        checkKeyGetAndSet("android.control.aeAvailableModes", int[].class, new int[] {
            1, 2, 3, 4
        });
    }

    private enum ColorCorrectionMode {
        TRANSFORM_MATRIX,
        FAST,
        HIGH_QUALITY
    }

    private enum AeAntibandingMode {
        OFF,
        _50HZ,
        _60HZ,
        AUTO
    }

    private enum AvailableFormat {
        RAW_SENSOR,
        YV12,
        YCrCb_420_SP,
        IMPLEMENTATION_DEFINED,
        YCbCr_420_888,
        BLOB
    }

    @SmallTest
    public void testReadWriteEnum() {
        // byte (single)
        checkKeyGetAndSet("android.colorCorrection.mode", ColorCorrectionMode.class,
                ColorCorrectionMode.HIGH_QUALITY);

        // byte (single)
        checkKeyGetAndSet("android.control.aeAntibandingMode", AeAntibandingMode.class,
                AeAntibandingMode.AUTO);

        // byte (n)
        checkKeyGetAndSet("android.control.aeAvailableAntibandingModes",
                AeAntibandingMode[].class, new AeAntibandingMode[] {
                        AeAntibandingMode.OFF, AeAntibandingMode._50HZ, AeAntibandingMode._60HZ,
                        AeAntibandingMode.AUTO
                });

        /**
         * Stranger cases that don't use byte enums
         */
        // int (n)
        checkKeyGetAndSet("android.scaler.availableFormats", AvailableFormat[].class,
                new AvailableFormat[] {
                        AvailableFormat.RAW_SENSOR,
                        AvailableFormat.YV12,
                        AvailableFormat.IMPLEMENTATION_DEFINED,
                        AvailableFormat.YCbCr_420_888,
                        AvailableFormat.BLOB
                });

    }

    @SmallTest
    public void testReadWriteEnumWithCustomValues() {
        MarshalQueryableEnum.registerEnumValues(AeAntibandingMode.class, new int[] {
            0,
            10,
            20,
            30
        });

        // byte (single)
        checkKeyGetAndSet("android.control.aeAntibandingMode", AeAntibandingMode.class,
                AeAntibandingMode.AUTO);

        // byte (n)
        checkKeyGetAndSet("android.control.aeAvailableAntibandingModes",
                AeAntibandingMode[].class, new AeAntibandingMode[] {
                        AeAntibandingMode.OFF, AeAntibandingMode._50HZ, AeAntibandingMode._60HZ,
                        AeAntibandingMode.AUTO
                });

        byte[] aeAntibandingModeValues = mMetadata.readValues(CameraMetadataNative
                .getTag("android.control.aeAvailableAntibandingModes"));
        byte[] expectedValues = new byte[] { 0, 10, 20, 30 };
        assertArrayEquals(expectedValues, aeAntibandingModeValues);


        /**
         * Stranger cases that don't use byte enums
         */
        // int (n)
        MarshalQueryableEnum.registerEnumValues(AvailableFormat.class, new int[] {
            0x20,
            0x32315659,
            0x11,
            0x22,
            0x23,
            0x21,
        });

        checkKeyGetAndSet("android.scaler.availableFormats", AvailableFormat[].class,
                new AvailableFormat[] {
                        AvailableFormat.RAW_SENSOR,
                        AvailableFormat.YV12,
                        AvailableFormat.IMPLEMENTATION_DEFINED,
                        AvailableFormat.YCbCr_420_888,
                        AvailableFormat.BLOB
                });

        Key<AvailableFormat[]> availableFormatsKey =
                new Key<AvailableFormat[]>("android.scaler.availableFormats",
                        AvailableFormat[].class);
        byte[] availableFormatValues = mMetadata.readValues(CameraMetadataNative
                .getTag(availableFormatsKey.getName()));

        int[] expectedIntValues = new int[] {
                0x20,
                0x32315659,
                0x22,
                0x23,
                0x21
        };

        ByteBuffer bf = ByteBuffer.wrap(availableFormatValues).order(ByteOrder.nativeOrder());

        assertEquals(expectedIntValues.length * 4, availableFormatValues.length);
        for (int i = 0; i < expectedIntValues.length; ++i) {
            assertEquals(expectedIntValues[i], bf.getInt());
        }
    }

    @SmallTest
    public void testReadWriteSize() {
        // int32 x n
        checkKeyGetAndSet("android.jpeg.thumbnailSize", Size.class, new Size(123, 456));

        // int32 x 2 x n
        checkKeyGetAndSet("android.scaler.availableJpegSizes", Size[].class, new Size[] {
            new Size(123, 456),
            new Size(0xDEAD, 0xF00D),
            new Size(0xF00, 0xB00)
        });
    }

    @SmallTest
    public void testReadWriteRggbChannelVector() {
        // int32 x n
        checkKeyMarshal("android.colorCorrection.gains",
                new RggbChannelVector(1.0f, 2.1f, 3.2f, 4.5f),
                toByteArray(1.0f, 2.1f, 3.2f, 4.5f));

        // int32 x 2 x n [pretend; actual is not array]
        checkKeyMarshal("android.colorCorrection.gains",
                new RggbChannelVector[] {
                    new RggbChannelVector(1.0f, 2.0f, 3.0f, 4.0f),
                    new RggbChannelVector(9.0f, 8.0f, 7.0f, 6.0f),
                    new RggbChannelVector(1.3f, 5.5f, 2.4f, 6.7f),
                }, toByteArray(
                        1.0f, 2.0f, 3.0f, 4.0f,
                        9.0f, 8.0f, 7.0f, 6.0f,
                        1.3f, 5.5f, 2.4f, 6.7f
                ));
    }

    @SmallTest
    public void testReadWriteSizeF() {
        // int32 x n
        checkKeyMarshal("android.sensor.info.physicalSize",
                new SizeF(123f, 456f),
                toByteArray(123f, 456f));

        // int32 x 2 x n
        checkKeyMarshal("android.sensor.info.physicalSize",
                new SizeF[] {
                    new SizeF(123f, 456f),
                    new SizeF(1.234f, 4.567f),
                    new SizeF(999.0f, 555.0f)
                },
                toByteArray(
                        123f, 456f,
                        1.234f, 4.567f,
                        999.0f, 555.0f)
        );
    }

    @SmallTest
    public void testReadWriteRectangle() {
        // int32 x n
        checkKeyMarshal("android.scaler.cropRegion",
                // x1, y1, x2, y2
                new Rect(10, 11, 1280, 1024),
                // x, y, width, height
                toByteArray(10, 11, 1280 - 10, 1024 - 11));

        // int32 x 2 x n  [actually not array, but we pretend it is]
        checkKeyMarshal("android.scaler.cropRegion", new Rect[] {
            new Rect(110, 111, 11280, 11024),
            new Rect(210, 111, 21280, 21024),
            new Rect(310, 111, 31280, 31024)
        }, toByteArray(
                110, 111, 11280 - 110, 11024 - 111,
                210, 111, 21280 - 210, 21024 - 111,
                310, 111, 31280 - 310, 31024 - 111
        ));
    }

    @SmallTest
    public void testReadWriteMeteringRectangle() {
        // int32 x 5 x area_count [but we pretend it's a single element]
        checkKeyMarshal("android.control.aeRegions",
                new MeteringRectangle(/*x*/1, /*y*/2, /*width*/100, /*height*/200, /*weight*/5),
                /* xmin, ymin, xmax, ymax, weight */
                toByteArray(1, 2, 1 + 100, 2 + 200, 5));

        // int32 x 5 x area_count
        checkKeyMarshal("android.control.afRegions",
                new MeteringRectangle[] {
                    new MeteringRectangle(/*x*/5, /*y*/6, /*width*/123, /*height*/456, /*weight*/7),
                    new MeteringRectangle(/*x*/7, /*y*/8, /*width*/456, /*height*/999, /*weight*/6),
                    new MeteringRectangle(/*x*/1, /*y*/2, /*width*/100, /*height*/200, /*weight*/5)
                },
                toByteArray(
                        5, 6, 5 + 123, 6 + 456, 7,
                        7, 8, 7 + 456, 8 + 999, 6,
                        1, 2, 1 + 100, 2 + 200, 5
        ));
    }

    @SmallTest
    public void testReadWriteHighSpeedVideoConfiguration() {
        // int32 x 5 x 1
        checkKeyMarshal("android.control.availableHighSpeedVideoConfigurations",
                new HighSpeedVideoConfiguration(
                        /*width*/1000, /*height*/255, /*fpsMin*/30, /*fpsMax*/200,
                        /*batchSizeMax*/8),
                /* width, height, fpsMin, fpsMax */
                toByteArray(1000, 255, 30, 200, 8));

        // int32 x 5 x 3
        checkKeyMarshal("android.control.availableHighSpeedVideoConfigurations",
                new HighSpeedVideoConfiguration[] {
                    new HighSpeedVideoConfiguration(
                            /*width*/1280, /*height*/720, /*fpsMin*/60, /*fpsMax*/120,
                            /*batchSizeMax*/8),
                    new HighSpeedVideoConfiguration(
                            /*width*/123, /*height*/456, /*fpsMin*/1, /*fpsMax*/200,
                            /*batchSizeMax*/4),
                    new HighSpeedVideoConfiguration(
                            /*width*/4096, /*height*/2592, /*fpsMin*/30, /*fpsMax*/60,
                            /*batchSizeMax*/2)
                },
                toByteArray(
                        1280, 720, 60, 120, 8,
                        123, 456, 1, 200, 4,
                        4096, 2592, 30, 60, 2
        ));
    }

    @SmallTest
    public void testReadWriteColorSpaceTransform() {
        // rational x 3 x 3
        checkKeyMarshal("android.colorCorrection.transform",
                new ColorSpaceTransform(new Rational[] {
                        new Rational(1, 2), new Rational(3, 4), new Rational(5, 6),
                        new Rational(7, 8), new Rational(8, 9), new Rational(10, 11),
                        new Rational(1, 5), new Rational(2, 8), new Rational(3, 9),
                }),
                toByteArray(
                        1, 2, 3, 4, 5, 6,
                        7, 8, 8, 9, 10, 11,
                        1, 5, 1, 4, 1, 3));
    }

    @SmallTest
    public void testReadWritePoint() {
        // int32 x 2 [actually 'x n' but pretend it's a single value for now]
        checkKeyMarshal("android.statistics.hotPixelMap",
                new Point(1, 2),
                toByteArray(1, 2));

        // int32 x 2 x samples
        checkKeyMarshal("android.statistics.hotPixelMap",
                new Point[] {
                    new Point(1, 2),
                    new Point(3, 4),
                    new Point(5, 6),
                    new Point(7, 8),
                },
                toByteArray(
                        1, 2,
                        3, 4,
                        5, 6,
                        7, 8)
        );
    }

    @SmallTest
    public void testReadWritePointF() {
        // float x 2 [actually 'x samples' but pretend it's a single value for now]
        checkKeyMarshal(
                "android.sensor.profileToneCurve",
                new PointF(1.0f, 2.0f),
                toByteArray(1.0f, 2.0f));

        // float x 2 x samples
        checkKeyMarshal("android.sensor.profileToneCurve",
                new PointF[] {
                    new PointF(1.0f, 2.0f),
                    new PointF(3.0f, 4.0f),
                    new PointF(5.0f, 6.0f),
                    new PointF(7.0f, 8.0f),
                },
                toByteArray(
                        1.0f, 2.0f,
                        3.0f, 4.0f,
                        5.0f, 6.0f,
                        7.0f, 8.0f));
    }

    @SmallTest
    public void testReadWritePair() {
        // float x 2
        checkKeyMarshal("android.lens.focusRange",
                new TypeReference<Pair<Float, Float>>() {{ }},
                Pair.create(1.0f / 2.0f, 1.0f / 3.0f),
                toByteArray(1.0f / 2.0f, 1.0f / 3.0f));

        // byte, int (fake from TYPE_BYTE)
        // This takes advantage of the TYPE_BYTE -> int marshaler designed for enums.
        checkKeyMarshal("android.flash.mode",
                new TypeReference<Pair<Byte, Integer>>() {{ }},
                Pair.create((byte)123, 22),
                toByteArray((byte)123, (byte)22));
    }

    @SmallTest
    public void testReadWriteRange() {
        // int32 x 2
        checkKeyMarshal("android.control.aeTargetFpsRange",
                new TypeReference<Range<Integer>>() {{ }},
                Range.create(123, 456),
                toByteArray(123, 456));

        // int64 x 2
        checkKeyMarshal("android.sensor.info.exposureTimeRange",
                new TypeReference<Range<Long>>() {{ }},
                Range.create(123L, 456L),
                toByteArray(123L, 456L));
    }

    @SmallTest
    public void testReadWriteStreamConfiguration() {
        // int32 x 4 x n
        checkKeyMarshal("android.scaler.availableStreamConfigurations",
                new StreamConfiguration[] {
                    new StreamConfiguration(ImageFormat.YUV_420_888, 640, 480, /*input*/false),
                    new StreamConfiguration(ImageFormat.RGB_565, 320, 240, /*input*/true),
                },
                toByteArray(
                        ImageFormat.YUV_420_888, 640, 480, /*input*/0,
                        ImageFormat.RGB_565, 320, 240, /*input*/1)
        );
    }

    @SmallTest
    public void testReadWriteStreamConfigurationDuration() {
        // Avoid sign extending ints when converting to a long
        final long MASK_UNSIGNED_INT = 0x00000000ffffffffL;

        // int64 x 4 x n
        checkKeyMarshal("android.scaler.availableMinFrameDurations",
                new StreamConfigurationDuration[] {
                    new StreamConfigurationDuration(
                            ImageFormat.YUV_420_888, 640, 480, /*duration*/123L),
                    new StreamConfigurationDuration(
                            ImageFormat.RGB_565, 320, 240, /*duration*/345L),
                },
                toByteArray(
                        ImageFormat.YUV_420_888 & MASK_UNSIGNED_INT, 640L, 480L, /*duration*/123L,
                        ImageFormat.RGB_565 & MASK_UNSIGNED_INT, 320L, 240L, /*duration*/345L)
        );
    }


    @SmallTest
    public void testReadWriteReprocessFormatsMap() {

        // final int RAW_OPAQUE = 0x24; // TODO: add RAW_OPAQUE to ImageFormat
        final int RAW16 = ImageFormat.RAW_SENSOR;
        final int YUV_420_888 = ImageFormat.YUV_420_888;
        final int BLOB = 0x21;

        // TODO: also test HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED as an output
        int[] contents = new int[] {
                YUV_420_888, 3, YUV_420_888, ImageFormat.NV21, BLOB,
                RAW16, 2, YUV_420_888, BLOB,

        };

        // int32 x n
        Key<ReprocessFormatsMap> key = new Key<ReprocessFormatsMap>(
                "android.scaler.availableInputOutputFormatsMap", ReprocessFormatsMap.class);
        mMetadata.writeValues(key.getTag(), toByteArray(contents));

        ReprocessFormatsMap map = mMetadata.get(key);

        /*
         * Make sure the inputs/outputs were what we expected.
         * - Use public image format constants here.
         */

        int[] expectedInputs = new int[] {
                YUV_420_888, RAW16
        };
        assertArrayEquals(expectedInputs, map.getInputs());

        int[] expectedYuvOutputs = new int[] {
                YUV_420_888, ImageFormat.NV21, ImageFormat.JPEG,
        };
        assertArrayEquals(expectedYuvOutputs, map.getOutputs(ImageFormat.YUV_420_888));

        int[] expectedRaw16Outputs = new int[] {
                YUV_420_888, ImageFormat.JPEG,
        };
        assertArrayEquals(expectedRaw16Outputs, map.getOutputs(ImageFormat.RAW_SENSOR));

        // Finally, do a round-trip check as a sanity
        checkKeyMarshal(
                "android.scaler.availableInputOutputFormatsMap",
                new ReprocessFormatsMap(contents),
                toByteArray(contents)
        );
    }

    @SmallTest
    public void testReadWriteString() {
        // (byte) string
        Key<String> gpsProcessingMethodKey =
                new Key<String>("android.jpeg.gpsProcessingMethod", String.class);

        String helloWorld = new String("HelloWorld");
        byte[] helloWorldBytes = new byte[] {
                'H', 'e', 'l', 'l', 'o', 'W', 'o', 'r', 'l', 'd', '\0' };

        mMetadata.set(gpsProcessingMethodKey, helloWorld);

        String actual = mMetadata.get(gpsProcessingMethodKey);
        assertEquals(helloWorld, actual);

        byte[] actualBytes = mMetadata.readValues(getTag(gpsProcessingMethodKey.getName()));
        assertArrayEquals(helloWorldBytes, actualBytes);

        // Does not yet test as a string[] since we don't support that in native code.

        // (byte) string
        Key<String[]> gpsProcessingMethodKeyArray =
                new Key<String[]>("android.jpeg.gpsProcessingMethod", String[].class);

        String[] gpsStrings = new String[] { "HelloWorld", "FooBar", "Shazbot" };
        byte[] gpsBytes = new byte[] {
                'H', 'e', 'l', 'l', 'o', 'W', 'o', 'r', 'l', 'd', '\0',
                'F', 'o', 'o', 'B', 'a', 'r', '\0',
                'S', 'h', 'a', 'z', 'b', 'o', 't', '\0'};

        mMetadata.set(gpsProcessingMethodKeyArray, gpsStrings);

        String[] actualArray = mMetadata.get(gpsProcessingMethodKeyArray);
        assertArrayEquals(gpsStrings, actualArray);

        byte[] actualBytes2 = mMetadata.readValues(getTag(gpsProcessingMethodKeyArray.getName()));
        assertArrayEquals(gpsBytes, actualBytes2);
    }

    @SmallTest
    public void testReadWriteOverride() {
        //
        // android.scaler.availableFormats (int x n array)
        //
        int[] availableFormats = new int[] {
                0x20,       // RAW_SENSOR
                0x32315659, // YV12
                0x11,       // YCrCb_420_SP
                0x100,      // ImageFormat.JPEG
                0x22,       // IMPLEMENTATION_DEFINED
                0x23,       // YCbCr_420_888
        };
        int[] expectedIntValues = new int[] {
                0x20,       // RAW_SENSOR
                0x32315659, // YV12
                0x11,       // YCrCb_420_SP
                0x21,       // BLOB
                0x22,       // IMPLEMENTATION_DEFINED
                0x23,       // YCbCr_420_888
        };
        int availableFormatTag = CameraMetadataNative.getTag("android.scaler.availableFormats");

        Key<int[]> formatKey = CameraCharacteristics.SCALER_AVAILABLE_FORMATS.getNativeKey();

        validateArrayMetadataReadWriteOverride(formatKey, availableFormats,
                expectedIntValues, availableFormatTag);

        //
        // android.statistics.faces (Face x n array)
        //
        int[] expectedFaceIds = new int[] {1, 2, 3, 4, 5};
        byte[] expectedFaceScores = new byte[] {10, 20, 30, 40, 50};
        int numFaces = expectedFaceIds.length;
        Rect[] expectedRects = new Rect[numFaces];
        for (int i = 0; i < numFaces; i++) {
            expectedRects[i] = new Rect(i*4 + 1, i * 4 + 2, i * 4 + 3, i * 4 + 4);
        }
        int[] expectedFaceLM = new int[] {
                1, 2, 3, 4, 5, 6,
                7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18,
                19, 20, 21, 22, 23, 24,
                25, 26, 27, 28, 29, 30,
        };
        Point[] expectedFaceLMPoints = new Point[numFaces * 3];
        for (int i = 0; i < numFaces; i++) {
            expectedFaceLMPoints[i*3] = new Point(expectedFaceLM[i*6], expectedFaceLM[i*6+1]);
            expectedFaceLMPoints[i*3+1] = new Point(expectedFaceLM[i*6+2], expectedFaceLM[i*6+3]);
            expectedFaceLMPoints[i*3+2] = new Point(expectedFaceLM[i*6+4], expectedFaceLM[i*6+5]);
        }

        /**
         * Read - FACE_DETECT_MODE == FULL
         */
        mMetadata.set(CaptureResult.STATISTICS_FACE_DETECT_MODE,
                CaptureResult.STATISTICS_FACE_DETECT_MODE_FULL);
        mMetadata.set(CaptureResult.STATISTICS_FACE_IDS, expectedFaceIds);
        mMetadata.set(CaptureResult.STATISTICS_FACE_SCORES, expectedFaceScores);
        mMetadata.set(CaptureResult.STATISTICS_FACE_RECTANGLES, expectedRects);
        mMetadata.set(CaptureResult.STATISTICS_FACE_LANDMARKS, expectedFaceLM);
        Face[] resultFaces = mMetadata.get(CaptureResult.STATISTICS_FACES);
        assertEquals(numFaces, resultFaces.length);
        for (int i = 0; i < numFaces; i++) {
            assertEquals(expectedFaceIds[i], resultFaces[i].getId());
            assertEquals(expectedFaceScores[i], resultFaces[i].getScore());
            assertEquals(expectedRects[i], resultFaces[i].getBounds());
            assertEquals(expectedFaceLMPoints[i*3], resultFaces[i].getLeftEyePosition());
            assertEquals(expectedFaceLMPoints[i*3+1], resultFaces[i].getRightEyePosition());
            assertEquals(expectedFaceLMPoints[i*3+2], resultFaces[i].getMouthPosition());
        }

        /**
         * Read - FACE_DETECT_MODE == SIMPLE
         */
        mMetadata.set(CaptureResult.STATISTICS_FACE_DETECT_MODE,
                CaptureResult.STATISTICS_FACE_DETECT_MODE_SIMPLE);
        mMetadata.set(CaptureResult.STATISTICS_FACE_SCORES, expectedFaceScores);
        mMetadata.set(CaptureResult.STATISTICS_FACE_RECTANGLES, expectedRects);
        Face[] resultSimpleFaces = mMetadata.get(CaptureResult.STATISTICS_FACES);
        assertEquals(numFaces, resultSimpleFaces.length);
        for (int i = 0; i < numFaces; i++) {
            assertEquals(Face.ID_UNSUPPORTED, resultSimpleFaces[i].getId());
            assertEquals(expectedFaceScores[i], resultSimpleFaces[i].getScore());
            assertEquals(expectedRects[i], resultSimpleFaces[i].getBounds());
            assertNull(resultSimpleFaces[i].getLeftEyePosition());
            assertNull(resultSimpleFaces[i].getRightEyePosition());
            assertNull(resultSimpleFaces[i].getMouthPosition());
        }

        /**
         * Read/Write TonemapCurve
         */
        float[] red = new float[] {0.0f, 0.0f, 1.0f, 1.0f};
        float[] green = new float[] {0.0f, 1.0f, 1.0f, 0.0f};
        float[] blue = new float[] {
                0.0000f, 0.0000f, 0.0667f, 0.2920f, 0.1333f, 0.4002f, 0.2000f, 0.4812f,
                0.2667f, 0.5484f, 0.3333f, 0.6069f, 0.4000f, 0.6594f, 0.4667f, 0.7072f,
                0.5333f, 0.7515f, 0.6000f, 0.7928f, 0.6667f, 0.8317f, 0.7333f, 0.8685f,
                0.8000f, 0.9035f, 0.8667f, 0.9370f, 0.9333f, 0.9691f, 1.0000f, 1.0000f};
        TonemapCurve tcIn = new TonemapCurve(red, green, blue);
        mMetadata.set(CaptureResult.TONEMAP_CURVE, tcIn);
        float[] redOut = mMetadata.get(CaptureResult.TONEMAP_CURVE_RED);
        float[] greenOut = mMetadata.get(CaptureResult.TONEMAP_CURVE_GREEN);
        float[] blueOut = mMetadata.get(CaptureResult.TONEMAP_CURVE_BLUE);
        assertArrayEquals(red, redOut);
        assertArrayEquals(green, greenOut);
        assertArrayEquals(blue, blueOut);
        TonemapCurve tcOut = mMetadata.get(CaptureResult.TONEMAP_CURVE);
        assertEquals(tcIn, tcOut);
        mMetadata.set(CaptureResult.TONEMAP_CURVE_GREEN, null);
        // If any of channel has null curve, return a null TonemapCurve
        assertNull(mMetadata.get(CaptureResult.TONEMAP_CURVE));
    }

    /**
     * Set the raw native value of the available stream configurations; ensure that
     * the read-out managed value is consistent with what we write in.
     */
    @SmallTest
    public void testOverrideStreamConfigurationMap() {

        /*
         * First, write all the raw values:
         * - availableStreamConfigurations
         * - availableMinFrameDurations
         * - availableStallDurations
         *
         * Then, read this out as a synthetic multi-key 'streamConfigurationMap'
         *
         * Finally, validate that the map was unmarshaled correctly
         * and is converting the internal formats to public formats properly.
         */

        //
        // android.scaler.availableStreamConfigurations (int x n x 4 array)
        //
        final int OUTPUT = 0;
        final int INPUT = 1;
        int[] rawAvailableStreamConfigs = new int[] {
                0x20, 3280, 2464, OUTPUT, // RAW16
                0x23, 3264, 2448, OUTPUT, // YCbCr_420_888
                0x23, 3200, 2400, OUTPUT, // YCbCr_420_888
                0x21, 3264, 2448, OUTPUT, // BLOB
                0x21, 3200, 2400, OUTPUT, // BLOB
                0x21, 2592, 1944, OUTPUT, // BLOB
                0x21, 2048, 1536, OUTPUT, // BLOB
                0x21, 1920, 1080, OUTPUT, // BLOB
                0x22, 640, 480, OUTPUT,   // IMPLEMENTATION_DEFINED
                0x20, 320, 240, INPUT,   // RAW16
        };
        Key<StreamConfiguration[]> configKey =
                CameraCharacteristics.SCALER_AVAILABLE_STREAM_CONFIGURATIONS.getNativeKey();
        mMetadata.writeValues(configKey.getTag(),
                toByteArray(rawAvailableStreamConfigs));

        //
        // android.scaler.availableMinFrameDurations (int x n x 4 array)
        //
        long[] expectedAvailableMinDurations = new long[] {
                0x20, 3280, 2464, 33333331, // RAW16
                0x23, 3264, 2448, 33333332, // YCbCr_420_888
                0x23, 3200, 2400, 33333333, // YCbCr_420_888
                0x100, 3264, 2448, 33333334, // ImageFormat.JPEG
                0x100, 3200, 2400, 33333335, // ImageFormat.JPEG
                0x100, 2592, 1944, 33333336, // ImageFormat.JPEG
                0x100, 2048, 1536, 33333337, // ImageFormat.JPEG
                0x100, 1920, 1080, 33333338  // ImageFormat.JPEG
        };
        long[] rawAvailableMinDurations = new long[] {
                0x20, 3280, 2464, 33333331, // RAW16
                0x23, 3264, 2448, 33333332, // YCbCr_420_888
                0x23, 3200, 2400, 33333333, // YCbCr_420_888
                0x21, 3264, 2448, 33333334, // BLOB
                0x21, 3200, 2400, 33333335, // BLOB
                0x21, 2592, 1944, 33333336, // BLOB
                0x21, 2048, 1536, 33333337, // BLOB
                0x21, 1920, 1080, 33333338  // BLOB
        };
        Key<StreamConfigurationDuration[]> durationKey =
                CameraCharacteristics.SCALER_AVAILABLE_MIN_FRAME_DURATIONS.getNativeKey();
        mMetadata.writeValues(durationKey.getTag(),
                toByteArray(rawAvailableMinDurations));

        //
        // android.scaler.availableStallDurations (int x n x 4 array)
        //
        long[] expectedAvailableStallDurations = new long[] {
                0x20, 3280, 2464, 0,        // RAW16
                0x23, 3264, 2448, 0,        // YCbCr_420_888
                0x23, 3200, 2400, 0,        // YCbCr_420_888
                0x100, 3264, 2448, 33333334, // ImageFormat.JPEG
                0x100, 3200, 2400, 33333335, // ImageFormat.JPEG
                0x100, 2592, 1944, 33333336, // ImageFormat.JPEG
                0x100, 2048, 1536, 33333337, // ImageFormat.JPEG
                0x100, 1920, 1080, 33333338  // ImageFormat.JPEG
        };
        // Note: RAW16 and YUV_420_888 omitted intentionally; omitted values should default to 0
        long[] rawAvailableStallDurations = new long[] {
                0x21, 3264, 2448, 33333334, // BLOB
                0x21, 3200, 2400, 33333335, // BLOB
                0x21, 2592, 1944, 33333336, // BLOB
                0x21, 2048, 1536, 33333337, // BLOB
                0x21, 1920, 1080, 33333338  // BLOB
        };
        Key<StreamConfigurationDuration[]> stallDurationKey =
                CameraCharacteristics.SCALER_AVAILABLE_STALL_DURATIONS.getNativeKey();
        mMetadata.writeValues(stallDurationKey.getTag(),
                toByteArray(rawAvailableStallDurations));

        //
        // android.scaler.streamConfigurationMap (synthetic as StreamConfigurationMap)
        //
        StreamConfigurationMap streamConfigMap = mMetadata.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        // Inputs
        checkStreamConfigurationMapByFormatSize(
                streamConfigMap, ImageFormat.RAW_SENSOR, 320, 240, /*output*/false);

        // Outputs
        checkStreamConfigurationMapByFormatSize(
                streamConfigMap, HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED, 640, 480, /*output*/true);
        checkStreamConfigurationMapByFormatSize(
                streamConfigMap, ImageFormat.JPEG, 1920, 1080, /*output*/true);
        checkStreamConfigurationMapByFormatSize(
                streamConfigMap, ImageFormat.JPEG, 2048, 1536, /*output*/true);
        checkStreamConfigurationMapByFormatSize(
                streamConfigMap, ImageFormat.JPEG, 2592, 1944, /*output*/true);
        checkStreamConfigurationMapByFormatSize(
                streamConfigMap, ImageFormat.JPEG, 3200, 2400, /*output*/true);
        checkStreamConfigurationMapByFormatSize(
                streamConfigMap, ImageFormat.YUV_420_888, 3200, 2400, /*output*/true);
        checkStreamConfigurationMapByFormatSize(
                streamConfigMap, ImageFormat.YUV_420_888, 3264, 2448, /*output*/true);
        checkStreamConfigurationMapByFormatSize(
                streamConfigMap, ImageFormat.RAW_SENSOR, 3280, 2464, /*output*/true);

        // Min Frame Durations

        final int DURATION_TUPLE_SIZE = 4;
        for (int i = 0; i < expectedAvailableMinDurations.length; i += DURATION_TUPLE_SIZE) {
            checkStreamConfigurationMapDurationByFormatSize(
                    streamConfigMap,
                    (int)expectedAvailableMinDurations[i],
                    (int)expectedAvailableMinDurations[i+1],
                    (int)expectedAvailableMinDurations[i+2],
                    Duration.MinFrame,
                    expectedAvailableMinDurations[i+3]);
        }

        // Stall Frame Durations

        for (int i = 0; i < expectedAvailableStallDurations.length; i += DURATION_TUPLE_SIZE) {
            checkStreamConfigurationMapDurationByFormatSize(
                    streamConfigMap,
                    (int)expectedAvailableStallDurations[i],
                    (int)expectedAvailableStallDurations[i+1],
                    (int)expectedAvailableStallDurations[i+2],
                    Duration.Stall,
                    expectedAvailableStallDurations[i+3]);
        }
    }

    private <T> void assertKeyValueEquals(T expected, CameraCharacteristics.Key<T> key) {
        assertKeyValueEquals(expected, key.getNativeKey());
    }

    private <T> void assertKeyValueEquals(T expected, Key<T> key) {
        T actual = mMetadata.get(key);

        assertEquals("Expected value for key " + key + " to match", expected, actual);
    }

    @SmallTest
    public void testOverrideMaxRegions() {
        // All keys are null before doing any writes.
        assertKeyValueEquals(null, CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        assertKeyValueEquals(null, CameraCharacteristics.CONTROL_MAX_REGIONS_AWB);
        assertKeyValueEquals(null, CameraCharacteristics.CONTROL_MAX_REGIONS_AF);

        mMetadata.set(CameraCharacteristics.CONTROL_MAX_REGIONS,
                new int[] { /*AE*/1, /*AWB*/2, /*AF*/3 });

        // All keys are the expected value after doing a write
        assertKeyValueEquals(1, CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        assertKeyValueEquals(2, CameraCharacteristics.CONTROL_MAX_REGIONS_AWB);
        assertKeyValueEquals(3, CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
    }

    @SmallTest
    public void testOverrideMaxNumOutputStreams() {
        // All keys are null before doing any writes.
        assertKeyValueEquals(null, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW);
        assertKeyValueEquals(null, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC);
        assertKeyValueEquals(null, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING);

        mMetadata.set(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_STREAMS,
                new int[] { /*AE*/1, /*AWB*/2, /*AF*/3 });

        // All keys are the expected value after doing a write
        assertKeyValueEquals(1, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW);
        assertKeyValueEquals(2, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC);
        assertKeyValueEquals(3, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING);
    }

    @SmallTest
    public void testCaptureResult() {
        mMetadata.set(CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);

        if (VERBOSE) mMetadata.dumpToLog();

        CaptureResult captureResult = new CaptureResult(mMetadata, /*sequenceId*/0);

        List<CaptureResult.Key<?>> allKeys = captureResult.getKeys();
        if (VERBOSE) Log.v(TAG, "testCaptureResult: key list size " + allKeys);
        for (CaptureResult.Key<?> key : captureResult.getKeys()) {
            if (VERBOSE) {
                Log.v(TAG,
                    "testCaptureResult: key " + key + " value" + captureResult.get(key));
            }
        }

        assertTrue(allKeys.size() >= 1); // FIXME: android.statistics.faces counts as a key
        assertTrue(allKeys.contains(CaptureResult.CONTROL_AE_MODE));

        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH,
                (int)captureResult.get(CaptureResult.CONTROL_AE_MODE));
    }

    private static void checkStreamConfigurationMapByFormatSize(StreamConfigurationMap configMap,
            int format, int width, int height,
            boolean output) {

        /** arbitrary class for which StreamConfigurationMap#isOutputSupportedFor(Class) is true */
        final Class<?> IMPLEMENTATION_DEFINED_OUTPUT_CLASS = SurfaceTexture.class;

        android.util.Size[] sizes;
        int[] formats;

        if (output) {
            if (format == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) {
                sizes = configMap.getOutputSizes(IMPLEMENTATION_DEFINED_OUTPUT_CLASS);
                // in this case the 'is output format supported' is vacuously true
                formats = new int[] { HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED };
            } else {
                sizes = configMap.getOutputSizes(format);
                formats = configMap.getOutputFormats();
                assertTrue("Format must be supported by stream configuration map",
                        configMap.isOutputSupportedFor(format));
            }
        } else {
            // NOTE: No function to do input sizes from IMPL_DEFINED, so it would just fail for that
            sizes = configMap.getInputSizes(format);
            formats = configMap.getInputFormats();
        }

        android.util.Size expectedSize = new android.util.Size(width, height);

        assertArrayContains(format, formats);
        assertArrayContains(expectedSize, sizes);
    }

    private enum Duration {
        MinFrame,
        Stall
    }

    private static void checkStreamConfigurationMapDurationByFormatSize(
            StreamConfigurationMap configMap,
            int format, int width, int height, Duration durationKind, long expectedDuration) {

        /** arbitrary class for which StreamConfigurationMap#isOutputSupportedFor(Class) is true */
        final Class<?> IMPLEMENTATION_DEFINED_OUTPUT_CLASS = SurfaceTexture.class;

        long actualDuration;

        android.util.Size size = new android.util.Size(width, height);
        switch (durationKind) {
            case MinFrame:
                if (format == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) {
                    actualDuration = configMap.getOutputMinFrameDuration(
                            IMPLEMENTATION_DEFINED_OUTPUT_CLASS, size);
                } else {
                    actualDuration = configMap.getOutputMinFrameDuration(format, size);
                }

                break;
            case Stall:
                if (format == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) {
                    actualDuration = configMap.getOutputStallDuration(
                            IMPLEMENTATION_DEFINED_OUTPUT_CLASS, size);
                } else {
                    actualDuration = configMap.getOutputStallDuration(format, size);
                }

                break;
            default:
                throw new AssertionError();
        }

        assertEquals("Expected " + durationKind + " to match actual value", expectedDuration,
                actualDuration);
    }

    /**
     * Validate metadata array tag read/write override.
     *
     * <p>Only support long and int array for now, can be easily extend to support other
     * primitive arrays.</p>
     */
    private <T> void validateArrayMetadataReadWriteOverride(Key<T> key, T expectedWriteValues,
            T expectedReadValues, int tag) {
        Class<?> type = expectedWriteValues.getClass();
        if (!type.isArray()) {
            throw new IllegalArgumentException("This function expects an key with array type");
        } else if (type != int[].class && type != long[].class) {
            throw new IllegalArgumentException("This function expects long or int array values");
        }

        // Write
        mMetadata.set(key, expectedWriteValues);

        byte[] readOutValues = mMetadata.readValues(tag);

        ByteBuffer bf = ByteBuffer.wrap(readOutValues).order(ByteOrder.nativeOrder());

        int readValuesLength = Array.getLength(expectedReadValues);
        int readValuesNumBytes = readValuesLength * 4;
        if (type == long[].class) {
            readValuesNumBytes = readValuesLength * 8;
        }

        assertEquals(readValuesNumBytes, readOutValues.length);
        for (int i = 0; i < readValuesLength; ++i) {
            if (type == int[].class) {
                assertEquals(Array.getInt(expectedReadValues, i), bf.getInt());
            } else if (type == long[].class) {
                assertEquals(Array.getLong(expectedReadValues, i), bf.getLong());
            }
        }

        // Read
        byte[] readOutValuesAsByteArray = new byte[readValuesNumBytes];
        ByteBuffer readOutValuesByteBuffer =
                ByteBuffer.wrap(readOutValuesAsByteArray).order(ByteOrder.nativeOrder());
        for (int i = 0; i < readValuesLength; ++i) {
            if (type == int[].class) {
                readOutValuesByteBuffer.putInt(Array.getInt(expectedReadValues, i));
            } else if (type == long[].class) {
                readOutValuesByteBuffer.putLong(Array.getLong(expectedReadValues, i));
            }
        }
        mMetadata.writeValues(tag, readOutValuesAsByteArray);

        T result = mMetadata.get(key);
        assertNotNull(key.getName() + " result shouldn't be null", result);
        assertArrayEquals(expectedWriteValues, result);
    }

    // TODO: move somewhere else
    @SmallTest
    public void testToByteArray() {
        assertArrayEquals(new byte[] { 5, 0, 0, 0, 6, 0, 0, 0 },
                toByteArray(5, 6));
        assertArrayEquals(new byte[] { 5, 0, 6, 0, },
                toByteArray((short)5, (short)6));
        assertArrayEquals(new byte[] { (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
                                        (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,},
                toByteArray(~0, ~0));

        assertArrayEquals(new byte[] { (byte)0xAB, (byte)0xFF, 0, 0,
                0x0D, (byte)0xF0, (byte)0xAD, (byte)0xDE },
                toByteArray(0xFFAB, 0xDEADF00D));
    }
}
