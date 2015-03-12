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

import com.android.databinding.testapp.generated.BracketTestBinder;

import android.test.UiThreadTest;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.widget.LinearLayout;
import android.widget.TextView;

public class BracketTest extends BaseDataBinderTest<BracketTestBinder> {
    private String[] mArray = {
            "Hello World"
    };

    private SparseArray<String> mSparseArray = new SparseArray<>();
    private SparseIntArray mSparseIntArray = new SparseIntArray();
    private SparseBooleanArray mSparseBooleanArray = new SparseBooleanArray();
    private SparseLongArray mSparseLongArray = new SparseLongArray();
    private LongSparseArray<String> mLongSparseArray = new LongSparseArray<>();

    public BracketTest() {
        super(BracketTestBinder.class, R.layout.bracket_test);
        mSparseArray.put(0, "Hello");
        mLongSparseArray.put(0, "World");
        mSparseIntArray.put(0, 100);
        mSparseBooleanArray.put(0, true);
        mSparseLongArray.put(0, 5);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        try {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBinder.setArray(mArray);
                    mBinder.setSparseArray(mSparseArray);
                    mBinder.setSparseIntArray(mSparseIntArray);
                    mBinder.setSparseBooleanArray(mSparseBooleanArray);
                    mBinder.setSparseLongArray(mSparseLongArray);
                    mBinder.setLongSparseArray(mLongSparseArray);

                    mBinder.rebindDirty();
                }
            });
        } catch (Throwable throwable) {
            throw new Exception(throwable);
        }
    }

    @UiThreadTest
    public void testBrackets() {
        assertEquals("Hello World", mBinder.getArrayText().getText().toString());
        assertEquals("Hello", mBinder.getSparseArrayText().getText().toString());
        assertEquals("World", mBinder.getLongSparseArrayText().getText().toString());
        assertEquals("100", mBinder.getSparseIntArrayText().getText().toString());
        assertEquals("true", mBinder.getSparseBooleanArrayText().getText().toString());
        assertEquals("5", mBinder.getSparseLongArrayText().getText().toString());
    }
}
