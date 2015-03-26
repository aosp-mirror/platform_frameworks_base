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

import android.databinding.testapp.generated.ObservableFieldTestBinding;
import android.databinding.testapp.vo.ObservableFieldBindingObject;

import android.test.UiThreadTest;
import android.widget.TextView;

public class ObservableFieldTest extends BaseDataBinderTest<ObservableFieldTestBinding> {
    private ObservableFieldBindingObject mObj;

    public ObservableFieldTest() {
        super(ObservableFieldTestBinding.class);
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
                    mBinder.executePendingBindings();
                }
            });
        } catch (Throwable throwable) {
            throw new Exception(throwable);
        }
    }

    @UiThreadTest
    public void testBoolean() {
        TextView view = mBinder.bField;
        assertEquals("false", view.getText());

        mObj.bField.set(true);
        mBinder.executePendingBindings();

        assertEquals("true", view.getText());
    }

    @UiThreadTest
    public void testByte() {
        TextView view = mBinder.tField;
        assertEquals("0", view.getText());

        mObj.tField.set((byte) 1);
        mBinder.executePendingBindings();

        assertEquals("1", view.getText());
    }

    @UiThreadTest
    public void testShort() {
        TextView view = mBinder.sField;
        assertEquals("0", view.getText());

        mObj.sField.set((short) 1);
        mBinder.executePendingBindings();

        assertEquals("1", view.getText());
    }

    @UiThreadTest
    public void testChar() {
        TextView view = mBinder.cField;
        assertEquals("\u0000", view.getText());

        mObj.cField.set('A');
        mBinder.executePendingBindings();

        assertEquals("A", view.getText());
    }

    @UiThreadTest
    public void testInt() {
        TextView view = mBinder.iField;
        assertEquals("0", view.getText());

        mObj.iField.set(1);
        mBinder.executePendingBindings();

        assertEquals("1", view.getText());
    }

    @UiThreadTest
    public void testLong() {
        TextView view = mBinder.lField;
        assertEquals("0", view.getText());

        mObj.lField.set(1);
        mBinder.executePendingBindings();

        assertEquals("1", view.getText());
    }

    @UiThreadTest
    public void testFloat() {
        TextView view = mBinder.fField;
        assertEquals("0.0", view.getText());

        mObj.fField.set(1);
        mBinder.executePendingBindings();

        assertEquals("1.0", view.getText());
    }

    @UiThreadTest
    public void testDouble() {
        TextView view = mBinder.dField;
        assertEquals("0.0", view.getText());

        mObj.dField.set(1);
        mBinder.executePendingBindings();

        assertEquals("1.0", view.getText());
    }

    @UiThreadTest
    public void testObject() {
        TextView view = mBinder.oField;
        assertEquals("Hello", view.getText());

        mObj.oField.set("World");
        mBinder.executePendingBindings();

        assertEquals("World", view.getText());
    }
}
