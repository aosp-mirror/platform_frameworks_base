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

package com.android.mediaframeworktest.unit;

import static android.media.MediaFile.getFormatCode;
import static android.media.MediaFile.getMimeType;
import static android.media.MediaFile.isAudioMimeType;
import static android.media.MediaFile.isImageMimeType;
import static android.media.MediaFile.isPlayListMimeType;
import static android.media.MediaFile.isVideoMimeType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.mtp.MtpConstants;

import androidx.test.runner.AndroidJUnit4;

import libcore.net.MimeUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MediaFileTest {
    @Test
    public void testCommon() throws Exception {
        assertConsistent("FOO.TXT", "text/plain", MtpConstants.FORMAT_TEXT);
        assertConsistent("FOO.XML", "text/xml", MtpConstants.FORMAT_XML_DOCUMENT);
        assertConsistent("FOO.HTML", "text/html", MtpConstants.FORMAT_HTML);
    }

    @Test
    public void testAudio() throws Exception {
        assertTrue(isAudioMimeType("audio/flac"));
        assertTrue(isAudioMimeType("application/x-flac"));
        assertFalse(isAudioMimeType("video/mpeg"));

        assertConsistent("FOO.MP3", "audio/mpeg", MtpConstants.FORMAT_MP3);
        assertConsistent("FOO.AAC", "audio/aac", MtpConstants.FORMAT_AAC);
        assertConsistent("FOO.OGG", "audio/ogg", MtpConstants.FORMAT_OGG);
        assertConsistent("FOO.FLAC", "audio/flac", MtpConstants.FORMAT_FLAC);
    }

    @Test
    public void testVideo() throws Exception {
        assertTrue(isVideoMimeType("video/x-msvideo"));
        assertFalse(isVideoMimeType("audio/mpeg"));

        assertConsistent("FOO.AVI", "video/avi", MtpConstants.FORMAT_AVI);
        assertConsistent("FOO.MP4", "video/mp4", MtpConstants.FORMAT_MP4_CONTAINER);
        assertConsistent("FOO.3GP", "video/3gpp", MtpConstants.FORMAT_3GP_CONTAINER);
    }

    @Test
    public void testImage() throws Exception {
        assertTrue(isImageMimeType("image/jpeg"));
        assertTrue(isImageMimeType("image/heif"));
        assertTrue(isImageMimeType("image/webp"));
        assertFalse(isImageMimeType("video/webm"));

        assertConsistent("FOO.JPG", "image/jpeg", MtpConstants.FORMAT_EXIF_JPEG);
        assertConsistent("FOO.PNG", "image/png", MtpConstants.FORMAT_PNG);
        assertConsistent("FOO.HEIF", "image/heif", MtpConstants.FORMAT_HEIF);
        assertConsistent("FOO.DNG", "image/x-adobe-dng", MtpConstants.FORMAT_DNG);
        assertConsistent("FOO.TIFF", "image/tiff", MtpConstants.FORMAT_TIFF);
    }

    @Test
    public void testPlayList() throws Exception {
        assertTrue(isPlayListMimeType(MimeUtils.guessMimeTypeFromExtension("pls")));
        assertTrue(isPlayListMimeType(MimeUtils.guessMimeTypeFromExtension("wpl")));
        assertTrue(isPlayListMimeType(MimeUtils.guessMimeTypeFromExtension("m3u")));
        assertTrue(isPlayListMimeType(MimeUtils.guessMimeTypeFromExtension("m3u8")));
        assertTrue(isPlayListMimeType(MimeUtils.guessMimeTypeFromExtension("asf")));
    }

    @Test
    public void testImageRaw() throws Exception {
        // We trust MIME types before filenames
        assertHexEquals(MtpConstants.FORMAT_TIFF, getFormatCode("FOO.CR2", "image/x-canon-cr2"));
        // We trust filenames before format codes
        assertEquals("image/x-canon-cr2", getMimeType("FOO.CR2", MtpConstants.FORMAT_TIFF));
    }

    @Test
    public void testConfusing() throws Exception {
        // We trust MIME types before filenames
        assertHexEquals(MtpConstants.FORMAT_MP3, getFormatCode("foo.avi", "audio/mpeg"));
        // We trust filenames before format codes
        assertEquals("video/avi", getMimeType("foo.avi", MtpConstants.FORMAT_MP3));
    }

    @Test
    public void testUnknown() throws Exception {
        assertHexEquals(MtpConstants.FORMAT_UNDEFINED,
                getFormatCode("foo.example", "application/x-example"));
        assertHexEquals(MtpConstants.FORMAT_UNDEFINED_AUDIO,
                getFormatCode("foo.example", "audio/x-example"));
        assertHexEquals(MtpConstants.FORMAT_UNDEFINED_VIDEO,
                getFormatCode("foo.example", "video/x-example"));
        assertHexEquals(MtpConstants.FORMAT_DEFINED,
                getFormatCode("foo.example", "image/x-example"));
    }

    private static void assertConsistent(String path, String mimeType, int formatCode) {
        assertHexEquals(formatCode, getFormatCode(path, null));
        assertHexEquals(formatCode, getFormatCode(null, mimeType));
        assertHexEquals(formatCode, getFormatCode(path, mimeType));

        assertEquals(mimeType, getMimeType(path, MtpConstants.FORMAT_UNDEFINED));
        assertEquals(mimeType, getMimeType(null, formatCode));
        assertEquals(mimeType, getMimeType(path, formatCode));
    }

    private static void assertHexEquals(int expected, int actual) {
        assertEquals(Integer.toHexString(expected), Integer.toHexString(actual));
    }
}
