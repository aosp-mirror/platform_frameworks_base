/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.testapp;

import android.databinding.ViewDataBinding;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

public class BaseDataBinderTest<T extends ViewDataBinding>
        extends ActivityInstrumentationTestCase2<TestActivity> {
    protected Class<T> mBinderClass;
    private int mOrientation;
    protected T mBinder;

    public BaseDataBinderTest(final Class<T> binderClass) {
        this(binderClass, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    public BaseDataBinderTest(final Class<T> binderClass, final int orientation) {
        super(TestActivity.class);
        mBinderClass = binderClass;
        mOrientation = orientation;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        getActivity().setRequestedOrientation(mOrientation);
        createBinder();
    }

    public boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    protected void createBinder() {
        mBinder = null;
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Method method = null;
                try {
                    method = mBinderClass.getMethod("inflate", Context.class);
                    mBinder = (T) method.invoke(null, getActivity());
                    getActivity().setContentView(mBinder.getRoot());
                } catch (Exception e) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    e.printStackTrace(pw);
                    fail("Error creating binder: " + sw.toString());
                }
            }
        });
        if (!isMainThread()) {
            getInstrumentation().waitForIdleSync();
        }
        assertNotNull(mBinder);
    }

    protected void assertMethod(Class<?> klass, String methodName) throws NoSuchMethodException {
        assertEquals(klass, mBinder.getClass().getDeclaredMethod(methodName).getReturnType());
    }

    protected void assertField(Class<?> klass, String fieldName) throws NoSuchFieldException {
        assertEquals(klass, mBinder.getClass().getDeclaredField(fieldName).getType());
    }

    protected void assertNoField(String fieldName) {
        Exception[] ex = new Exception[1];
        try {
            mBinder.getClass().getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            ex[0] = e;
        }
        assertNotNull(ex[0]);
    }
}
