/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.mediaframeworktest.R;

import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.os.Environment;
import android.test.AndroidTestCase;
import android.util.Log;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;

import libcore.io.IoUtils;
import libcore.io.Streams;

public class ExifInterfaceTest extends AndroidTestCase {
    private static final String TAG = ExifInterface.class.getSimpleName();
    private static final boolean VERBOSE = false;  // lots of logging

    private static final double DIFFERENCE_TOLERANCE = .005;

    // List of files.
    private static final String EXIF_BYTE_ORDER_II_JPEG = "image_exif_byte_order_ii.jpg";
    private static final String EXIF_BYTE_ORDER_MM_JPEG = "image_exif_byte_order_mm.jpg";
    private static final String LG_G4_ISO_800_DNG = "lg_g4_iso_800.dng";
    private static final int[] IMAGE_RESOURCES = new int[] {
            R.raw.image_exif_byte_order_ii,  R.raw.image_exif_byte_order_mm, R.raw.lg_g4_iso_800 };
    private static final String[] IMAGE_FILENAMES = new String[] {
            EXIF_BYTE_ORDER_II_JPEG, EXIF_BYTE_ORDER_MM_JPEG, LG_G4_ISO_800_DNG };

    private static final String[] EXIF_TAGS = {
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_APERTURE,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_ISO,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_WHITE_BALANCE
    };

    private static class ExpectedValue {
        // Thumbnail information.
        public final boolean hasThumbnail;
        public final int thumbnailWidth;
        public final int thumbnailHeight;

        // GPS information.
        public final boolean hasLatLong;
        public final float latitude;
        public final float longitude;
        public final float altitude;

        // Values.
        public final String make;
        public final String model;
        public final float aperture;
        public final String datetime;
        public final float exposureTime;
        public final float flash;
        public final String focalLength;
        public final String gpsAltitude;
        public final String gpsAltitudeRef;
        public final String gpsDatestamp;
        public final String gpsLatitude;
        public final String gpsLatitudeRef;
        public final String gpsLongitude;
        public final String gpsLongitudeRef;
        public final String gpsProcessingMethod;
        public final String gpsTimestamp;
        public final String imageLength;
        public final String imageWidth;
        public final String iso;
        public final String whiteBalance;
        public final String orientation;

        private static String getString(TypedArray typedArray, int index) {
            String stringValue = typedArray.getString(index);
            if (stringValue == null || stringValue.equals("")) {
                return null;
            }
            return stringValue.trim();
        }

        public ExpectedValue(TypedArray typedArray) {
            // Reads thumbnail information.
            hasThumbnail = typedArray.getBoolean(0, false);
            thumbnailWidth = typedArray.getInt(1, 0);
            thumbnailHeight = typedArray.getInt(2, 0);

            // Reads GPS information.
            hasLatLong = typedArray.getBoolean(3, false);
            latitude = typedArray.getFloat(4, 0f);
            longitude = typedArray.getFloat(5, 0f);
            altitude = typedArray.getFloat(6, 0f);

            // Read values.
            make = getString(typedArray, 7);
            model = getString(typedArray, 8);
            aperture = typedArray.getFloat(9, 0f);
            datetime = getString(typedArray, 10);
            exposureTime = typedArray.getFloat(11, 0f);
            flash = typedArray.getFloat(12, 0f);
            focalLength = getString(typedArray, 13);
            gpsAltitude = getString(typedArray, 14);
            gpsAltitudeRef = getString(typedArray, 15);
            gpsDatestamp = getString(typedArray, 16);
            gpsLatitude = getString(typedArray, 17);
            gpsLatitudeRef = getString(typedArray, 18);
            gpsLongitude = getString(typedArray, 19);
            gpsLongitudeRef = getString(typedArray, 20);
            gpsProcessingMethod = getString(typedArray, 21);
            gpsTimestamp = getString(typedArray, 22);
            imageLength = getString(typedArray, 23);
            imageWidth = getString(typedArray, 24);
            iso = getString(typedArray, 25);
            orientation = getString(typedArray, 26);
            whiteBalance = getString(typedArray, 27);

            typedArray.recycle();
        }
    }

