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

import android.databinding.testapp.generated.NoIdTestBinding;

import android.test.UiThreadTest;
import android.widget.LinearLayout;
import android.widget.TextView;

public class NoIdTest extends BaseDataBinderTest<NoIdTestBinding> {
    public NoIdTest() {
        super(NoIdTestBinding.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        try {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBinder.setName("hello");
                    mBinder.setOrientation(LinearLayout.VERTICAL);
                    mBinder.executePendingBindings();
                }
            });
        } catch (Throwable throwable) {
            throw new Exception(throwable);
        }
    }

    @UiThreadTest
    public void testOnRoot() {
        LinearLayout linearLayout = (LinearLayout) mBinder.getRoot();
        assertEquals(LinearLayout.VERTICAL, linearLayout.getOrientation());
        mBinder.setOrientation(LinearLayout.HORIZONTAL);
        mBinder.executePendingBindings();
        assertEquals(LinearLayout.HORIZONTAL, linearLayout.getOrientation());
    }

    @UiThreadTest
    public void testNormal() {
        LinearLayout linearLayout = (LinearLayout) mBinder.getRoot();
        TextView view = (TextView) linearLayout.getChildAt(0);
        assertEquals("hello world", view.getTag());
        assertEquals("hello", view.getText().toString());
        mBinder.setName("world");
        mBinder.executePendingBindings();
        assertEquals("world", view.getText().toString());
    }

    @UiThreadTest
    public void testNoTag() {
        LinearLayout linearLayout = (LinearLayout) mBinder.getRoot();
        TextView view = (TextView) linearLayout.getChildAt(1);
        assertNull(view.getTag());
    }

    @UiThreadTest
    public void testResourceTag() {
        LinearLayout linearLayout = (LinearLayout) mBinder.getRoot();
        TextView view = (TextView) linearLayout.getChildAt(2);
        String expectedValue = view.getResources().getString(R.string.app_name);
        assertEquals(expectedValue, view.getTag());
    }

    @UiThreadTest
    public void testAndroidResourceTag() {
        LinearLayout linearLayout = (LinearLayout) mBinder.getRoot();
        TextView view = (TextView) linearLayout.getChildAt(3);
        String expectedValue = view.getResources().getString(android.R.string.ok);
        assertEquals(expectedValue, view.getTag());
    }

    @UiThreadTest
    public void testIdOnly() {
        assertEquals("hello", mBinder.textView.getText().toString());
    }
}
