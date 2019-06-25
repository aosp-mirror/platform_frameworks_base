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
package android.gameperformance;

/**
 * Ballast thread that emulates CPU load by performing heavy computation in loop.
 */
public class CPULoadThread extends Thread {
    private boolean mStopRequest;

    public CPULoadThread() {
        mStopRequest = false;
    }

    private static double computePi() {
        double accumulator = 0;
        double prevAccumulator = -1;
        int index = 1;
        while (true) {
            accumulator += ((1.0 / (2.0 * index - 1)) - (1.0 / (2.0 * index + 1)));
            if (accumulator == prevAccumulator) {
                break;
            }
            prevAccumulator = accumulator;
            index += 2;
        }
        return 4 * accumulator;
    }

    // Requests thread to stop.
    public void issueStopRequest() {
        synchronized (this) {
            mStopRequest = true;
        }
    }

    @Override
    public void run() {
        // Load CPU by PI computation.
        while (computePi() != 0) {
            synchronized (this) {
                if (mStopRequest) {
                    break;
                }
            }
        }
    }
}
