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

import android.os.Debug;
import android.os.Environment;

/**
 *
 * Utilities for media framework test.
 *
 */
public class MediaTestUtil {
    private MediaTestUtil(){
    }

    private static final String STORAGE_PATH =
        Environment.getExternalStorageDirectory().toString();

    //Catpure the heapdump for memory leaksage analysis\
    public static void getNativeHeapDump (String name) throws Exception {
        System.gc();
        System.runFinalization();
        Thread.sleep(1000);
        FileOutputStream o = new FileOutputStream(STORAGE_PATH + '/' +name + ".dump");
        Debug.dumpNativeHeap(o.getFD());
        o.close();
    }
}