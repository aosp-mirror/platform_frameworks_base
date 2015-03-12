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

import com.android.databinding.testapp.generated.ObservableFieldTestBinder;
import com.android.databinding.testapp.vo.ObservableFieldBindingObject;

import android.test.UiThreadTest;
import android.widget.TextView;

public class ObservableFieldTest extends BaseDataBinderTest<ObservableFieldTestBinder> {
    private ObservableFieldBindingObject mObj;

    public ObservableFieldTest() {
        super(ObservableFieldTestBinder.class, R.layout.observable_field_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        try {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mObj = new ObservableFieldBindingObject();
                    mBinder.setObj(mObj);
                    mBinder.rebindDirty();
                }
            });
        } catch (Throwable throwable) {
            throw new Exception(throwable);
        }
    }

    @UiThreadTest
    public void testBoolean() {
        TextView view = mBinder.getBField();
        assertEquals("false", view.getText());

        mObj.bField.set(true);
        mBinder.rebindDirty();

        assertEquals("true", view.getText());
    }

    @UiThreadTest
    public void testByte() {
        TextView view = mBinder.getTField();
        assertEquals("0", view.getText());

        mObj.tField.set((byte) 1);
        mBinder.rebindDirty();

        assertEquals("1", view.getText());
    }

    @UiThreadTest
    public void testShort() {
        TextView view = mBinder.getSField();
        assertEquals("0", view.getText());

        mObj.sField.set((short) 1);
        mBinder.rebindDirty();

        assertEquals("1", view.getText());
    }

    @UiThreadTest
    public void testChar() {
        TextView view = mBinder.getCField();
        assertEquals("\u0000", view.getText());

        mObj.cField.set('A');
        mBinder.rebindDirty();

        assertEquals("A", view.getText());
    }

    @UiThreadTest
    public void testInt() {
        TextView view = mBinder.getIField();
        assertEquals("0", view.getText());

        mObj.iField.set(1);
        mBinder.rebindDirty();

        assertEquals("1", view.getText());
    }

    @UiThreadTest
    public void testLong() {
        TextView view = mBinder.getLField();
        assertEquals("0", view.getText());

        mObj.lField.set(1);
        mBinder.rebindDirty();

        assertEquals("1", view.getText());
    }

    @UiThreadTest
    public void testFloat() {
        TextView view = mBinder.getFField();
        assertEquals("0.0", view.getText());

        mObj.fField.set(1);
        mBinder.rebindDirty();

        assertEquals("1.0", view.getText());
    }

    @UiThreadTest
    public void testDouble() {
        TextView view = mBinder.getDField();
        assertEquals("0.0", view.getText());

        mObj.dField.set(1);
        mBinder.rebindDirty();

        assertEquals("1.0", view.getText());
    }

    @UiThreadTest
    public void testObject() {
        TextView view = mBinder.getOField();
        assertEquals("Hello", view.getText());

        mObj.oField.set("World");
        mBinder.rebindDirty();

        assertEquals("World", view.getText());
    }
}
