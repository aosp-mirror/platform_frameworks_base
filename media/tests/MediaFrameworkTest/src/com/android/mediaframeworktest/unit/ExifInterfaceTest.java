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

    private static final double DIFFERENCE_TOLERANCE = .001;

    // List of files.
    private static final String EXIF_BYTE_ORDER_II_JPEG = "image_exif_byte_order_ii.jpg";
    private static final String EXIF_BYTE_ORDER_MM_JPEG = "image_exif_byte_order_mm.jpg";
    private static final String LG_G4_ISO_800_DNG = "lg_g4_iso_800.dng";
    private static final String VOLANTIS_JPEG = "volantis.jpg";
    private static final int[] IMAGE_RESOURCES = new int[] {
            R.raw.image_exif_byte_order_ii,  R.raw.image_exif_byte_order_mm, R.raw.lg_g4_iso_800,
            R.raw.volantis };
    private static final String[] IMAGE_FILENAMES = new String[] {
            EXIF_BYTE_ORDER_II_JPEG, EXIF_BYTE_ORDER_MM_JPEG, LG_G4_ISO_800_DNG, VOLANTIS_JPEG };

    private static final String[] EXIF_TAGS = {
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
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
            ExifInterface.TAG_ISO_SPEED_RATINGS,
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
        public final float fNumber;
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
        public final int imageLength;
        public final int imageWidth;
        public final String iso;
        public final int orientation;
        public final int whiteBalance;

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

            // Reads values.
            make = getString(typedArray, 7);
            model = getString(typedArray, 8);
            fNumber = typedArray.getFloat(9, 0f);
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
            imageLength = typedArray.getInt(23, 0);
            imageWidth = typedArray.getInt(24, 0);
            iso = getString(typedArray, 25);
            orientation = typedArray.getInt(26, 0);
            whiteBalance = typedArray.getInt(27, 0);

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
                Log.e(TAG, fileName + " Unexpected result: No thumbnails were found. "
                        + "A thumbnail is expected.");
            }
        } else {
            if (exifInterface.getThumbnail() != null) {
                Log.e(TAG, fileName + " Unexpected result: A thumbnail was found. "
                        + "No thumbnail is expected.");
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
            Log.v(TAG, fileName + " No latlong data");
        }

        // Prints values.
        for (String tagKey : EXIF_TAGS) {
            String tagValue = exifInterface.getAttribute(tagKey);
            Log.v(TAG, fileName + " Key{" + tagKey + "} = '" + tagValue + "'");
        }
    }

    private void assertIntTag(ExifInterface exifInterface, String tag, int expectedValue) {
        int intValue = exifInterface.getAttributeInt(tag, 0);
        assertEquals(expectedValue, intValue);
    }

    private void assertDoubleTag(ExifInterface exifInterface, String tag, float expectedValue) {
        double doubleValue = exifInterface.getAttributeDouble(tag, 0.0);
        assertEquals(expectedValue, doubleValue, DIFFERENCE_TOLERANCE);
    }

    private void assertStringTag(ExifInterface exifInterface, String tag, String expectedValue) {
        String stringValue = exifInterface.getAttribute(tag);
        if (stringValue != null) {
            stringValue = stringValue.trim();
        }

        assertEquals(expectedValue, stringValue);
    }

    private void compareWithExpectedValue(ExifInterface exifInterface,
            ExpectedValue expectedValue, String verboseTag) {
        if (VERBOSE) {
            printExifTagsAndValues(verboseTag, exifInterface);
        }
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
        assertStringTag(exifInterface, ExifInterface.TAG_MAKE, expectedValue.make);
        assertStringTag(exifInterface, ExifInterface.TAG_MODEL, expectedValue.model);
        assertDoubleTag(exifInterface, ExifInterface.TAG_F_NUMBER, expectedValue.fNumber);
        assertStringTag(exifInterface, ExifInterface.TAG_DATETIME, expectedValue.datetime);
        assertDoubleTag(exifInterface, ExifInterface.TAG_EXPOSURE_TIME, expectedValue.exposureTime);
        assertDoubleTag(exifInterface, ExifInterface.TAG_FLASH, expectedValue.flash);
        assertStringTag(exifInterface, ExifInterface.TAG_FOCAL_LENGTH, expectedValue.focalLength);
        assertStringTag(exifInterface, ExifInterface.TAG_GPS_ALTITUDE, expectedValue.gpsAltitude);
        assertStringTag(exifInterface, ExifInterface.TAG_GPS_ALTITUDE_REF,
                expectedValue.gpsAltitudeRef);
        assertStringTag(exifInterface, ExifInterface.TAG_GPS_DATESTAMP, expectedValue.gpsDatestamp);
        assertStringTag(exifInterface, ExifInterface.TAG_GPS_LATITUDE, expectedValue.gpsLatitude);
        assertStringTag(exifInterface, ExifInterface.TAG_GPS_LATITUDE_REF,
                expectedValue.gpsLatitudeRef);
        assertStringTag(exifInterface, ExifInterface.TAG_GPS_LONGITUDE, expectedValue.gpsLongitude);
        assertStringTag(exifInterface, ExifInterface.TAG_GPS_LONGITUDE_REF,
                expectedValue.gpsLongitudeRef);
        assertStringTag(exifInterface, ExifInterface.TAG_GPS_PROCESSING_METHOD,
                expectedValue.gpsProcessingMethod);
        assertStringTag(exifInterface, ExifInterface.TAG_GPS_TIMESTAMP, expectedValue.gpsTimestamp);
        assertIntTag(exifInterface, ExifInterface.TAG_IMAGE_LENGTH, expectedValue.imageLength);
        assertIntTag(exifInterface, ExifInterface.TAG_IMAGE_WIDTH, expectedValue.imageWidth);
        assertStringTag(exifInterface, ExifInterface.TAG_ISO_SPEED_RATINGS, expectedValue.iso);
        assertIntTag(exifInterface, ExifInterface.TAG_ORIENTATION, expectedValue.orientation);
        assertIntTag(exifInterface, ExifInterface.TAG_WHITE_BALANCE, expectedValue.whiteBalance);
    }

    private void testExifInterfaceCommon(File imageFile, ExpectedValue expectedValue)
            throws IOException {
        String verboseTag = imageFile.getName();

        // Creates via path.
        ExifInterface exifInterface = new ExifInterface(imageFile.getAbsolutePath());
        compareWithExpectedValue(exifInterface, expectedValue, verboseTag);

        // Creates from an asset file.
        InputStream in = null;
        try {
            in = mContext.getAssets().open(imageFile.getName());
            exifInterface = new ExifInterface(in);
            compareWithExpectedValue(exifInterface, expectedValue, verboseTag);
        } finally {
            IoUtils.closeQuietly(in);
        }

        // Creates via InputStream.
        in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(imageFile.getAbsolutePath()));
            exifInterface = new ExifInterface(in);
            compareWithExpectedValue(exifInterface, expectedValue, verboseTag);
        } finally {
            IoUtils.closeQuietly(in);
        }

        // Creates via FileDescriptor.
        FileDescriptor fd = null;
        try {
            fd = Os.open(imageFile.getAbsolutePath(), OsConstants.O_RDONLY, 0600);
            exifInterface = new ExifInterface(fd);
            compareWithExpectedValue(exifInterface, expectedValue, verboseTag);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        } finally {
            IoUtils.closeQuietly(fd);
        }
    }

    private void testSaveAttributes_withFileName(File imageFile, ExpectedValue expectedValue)
            throws IOException {
        String verboseTag = imageFile.getName();

        ExifInterface exifInterface = new ExifInterface(imageFile.getAbsolutePath());
        exifInterface.saveAttributes();
        exifInterface = new ExifInterface(imageFile.getAbsolutePath());
        compareWithExpectedValue(exifInterface, expectedValue, verboseTag);

        // Test for modifying one attribute.
        String backupValue = exifInterface.getAttribute(ExifInterface.TAG_MAKE);
        exifInterface.setAttribute(ExifInterface.TAG_MAKE, "abc");
        exifInterface.saveAttributes();
        exifInterface = new ExifInterface(imageFile.getAbsolutePath());
        assertEquals("abc", exifInterface.getAttribute(ExifInterface.TAG_MAKE));
        // Restore the backup value.
        exifInterface.setAttribute(ExifInterface.TAG_MAKE, backupValue);
        exifInterface.saveAttributes();
        exifInterface = new ExifInterface(imageFile.getAbsolutePath());
        compareWithExpectedValue(exifInterface, expectedValue, verboseTag);
    }

    private void testSaveAttributes_withFileDescriptor(File imageFile, ExpectedValue expectedValue)
            throws IOException {
        String verboseTag = imageFile.getName();

        FileDescriptor fd = null;
        try {
            fd = Os.open(imageFile.getAbsolutePath(), OsConstants.O_RDWR, 0600);
            ExifInterface exifInterface = new ExifInterface(fd);
            exifInterface.saveAttributes();
            Os.lseek(fd, 0, OsConstants.SEEK_SET);
            exifInterface = new ExifInterface(fd);
            compareWithExpectedValue(exifInterface, expectedValue, verboseTag);

            // Test for modifying one attribute.
            String backupValue = exifInterface.getAttribute(ExifInterface.TAG_MAKE);
            exifInterface.setAttribute(ExifInterface.TAG_MAKE, "abc");
            exifInterface.saveAttributes();
            Os.lseek(fd, 0, OsConstants.SEEK_SET);
            exifInterface = new ExifInterface(fd);
            assertEquals("abc", exifInterface.getAttribute(ExifInterface.TAG_MAKE));
            // Restore the backup value.
            exifInterface.setAttribute(ExifInterface.TAG_MAKE, backupValue);
            exifInterface.saveAttributes();
            Os.lseek(fd, 0, OsConstants.SEEK_SET);
            exifInterface = new ExifInterface(fd);
            compareWithExpectedValue(exifInterface, expectedValue, verboseTag);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        } finally {
            IoUtils.closeQuietly(fd);
        }
    }

    private void testSaveAttributes_withInputStream(File imageFile, ExpectedValue expectedValue)
            throws IOException {
        InputStream in = null;
        try {
            in = getContext().getAssets().open(imageFile.getName());
            ExifInterface exifInterface = new ExifInterface(in);
            exifInterface.saveAttributes();
        } catch (IOException e) {
            // Expected. saveAttributes is not supported with an ExifInterface object which was
            // created with InputStream.
            return;
        } finally {
            IoUtils.closeQuietly(in);
        }
        fail("Should not reach here!");
    }

    private void testExifInterfaceForJpeg(String fileName, int typedArrayResourceId)
            throws IOException {
        ExpectedValue expectedValue = new ExpectedValue(
                getContext().getResources().obtainTypedArray(typedArrayResourceId));
        File imageFile = new File(Environment.getExternalStorageDirectory(), fileName);

        // Test for reading from various inputs.
        testExifInterfaceCommon(imageFile, expectedValue);

        // Test for saving attributes.
        testSaveAttributes_withFileName(imageFile, expectedValue);
        testSaveAttributes_withFileDescriptor(imageFile, expectedValue);
        testSaveAttributes_withInputStream(imageFile, expectedValue);
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

    public void testDoNotFailOnCorruptedImage() throws Throwable {
        // To keep the compatibility with old versions of ExifInterface, even on a corrupted image,
        // it shouldn't raise any exceptions except an IOException when unable to open a file.
        byte[] bytes = new byte[1024];
        try {
            new ExifInterface(new ByteArrayInputStream(bytes));
            // Always success
        } catch (IOException e) {
            fail("Should not reach here!");
        }
    }

    public void testReadExifDataFromVolantisJpg() throws Throwable {
        // Test if it is possible to parse the volantis generated JPEG smoothly.
        testExifInterfaceForJpeg(VOLANTIS_JPEG, R.array.volantis_jpg);
    }
}
