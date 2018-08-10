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

import android.annotation.UnsupportedAppUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the ObjectPropList dataset used by the GetObjectPropList command.
 * The fields of this class are read by JNI code in android_media_MtpDatabase.cpp
 */

class MtpPropertyList {

    // list of object handles (first field in quadruplet)
    private List<Integer> mObjectHandles;
    // list of object property codes (second field in quadruplet)
    private List<Integer> mPropertyCodes;
    // list of data type codes (third field in quadruplet)
    private List<Integer> mDataTypes;
    // list of long int property values (fourth field in quadruplet, when value is integer type)
    private List<Long> mLongValues;
    // list of long int property values (fourth field in quadruplet, when value is string type)
    private List<String> mStringValues;

    // Return value of this operation
    private int mCode;

    public MtpPropertyList(int code) {
        mCode = code;
        mObjectHandles = new ArrayList<>();
        mPropertyCodes = new ArrayList<>();
        mDataTypes = new ArrayList<>();
        mLongValues = new ArrayList<>();
        mStringValues = new ArrayList<>();
    }

    @UnsupportedAppUsage
    public void append(int handle, int property, int type, long value) {
        mObjectHandles.add(handle);
        mPropertyCodes.add(property);
        mDataTypes.add(type);
        mLongValues.add(value);
        mStringValues.add(null);
    }

    @UnsupportedAppUsage
    public void append(int handle, int property, String value) {
        mObjectHandles.add(handle);
        mPropertyCodes.add(property);
        mDataTypes.add(MtpConstants.TYPE_STR);
        mStringValues.add(value);
        mLongValues.add(0L);
    }

    public int getCode() {
        return mCode;
    }

    public int getCount() {
        return mObjectHandles.size();
    }

    public int[] getObjectHandles() {
        return mObjectHandles.stream().mapToInt(Integer::intValue).toArray();
    }

    public int[] getPropertyCodes() {
        return mPropertyCodes.stream().mapToInt(Integer::intValue).toArray();
    }

    public int[] getDataTypes() {
        return mDataTypes.stream().mapToInt(Integer::intValue).toArray();
    }

    public long[] getLongValues() {
        return mLongValues.stream().mapToLong(Long::longValue).toArray();
    }

    public String[] getStringValues() {
        return mStringValues.toArray(new String[0]);
    }
}
