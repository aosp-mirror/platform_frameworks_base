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

import android.compat.annotation.UnsupportedAppUsage;

import java.util.Arrays;
import java.util.Map.Entry;

/**
 * @hide
 */
public class FrameFormat {

    public static final int TYPE_UNSPECIFIED = 0;
    public static final int TYPE_BIT         = 1;
    public static final int TYPE_BYTE        = 2;
    public static final int TYPE_INT16       = 3;
    public static final int TYPE_INT32       = 4;
    public static final int TYPE_FLOAT       = 5;
    public static final int TYPE_DOUBLE      = 6;
    public static final int TYPE_POINTER     = 7;
    public static final int TYPE_OBJECT      = 8;

    public static final int TARGET_UNSPECIFIED  = 0;
    public static final int TARGET_SIMPLE       = 1;
    public static final int TARGET_NATIVE       = 2;
    public static final int TARGET_GPU          = 3;
    public static final int TARGET_VERTEXBUFFER = 4;
    public static final int TARGET_RS           = 5;

    public static final int SIZE_UNSPECIFIED = 0;

    // TODO: When convenience formats are used, consider changing this to 0 and have the convenience
    // intializers use a proper BPS.
    public static final int BYTES_PER_SAMPLE_UNSPECIFIED = 1;

    protected static final int SIZE_UNKNOWN = -1;

    protected int mBaseType = TYPE_UNSPECIFIED;
    protected int mBytesPerSample = 1;
    protected int mSize = SIZE_UNKNOWN;
    protected int mTarget = TARGET_UNSPECIFIED;
    protected int[] mDimensions;
    protected KeyValueMap mMetaData;
    protected Class mObjectClass;

    protected FrameFormat() {
    }

    public FrameFormat(int baseType, int target) {
        mBaseType = baseType;
        mTarget = target;
        initDefaults();
    }

    public static FrameFormat unspecified() {
        return new FrameFormat(TYPE_UNSPECIFIED, TARGET_UNSPECIFIED);
    }

    public int getBaseType() {
        return mBaseType;
    }

    public boolean isBinaryDataType() {
        return mBaseType >= TYPE_BIT && mBaseType <= TYPE_DOUBLE;
    }

    public int getBytesPerSample() {
        return mBytesPerSample;
    }

    public int getValuesPerSample() {
        return mBytesPerSample / bytesPerSampleOf(mBaseType);
    }

    @UnsupportedAppUsage
    public int getTarget() {
        return mTarget;
    }

    public int[] getDimensions() {
        return mDimensions;
    }

    public int getDimension(int i) {
        return mDimensions[i];
    }

    public int getDimensionCount() {
        return mDimensions == null ? 0 : mDimensions.length;
    }

    public boolean hasMetaKey(String key) {
        return mMetaData != null ? mMetaData.containsKey(key) : false;
    }

    public boolean hasMetaKey(String key, Class expectedClass) {
        if (mMetaData != null && mMetaData.containsKey(key)) {
            if (!expectedClass.isAssignableFrom(mMetaData.get(key).getClass())) {
                throw new RuntimeException(
                    "FrameFormat meta-key '" + key + "' is of type " +
                    mMetaData.get(key).getClass() + " but expected to be of type " +
                    expectedClass + "!");
            }
            return true;
        }
        return false;
    }

    public Object getMetaValue(String key) {
        return mMetaData != null ? mMetaData.get(key) : null;
    }

    public int getNumberOfDimensions() {
        return mDimensions != null ? mDimensions.length : 0;
    }

    public int getLength() {
        return (mDimensions != null && mDimensions.length >= 1) ? mDimensions[0] : -1;
    }

    @UnsupportedAppUsage
    public int getWidth() {
        return getLength();
    }

    @UnsupportedAppUsage
    public int getHeight() {
        return (mDimensions != null && mDimensions.length >= 2) ? mDimensions[1] : -1;
    }

    public int getDepth() {
        return (mDimensions != null && mDimensions.length >= 3) ? mDimensions[2] : -1;
    }

    public int getSize() {
        if (mSize == SIZE_UNKNOWN) mSize = calcSize(mDimensions);
        return mSize;
    }

    public Class getObjectClass() {
        return mObjectClass;
    }