    @Override
    protected void setUp() throws Exception {
        for (int i = 0; i < IMAGE_RESOURCES.length; ++i) {
            String outputPath = new File(Environment.getExternalStorageDirectory(),
                    IMAGE_FILENAMES[i]).getAbsolutePath();
            try (InputStream inputStream = getContext().getResources().openRawResource(
                    IMAGE_RESOURCES[i])) {
                try (FileOutputStream outputStream = new FileOutputStream(outputPath)) {
                    Streams.copy(inputStream, outputStream);
                }
            }
        }
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        for (int i = 0; i < IMAGE_RESOURCES.length; ++i) {
            String imageFilePath = new File(Environment.getExternalStorageDirectory(),
                    IMAGE_FILENAMES[i]).getAbsolutePath();
            File imageFile = new File(imageFilePath);
            if (imageFile.exists()) {
                imageFile.delete();
            }
        }

        super.tearDown();
    }

    private void printExifTagsAndValues(String fileName, ExifInterface exifInterface) {
        // Prints thumbnail information.
        if (exifInterface.hasThumbnail()) {
            byte[] thumbnailBytes = exifInterface.getThumbnail();
            if (thumbnailBytes != null) {
                Log.v(TAG, fileName + " Thumbnail size = " + thumbnailBytes.length);
                Bitmap bitmap = BitmapFactory.decodeByteArray(
                        thumbnailBytes, 0, thumbnailBytes.length);
                if (bitmap == null) {
                    Log.e(TAG, fileName + " Corrupted thumbnail!");
                } else {
                    Log.v(TAG, fileName + " Thumbnail size: " + bitmap.getWidth() + ", "
                            + bitmap.getHeight());
                }
            } else {
                Log.e(TAG, fileName + " Corrupted image (no thumbnail)");
            }
        } else {
            if (exifInterface.getThumbnail() != null) {
                Log.e(TAG, fileName + " Corrupted image (a thumbnail exists)");
            } else {
                Log.v(TAG, fileName + " No thumbnail");
            }
        }

        // Prints GPS information.
        Log.v(TAG, fileName + " Altitude = " + exifInterface.getAltitude(.0));

        float[] latLong = new float[2];
        if (exifInterface.getLatLong(latLong)) {
            Log.v(TAG, fileName + " Latitude = " + latLong[0]);
            Log.v(TAG, fileName + " Longitude = " + latLong[1]);
        } else {
            Log.v(TAG, fileName + "No latlong data");
        }

        // Prints values.
        for (String tagKey : EXIF_TAGS) {
            String tagValue = exifInterface.getAttribute(tagKey);
            Log.v(TAG, fileName + "Key{" + tagKey + "} = '" + tagValue + "'");
        }
    }

    private void compareFloatTag(ExifInterface exifInterface, String tag, float expectedValue) {
        String stringValue = exifInterface.getAttribute(tag);
        float floatValue = 0f;

        if (stringValue != null) {
            floatValue = Float.parseFloat(stringValue);
        }

        assertEquals(expectedValue, floatValue, DIFFERENCE_TOLERANCE);
    }

    private void compareStringTag(ExifInterface exifInterface, String tag, String expectedValue) {
        String stringValue = exifInterface.getAttribute(tag);
        if (stringValue != null) {
            stringValue = stringValue.trim();
        }

        assertEquals(expectedValue, stringValue);
    }

    private void compareWithExpectedValue(ExifInterface exifInterface,
            ExpectedValue expectedValue) {
        // Checks a thumbnail image.
        assertEquals(expectedValue.hasThumbnail, exifInterface.hasThumbnail());
        if (expectedValue.hasThumbnail) {
            byte[] thumbnailBytes = exifInterface.getThumbnail();
            assertNotNull(thumbnailBytes);
            Bitmap thumbnailBitmap =
                    BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.length);
            assertNotNull(thumbnailBitmap);
            assertEquals(expectedValue.thumbnailWidth, thumbnailBitmap.getWidth());
            assertEquals(expectedValue.thumbnailHeight, thumbnailBitmap.getHeight());
        } else {
            assertNull(exifInterface.getThumbnail());
        }

