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

import android.databinding.testapp.generated.ViewStubBinding;
import android.databinding.testapp.generated.ViewStubContentsBinding;
import android.databinding.ViewStubProxy;
import android.support.v4.util.ArrayMap;
import android.test.UiThreadTest;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;

public class ViewStubTest extends BaseDataBinderTest<ViewStubBinding> {

    public ViewStubTest() {
        super(ViewStubBinding.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mBinder.setViewStubVisibility(View.GONE);
        mBinder.setFirstName("Hello");
        mBinder.setLastName("World");
        try {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBinder.executePendingBindings();
                }
            });
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }

    @UiThreadTest
    public void testInflation() throws Throwable {
        ViewStubProxy viewStubProxy = mBinder.viewStub;
        assertFalse(viewStubProxy.isInflated());
        assertNull(viewStubProxy.getBinding());
        assertNotNull(viewStubProxy.getViewStub());
        assertNull(mBinder.getRoot().findViewById(R.id.firstNameContents));
        assertNull(mBinder.getRoot().findViewById(R.id.lastNameContents));
        mBinder.setViewStubVisibility(View.VISIBLE);
        mBinder.executePendingBindings();
        assertTrue(viewStubProxy.isInflated());
        assertNotNull(viewStubProxy.getBinding());
        assertNull(viewStubProxy.getViewStub());
        ViewStubContentsBinding contentsBinding = (ViewStubContentsBinding)
                viewStubProxy.getBinding();
        assertNotNull(contentsBinding.firstNameContents);
        assertNotNull(contentsBinding.lastNameContents);
        assertEquals("Hello", contentsBinding.firstNameContents.getText().toString());
        assertEquals("World", contentsBinding.lastNameContents.getText().toString());
    }

    @UiThreadTest
    public void testChangeValues() throws Throwable {
        ViewStubProxy viewStubProxy = mBinder.viewStub;
        mBinder.setViewStubVisibility(View.VISIBLE);
        mBinder.executePendingBindings();
        ViewStubContentsBinding contentsBinding = (ViewStubContentsBinding)
                viewStubProxy.getBinding();
        assertEquals("Hello", contentsBinding.firstNameContents.getText().toString());
        mBinder.setFirstName("Goodbye");
        mBinder.executePendingBindings();
        assertEquals("Goodbye", contentsBinding.firstNameContents.getText().toString());
    }
}
