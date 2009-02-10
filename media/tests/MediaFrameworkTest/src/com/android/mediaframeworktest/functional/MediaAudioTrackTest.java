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

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;

import android.media.AudioTrack;
import android.content.Context;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

/**
 * Junit / Instrumentation test case for the media AudioTrack api
 
 */  
public class MediaAudioTrackTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {    
    private String TAG = "MediaAudioTrack";
   
    public MediaAudioTrackTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
    }
    
    @Override 
    protected void tearDown() throws Exception {     
        super.tearDown();              
    }
       
    //Test case 1: Set the invalid volume
    @MediumTest
    public void testGetMinVolume() throws Exception {
        //To Do: Create the test case for GetMinVolume  
        assertTrue("testGetMinVolume", true);  
    }

}

