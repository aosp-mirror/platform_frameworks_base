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

import android.media.MediaPlayer;
import android.os.Parcel;
import android.test.ActivityInstrumentationTestCase2;

import androidx.test.filters.MediumTest;
import androidx.test.filters.Suppress;

import com.android.mediaframeworktest.MediaFrameworkTest;

import java.util.Calendar;
import java.util.Random;

// Tests for the invoke method in the MediaPlayer.
public class MediaPlayerInvokeTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
   private static final String TAG = "MediaPlayerInvokeTest";
   private MediaPlayer mPlayer;
   private Random rnd;

   public MediaPlayerInvokeTest() {
       super("com.android.mediaframeworktest", MediaFrameworkTest.class);
       rnd = new Random(Calendar.getInstance().getTimeInMillis());
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
      mPlayer = new MediaPlayer();
    }

    @Override
    protected void tearDown() throws Exception {
        mPlayer.release();
        super.tearDown();
    }

    // Generate a random number, sends it to the ping test player.
    @Suppress
    @MediumTest
    public void testPing() throws Exception {
        mPlayer.setDataSource("test:invoke_mock_media_player.so?url=ping");

        Parcel request = mPlayer.newRequest();
        Parcel reply = Parcel.obtain();

        int val = rnd.nextInt();
        request.writeInt(val);
        mPlayer.invoke(request, reply);
        assertEquals(val, reply.readInt());
   }
}
