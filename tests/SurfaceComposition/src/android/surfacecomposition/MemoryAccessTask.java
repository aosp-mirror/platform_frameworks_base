/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.surfacecomposition;

import android.util.Log;

/**
 * This task will simulate CPU activity by consuming memory bandwidth from the system.
 * Note: On most system the CPU and GPU will share the same memory.
 */
public class MemoryAccessTask {
    private final static String TAG = "MemoryAccessTask";
    private final static int BUFFER_SIZE = 32 * 1024 * 1024;
    private final static int BUFFER_STEP = 256;
    private boolean mStopRequested;
    private WorkThread mThread;
    private final Object mLock = new Object();

    public class WorkThread extends Thread {
        public void run() {
            byte[] memory = new byte[BUFFER_SIZE];
            while (true) {
                synchronized (mLock) {
                    if (mStopRequested) {
                        break;
                    }
                }
                long result = 0;
                for (int index = 0; index < BUFFER_SIZE; index += BUFFER_STEP) {
                    result += ++memory[index];
                }
                Log.v(TAG, "Processing...:" + result);
            }
        }
    }

    public void start() {
        if (mThread != null) {
            throw new RuntimeException("Work thread is already started");
        }
        mStopRequested = false;
        mThread = new WorkThread();
        mThread.start();
    }

    public void stop() {
        if (mThread != null) {
            synchronized (mLock) {
                mStopRequested = true;
            }
            try {
                mThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
