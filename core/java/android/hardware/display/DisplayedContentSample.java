/*
 * Copyright 2018 The Android Open Source Project
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

package android.hardware.display;

/**
 * @hide
 */
public final class DisplayedContentSample {
    private long mNumFrames;
    private long[] mSamplesComponent0;
    private long[] mSamplesComponent1;
    private long[] mSamplesComponent2;
    private long[] mSamplesComponent3;

    /**
     * Construct an object representing a color histogram of pixels that were displayed on screen.
     *
     * @param numFrames The number of frames represented by this sample.
     * @param mSamplesComponent0 is a histogram counting how many times and for how long a pixel
     * of a given value was displayed onscreen for FORMAT_COMPONENT_0. The buckets of the
     * histogram are evenly weighted, the number of buckets is device specific.
     * The units are in pixels * milliseconds, with 1 pixel millisecond being 1 pixel displayed
     * onscreen for 1ms.
     * eg, for RGBA_8888, if sampleComponent0 is {100, 50, 30, 20},  then red component was
     * onscreen for 100 pixel milliseconds in range 0x00->0x3F, 30 pixel milliseconds in
     * range 0x40->0x7F, etc.
     * @param mSamplesComponent1 is the same sample definition as sampleComponent0, but for the
     * second component of format.
     * @param mSamplesComponent2 is the same sample definition as sampleComponent0, but for the
     * third component of format.
     * @param mSamplesComponent3 is the same sample definition as sampleComponent0, but for the
     * fourth component of format.
     */
    public DisplayedContentSample(long numFrames,
            long[] sampleComponent0,
            long[] sampleComponent1,
            long[] sampleComponent2,
            long[] sampleComponent3) {
        mNumFrames = numFrames;
        mSamplesComponent0 = sampleComponent0;
        mSamplesComponent1 = sampleComponent1;
        mSamplesComponent2 = sampleComponent2;
        mSamplesComponent3 = sampleComponent3;
    }

    public enum ColorComponent {
        CHANNEL0,
        CHANNEL1,
        CHANNEL2,
        CHANNEL3,
    }

    /**
     * Returns a color histogram according to component channel.
     *
     * @param component the component to return, according to the PixelFormat ordering
     * (eg, for RGBA, CHANNEL0 is R, CHANNEL1 is G, etc).
     *
     * @return an evenly weighted histogram counting how many times a pixel was
     *         displayed onscreen that fell into the corresponding bucket, with the first entry
     *         corresponding to the normalized 0.0 value, and the last corresponding to the 1.0
     *         value for that PixelFormat component.
     */
    public long[] getSampleComponent(ColorComponent component) {
        switch (component) {
            case CHANNEL0: return mSamplesComponent0;
            case CHANNEL1: return mSamplesComponent1;
            case CHANNEL2: return mSamplesComponent2;
            case CHANNEL3: return mSamplesComponent3;
            default: throw new ArrayIndexOutOfBoundsException();
        }
    }

    /**
     * Return the number of frames this sample was collected over.
     *
     * @return  the number of frames that this sample was collected over.
     */
    public long getNumFrames() {
        return mNumFrames;
    }
}
