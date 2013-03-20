/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.mediaframeworktest;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;

import android.os.Debug;
import android.os.Environment;
import android.util.Log;

/**
 *
 * Utilities for media framework test.
 *
 */
public class MediaTestUtil {

    private static String TAG = "MediaTestUtil";
    private static final String STORAGE_PATH = Environment.getExternalStorageDirectory().toString();
    private int mStartMemory = 0;
    private int mStartPid = 0;
    private Writer mOutput = null;
    private String mTestName = null;
    private String mProcessName = null;

    public MediaTestUtil(String memoryOutFileName, String testName, String processName)
            throws Exception {
        File memoryOut = new File(memoryOutFileName);
        mOutput = new BufferedWriter(new FileWriter(memoryOut, true));
        mProcessName = processName;
        mTestName = testName;
        mStartPid = getPid();
        mStartMemory = getVsize();
    }

    // Catpure the heapdump for memory leaksage analysis
    public static void getNativeHeapDump(String name) throws Exception {
        System.gc();
        System.runFinalization();
        Thread.sleep(1000);
        FileOutputStream o = new FileOutputStream(STORAGE_PATH + '/' + name + ".dump");
        Debug.dumpNativeHeap(o.getFD());
        o.close();
    }

    private void validateProcessStatus() throws Exception {
        int currentPid = getPid();
        //Process crash
        if (mStartPid != currentPid) {
            mOutput.write(mProcessName + " died. Test failed\n");
        }
    }

    private int getPid() {
        String memoryUsage = null;
        int pidvalue = 0;
        memoryUsage = captureMemInfo();
        String[] poList2 = memoryUsage.split("\t|\\s+");
        String pid = poList2[1];
        pidvalue = Integer.parseInt(pid);
        Log.v(TAG, "PID = " + pidvalue);
        return pidvalue;
    }

    private String captureMemInfo() {
        String cm = "ps ";
        cm += mProcessName;
        Log.v(TAG, cm);
        String memoryUsage = null;

        int ch;
        try {
            Process p = Runtime.getRuntime().exec(cm);
            InputStream in = p.getInputStream();
            StringBuffer sb = new StringBuffer(512);
            while ((ch = in.read()) != -1) {
                sb.append((char) ch);
            }
            memoryUsage = sb.toString();
        } catch (IOException e) {
            Log.v(TAG, e.toString());
        }
        String[] poList = memoryUsage.split("\r|\n|\r\n");
        // Skip the first two lines since there
        // is a new media.log introudced recently.
        String memusage = poList[2].concat("\n");
        return memusage;
    }

    private int getVsize() {
        String memoryUsage = captureMemInfo();
        String[] poList2 = memoryUsage.split("\t|\\s+");
        String vsize = poList2[3];
        int vsizevalue = Integer.parseInt(vsize);
        Log.v(TAG, "VSIZE = " + vsizevalue);
        return vsizevalue;
    }

    // Write the startup media memory mOutput to the file
    public void getStartMemoryLog() throws Exception {
        String memusage = null;
        mStartMemory = getVsize();
        mOutput.write(mTestName + '\n');
        mOutput.write("Start memory : " + mStartMemory + "\n");
        memusage = captureMemInfo();
        mOutput.write(memusage);
    }

    // Write the ps mediaserver mOutput to the file
    public void getMemoryLog() throws Exception {
        String memusage = null;
        memusage = captureMemInfo();
        mOutput.write(memusage);
        mOutput.flush();
    }

    public void getMemorySummary() throws Exception {
        int endMemory = 0;
        int memDiff = 0;

        endMemory = getVsize();
        memDiff = endMemory - mStartMemory;

        mOutput.write("End Memory :" + endMemory + "\n");
        if (memDiff < 0) {
            memDiff = 0;
        }
        mOutput.write(mTestName + " total diff = " + memDiff);
        mOutput.write("\n\n");
        validateProcessStatus();
        mOutput.flush();
        mOutput.close();
    }
}
