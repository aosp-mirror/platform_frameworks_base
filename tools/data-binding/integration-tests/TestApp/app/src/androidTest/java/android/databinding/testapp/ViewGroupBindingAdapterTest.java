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

import android.databinding.testapp.databinding.ViewGroupAdapterTestBinding;
import android.databinding.testapp.vo.ViewGroupBindingObject;

import android.os.Build;
import android.view.ViewGroup;

public class ViewGroupBindingAdapterTest
        extends BindingAdapterTestBase<ViewGroupAdapterTestBinding, ViewGroupBindingObject> {

    ViewGroup mView;

    public ViewGroupBindingAdapterTest() {
        super(ViewGroupAdapterTestBinding.class, ViewGroupBindingObject.class,
                R.layout.view_group_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mView = mBinder.view;
    }

    public void testDrawnWithCache() throws Throwable {
        assertEquals(mBindingObject.isAlwaysDrawnWithCache(),
                mView.isAlwaysDrawnWithCacheEnabled());

        changeValues();

        assertEquals(mBindingObject.isAlwaysDrawnWithCache(),
                mView.isAlwaysDrawnWithCacheEnabled());
    }

    public void testAnimationCache() throws Throwable {
        assertEquals(mBindingObject.isAnimationCache(), mView.isAnimationCacheEnabled());

        changeValues();

        assertEquals(mBindingObject.isAnimationCache(), mView.isAnimationCacheEnabled());
    }

    public void testSplitMotionEvents() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            assertEquals(mBindingObject.isSplitMotionEvents(),
                    mView.isMotionEventSplittingEnabled());

            changeValues();

            assertEquals(mBindingObject.isSplitMotionEvents(),
                    mView.isMotionEventSplittingEnabled());
        }
    }

    public void testAnimateLayoutChanges() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            assertEquals(mBindingObject.isAnimateLayoutChanges(),
                    mView.getLayoutTransition() != null);

            changeValues();

            assertEquals(mBindingObject.isAnimateLayoutChanges(),
                    mView.getLayoutTransition() != null);
        }
    }
}
