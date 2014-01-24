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

package com.android.tools.layoutlib.create;


/**
 * An adapter to make it easier to use {@link MethodListener}.
 * <p/>
 * The adapter calls the void {@link #onInvokeV(String, boolean, Object)} listener
 * for all types (I, L, F, D and A), returning 0 or null as appropriate.
 */
public class MethodAdapter implements MethodListener {
    /**
     * A stub method is being invoked.
     * <p/>
     * Known limitation: caller arguments are not available.
     *
     * @param signature The signature of the method being invoked, composed of the
     *                  binary class name followed by the method descriptor (aka argument
     *                  types). Example: "com/foo/MyClass/InnerClass/printInt(I)V".
     * @param isNative True if the method was a native method.
     * @param caller The calling object. Null for static methods, "this" for instance methods.
     */
    @Override
    public void onInvokeV(String signature, boolean isNative, Object caller) {
    }

    /**
     * Same as {@link #onInvokeV(String, boolean, Object)} but returns an integer or similar.
     * @see #onInvokeV(String, boolean, Object)
     * @return an integer, or a boolean, or a short or a byte.
     */
    @Override
    public int onInvokeI(String signature, boolean isNative, Object caller) {
        onInvokeV(signature, isNative, caller);
        return 0;
    }

    /**
     * Same as {@link #onInvokeV(String, boolean, Object)} but returns a long.
     * @see #onInvokeV(String, boolean, Object)
     * @return a long.
     */
    @Override
    public long onInvokeL(String signature, boolean isNative, Object caller) {
        onInvokeV(signature, isNative, caller);
        return 0;
    }

    /**
     * Same as {@link #onInvokeV(String, boolean, Object)} but returns a float.
     * @see #onInvokeV(String, boolean, Object)
     * @return a float.
     */
    @Override
    public float onInvokeF(String signature, boolean isNative, Object caller) {
        onInvokeV(signature, isNative, caller);
        return 0;
    }

    /**
     * Same as {@link #onInvokeV(String, boolean, Object)} but returns a double.
     * @see #onInvokeV(String, boolean, Object)
     * @return a double.
     */
    @Override
    public double onInvokeD(String signature, boolean isNative, Object caller) {
        onInvokeV(signature, isNative, caller);
        return 0;
    }

    /**
     * Same as {@link #onInvokeV(String, boolean, Object)} but returns an object.
     * @see #onInvokeV(String, boolean, Object)
     * @return an object.
     */
    @Override
    public Object onInvokeA(String signature, boolean isNative, Object caller) {
        onInvokeV(signature, isNative, caller);
        return null;
    }
}

