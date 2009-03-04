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

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;

import android.util.Log;
import android.view.KeyEvent;

import android.os.Bundle;
import android.os.Message;
import android.test.ActivityInstrumentationTestCase;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.dumprendertree.HTMLHostActivity;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class LayoutTestsAutoTest extends ActivityInstrumentationTestCase<Menu> {

    private final static String LOGTAG = "LayoutTests";
    private final static int DEFAULT_TIMEOUT_IN_MILLIS = 6000;
    private static String layoutTestDir = null;
    private static int mTimeoutInMillis = 0;
    
    public LayoutTestsAutoTest() {
      super("com.android.dumprendertree", Menu.class);
    }

    // This function writes the result of the layout test to
    // Am status so that it can be picked up from a script.
    public void passOrFailCallback(String file, boolean result) {
      Instrumentation inst = getInstrumentation();
      Bundle bundle = new Bundle();
      bundle.putBoolean(file, result);
      inst.sendStatus(0, bundle);
    }

    public static void setTimeoutInMillis(int millis) {
        mTimeoutInMillis = (millis > 0) ? millis : DEFAULT_TIMEOUT_IN_MILLIS;
    }

    public static void setLayoutTestDir(String name) {
        if (name == null)
            throw new AssertionError("Layout test directory cannot be null.");
      layoutTestDir = HTMLHostActivity.LAYOUT_TESTS_ROOT + name;
      Log.v("LayoutTestsAutoTest", " Only running the layout tests : " + layoutTestDir);
    }

    // Invokes running of layout tests
    // and waits till it has finished running.
    public void executeLayoutTests(boolean resume) {
      Instrumentation inst = getInstrumentation();
      
      {
          Activity activity = getActivity();
          Intent intent = new Intent();
          intent.setClass(activity, HTMLHostActivity.class);
          intent.putExtra(HTMLHostActivity.RESUME_FROM_CRASH, resume);
          intent.putExtra(HTMLHostActivity.SINGLE_TEST_MODE, false);
          intent.putExtra(HTMLHostActivity.TEST_PATH_PREFIX, layoutTestDir);
          intent.putExtra(HTMLHostActivity.TIMEOUT_IN_MILLIS, mTimeoutInMillis);
          activity.startActivity(intent);
      }
      
      ActivityMonitor htmlHostActivityMonitor =
          inst.addMonitor("com.android.dumprendertree.HTMLHostActivity", null, false);

      HTMLHostActivity activity =
          (HTMLHostActivity) htmlHostActivityMonitor.waitForActivity();

      while (!activity.hasFinishedRunning()) {
          // Poll every 5 seconds to determine if the layout
          // tests have finished running
          try {Thread.sleep(5000); } catch(Exception e){}
      }

      // Wait few more seconds so that results are
      // flushed to the /sdcard
      try {Thread.sleep(5000); } catch(Exception e){}

      // Clean up the HTMLHostActivity activity
      activity.finish();
    }
    
    public void generateTestList() {
        try {
            File tests_list = new File(HTMLHostActivity.LAYOUT_TESTS_LIST_FILE);
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tests_list, false));
            findTestsRecursively(bos, layoutTestDir);
            bos.flush();
            bos.close();
       } catch (Exception e) {
           Log.e(LOGTAG, "Error when creating test list: " + e.getMessage());
       }
    }

    private void findTestsRecursively(BufferedOutputStream bos, String dir) throws IOException {
         Log.v(LOGTAG, "Searching tests under " + dir);
         
         File d = new File(dir);
         if (!d.isDirectory()) {
             throw new AssertionError("A directory expected, but got " + dir);
         }
         
         String[] files = d.list();
         for (int i = 0; i < files.length; i++) {
             String s = dir + "/" + files[i];
             if (FileFilter.ignoreTest(s)) {
                 Log.v(LOGTAG, "  Ignoring: " + s);
                 continue;
             }
             if (s.toLowerCase().endsWith(".html") 
                 || s.toLowerCase().endsWith(".xml")) {
                 bos.write(s.getBytes());
                 bos.write('\n');
                 continue;
             }
             
             File f = new File(s);
             if (f.isDirectory()) {
                 findTestsRecursively(bos, s);
                 continue;
             }
             
             Log.v(LOGTAG, "Skipping " + s);
        }
    }
    
    // Running all the layout tests at once sometimes
    // causes the dumprendertree to run out of memory.
    // So, additional tests are added to run the tests
    // in chunks.
    public void startLayoutTests() {
        try {
            File tests_list = new File(HTMLHostActivity.LAYOUT_TESTS_LIST_FILE);
            if (!tests_list.exists())
              generateTestList();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        executeLayoutTests(false);
    }

    public void resumeLayoutTests() {
        executeLayoutTests(true);
    }
}
