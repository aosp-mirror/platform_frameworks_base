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

package android.animation;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

/**
 * Delegate implementing the native methods of android.animation.PropertyValuesHolder
 *
 * Through the layoutlib_create tool, the original native methods of PropertyValuesHolder have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * Because it's a stateless class to start with, there's no need to keep a {@link DelegateManager}
 * around to map int to instance of the delegate.
 *
 * The main goal of this class' methods are to provide a native way to access setters and getters
 * on some object. In this case we want to default to using Java reflection instead so the native
 * methods do nothing.
 *
 */
/*package*/ class PropertyValuesHolder_Delegate {

    @LayoutlibDelegate
    /*package*/ static long nGetIntMethod(Class<?> targetClass, String methodName) {
        // return 0 to force PropertyValuesHolder to use Java reflection.
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static long nGetFloatMethod(Class<?> targetClass, String methodName) {
        // return 0 to force PropertyValuesHolder to use Java reflection.
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static long nGetMultipleIntMethod(Class<?> targetClass, String methodName,
            int numParams) {
        // TODO: return the right thing.
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static long nGetMultipleFloatMethod(Class<?> targetClass, String methodName,
            int numParams) {
        // TODO: return the right thing.
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static void nCallIntMethod(Object target, long methodID, int arg) {
        // do nothing
    }

    @LayoutlibDelegate
    /*package*/ static void nCallFloatMethod(Object target, long methodID, float arg) {
        // do nothing
    }

    @LayoutlibDelegate
    /*package*/ static void nCallTwoIntMethod(Object target, long methodID, int arg1,
            int arg2) {
        // do nothing
    }

    @LayoutlibDelegate
    /*package*/ static void nCallFourIntMethod(Object target, long methodID, int arg1,
            int arg2, int arg3, int arg4) {
        // do nothing
    }

    @LayoutlibDelegate
    /*package*/ static void nCallMultipleIntMethod(Object target, long methodID,
            int[] args) {
        // do nothing
    }

    @LayoutlibDelegate
    /*package*/ static void nCallTwoFloatMethod(Object target, long methodID, float arg1,
            float arg2) {
        // do nothing
    }

    @LayoutlibDelegate
    /*package*/ static void nCallFourFloatMethod(Object target, long methodID, float arg1,
            float arg2, float arg3, float arg4) {
        // do nothing
    }

    @LayoutlibDelegate
    /*package*/ static void nCallMultipleFloatMethod(Object target, long methodID,
            float[] args) {
        // do nothing
    }
}
