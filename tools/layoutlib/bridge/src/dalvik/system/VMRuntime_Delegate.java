/*
 * Copyright (C) 2014 The Android Open Source Project
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

package dalvik.system;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

/**
 * Delegate used to provide implementation of a select few native methods of {@link VMRuntime}
 * <p/>
 * Through the layoutlib_create tool, the original native methods of VMRuntime have been replaced
 * by calls to methods of the same name in this delegate class.
 */
public class VMRuntime_Delegate {

    // Copied from libcore/libdvm/src/main/java/dalvik/system/VMRuntime
    @LayoutlibDelegate
    /*package*/ static Object newUnpaddedArray(VMRuntime runtime, Class<?> componentType,
            int minLength) {
        // Dalvik has 32bit pointers, the array header is 16bytes plus 4bytes for dlmalloc,
        // allocations are 8byte aligned so having 4bytes of array data avoids padding.
        if (!componentType.isPrimitive()) {
            int size = ((minLength & 1) == 0) ? minLength + 1 : minLength;
            return java.lang.reflect.Array.newInstance(componentType, size);
        } else if (componentType == char.class) {
            int bytes = 20 + (2 * minLength);
            int alignedUpBytes = (bytes + 7) & -8;
            int dataBytes = alignedUpBytes - 20;
            int size = dataBytes / 2;
            return new char[size];
        } else if (componentType == int.class) {
            int size = ((minLength & 1) == 0) ? minLength + 1 : minLength;
            return new int[size];
        } else if (componentType == byte.class) {
            int bytes = 20 + minLength;
            int alignedUpBytes = (bytes + 7) & -8;
            int dataBytes = alignedUpBytes - 20;
            int size = dataBytes;
            return new byte[size];
        } else if (componentType == boolean.class) {
            int bytes = 20 + minLength;
            int alignedUpBytes = (bytes + 7) & -8;
            int dataBytes = alignedUpBytes - 20;
            int size = dataBytes;
            return new boolean[size];
        } else if (componentType == short.class) {
            int bytes = 20 + (2 * minLength);
            int alignedUpBytes = (bytes + 7) & -8;
            int dataBytes = alignedUpBytes - 20;
            int size = dataBytes / 2;
            return new short[size];
        } else if (componentType == float.class) {
            int size = ((minLength & 1) == 0) ? minLength + 1 : minLength;
            return new float[size];
        } else if (componentType == long.class) {
            return new long[minLength];
        } else if (componentType == double.class) {
            return new double[minLength];
        } else {
            assert componentType == void.class;
            throw new IllegalArgumentException("Can't allocate an array of void");
        }
    }

}