    @UnsupportedAppUsage
    public MutableFrameFormat mutableCopy() {
        MutableFrameFormat result = new MutableFrameFormat();
        result.setBaseType(getBaseType());
        result.setTarget(getTarget());
        result.setBytesPerSample(getBytesPerSample());
        result.setDimensions(getDimensions());
        result.setObjectClass(getObjectClass());
        result.mMetaData = mMetaData == null ? null : (KeyValueMap)mMetaData.clone();
        return result;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof FrameFormat)) {
            return false;
        }

        FrameFormat format = (FrameFormat)object;
        return format.mBaseType == mBaseType &&
                format.mTarget == mTarget &&
                format.mBytesPerSample == mBytesPerSample &&
                Arrays.equals(format.mDimensions, mDimensions) &&
                format.mMetaData.equals(mMetaData);
    }

    @Override
    public int hashCode() {
        return 4211 ^ mBaseType ^ mBytesPerSample ^ getSize();
    }

    public boolean isCompatibleWith(FrameFormat specification) {
        // Check base type
        if (specification.getBaseType() != TYPE_UNSPECIFIED
            && getBaseType() != specification.getBaseType()) {
            return false;
        }

        // Check target
        if (specification.getTarget() != TARGET_UNSPECIFIED
            && getTarget() != specification.getTarget()) {
            return false;
        }

        // Check bytes per sample
        if (specification.getBytesPerSample() != BYTES_PER_SAMPLE_UNSPECIFIED
            && getBytesPerSample() != specification.getBytesPerSample()) {
            return false;
        }

        // Check number of dimensions
        if (specification.getDimensionCount() > 0
            && getDimensionCount() != specification.getDimensionCount()) {
            return false;
        }

        // Check dimensions
        for (int i = 0; i < specification.getDimensionCount(); ++i) {
            int specDim = specification.getDimension(i);
            if (specDim != SIZE_UNSPECIFIED && getDimension(i) != specDim) {
                return false;
            }
        }

        // Check class
        if (specification.getObjectClass() != null) {
            if (getObjectClass() == null
                || !specification.getObjectClass().isAssignableFrom(getObjectClass())) {
                return false;
            }
        }

        // Check meta-data
        if (specification.mMetaData != null) {
            for (String specKey : specification.mMetaData.keySet()) {
                if (mMetaData == null
                || !mMetaData.containsKey(specKey)
                || !mMetaData.get(specKey).equals(specification.mMetaData.get(specKey))) {
                    return false;
                }
            }
        }

        // Passed all the tests
        return true;
    }

    public boolean mayBeCompatibleWith(FrameFormat specification) {
        // Check base type
        if (specification.getBaseType() != TYPE_UNSPECIFIED
            && getBaseType() != TYPE_UNSPECIFIED
            && getBaseType() != specification.getBaseType()) {
            return false;
        }

        // Check target
        if (specification.getTarget() != TARGET_UNSPECIFIED
            && getTarget() != TARGET_UNSPECIFIED
            && getTarget() != specification.getTarget()) {
            return false;
        }

        // Check bytes per sample
        if (specification.getBytesPerSample() != BYTES_PER_SAMPLE_UNSPECIFIED
            && getBytesPerSample() != BYTES_PER_SAMPLE_UNSPECIFIED
            && getBytesPerSample() != specification.getBytesPerSample()) {
            return false;
        }

        // Check number of dimensions
        if (specification.getDimensionCount() > 0
            && getDimensionCount() > 0
            && getDimensionCount() != specification.getDimensionCount()) {
            return false;
        }

        // Check dimensions
        for (int i = 0; i < specification.getDimensionCount(); ++i) {
            int specDim = specification.getDimension(i);
            if (specDim != SIZE_UNSPECIFIED
                && getDimension(i) != SIZE_UNSPECIFIED
                && getDimension(i) != specDim) {
                return false;
            }
        }

        // Check class
        if (specification.getObjectClass() != null && getObjectClass() != null) {
            if (!specification.getObjectClass().isAssignableFrom(getObjectClass())) {
                return false;
            }
        }

        // Check meta-data
        if (specification.mMetaData != null && mMetaData != null) {
            for (String specKey : specification.mMetaData.keySet()) {
                if (mMetaData.containsKey(specKey)
                    && !mMetaData.get(specKey).equals(specification.mMetaData.get(specKey))) {
                    return false;
                }
            }
        }

        // Passed all the tests
        return true;
    }

    public static int bytesPerSampleOf(int baseType) {
        // Defaults based on base-type
        switch (baseType) {
            case TYPE_BIT:
            case TYPE_BYTE:
                return 1;
            case TYPE_INT16:
                return 2;
            case TYPE_INT32:
            case TYPE_FLOAT:
            case TYPE_POINTER:
                return 4;
            case TYPE_DOUBLE:
                return 8;
            default:
                return 1;
        }
    }

    public static String dimensionsToString(int[] dimensions) {
        StringBuffer buffer = new StringBuffer();
        if (dimensions != null) {
            int n = dimensions.length;
            for (int i = 0; i < n; ++i) {
                if (dimensions[i] == SIZE_UNSPECIFIED) {
                    buffer.append("[]");
                } else {
                    buffer.append("[" + String.valueOf(dimensions[i]) + "]");
                }
            }
        }
        return buffer.toString();
    }

    public static String baseTypeToString(int baseType) {
        switch (baseType) {
            case TYPE_UNSPECIFIED: return "unspecified";
            case TYPE_BIT:         return "bit";
            case TYPE_BYTE:        return "byte";
            case TYPE_INT16:       return "int";
            case TYPE_INT32:       return "int";
            case TYPE_FLOAT:       return "float";
            case TYPE_DOUBLE:      return "double";
            case TYPE_POINTER:     return "pointer";
            case TYPE_OBJECT:      return "object";
            default:               return "unknown";
        }
    }

    public static String targetToString(int target) {
        switch (target) {
            case TARGET_UNSPECIFIED:  return "unspecified";
            case TARGET_SIMPLE:       return "simple";
            case TARGET_NATIVE:       return "native";
            case TARGET_GPU:          return "gpu";
            case TARGET_VERTEXBUFFER: return "vbo";
            case TARGET_RS:           return "renderscript";
            default:                  return "unknown";
        }
    }

    public static String metaDataToString(KeyValueMap metaData) {
        if (metaData == null) {
            return "";
        } else {
            StringBuffer buffer = new StringBuffer();
            buffer.append("{ ");
            for (Entry<String, Object> entry : metaData.entrySet()) {
                buffer.append(entry.getKey() + ": " + entry.getValue() + " ");
            }
            buffer.append("}");
            return buffer.toString();
        }
    }

    public static int readTargetString(String targetString) {
        if (targetString.equalsIgnoreCase("CPU") || targetString.equalsIgnoreCase("NATIVE")) {
            return FrameFormat.TARGET_NATIVE;
        } else if (targetString.equalsIgnoreCase("GPU")) {
            return FrameFormat.TARGET_GPU;
        } else if (targetString.equalsIgnoreCase("SIMPLE")) {
            return FrameFormat.TARGET_SIMPLE;
        } else if (targetString.equalsIgnoreCase("VERTEXBUFFER")) {
            return FrameFormat.TARGET_VERTEXBUFFER;
        } else if (targetString.equalsIgnoreCase("UNSPECIFIED")) {
            return FrameFormat.TARGET_UNSPECIFIED;
        } else {
            throw new RuntimeException("Unknown target type '" + targetString + "'!");
        }
    }

    // TODO: FromString

    public String toString() {
        int valuesPerSample = getValuesPerSample();
        String sampleCountString = valuesPerSample == 1 ? "" : String.valueOf(valuesPerSample);
        String targetString = mTarget == TARGET_UNSPECIFIED ? "" : (targetToString(mTarget) + " ");
        String classString = mObjectClass == null
            ? ""
            : (" class(" + mObjectClass.getSimpleName() + ") ");

        return targetString
            + baseTypeToString(mBaseType)
            + sampleCountString
            + dimensionsToString(mDimensions)
            + classString
            + metaDataToString(mMetaData);
    }

    private void initDefaults() {
        mBytesPerSample = bytesPerSampleOf(mBaseType);
    }

    // Core internal methods ///////////////////////////////////////////////////////////////////////
    int calcSize(int[] dimensions) {
        if (dimensions != null && dimensions.length > 0) {
            int size = getBytesPerSample();
            for (int dim : dimensions) {
                size *= dim;
            }
            return size;
        }
        return 0;
    }

    boolean isReplaceableBy(FrameFormat format) {
        return mTarget == format.mTarget
            && getSize() == format.getSize()
            && Arrays.equals(format.mDimensions, mDimensions);
    }
}
