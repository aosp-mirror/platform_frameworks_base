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

import java.io.FileOutputStream;
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
    private MediaTestUtil(){
    }

    private static String TAG = "MediaTestUtil";
    private static final String STORAGE_PATH =
        Environment.getExternalStorageDirectory().toString();
    private static int mMediaStartMemory = 0;
    private static int mDrmStartMemory = 0;

    //Catpure the heapdump for memory leaksage analysis
    public static void getNativeHeapDump (String name) throws Exception {
        System.gc();
        System.runFinalization();
        Thread.sleep(1000);
        FileOutputStream o = new FileOutputStream(STORAGE_PATH + '/' +name + ".dump");
        Debug.dumpNativeHeap(o.getFD());
        o.close();
    }

    public static String captureMemInfo(String type) {
        String cm = "ps ";
        cm += type;
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
        String memusage = poList[1].concat("\n");
        return memusage;
    }

    public static int getMediaServerVsize() {
        String memoryUsage = captureMemInfo("mediaserver");
        String[] poList2 = memoryUsage.split("\t|\\s+");
        String vsize = poList2[3];
        int vsizevalue = Integer.parseInt(vsize);
        Log.v(TAG, "VSIZE = " + vsizevalue);
        return vsizevalue;
    }

    public static int getDrmServerVsize() {
        String memoryUsage = captureMemInfo("drmserver");
        String[] poList2 = memoryUsage.split("\t|\\s+");
        String vsize = poList2[3];
        int vsizevalue = Integer.parseInt(vsize);
        Log.v(TAG, "VSIZE = " + vsizevalue);
        return vsizevalue;
    }

    // Write the ps mediaserver output to the file
    public static void getMediaServerMemoryLog(Writer output, int writeCount, int totalCount)
            throws Exception {
        String memusage = null;

        if (writeCount == 0) {
            mMediaStartMemory = getMediaServerVsize();
            output.write("Start memory : " + mMediaStartMemory + "\n");
        }
        memusage = captureMemInfo("mediaserver");
        output.write(memusage);
    }

    // Write the ps drmserver output to the file
    public static void getDrmServerMemoryLog(Writer output, int writeCount, int totalCount)
            throws Exception {
        String memusage = null;

        if (writeCount == 0) {
            mDrmStartMemory = getDrmServerVsize();
            output.write("Start memory : " + mDrmStartMemory + "\n");
        }
        memusage = captureMemInfo("drmserver");
        output.write(memusage);
    }

    // Write the ps drmserver output to the file
    public static void getDrmServerMemorySummary(Writer output, String tag) throws Exception {

        getTestMemorySummary(output, tag, "drmMem");
    }

    // Write the ps drmserver output to the file
    public static void getMediaServerMemorySummary(Writer output, String tag) throws Exception {

        getTestMemorySummary(output, tag, "mediaMem");
    }

    public static void getTestMemorySummary(Writer output, String tag, String type)
            throws Exception {

        int endMemory = 0;
        int memDiff = 0;

        if (type == "mediaMem") {
            endMemory = getMediaServerVsize();
            memDiff = endMemory - mMediaStartMemory;
        } else if (type == "drmMem") {
            endMemory = getDrmServerVsize();
            memDiff = endMemory - mDrmStartMemory;
        }
        output.write("End Memory :" + endMemory + "\n");
        if (memDiff < 0) {
            memDiff = 0;
        }
        output.write(tag + " total diff = " + memDiff);
        output.write("\n\n");
    }
}
