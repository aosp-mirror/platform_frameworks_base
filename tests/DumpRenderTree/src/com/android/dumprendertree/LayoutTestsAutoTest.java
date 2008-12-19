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

package com.android.dumprendertree;

import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.util.Log;
import android.view.KeyEvent;

import android.test.ActivityInstrumentationTestCase;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.dumprendertree.HTMLHostActivity;

public class LayoutTestsAutoTest extends ActivityInstrumentationTestCase<Menu> {

    private final static String LOGTAG = "LayoutTests";
    private final static String LAYOUT_TESTS_ROOT = "/sdcard/android/layout_tests/";

    public LayoutTestsAutoTest() {
      super("com.android.dumprendertree", Menu.class);
    }

    // Invokes running of layout tests
    // and waits till it has finished running.
    public void executeLayoutTests(String folder) {
      Instrumentation inst = getInstrumentation();
      getActivity().processFile(folder, true);

      ActivityMonitor htmlHostActivityMonitor =
          inst.addMonitor("com.android.dumprendertree.HTMLHostActivity", null, false);
      HTMLHostActivity activity =
          (HTMLHostActivity) htmlHostActivityMonitor.waitForActivityWithTimeout(6000);
      
      while (!activity.hasFinishedRunning()) {
          // Poll every 5 seconds to determine if the layout
          // tests have finished running
          try {Thread.sleep(5000); } catch(Exception e){}
      }
      
      // Wait few more seconds so that results are
      // flushed to the /sdcard
      try {Thread.sleep(5000); } catch(Exception e){}

      return ;
    }
    
    // Running all the layout tests at once sometimes
    // causes the dumprendertree to run out of memory.
    // So, additional tests are added to run the tests
    // in chunks.
    @LargeTest
    public void testAllLayoutTests() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast");
    }

    @LargeTest
    public void testLayoutSubset1() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/backgrounds");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/borders");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/box-shadow");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/box-sizing");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/canvas");   
    }

    @LargeTest
    public void testLayoutSubset2() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/clip");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/compact");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/cookies");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/css");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/css-generated-content");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/doctypes");
    }

    @LargeTest  
    public void testLayoutSubset3() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/dom");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/dynamic");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/encoding");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/events");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/flexbox");
    }

    @LargeTest  
    public void testLayoutSubset4() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/forms");     
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/frames");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/gradients");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/history");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/html");
    }

    @LargeTest  
    public void testLayoutSubset5() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/images");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/inline");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/inline-block");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/innerHTML");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/invalid");
    }

    @LargeTest
    public void testLayoutSubset6() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/js");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/layers");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/leaks");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/lists");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/loader");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/media");
    }

    @LargeTest
    public void testLayoutSubset7() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/multicol");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/overflow");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/parser");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/profiler");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/reflections");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/regex");
    }

    @LargeTest
    public void testLayoutSubset8() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/repaint");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/replaced");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/runin");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/selectors");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/table");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/text");
    }

    @LargeTest
    public void testLayoutSubset9() {
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/tokenizer");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/transforms");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/xpath");
      executeLayoutTests(LAYOUT_TESTS_ROOT + "fast/xsl");
    }
}
