/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.databinding.testapp;

import com.android.databinding.library.IViewDataBinder;
import com.android.databinding.testapp.vo.BindingAdapterBindingObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class BindingAdapterTestBase<T extends IViewDataBinder, V extends BindingAdapterBindingObject>
        extends BaseDataBinderTest<T> {
    private Class<V> mBindingObjectClass;

    protected V mBindingObject;

    private Method mSetMethod;

    public BindingAdapterTestBase(Class<T> binderClass, Class<V> observableClass, int layoutId) {
        super(binderClass, layoutId);
        mBindingObjectClass = observableClass;
        try {
            mSetMethod = binderClass.getDeclaredMethod("setObj", observableClass);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        try {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        mBindingObject = mBindingObjectClass.newInstance();
                        mSetMethod.invoke(mBinder, mBindingObject);
                        mBinder.rebindDirty();
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    } catch (InvocationTargetException e) {
                        throw new RuntimeException(e);
                    } catch (InstantiationException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        } catch (Throwable throwable) {
            throw new Exception(throwable);
        }
    }

    protected void changeValues() throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBindingObject.changeValues();
                mBinder.rebindDirty();
            }
        });
    }
}
