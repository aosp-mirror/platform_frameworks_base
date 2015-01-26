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

import com.android.databinding.testapp.generated.BasicDependantBindingBinder;
import com.android.databinding.testapp.vo.NotBindableVo;

import android.test.UiThreadTest;

public class BasicDependantBindingTest extends BaseDataBinderTest<BasicDependantBindingBinder> {

    public BasicDependantBindingTest() {
        super(BasicDependantBindingBinder.class, R.layout.basic_dependant_binding);
    }

    public void testFull() {
        testWith(new NotBindableVo("a"), new NotBindableVo("b"));
    }

    private void testWith(NotBindableVo obj1, NotBindableVo obj2) {
        mBinder.setObj1(obj1);
        mBinder.setObj2(obj2);
        mBinder.rebindDirty();
        assertValues(safeGet(obj1), safeGet(obj2),
                obj1 == null ? "" : obj1.mergeStringFields(obj2),
                obj2 == null ? "" : obj2.mergeStringFields(obj1),
                safeGet(obj1) + safeGet(obj2)
        );
    }

    private String safeGet(NotBindableVo vo) {
        if (vo == null || vo.getStringValue() == null) {
            return "";
        }
        return vo.getStringValue();
    }

    private void assertValues(String textView1, String textView2,
            String mergedView1, String mergedView2, String rawMerge) {
        assertEquals(textView1, mBinder.getTextView1().getText().toString());
        assertEquals(textView2, mBinder.getTextView2().getText().toString());
        assertEquals(mergedView1, mBinder.getMergedTextView1().getText().toString());
        assertEquals(mergedView2, mBinder.getMergedTextView2().getText().toString());
        assertEquals(rawMerge, mBinder.getRawStringMerge().getText().toString());
    }
}
