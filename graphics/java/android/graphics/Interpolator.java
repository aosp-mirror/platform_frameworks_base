/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

import android.os.SystemClock;

public class Interpolator {

    public Interpolator(int valueCount) {
        mValueCount = valueCount;
        mFrameCount = 2;
        native_instance = nativeConstructor(valueCount, 2);
    }
    
    public Interpolator(int valueCount, int frameCount) {
        mValueCount = valueCount;
        mFrameCount = frameCount;
        native_instance = nativeConstructor(valueCount, frameCount);
    }
    
    /**
     * Reset the Interpolator to have the specified number of values and an
     * implicit keyFrame count of 2 (just a start and end). After this call the
     * values for each keyFrame must be assigned using setKeyFrame().
     */
    public void reset(int valueCount) {
        reset(valueCount, 2);
    }
    
    /**
     * Reset the Interpolator to have the specified number of values and
     * keyFrames. After this call the values for each keyFrame must be assigned
     * using setKeyFrame().
     */
    public void reset(int valueCount, int frameCount) {
        mValueCount = valueCount;
        mFrameCount = frameCount;
        nativeReset(native_instance, valueCount, frameCount);
    }
    
    public final int getKeyFrameCount() {
        return mFrameCount;
    }
    
    public final int getValueCount() {
        return mValueCount;
    }
    
    /**
     * Assign the keyFrame (specified by index) a time value and an array of key
     * values (with an implicity blend array of [0, 0, 1, 1] giving linear
     * transition to the next set of key values).
     * 
     * @param index The index of the key frame to assign
     * @param msec The time (in mililiseconds) for this key frame. Based on the
     *        SystemClock.uptimeMillis() clock
     * @param values Array of values associated with theis key frame
     */
    public void setKeyFrame(int index, int msec, float[] values) {
        setKeyFrame(index, msec, values, null);
    }

    /**
     * Assign the keyFrame (specified by index) a time value and an array of key
     * values and blend array.
     * 
     * @param index The index of the key frame to assign
     * @param msec The time (in mililiseconds) for this key frame. Based on the
     *        SystemClock.uptimeMillis() clock
     * @param values Array of values associated with theis key frame
     * @param blend (may be null) Optional array of 4 blend values
     */
    public void setKeyFrame(int index, int msec, float[] values, float[] blend) {
        if (index < 0 || index >= mFrameCount) {
            throw new IndexOutOfBoundsException();
        }
        if (values.length < mValueCount) {
            throw new ArrayStoreException();
        }
        if (blend != null && blend.length < 4) {
            throw new ArrayStoreException();
        }
        nativeSetKeyFrame(native_instance, index, msec, values, blend);
    }
    
    /**
     * Set a repeat count (which may be fractional) for the interpolator, and
     * whether the interpolator should mirror its repeats. The default settings
     * are repeatCount = 1, and mirror = false.
     */
    public void setRepeatMirror(float repeatCount, boolean mirror) {
        if (repeatCount >= 0) {
            nativeSetRepeatMirror(native_instance, repeatCount, mirror);
        }
    }
    
    public enum Result {
        NORMAL,
        FREEZE_START,
        FREEZE_END
    }

    /**
     * Calls timeToValues(msec, values) with the msec set to now (by calling
     * (int)SystemClock.uptimeMillis().)
     */
    public Result timeToValues(float[] values) {
        return timeToValues((int)SystemClock.uptimeMillis(), values);
    }

    /**
     * Given a millisecond time value (msec), return the interpolated values and
     * return whether the specified time was within the range of key times
     * (NORMAL), was before the first key time (FREEZE_START) or after the last
     * key time (FREEZE_END). In any event, computed values are always returned.
     * 
     * @param msec The time (in milliseconds) used to sample into the
     *        Interpolator. Based on the SystemClock.uptimeMillis() clock
     * @param values Where to write the computed values (may be NULL).
     * @return how the values were computed (even if values == null)
     */
    public Result timeToValues(int msec, float[] values) {
        if (values != null && values.length < mValueCount) {
            throw new ArrayStoreException();
        }
        switch (nativeTimeToValues(native_instance, msec, values)) {
            case 0: return Result.NORMAL;
            case 1: return Result.FREEZE_START;
            default: return Result.FREEZE_END;
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        nativeDestructor(native_instance);
    }
    
    private int mValueCount;
    private int mFrameCount;
    private final int native_instance;

    private static native int  nativeConstructor(int valueCount, int frameCount);
    private static native void nativeDestructor(int native_instance);
    private static native void nativeReset(int native_instance, int valueCount, int frameCount);
    private static native void nativeSetKeyFrame(int native_instance, int index, int msec, float[] values, float[] blend);
    private static native void nativeSetRepeatMirror(int native_instance, float repeatCount, boolean mirror);
    private static native int  nativeTimeToValues(int native_instance, int msec, float[] values);
}

