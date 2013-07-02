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

import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;
import android.graphics.ImageFormat;
import android.hardware.photography.CameraMetadata;
import android.hardware.photography.Rational;

import static android.hardware.photography.CameraMetadata.*;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertArrayEquals;

/**
 * <pre>
 * adb shell am instrument \
 *      -e class 'com.android.mediaframeworktest.unit.CameraMetadataTest' \
 *      -w com.android.mediaframeworktest/.MediaFrameworkUnitTestRunner
 * </pre>
 */
public class CameraMetadataTest extends junit.framework.TestCase {

    CameraMetadata mMetadata;
    Parcel mParcel;

    // Sections
    static final int ANDROID_COLOR_CORRECTION = 0;
    static final int ANDROID_CONTROL = 1;

    // Section starts
    static final int ANDROID_COLOR_CORRECTION_START = ANDROID_COLOR_CORRECTION << 16;
    static final int ANDROID_CONTROL_START = ANDROID_CONTROL << 16;

    // Tags
    static final int ANDROID_COLOR_CORRECTION_MODE = ANDROID_COLOR_CORRECTION_START;
    static final int ANDROID_COLOR_CORRECTION_TRANSFORM = ANDROID_COLOR_CORRECTION_START + 1;

    static final int ANDROID_CONTROL_AE_ANTIBANDING_MODE = ANDROID_CONTROL_START;
    static final int ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION = ANDROID_CONTROL_START + 1;

    @Override
    public void setUp() {
        mMetadata = new CameraMetadata();
        mParcel = Parcel.obtain();
    }

    @Override
    public void tearDown() throws Exception {
        mMetadata.close();
        mMetadata = null;

        mParcel.recycle();
        mParcel = null;
    }

    @SmallTest
    public void testNew() {
        assertEquals(0, mMetadata.getEntryCount());
        assertTrue(mMetadata.isEmpty());
    }

