/*
 * Copyright (C) 2019 The Android Open Source Project
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

/**
 *  A threaded CPU intensive class for use in benchmarks.
 */

package com.android.startop.test;

final class CPUIntensive {
    public static final int THREAD_COUNT = 8;
    public static final int ARRAY_SIZE = 30000;
    public static int[][] array = new int[THREAD_COUNT][ARRAY_SIZE];

    static class WorkerThread extends Thread {
        int mThreadNumber;
        WorkerThread(int number) {
            mThreadNumber = number;
        }
        public void run() {
            final int arrayLength = array[mThreadNumber].length;
            for (int i = 0; i < arrayLength; ++i) {
                array[mThreadNumber][i] = i * i;
            }
            for (int i = 0; i < arrayLength; ++i) {
                for (int j = 0; j < arrayLength; ++j) {
                    int swap = array[mThreadNumber][j];
                    array[mThreadNumber][j] = array[mThreadNumber][(j + i) % arrayLength];
                    array[mThreadNumber][(j + i) % arrayLength] = swap;
                }
            }
        }
    };

    public static void doSomeWork(int threadCount) {
        WorkerThread[] threads = new WorkerThread[threadCount];
        // Create the threads.
        for (int i = 0; i < threadCount; ++i) {
            threads[i] = new WorkerThread(i);
        }
        // Start the threads.
        for (int i = 0; i < threadCount; ++i) {
            threads[i].start();
        }
        // Join the threads.
        for (int i = 0; i < threadCount; ++i) {
            try {
                threads[i].join();
            } catch (Exception ex) {
            }
        }
    }
}

