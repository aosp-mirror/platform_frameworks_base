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

package com.android.mediatranscodingtest;

/*
 * Test for ApplicationMediaCapabilities in the media framework.
 *
 * To run this test suite:
     make frameworks/base/media/tests/MediaTranscodingTest
     make mediatranscodingtest

     adb install -r testcases/mediatranscodingtest/arm64/mediatranscodingtest.apk

     adb shell am instrument -e class \
     com.android.mediatranscodingtest.ApplicationMediaCapabilitiesTest \
     -w com.android.mediatranscodingtest/.MediaTranscodingTestRunner
 *
 */

import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.media.ApplicationMediaCapabilities;
import android.media.MediaFeature;
import android.media.MediaFormat;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.util.Xml;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ApplicationMediaCapabilitiesTest extends
        ActivityInstrumentationTestCase2<MediaTranscodingTest> {
    private static final String TAG = "MediaCapabilityTest";

    private Context mContext;

    public ApplicationMediaCapabilitiesTest() {
        super("com.android.MediaCapabilityTest", MediaTranscodingTest.class);
    }

    public void setUp() throws Exception {
        Log.d(TAG, "setUp");
        super.setUp();

        mContext = getInstrumentation().getContext();
    }


    @Test
    public void testSetSupportHevc() throws Exception {
        ApplicationMediaCapabilities capability =
                new ApplicationMediaCapabilities.Builder().addSupportedVideoMimeType(
                        MediaFormat.MIMETYPE_VIDEO_HEVC).build();
        assertTrue(capability.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC));

        ApplicationMediaCapabilities capability2 =
                new ApplicationMediaCapabilities.Builder().build();
        assertFalse(capability2.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC));
    }

    @Test
    public void testSetSupportHdr() throws Exception {
        ApplicationMediaCapabilities capability =
                new ApplicationMediaCapabilities.Builder().addSupportedHdrType(
                        MediaFeature.HdrType.HDR10_PLUS).addSupportedVideoMimeType(
                        MediaFormat.MIMETYPE_VIDEO_HEVC).build();
        assertEquals(true, capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10_PLUS));
    }

    @Test
    public void testSetSupportSlowMotion() throws Exception {
        ApplicationMediaCapabilities capability =
                new ApplicationMediaCapabilities.Builder().setSlowMotionSupported(true).build();
        assertTrue(capability.isSlowMotionSupported());
    }

    @Test
    public void testBuilder() throws Exception {
        ApplicationMediaCapabilities capability =
                new ApplicationMediaCapabilities.Builder().addSupportedVideoMimeType(
                        MediaFormat.MIMETYPE_VIDEO_HEVC).addSupportedHdrType(
                        MediaFeature.HdrType.HDR10_PLUS).setSlowMotionSupported(true).build();
        assertTrue(capability.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC));
        assertTrue(capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10_PLUS));
        assertTrue(capability.isSlowMotionSupported());
    }

    @Test
    public void testSupportHdrWithoutSupportHevc() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            ApplicationMediaCapabilities capability =
                    new ApplicationMediaCapabilities.Builder().addSupportedHdrType(
                            MediaFeature.HdrType.HDR10_PLUS).build();
        });
    }

    //   Test read the application's xml from res/xml folder using the XmlResourceParser.
    //    <format android:name="HEVC" supported="true"/>
    //    <format android:name="HDR10" supported="false"/>
    //    <format android:name="SlowMotion" supported="false"/>
    @Test
    public void testReadMediaCapabilitiesXml() throws Exception {
        XmlResourceParser parser = mContext.getResources().getXml(R.xml.mediacapabilities);
        ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                parser);
        assertFalse(capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10));
        assertFalse(capability.isSlowMotionSupported());
        assertTrue(capability.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC));
    }

    //   Test read the application's xml from res/xml folder using the XmlResourceParser.
    //    <format android:name="HEVC" supported="true"/>
    //    <format android:name="HDR10" supported="true"/>
    //    <format android:name="HDR10Plus" supported="true"/>
    //    <format android:name="Dolby-Vision" supported="true"/>
    //    <format android:name="HLG" supported="true"/>
    //    <format android:name="SlowMotion" supported="true"/>
    @Test
    public void testReadMediaCapabilitiesXmlWithSupportAllHdr() throws Exception {
        InputStream xmlIs = mContext.getAssets().open("SupportAllHdr.xml");
        final XmlPullParser parser = Xml.newPullParser();
        parser.setInput(xmlIs, StandardCharsets.UTF_8.name());

        ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                parser);
        assertTrue(capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10));
        assertTrue(capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10_PLUS));
        assertTrue(capability.isHdrTypeSupported(MediaFeature.HdrType.DOLBY_VISION));
        assertTrue(capability.isHdrTypeSupported(MediaFeature.HdrType.HLG));
        assertTrue(capability.isSlowMotionSupported());
        assertTrue(capability.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC));
    }

    //   Test read the xml from res/assets folder using the InputStream.
    //    <format android:name="HEVC" supported="true"/>
    //    <format android:name="HDR10" supported="false"/>
    //    <format android:name="SlowMotion" supported="false"/>
    @Test
    public void testReadFromCorrectXmlWithInputStreamInAssets() throws Exception {
        InputStream xmlIs = mContext.getAssets().open("MediaCapabilities.xml");
        final XmlPullParser parser = Xml.newPullParser();
        parser.setInput(xmlIs, StandardCharsets.UTF_8.name());

        ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                parser);
        assertFalse(capability.isHdrTypeSupported(MediaFeature.HdrType.HDR10));
        assertFalse(capability.isSlowMotionSupported());
        assertTrue(capability.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC));
    }

    // Test parsing invalid xml with wrong tag expect UnsupportedOperationException
    // MediaCapability does not match MediaCapabilities at the end which will lead to
    // exception with "Ill-formatted xml file"
    // <MediaCapability xmlns:android="http://schemas.android.com/apk/res/android">
    //    <format android:name="HEVC" supported="true"/>
    //    <format android:name="HDR10" supported="true"/>
    //    <format android:name="SlowMotion" supported="true"/>
    // </MediaCapabilities>
    @Test
    public void testReadFromWrongMediaCapabilityXml() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            InputStream xmlIs = mContext.getAssets().open("WrongMediaCapabilityTag.xml");
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(xmlIs, StandardCharsets.UTF_8.name());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);
        });
    }

    // Test invalid xml with wrong tag expect UnsupportedOperationException
    // MediaCapability is wrong tag.
    // <MediaCapability xmlns:android="http://schemas.android.com/apk/res/android">
    //    <format android:name="HEVC" supported="true"/>
    //    <format android:name="HDR10" supported="true"/>
    //    <format android:name="SlowMotion" supported="true"/>
    // </MediaCapability>
    @Test
    public void testReadFromWrongMediaCapabilityXml2() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            InputStream xmlIs = mContext.getAssets().open("WrongMediaCapabilityTag2.xml");
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(xmlIs, StandardCharsets.UTF_8.name());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);
        });
    }

    // Test invalid attribute value of "support" with true->yes expect UnsupportedOperationException
    // <MediaCapabilities xmlns:android="http://schemas.android.com/apk/res/android">
    //    <format android:name="HEVC" supported="yes"/>
    //    <format android:name="HDR10" supported="false"/>
    //    <format android:name="SlowMotion" supported="false"/>
    // </MediaCapabilities>
    @Test
    public void testReadFromXmlWithWrongBoolean() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            InputStream xmlIs = mContext.getAssets().open("WrongBooleanValue.xml");
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(xmlIs, StandardCharsets.UTF_8.name());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);
        });
    }

    // Test parsing capabilities that support HDR10 but not support HEVC.
    // Expect UnsupportedOperationException
    // <MediaCapability xmlns:android="http://schemas.android.com/apk/res/android">
    //    <format android:name="HDR10" supported="true"/>
    //    <format android:name="SlowMotion" supported="false"/>
    // </MediaCapabilities>
    @Test
    public void testReadXmlSupportHdrWithoutSupportHevc() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            InputStream xmlIs = mContext.getAssets().open("SupportHdrWithoutHevc.xml");
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(xmlIs, StandardCharsets.UTF_8.name());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);
        });
    }

    // Test parsing capabilities that has conflicted supported value.
    // Expect UnsupportedOperationException
    // <media-capabilities xmlns:android="http://schemas.android.com/apk/res/android">
    //     <format android:name="HEVC" supported="true"/>
    //     <format android:name="HEVC" supported="false"/>
    // </media-capabilities>
    @Test
    public void testReadXmlConflictSupportedValue() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            InputStream xmlIs = mContext.getAssets().open("ConflictSupportedValue.xml");
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(xmlIs, StandardCharsets.UTF_8.name());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);
        });
    }

    // Test parsing capabilities that has empty format.
    // Expect UnsupportedOperationException
    // <media-capabilities xmlns:android="http://schemas.android.com/apk/res/android">
    //     <format/>
    // </media-capabilities>
    @Test
    public void testReadXmlWithEmptyFormat() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            InputStream xmlIs = mContext.getAssets().open("EmptyFormat.xml");
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(xmlIs, StandardCharsets.UTF_8.name());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);
        });
    }

    // Test parsing capabilities that has empty format.
    // Expect UnsupportedOperationException
    // <media-capabilities xmlns:android="http://schemas.android.com/apk/res/android">
    //     <format android:name="HEVC"/>
    // </media-capabilities>
    @Test
    public void testReadXmlFormatWithoutSupported() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            InputStream xmlIs = mContext.getAssets().open("FormatWithoutSupported.xml");
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(xmlIs, StandardCharsets.UTF_8.name());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);
        });
    }

    // Test parsing capabilities that has supported without the format name.
    // Expect UnsupportedOperationException
    // <media-capabilities xmlns:android="http://schemas.android.com/apk/res/android">
    //     <format supported="true"/>
    // </media-capabilities>
    @Test
    public void testReadXmlSupportedWithoutFormat() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            InputStream xmlIs = mContext.getAssets().open("SupportedWithoutFormat.xml");
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(xmlIs, StandardCharsets.UTF_8.name());
            ApplicationMediaCapabilities capability = ApplicationMediaCapabilities.createFromXml(
                    parser);
        });
    }
}
