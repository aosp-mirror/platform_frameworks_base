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


package android.filterpacks.performance;

/**
 * @hide
 */
public class Throughput {

    private final int mTotalFrames;
    private final int mPeriodFrames;
    private final int mPeriodTime;
    private final int mPixels;

    public Throughput(int totalFrames, int periodFrames, int periodTime, int pixels) {
        mTotalFrames = totalFrames;
        mPeriodFrames = periodFrames;
        mPeriodTime = periodTime;
        mPixels = pixels;
    }

    public int getTotalFrameCount() {
        return mTotalFrames;
    }

    public int getPeriodFrameCount() {
        return mPeriodFrames;
    }

    public int getPeriodTime() {
        return mPeriodTime;
    }

    public float getFramesPerSecond() {
        return mPeriodFrames / (float)mPeriodTime;
    }

    public float getNanosPerPixel() {
        double frameTimeInNanos = (mPeriodTime / (double)mPeriodFrames) * 1000000.0;
        return (float)(frameTimeInNanos / mPixels);
    }

    public String toString() {
        return getFramesPerSecond() + " FPS";
    }
}
