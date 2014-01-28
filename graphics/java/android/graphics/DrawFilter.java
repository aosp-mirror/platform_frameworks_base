/*
 * Copyright (C) 2008 The Android Open Source Project
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

/**
 * A DrawFilter subclass can be installed in a Canvas. When it is present, it
 * can modify the paint that is used to draw (temporarily). With this, a filter
 * can disable/enable antialiasing, or change the color for everything this is
 * drawn.
 */
public class DrawFilter {

    // this is set by subclasses, but don't make it public
    /* package */ long mNativeInt;    // pointer to native object

    protected void finalize() throws Throwable {
        try {
            nativeDestructor(mNativeInt);
        } finally {
            super.finalize();
        }
    }
    
    private static native void nativeDestructor(long nativeDrawFilter);
}

