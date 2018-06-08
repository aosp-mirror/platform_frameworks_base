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

package android.util;

import android.media.ExifInterface;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MetadataReader;

import libcore.io.IoUtils;

import junit.framework.TestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public class MetadataReaderTest extends TestCase {

    private InputStream mInputStream;
    private Bundle mData;

    @Before
    protected void setUp() throws Exception {
        mInputStream = getClass().getClassLoader().getResourceAsStream("res/drawable/image.jpg");
        mData = new Bundle();
    }

    @After
    protected void tearDown() throws Exception {
        IoUtils.closeQuietly(mInputStream);
    }

    @Test
    public void testGetMetadata() throws IOException {
        MetadataReader.getMetadata(mData, mInputStream, "image/jpg", null);
        Bundle exif = mData.getBundle(DocumentsContract.METADATA_EXIF);
        assertEquals("3036", String.valueOf(exif.getInt(ExifInterface.TAG_IMAGE_WIDTH)));
        assertEquals("4048", String.valueOf(exif.getInt(ExifInterface.TAG_IMAGE_LENGTH)));
        assertEquals("2017:07:26 21:06:25", exif.getString(ExifInterface.TAG_DATETIME));
        assertEquals("33/1,59/1,4530/100", exif.getString(ExifInterface.TAG_GPS_LATITUDE));
        assertEquals("N", exif.getString(ExifInterface.TAG_GPS_LATITUDE_REF));
        assertEquals("118/1,28/1,3124/100", exif.getString(ExifInterface.TAG_GPS_LONGITUDE));
        assertEquals("W", exif.getString(ExifInterface.TAG_GPS_LONGITUDE_REF));
        assertEquals("Google", exif.getString(ExifInterface.TAG_MAKE));
        assertEquals("Pixel", exif.getString(ExifInterface.TAG_MODEL));
        assertEquals(mData.getStringArray(DocumentsContract.METADATA_TYPES)[0],
                DocumentsContract.METADATA_EXIF);
    }

    @Test
    public void testGetMetadata_JpegOneTag() throws IOException {
        String[] tags = {ExifInterface.TAG_MAKE};
        MetadataReader.getMetadata(mData, mInputStream, "image/jpg", tags);
        assertEquals("Google",
                mData.getBundle(DocumentsContract.METADATA_EXIF).getString(tags[0]));
    }

    @Test
    public void testGetMetadata_JpegNoResults() throws IOException {
        String[] tags = {ExifInterface.TAG_SPECTRAL_SENSITIVITY};
        assertEquals(0, mData.size());
        MetadataReader.getMetadata(mData, mInputStream, "image/jpg", tags);
        assertEquals(1, mData.size());
        assertEquals(mData.getStringArray(DocumentsContract.METADATA_TYPES).length, 0);
    }

    @Test
    public void testGetMetadata_BadFile() {
        try {
            InputStream stream = new FileInputStream("badString");
            MetadataReader.getMetadata(mData, stream, "image/jpg", null);
        } catch (IOException e) {
            assertEquals(FileNotFoundException.class, e.getClass());
        }
    }

    @Test
    public void testGetMetadata_UnsupportedMimeType() throws IOException {
        MetadataReader.getMetadata(mData, mInputStream, "no/metadata", null);
        assertEquals(1, mData.size());
    }

    @Test
    public void testGetMetadata_NoTags() throws IOException {
        MetadataReader.getMetadata(mData, mInputStream, "image/jpg", new String[0]);
        assertEquals(1, mData.size());
    }

    @Test
    public void testGetMetadata_Png() throws IOException {
        InputStream pngStream = getClass().getClassLoader().getResourceAsStream("res/drawable/png.png");
        MetadataReader.getMetadata(mData, pngStream, "image/png", null);
        assertEquals(1, mData.size());

    }

}
