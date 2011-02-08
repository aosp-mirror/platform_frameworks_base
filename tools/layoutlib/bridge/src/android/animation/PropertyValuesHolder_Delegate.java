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
    /*package*/ static int nGetIntMethod(Class<?> targetClass, String methodName) {
        // return 0 to force PropertyValuesHolder to use Java reflection.
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static int nGetFloatMethod(Class<?> targetClass, String methodName) {
        // return 0 to force PropertyValuesHolder to use Java reflection.
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static void nCallIntMethod(Object target, int methodID, int arg) {
        // do nothing
    }

    @LayoutlibDelegate
    /*package*/ static void nCallFloatMethod(Object target, int methodID, float arg) {
        // do nothing
    }
}
