/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.mediaframeworktest.functional;

import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * The EnergyProbe class provides audio signal energy measurements based on the FFT returned
 * by the Visualizer class. The measure is qualitative and not quantitative in that the returned
 * value has no unit and is just proportional to the amount of energy present around the
 * specified frequency.
 */

public class EnergyProbe {
    private String TAG = "EnergyProbe";

    private static int CAPTURE_SIZE = 1024;
    private static int MEASURE_COUNT = 5;
    private static int AVERAGE_COUNT = 3;

    private Visualizer mVisualizer = null;
    private int mMaxFrequency = 0;
    private int mCapturePeriodMs;
    private byte[] mFft = new byte[CAPTURE_SIZE];

    public EnergyProbe(int session) {
        try {
            mVisualizer = new Visualizer(session);
            if (mVisualizer != null) {
                mVisualizer.setCaptureSize(CAPTURE_SIZE);
                mMaxFrequency = mVisualizer.getSamplingRate() / 2000;
                mCapturePeriodMs = 1000000 / mVisualizer.getMaxCaptureRate();
            }
        } catch (UnsupportedOperationException e) {
            Log.e(TAG, "Error creating visualizer");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error configuring visualizer");
        }
    }

    public int capture(int freq) throws InterruptedException {
        int energy = 0;
        int count = 0;

        if (freq > mMaxFrequency) {
            return 0;
        }

        if (mVisualizer != null) {
            try {
                mVisualizer.setEnabled(true);
                for (int i = 0; i < MEASURE_COUNT; i++) {
                    if (mVisualizer.getFft(mFft) == Visualizer.SUCCESS) {
                        if (freq == mMaxFrequency) {
                            energy += (int)mFft[0] * (int)mFft[0];
                        } else {
                            int bin = 2 * (freq * CAPTURE_SIZE / mMaxFrequency / 2);
                            if (bin < 2) bin = 2;
                            int tmp = 0;
                            int j;
                            for (j = 0;
                                 (j < AVERAGE_COUNT) && ((bin + 2 * j) < CAPTURE_SIZE);
                                 j++) {
                                tmp += (int)mFft[bin + 2 * j] * (int)mFft[bin + 2 * j] +
                                       (int)mFft[bin + 2 * j + 1] * (int)mFft[bin + 2 * j + 1];
                            }
                            // j is always != 0
                            energy += tmp/j;
                        }
                        count++;
                    }
                    Thread.sleep(mCapturePeriodMs);
                }
                mVisualizer.setEnabled(false);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error capturing audio");
            }
        }
        if (count == 0) {
            return 0;
        }
        return energy/count;
    }

    public void release() {
        if (mVisualizer != null) {
            mVisualizer.release();
            mVisualizer = null;
        }
    }
}
