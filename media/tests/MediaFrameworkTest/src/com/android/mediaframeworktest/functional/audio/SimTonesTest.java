/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mediaframeworktest.functional.audio;

// import android.content.Resources;
import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.functional.TonesAutoTest;

import android.content.Context;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;

/**
 * Junit / Instrumentation test case for the SIM tone generator
 * 
 */  
public class SimTonesTest extends ActivityInstrumentationTestCase<MediaFrameworkTest> {    
    private String TAG = "SimTonesTest";
    
    Context mContext;

    public SimTonesTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
      }

       protected void setUp() throws Exception {
         super.setUp();
     }
   
   @LargeTest    
   public void testDtmfTones() throws Exception {
       boolean result = TonesAutoTest.tonesDtmfTest();
     assertTrue("DTMF Tones", result);  
   }

   @LargeTest
   public void testSupervisoryTones() throws Exception {
       boolean result = TonesAutoTest.tonesSupervisoryTest();
     assertTrue("Supervisory Tones", result);  
   }

   @LargeTest
   public void testProprietaryTones() throws Exception {
       boolean result = TonesAutoTest.tonesProprietaryTest();
     assertTrue("Proprietary Tones", result);  
   }

   @LargeTest
   public void testSimultaneousTones() throws Exception {
       boolean result = TonesAutoTest.tonesSimultaneousTest();
     assertTrue("Simultaneous Tones", result);  
   }

   @LargeTest
   public void testStressTones() throws Exception {
       boolean result = TonesAutoTest.tonesStressTest();
     assertTrue("Stress Tones", result);  
   }
}
