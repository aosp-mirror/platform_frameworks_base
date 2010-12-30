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

package android.mtp;

/**
 * Encapsulates the ObjectPropList dataset used by the GetObjectPropList command.
 * The fields of this class are read by JNI code in android_media_MtpDatabase.cpp
 */

class MtpPropertyList {

    // number of results returned
    private int             mCount;
    // maximum number of results
    private final int       mMaxCount;
    // result code for GetObjectPropList
    public int              mResult;
    // list of object handles (first field in quadruplet)
    public final int[]      mObjectHandles;
    // list of object propery codes (second field in quadruplet)
    public final int[]      mPropertyCodes;
    // list of data type codes (third field in quadruplet)
    public final int[]     mDataTypes;
    // list of long int property values (fourth field in quadruplet, when value is integer type)
    public long[]     mLongValues;
    // list of long int property values (fourth field in quadruplet, when value is string type)
    public String[]   mStringValues;

    // constructor only called from MtpDatabase
    public MtpPropertyList(int maxCount, int result) {
        mMaxCount = maxCount;
        mResult = result;
        mObjectHandles = new int[maxCount];
        mPropertyCodes = new int[maxCount];
        mDataTypes = new int[maxCount];
        // mLongValues and mStringValues are created lazily since both might not be necessary
    }

    public void append(int handle, int property, int type, long value) {
        int index = mCount++;
        if (mLongValues == null) {
            mLongValues = new long[mMaxCount];
        }
        mObjectHandles[index] = handle;
        mPropertyCodes[index] = property;
        mDataTypes[index] = type;
        mLongValues[index] = value;
    }

    public void append(int handle, int property, String value) {
        int index = mCount++;
        if (mStringValues == null) {
            mStringValues = new String[mMaxCount];
        }
        mObjectHandles[index] = handle;
        mPropertyCodes[index] = property;
        mDataTypes[index] = MtpConstants.TYPE_STR;
        mStringValues[index] = value;
    }

    public void setResult(int result) {
        mResult = result;
    }
}
