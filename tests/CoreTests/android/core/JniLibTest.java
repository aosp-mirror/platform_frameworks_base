/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.core;

import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;
import junit.framework.TestCase;


@Suppress
public class JniLibTest extends TestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        /*
         * This causes the native shared library to be loaded when the
         * class is first used.  The library is only loaded once, even if
         * multiple classes include this line.
         *
         * The library must be in java.library.path, which is derived from
         * LD_LIBRARY_PATH.  The actual library name searched for will be
         * "libjni_lib_test.so" under Linux, but may be different on other
         * platforms.
         */
        try {
            System.loadLibrary("jni_lib_test");
        } catch (UnsatisfiedLinkError ule) {
            Log.e("JniLibTest", "WARNING: Could not load jni_lib_test natives");
        }
    }

    private static native int nativeStaticThing(float f);
    private native void nativeThing(int val);

    public void testNativeCall() {
        Log.i("JniLibTest", "JNI search path is "
                + System.getProperty("java.library.path"));
        Log.i("JniLibTest", "'jni_lib_test' becomes '"
                + System.mapLibraryName("jni_lib_test") + "'");

        int result = nativeStaticThing(1234.5f);
        nativeThing(result);
    }
}
