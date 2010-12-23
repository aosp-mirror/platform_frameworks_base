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

package com.android.layoutlib.bridge.impl;

import android.util.SparseArray;

/**
 * Manages native delegates.
 *
 * This is used in conjunction with layoublib_create: certain Android java classes are mere
 * wrappers around a heavily native based implementation, and we need a way to run these classes
 * in our Eclipse rendering framework without bringing all the native code from the Android
 * platform.
 *
 * Thus we instruct layoutlib_create to modify the bytecode of these classes to replace their
 * native methods by "delegate calls".
 *
 * For example, a native method android.graphics.Matrix.init(...) will actually become
 * a call to android.graphics.Matrix_Delegate.init(...).
 *
 * The Android java classes that use native code uses an int (Java side) to reference native
 * objects. This int is generally directly the pointer to the C structure counterpart.
 * Typically a creation method will return such an int, and then this int will be passed later
 * to a Java method to identify the C object to manipulate.
 *
 * Since we cannot use the Java object reference as the int directly, DelegateManager manages the
 * int -> Delegate class link.
 *
 * Native methods usually always have the int as parameters. The first thing the delegate method
 * will do is call {@link #getDelegate(int)} to get the Java object matching the int.
 *
 * Typical native init methods are returning a new int back to the Java class, so
 * {@link #addDelegate(Object)} does the same.
 *
 * @param <T> the delegate class to manage
 */
public final class DelegateManager<T> {

    private final SparseArray<T> mDelegates = new SparseArray<T>();
    private int mDelegateCounter = 0;

    /**
     * Returns the delegate from the given native int.
     * <p>
     * If the int is zero, then this will always return null.
     * <p>
     * If the int is non zero and the delegate is not found, this will throw an assert.
     *
     * @param native_object the native int.
     * @return the delegate or null if not found.
     */
    public T getDelegate(int native_object) {
        if (native_object > 0) {
            T delegate =  mDelegates.get(native_object);
            assert delegate != null;
            return delegate;
        }
        return null;
    }

    /**
     * Adds a delegate to the manager and returns the native int used to identify it.
     * @param newDelegate the delegate to add
     * @return a unique native int to identify the delegate
     */
    public int addDelegate(T newDelegate) {
        int native_object = ++mDelegateCounter;
        mDelegates.put(native_object, newDelegate);
        return native_object;
    }

    /**
     * Removes the delegate matching the given native int.
     * @param native_object the native int.
     */
    public void removeDelegate(int native_object) {
        mDelegates.remove(native_object);
    }
}
