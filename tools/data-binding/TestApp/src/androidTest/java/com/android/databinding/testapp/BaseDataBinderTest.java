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

package com.android.databinding.testapp;

import com.android.databinding.library.DataBinder;
import com.android.databinding.library.IViewDataBinder;

import android.content.pm.ActivityInfo;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase2;

public class BaseDataBinderTest<T extends IViewDataBinder>
        extends ActivityInstrumentationTestCase2<TestActivity> {
    private Class<T> mBinderClass;
    private int mLayoutId;
    private int mOrientation;
    protected T mBinder;

    public BaseDataBinderTest(final Class<T> binderClass, final int layoutId) {
        this(binderClass, layoutId, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    public BaseDataBinderTest(final Class<T> binderClass, final int layoutId, final int orientation) {
        super(TestActivity.class);
        mBinderClass = binderClass;
        mLayoutId = layoutId;
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
                mBinder = DataBinder.createBinder(mBinderClass, getActivity(), mLayoutId, null);
                getActivity().setContentView(mBinder.getRoot());
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
