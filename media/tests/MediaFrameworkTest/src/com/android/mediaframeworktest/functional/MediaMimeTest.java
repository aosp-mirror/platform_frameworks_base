/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.mediaframeworktest.functional;

import java.io.File;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;
import com.android.mediaframeworktest.MediaFrameworkTest;

/*
 * System tests for the handling of mime type in the media framework.
 *
 * To run this test suite:
     make frameworks/base/media/tests/MediaFrameworkTest
     make mediaframeworktest

     adb install -r out/target/product/dream/data/app/mediaframeworktest.apk

     adb shell am instrument -e class \
     com.android.mediaframeworktest.functional.MediaMimeTest \
     -w com.android.mediaframeworktest/.MediaFrameworkTestRunner
 *
 */
public class MediaMimeTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {    
    private final String TAG = "MediaMimeTest";
    private Context mContext;
    private final String MP3_FILE = "/sdcard/media_api/music/SHORTMP3.mp3";

    public MediaMimeTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
      mContext = getActivity();
      // Checks you have all the test files on your SDCARD.
      assertTrue(new File(MP3_FILE).exists());
    }
    
    @Override 
    protected void tearDown() throws Exception {     
        super.tearDown();              
    }

    // ----------------------------------------------------------------------
    // AUDIO mime type resolution tests.

    @MediumTest
    // Checks the MediaPlaybackActivity handles audio/mp3.
    public void testCheckMediaPlaybackHandlesAudioMp3() throws Exception {
        assertMediaPlaybackActivityHandles("audio/mp3");
    }

    @Suppress
    // Checks the MediaPlaybackActivity handles audio/*.
    public void testCheckMediaPlaybackHandlesAudio() throws Exception {
        assertMediaPlaybackActivityHandles("audio/*");
    }

    // TODO: temporarily remove from medium suite because it hangs whole suite 
    // @MediumTest
    // Checks the MediaPlaybackActivity handles application/itunes. Some servers
    // set the Content-type hadb ieader to application/iTunes (with capital T, but
    // the download manager downcasts it) for their MP3 podcasts. This is non
    // standard but we try to support it anyway.
    // See bug 1401491
    public void testCheckMediaPlaybackHandlesApplicationItunes() throws Exception {
        assertMediaPlaybackActivityHandles("application/itunes");
    }

    @MediumTest
    // Checks the activity resolver handling of mime types is case sensitive.
    // See bug 1710534
    public void testCheckActivityResolverMimeHandlingIsCaseSensitive() throws Exception {
        assertNoActivityHandles("AUDIO/MP3");   // <--- look uppercase
    }

    @MediumTest
    // Checks the activity resolver does not trims leading whitespaces when
    // resolving mime types. Trailing whitespaces seems to be non
    // significant.
    // See bug 1710534
    public void testCheckWhiteSpacesInMimeTypeHandling() throws Exception {
        assertNoActivityHandles(" audio/mp3");
        assertNoActivityHandles(" audio/mp3 ");
        assertMediaPlaybackActivityHandles("audio/mp3 ");
    }

    // @return a ResolveInfo instance for the mime type or null if the type is
    // not handled by any activity.
    private ResolveInfo resolveMime(String mime) {
        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        Uri uri = Uri.fromParts("file", MP3_FILE, null);

        viewIntent.setDataAndType(uri, mime);
        return mContext.getPackageManager().resolveActivity(
                viewIntent, PackageManager.MATCH_DEFAULT_ONLY);
    }

    // Helper method to check the media playback activity handles the given mime type.
    // @param mime type to test for
    private void assertMediaPlaybackActivityHandles(String mime) throws Exception {
        ResolveInfo ri = resolveMime(mime);

        assertNotNull(ri);
    }

    // Helper method to check that NO activity handles the given mime type.
    // @param mime type to test for
    private void assertNoActivityHandles(String mime) throws Exception {
        assertNull(resolveMime(mime));
    }
}
