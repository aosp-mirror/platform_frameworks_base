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

import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
 * on some object. We override these methods to use reflection since the original reflection
 * implementation of the PropertyValuesHolder won't be able to access protected methods.
 *
 */
/*package*/
@SuppressWarnings("unused")
class PropertyValuesHolder_Delegate {
    // This code is copied from android.animation.PropertyValuesHolder and must be kept in sync
    // We try several different types when searching for appropriate setter/getter functions.
    // The caller may have supplied values in a type that does not match the setter/getter
    // functions (such as the integers 0 and 1 to represent floating point values for alpha).
    // Also, the use of generics in constructors means that we end up with the Object versions
    // of primitive types (Float vs. float). But most likely, the setter/getter functions
    // will take primitive types instead.
    // So we supply an ordered array of other types to try before giving up.
    private static Class[] FLOAT_VARIANTS = {float.class, Float.class, double.class, int.class,
            Double.class, Integer.class};
    private static Class[] INTEGER_VARIANTS = {int.class, Integer.class, float.class, double.class,
            Float.class, Double.class};

    private static final Object sMethodIndexLock = new Object();
    private static final Map<Long, Method> ID_TO_METHOD = new HashMap<Long, Method>();
    private static final Map<String, Long> METHOD_NAME_TO_ID = new HashMap<String, Long>();
    private static long sNextId = 1;

    private static long registerMethod(Class<?> targetClass, String methodName, Class[] types,
            int nArgs) {
        // Encode the number of arguments in the method name
        String methodIndexName = String.format("%1$s.%2$s#%3$d", targetClass.getSimpleName(),
                methodName, nArgs);
        synchronized (sMethodIndexLock) {
            Long methodId = METHOD_NAME_TO_ID.get(methodIndexName);

            if (methodId != null) {
                // The method was already registered
                return methodId;
            }

            Class[] args = new Class[nArgs];
            Method method = null;
            for (Class typeVariant : types) {
                for (int i = 0; i < nArgs; i++) {
                    args[i] = typeVariant;
                }
                try {
                    method = targetClass.getDeclaredMethod(methodName, args);
                } catch (NoSuchMethodException ignore) {
                }
            }

            if (method != null) {
                methodId = sNextId++;
                ID_TO_METHOD.put(methodId, method);
                METHOD_NAME_TO_ID.put(methodIndexName, methodId);

                return methodId;
            }
        }

        // Method not found
        return 0;
    }

    private static void callMethod(Object target, long methodID, Object... args) {
        Method method = ID_TO_METHOD.get(methodID);
        assert method != null;

        try {
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (IllegalAccessException e) {
            Bridge.getLog().error(null, "Unable to update property during animation", e, null);
        } catch (InvocationTargetException e) {
            Bridge.getLog().error(null, "Unable to update property during animation", e, null);
        }
    }

    @LayoutlibDelegate
    /*package*/ static long nGetIntMethod(Class<?> targetClass, String methodName) {
        return nGetMultipleIntMethod(targetClass, methodName, 1);
    }

    @LayoutlibDelegate
    /*package*/ static long nGetFloatMethod(Class<?> targetClass, String methodName) {
        return nGetMultipleFloatMethod(targetClass, methodName, 1);
    }

    @LayoutlibDelegate
    /*package*/ static long nGetMultipleIntMethod(Class<?> targetClass, String methodName,
            int numParams) {
        return registerMethod(targetClass, methodName, INTEGER_VARIANTS, numParams);
    }

    @LayoutlibDelegate
    /*package*/ static long nGetMultipleFloatMethod(Class<?> targetClass, String methodName,
            int numParams) {
        return registerMethod(targetClass, methodName, FLOAT_VARIANTS, numParams);
    }

    @LayoutlibDelegate
    /*package*/ static void nCallIntMethod(Object target, long methodID, int arg) {
        callMethod(target, methodID, arg);
    }

    @LayoutlibDelegate
    /*package*/ static void nCallFloatMethod(Object target, long methodID, float arg) {
        callMethod(target, methodID, arg);
    }

    @LayoutlibDelegate
    /*package*/ static void nCallTwoIntMethod(Object target, long methodID, int arg1,
            int arg2) {
        callMethod(target, methodID, arg1, arg2);
    }

    @LayoutlibDelegate
    /*package*/ static void nCallFourIntMethod(Object target, long methodID, int arg1,
            int arg2, int arg3, int arg4) {
        callMethod(target, methodID, arg1, arg2, arg3, arg4);
    }

    @LayoutlibDelegate
    /*package*/ static void nCallMultipleIntMethod(Object target, long methodID,
            int[] args) {
        assert args != null;

        // Box parameters
        Object[] params = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            params[i] = args;
        }
        callMethod(target, methodID, params);
    }

    @LayoutlibDelegate
    /*package*/ static void nCallTwoFloatMethod(Object target, long methodID, float arg1,
            float arg2) {
        callMethod(target, methodID, arg1, arg2);
    }

    @LayoutlibDelegate
    /*package*/ static void nCallFourFloatMethod(Object target, long methodID, float arg1,
            float arg2, float arg3, float arg4) {
        callMethod(target, methodID, arg1, arg2, arg3, arg4);
    }

    @LayoutlibDelegate
    /*package*/ static void nCallMultipleFloatMethod(Object target, long methodID,
            float[] args) {
        assert args != null;

        // Box parameters
        Object[] params = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            params[i] = args;
        }
        callMethod(target, methodID, params);
    }
}
