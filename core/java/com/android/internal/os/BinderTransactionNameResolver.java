/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.os;

import android.os.Binder;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;

/**
 * Maps a binder class and transaction code to the default transaction name.  Since this
 * resolution is class-based as opposed to instance-based, any custom implementation of
 * {@link Binder#getTransactionName} will be ignored.
 *
 * The class is NOT thread safe
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class BinderTransactionNameResolver {
    private static final Method NO_GET_DEFAULT_TRANSACTION_NAME_METHOD;

    /**
     * Generates the default transaction method name, which is just the transaction code.
     * Used when the binder does not define a static "getDefaultTransactionName" method.
     *
     * @hide
     */
    public static String noDefaultTransactionName(int transactionCode) {
        return String.valueOf(transactionCode);
    }

    static {
        try {
            NO_GET_DEFAULT_TRANSACTION_NAME_METHOD = BinderTransactionNameResolver.class.getMethod(
                    "noDefaultTransactionName", int.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private final HashMap<Class<? extends Binder>, Method>
            mGetDefaultTransactionNameMethods = new HashMap<>();

    /**
     * Given a binder class name and transaction code, returns the corresponding method name.
     *
     * @hide
     */
    public String getMethodName(Class<? extends Binder> binderClass, int transactionCode) {
        Method method = mGetDefaultTransactionNameMethods.get(binderClass);
        if (method == null) {
            try {
                method = binderClass.getMethod("getDefaultTransactionName", int.class);
            } catch (NoSuchMethodException e) {
                method = NO_GET_DEFAULT_TRANSACTION_NAME_METHOD;
            }
            if (method.getReturnType() != String.class
                    || !Modifier.isStatic(method.getModifiers())) {
                method = NO_GET_DEFAULT_TRANSACTION_NAME_METHOD;
            }
            mGetDefaultTransactionNameMethods.put(binderClass, method);
        }

        try {
            return (String) method.invoke(null, transactionCode);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
