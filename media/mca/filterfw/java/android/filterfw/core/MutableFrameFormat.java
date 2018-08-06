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


package android.filterfw.core;

import android.annotation.UnsupportedAppUsage;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.KeyValueMap;

import java.util.Arrays;

/**
 * @hide
 */
public class MutableFrameFormat extends FrameFormat {

    public MutableFrameFormat() {
        super();
    }

    @UnsupportedAppUsage
    public MutableFrameFormat(int baseType, int target) {
        super(baseType, target);
    }

    public void setBaseType(int baseType) {
        mBaseType = baseType;
        mBytesPerSample = bytesPerSampleOf(baseType);
    }

    public void setTarget(int target) {
        mTarget = target;
    }

    @UnsupportedAppUsage
    public void setBytesPerSample(int bytesPerSample) {
        mBytesPerSample = bytesPerSample;
        mSize = SIZE_UNKNOWN;
    }

    public void setDimensions(int[] dimensions) {
        mDimensions = (dimensions == null) ? null : Arrays.copyOf(dimensions, dimensions.length);
        mSize = SIZE_UNKNOWN;
    }

    public void setDimensions(int size) {
        int[] dimensions = new int[1];
        dimensions[0] = size;
        mDimensions = dimensions;
        mSize = SIZE_UNKNOWN;
    }

    @UnsupportedAppUsage
    public void setDimensions(int width, int height) {
        int[] dimensions = new int[2];
        dimensions[0] = width;
        dimensions[1] = height;
        mDimensions = dimensions;
        mSize = SIZE_UNKNOWN;
    }

    public void setDimensions(int width, int height, int depth) {
        int[] dimensions = new int[3];
        dimensions[0] = width;
        dimensions[1] = height;
        dimensions[2] = depth;
        mDimensions = dimensions;
        mSize = SIZE_UNKNOWN;
    }

    public void setDimensionCount(int count) {
        mDimensions = new int[count];
    }

    public void setObjectClass(Class objectClass) {
        mObjectClass = objectClass;
    }

    public void setMetaValue(String key, Object value) {
        if (mMetaData == null) {
            mMetaData = new KeyValueMap();
        }
        mMetaData.put(key, value);
    }

}