    @SmallTest
    public void testClose() throws Exception {
        mMetadata.isEmpty(); // no throw

        assertFalse(mMetadata.isClosed());

        mMetadata.close();

        assertTrue(mMetadata.isClosed());

        // OK: second close should not throw
        mMetadata.close();

        assertTrue(mMetadata.isClosed());

        // All other calls after close should throw IllegalStateException

        try {
            mMetadata.isEmpty();
            fail("Unreachable -- isEmpty after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
            // good: we expect calling this method after close to fail
        }

        try {
            mMetadata.getEntryCount();
            fail("Unreachable -- getEntryCount after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
            // good: we expect calling this method after close to fail
        }


        try {
            mMetadata.swap(mMetadata);
            fail("Unreachable -- swap after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
         // good: we expect calling this method after close to fail
        }

        try {
            mMetadata.readFromParcel(mParcel);
            fail("Unreachable -- readFromParcel after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
         // good: we expect calling this method after close to fail
        }

        try {
            mMetadata.writeToParcel(mParcel, /*flags*/0);
            fail("Unreachable -- writeToParcel after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
         // good: we expect calling this method after close to fail
        }

        try {
            mMetadata.readValues(/*tag*/0);
            fail("Unreachable -- readValues after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
         // good: we expect calling this method after close to fail
        }

        try {
            mMetadata.writeValues(/*tag*/0, /*source*/new byte[] { 1,2,3 });
            fail("Unreachable -- readValues after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
         // good: we expect calling this method after close to fail
        }
    }

    @SmallTest
    public void testGetTagFromKey() {

        // Test success

        assertEquals(ANDROID_COLOR_CORRECTION_MODE,
                CameraMetadata.getTag("android.colorCorrection.mode"));
        assertEquals(ANDROID_COLOR_CORRECTION_TRANSFORM,
                CameraMetadata.getTag("android.colorCorrection.transform"));
        assertEquals(ANDROID_CONTROL_AE_ANTIBANDING_MODE,
                CameraMetadata.getTag("android.control.aeAntibandingMode"));
        assertEquals(ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION,
                CameraMetadata.getTag("android.control.aeExposureCompensation"));

        // Test failures

        try {
            CameraMetadata.getTag(null);
            fail("A null key should throw NPE");
        } catch(NullPointerException e) {
        }

        try {
            CameraMetadata.getTag("android.control");
            fail("A section name only should not be a valid key");
        } catch(IllegalArgumentException e) {
        }

        try {
            CameraMetadata.getTag("android.control.thisTagNameIsFakeAndDoesNotExist");
            fail("A valid section with an invalid tag name should not be a valid key");
        } catch(IllegalArgumentException e) {
        }

        try {
            CameraMetadata.getTag("android");
            fail("A namespace name only should not be a valid key");
        } catch(IllegalArgumentException e) {
        }

        try {
            CameraMetadata.getTag("this.key.is.definitely.invalid");
            fail("A completely fake key name should not be valid");
        } catch(IllegalArgumentException e) {
        }
    }

    @SmallTest
    public void testGetTypeFromTag() {
        assertEquals(TYPE_BYTE, CameraMetadata.getNativeType(ANDROID_COLOR_CORRECTION_MODE));
        assertEquals(TYPE_FLOAT, CameraMetadata.getNativeType(ANDROID_COLOR_CORRECTION_TRANSFORM));
        assertEquals(TYPE_BYTE, CameraMetadata.getNativeType(ANDROID_CONTROL_AE_ANTIBANDING_MODE));
        assertEquals(TYPE_INT32,
                CameraMetadata.getNativeType(ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION));

        try {
            CameraMetadata.getNativeType(0xDEADF00D);
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
        // android.colorCorrection.transform (3x3 matrix)
        //

        final float[] transformMatrix = new float[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        byte[] transformMatrixAsByteArray = new byte[transformMatrix.length * 4];
        ByteBuffer transformMatrixByteBuffer =
                ByteBuffer.wrap(transformMatrixAsByteArray).order(ByteOrder.nativeOrder());
        for (float f : transformMatrix)
            transformMatrixByteBuffer.putFloat(f);

        // Read
        assertNull(mMetadata.readValues(ANDROID_COLOR_CORRECTION_TRANSFORM));
        mMetadata.writeValues(ANDROID_COLOR_CORRECTION_TRANSFORM, transformMatrixAsByteArray);

        // Write
        assertArrayEquals(transformMatrixAsByteArray,
                mMetadata.readValues(ANDROID_COLOR_CORRECTION_TRANSFORM));

        assertEquals(2, mMetadata.getEntryCount());
        assertEquals(false, mMetadata.isEmpty());

        // Erase
        mMetadata.writeValues(ANDROID_COLOR_CORRECTION_TRANSFORM, null);
        assertNull(mMetadata.readValues(ANDROID_COLOR_CORRECTION_TRANSFORM));
        assertEquals(1, mMetadata.getEntryCount());
    }

    private static <T> void assertArrayEquals(T expected, T actual) {
        assertEquals(Array.getLength(expected), Array.getLength(actual));

        int len = Array.getLength(expected);
        for (int i = 0; i < len; ++i) {
            assertEquals(Array.get(expected, i), Array.get(actual, i));
        }
    }

    private <T> void checkKeyGetAndSet(String keyStr, Class<T> type, T value) {
        Key<T> key = new Key<T>(keyStr, type);
        assertNull(mMetadata.get(key));
        mMetadata.set(key, value);
        assertEquals(value, mMetadata.get(key));
    }

    private <T> void checkKeyGetAndSetArray(String keyStr, Class<T> type, T value) {
        Key<T> key = new Key<T>(keyStr, type);
        assertNull(mMetadata.get(key));
        mMetadata.set(key, value);
        assertArrayEquals(value, mMetadata.get(key));
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
        checkKeyGetAndSetArray("android.sensor.info.availableSensitivities", int[].class,
                new int[] {
                        0xC0FFEE, 0xDEADF00D
                });

        // byte (n)
        checkKeyGetAndSetArray("android.statistics.faceScores", byte[].class, new byte[] {
                1, 2, 3, 4
        });

        // int64 (n)
        checkKeyGetAndSetArray("android.scaler.availableProcessedMinDurations", long[].class,
                new long[] {
                        0xABCD12345678FFFFL, 0x1234ABCD5678FFFFL, 0xFFFF12345678ABCDL
                });

        // float (n)
        checkKeyGetAndSetArray("android.lens.info.availableApertures", float[].class,
                new float[] {
                        Float.MAX_VALUE, Float.MIN_NORMAL, Float.MIN_VALUE
                });

        // double (n) -- in particular double x 3
        checkKeyGetAndSetArray("android.jpeg.gpsCoordinates", double[].class,
                new double[] {
                        Double.MAX_VALUE, Double.MIN_NORMAL, Double.MIN_VALUE
                });

        // rational (n) -- in particular rational x 9
        checkKeyGetAndSetArray("android.sensor.calibrationTransform1", Rational[].class,
                new Rational[] {
                        new Rational(1, 2), new Rational(3, 4), new Rational(5, 6),
                        new Rational(7, 8), new Rational(9, 10), new Rational(10, 11),
                        new Rational(12, 13), new Rational(14, 15), new Rational(15, 16)
                });

        /**
         * Weirder cases, that don't map 1:1 with the native types
         */

        // bool (n) -- with TYPE_BYTE
        checkKeyGetAndSetArray("android.control.aeLock", boolean[].class, new boolean[] {
                true, false, true
        });


        // integer (n) -- with TYPE_BYTE
        checkKeyGetAndSetArray("android.control.aeAvailableModes", int[].class, new int[] {
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

    // TODO: special values for the enum.
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
        checkKeyGetAndSetArray("android.control.aeAvailableAntibandingModes",
                AeAntibandingMode[].class, new AeAntibandingMode[] {
                        AeAntibandingMode.OFF, AeAntibandingMode._50HZ, AeAntibandingMode._60HZ,
                        AeAntibandingMode.AUTO
                });

        /**
         * Stranger cases that don't use byte enums
         */
        // int (n)
        checkKeyGetAndSetArray("android.scaler.availableFormats", AvailableFormat[].class,
                new AvailableFormat[] {
                        AvailableFormat.RAW_SENSOR,
                        AvailableFormat.YV12,
                        AvailableFormat.IMPLEMENTATION_DEFINED
                });

    }
}
