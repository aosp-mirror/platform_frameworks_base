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
package android.databinding.testapp;

import android.databinding.testapp.databinding.TableLayoutAdapterTestBinding;
import android.databinding.testapp.vo.TableLayoutBindingObject;

import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.widget.TableLayout;

public class TableLayoutBindingAdapterTest
        extends BindingAdapterTestBase<TableLayoutAdapterTestBinding, TableLayoutBindingObject> {

    TableLayout mView;

    public TableLayoutBindingAdapterTest() {
        super(TableLayoutAdapterTestBinding.class, TableLayoutBindingObject.class,
                R.layout.table_layout_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mView = mBinder.view;
    }

    public void testDivider() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            assertEquals(mBindingObject.getDivider(),
                    ((ColorDrawable) mView.getDividerDrawable()).getColor());
            changeValues();
            assertEquals(mBindingObject.getDivider(),
                    ((ColorDrawable) mView.getDividerDrawable()).getColor());
        }
    }

    public void testColumns() throws Throwable {
        assertFalse(mView.isColumnCollapsed(0));
        assertTrue(mView.isColumnCollapsed(1));
        assertFalse(mView.isColumnCollapsed(2));

        assertFalse(mView.isColumnShrinkable(0));
        assertTrue(mView.isColumnShrinkable(1));
        assertFalse(mView.isColumnShrinkable(2));

        assertFalse(mView.isColumnStretchable(0));
        assertTrue(mView.isColumnStretchable(1));
        assertFalse(mView.isColumnStretchable(2));

        changeValues();

        assertFalse(mView.isColumnCollapsed(0));
        assertFalse(mView.isColumnCollapsed(1));
        assertFalse(mView.isColumnCollapsed(2));

        assertTrue(mView.isColumnShrinkable(0));
        assertTrue(mView.isColumnShrinkable(1));
        assertFalse(mView.isColumnShrinkable(2));

        assertTrue(mView.isColumnStretchable(0));
        assertTrue(mView.isColumnStretchable(1));
        assertTrue(mView.isColumnStretchable(2));
    }
}
