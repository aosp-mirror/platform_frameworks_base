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

import android.util.Log;
import android.os.Debug;

/**
 * Loads a class, runs the garbage collector, and prints showmap output.
 *
 * <p>Usage: dalvikvm LoadClass [class name]
 */
class LoadClass {

    public static void main(String[] args) {
        System.loadLibrary("android_runtime");

        if (registerNatives() < 0) {
            throw new RuntimeException("Error registering natives.");    
        }

        Debug.startAllocCounting();

        if (args.length > 0) {
            try {
                long start = System.currentTimeMillis();
                Class.forName(args[0]);
                long elapsed = System.currentTimeMillis() - start;
                Log.i("LoadClass", "Loaded " + args[0] + " in " + elapsed
                        + "ms.");
            } catch (ClassNotFoundException e) {
                Log.w("LoadClass", e);
                return;
            }
        }

        System.gc();

        int allocCount = Debug.getGlobalAllocCount();
        int allocSize = Debug.getGlobalAllocSize();
        int freedCount = Debug.getGlobalFreedCount();
        int freedSize = Debug.getGlobalFreedSize();
        long nativeHeapSize = Debug.getNativeHeapSize();

        Debug.stopAllocCounting();

        StringBuilder response = new StringBuilder("DECAFBAD");

        int[] pages = new int[6];
        Debug.MemoryInfo memoryInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memoryInfo);
        response.append(',').append(memoryInfo.nativeSharedDirty);
        response.append(',').append(memoryInfo.dalvikSharedDirty);
        response.append(',').append(memoryInfo.otherSharedDirty);
        response.append(',').append(memoryInfo.nativePrivateDirty);
        response.append(',').append(memoryInfo.dalvikPrivateDirty);
        response.append(',').append(memoryInfo.otherPrivateDirty);

        response.append(',').append(allocCount);
        response.append(',').append(allocSize);
        response.append(',').append(freedCount);
        response.append(',').append(freedSize);
        response.append(',').append(nativeHeapSize);
        
        System.out.println(response.toString());
    }

    /**
     * Registers native functions. See AndroidRuntime.cpp.
     */
    static native int registerNatives();
}