        // Checks GPS information.
        float[] latLong = new float[2];
        assertEquals(expectedValue.hasLatLong, exifInterface.getLatLong(latLong));
        if (expectedValue.hasLatLong) {
            assertEquals(expectedValue.latitude, latLong[0], DIFFERENCE_TOLERANCE);
            assertEquals(expectedValue.longitude, latLong[1], DIFFERENCE_TOLERANCE);
        }
        assertEquals(expectedValue.altitude, exifInterface.getAltitude(.0), DIFFERENCE_TOLERANCE);

        // Checks values.
        compareStringTag(exifInterface, ExifInterface.TAG_MAKE, expectedValue.make);
        compareStringTag(exifInterface, ExifInterface.TAG_MODEL, expectedValue.model);
        compareFloatTag(exifInterface, ExifInterface.TAG_APERTURE, expectedValue.aperture);
        compareStringTag(exifInterface, ExifInterface.TAG_DATETIME, expectedValue.datetime);
        compareFloatTag(exifInterface, ExifInterface.TAG_EXPOSURE_TIME, expectedValue.exposureTime);
        compareFloatTag(exifInterface, ExifInterface.TAG_FLASH, expectedValue.flash);
        compareStringTag(exifInterface, ExifInterface.TAG_FOCAL_LENGTH, expectedValue.focalLength);
        compareStringTag(exifInterface, ExifInterface.TAG_GPS_ALTITUDE, expectedValue.gpsAltitude);
        compareStringTag(exifInterface, ExifInterface.TAG_GPS_ALTITUDE_REF,
                expectedValue.gpsAltitudeRef);
        compareStringTag(exifInterface, ExifInterface.TAG_GPS_DATESTAMP,
                expectedValue.gpsDatestamp);
        compareStringTag(exifInterface, ExifInterface.TAG_GPS_LATITUDE, expectedValue.gpsLatitude);
        compareStringTag(exifInterface, ExifInterface.TAG_GPS_LATITUDE_REF,
                expectedValue.gpsLatitudeRef);
        compareStringTag(exifInterface, ExifInterface.TAG_GPS_LONGITUDE,
                expectedValue.gpsLongitude);
        compareStringTag(exifInterface, ExifInterface.TAG_GPS_LONGITUDE_REF,
                expectedValue.gpsLongitudeRef);
        compareStringTag(exifInterface, ExifInterface.TAG_GPS_PROCESSING_METHOD,
                expectedValue.gpsProcessingMethod);
        compareStringTag(exifInterface, ExifInterface.TAG_GPS_TIMESTAMP,
                expectedValue.gpsTimestamp);
        compareStringTag(exifInterface, ExifInterface.TAG_IMAGE_LENGTH, expectedValue.imageLength);
        compareStringTag(exifInterface, ExifInterface.TAG_IMAGE_WIDTH, expectedValue.imageWidth);
        compareStringTag(exifInterface, ExifInterface.TAG_ISO, expectedValue.iso);
        compareStringTag(exifInterface, ExifInterface.TAG_ORIENTATION, expectedValue.orientation);
        compareStringTag(exifInterface, ExifInterface.TAG_WHITE_BALANCE,
                expectedValue.whiteBalance);
    }

    private void testExifInterfaceCommon(File imageFile, ExpectedValue expectedValue)
            throws IOException {
        // Created via path.
        ExifInterface exifInterface = new ExifInterface(imageFile.getAbsolutePath());
        if (VERBOSE) {
            printExifTagsAndValues(imageFile.getName(), exifInterface);
        }
        compareWithExpectedValue(exifInterface, expectedValue);

        // Created from an asset file.
        InputStream in = mContext.getAssets().open(imageFile.getName());
        exifInterface = new ExifInterface(in);
        if (VERBOSE) {
            printExifTagsAndValues(imageFile.getName(), exifInterface);
        }
        compareWithExpectedValue(exifInterface, expectedValue);

        // Created via InputStream.
        in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(imageFile.getAbsolutePath()));
            exifInterface = new ExifInterface(in);
            if (VERBOSE) {
                printExifTagsAndValues(imageFile.getName(), exifInterface);
            }
            compareWithExpectedValue(exifInterface, expectedValue);
        } finally {
            IoUtils.closeQuietly(in);
        }

        // Created via FileDescriptor.
        try {
            FileDescriptor fd = Os.open(imageFile.getAbsolutePath(), OsConstants.O_RDONLY, 0600);
            exifInterface = new ExifInterface(fd);
            if (VERBOSE) {
                printExifTagsAndValues(imageFile.getName(), exifInterface);
            }
            compareWithExpectedValue(exifInterface, expectedValue);
        } catch (ErrnoException e) {
            e.rethrowAsIOException();
        }
    }

    private void testExifInterfaceForJpeg(String fileName, int typedArrayResourceId)
            throws IOException {
        ExpectedValue expectedValue = new ExpectedValue(
                getContext().getResources().obtainTypedArray(typedArrayResourceId));
        File imageFile = new File(Environment.getExternalStorageDirectory(), fileName);

        // Test for reading from various inputs.
        testExifInterfaceCommon(imageFile, expectedValue);

        // Test for saving attributes.
        ExifInterface exifInterface;
        try {
            FileDescriptor fd = Os.open(imageFile.getAbsolutePath(), OsConstants.O_RDWR, 0600);
            exifInterface = new ExifInterface(fd);
            exifInterface.saveAttributes();
            fd = Os.open(imageFile.getAbsolutePath(), OsConstants.O_RDWR, 0600);
            exifInterface = new ExifInterface(fd);
            if (VERBOSE) {
                printExifTagsAndValues(fileName, exifInterface);
            }
            compareWithExpectedValue(exifInterface, expectedValue);
        } catch (ErrnoException e) {
            e.rethrowAsIOException();
        }

        // Test for modifying one attribute.
        exifInterface = new ExifInterface(imageFile.getAbsolutePath());
        exifInterface.setAttribute(ExifInterface.TAG_MAKE, "abc");
        exifInterface.saveAttributes();
        exifInterface = new ExifInterface(imageFile.getAbsolutePath());
        if (VERBOSE) {
            printExifTagsAndValues(fileName, exifInterface);
        }
        assertEquals("abc", exifInterface.getAttribute(ExifInterface.TAG_MAKE));
    }

    private void testExifInterfaceForRaw(String fileName, int typedArrayResourceId)
            throws IOException {
        ExpectedValue expectedValue = new ExpectedValue(
                getContext().getResources().obtainTypedArray(typedArrayResourceId));
        File imageFile = new File(Environment.getExternalStorageDirectory(), fileName);

        // Test for reading from various inputs.
        testExifInterfaceCommon(imageFile, expectedValue);

        // Since ExifInterface does not support for saving attributes for RAW files, do not test
        // about writing back in here.
    }

    public void testReadExifDataFromExifByteOrderIIJpeg() throws Throwable {
        testExifInterfaceForJpeg(EXIF_BYTE_ORDER_II_JPEG, R.array.exifbyteorderii_jpg);
    }

    public void testReadExifDataFromExifByteOrderMMJpeg() throws Throwable {
        testExifInterfaceForJpeg(EXIF_BYTE_ORDER_MM_JPEG, R.array.exifbyteordermm_jpg);
    }

    public void testReadExifDataFromLgG4Iso800Dng() throws Throwable {
        testExifInterfaceForRaw(LG_G4_ISO_800_DNG, R.array.lg_g4_iso_800_dng);
    }

    public void testCorruptedImage() {
        byte[] bytes = new byte[1024];
        try {
            new ExifInterface(new ByteArrayInputStream(bytes));
            fail("Should not reach here!");
        } catch (IOException e) {
            // Success
        }
    }
}
