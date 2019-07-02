/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.view;

import android.test.ActivityInstrumentationTestCase;
import android.test.TouchUtils;
import android.util.KeyUtils;
import android.view.View.OnLongClickListener;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

/**
 * Exercises {@link android.view.View}'s longpress plumbing by testing the
 * disabled case.
 */
public class DisabledLongpressTest extends ActivityInstrumentationTestCase<Longpress> {
    private View mSimpleView;
    private boolean mLongClicked;

    public DisabledLongpressTest() {
        super("com.android.frameworks.coretests", Longpress.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final Longpress a = getActivity();
        mSimpleView = a.findViewById(R.id.simple_view);
        mSimpleView.setOnLongClickListener(new OnLongClickListener() {
            public boolean onLongClick(View v) {
                mLongClicked = true;
                return true;
            }
        });
        // The View#setOnLongClickListener will ensure the View is long
        // clickable, we reverse that here
        mSimpleView.setLongClickable(false);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        mLongClicked = false;
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mSimpleView);
        assertFalse(mLongClicked);
    }

    @LargeTest
    public void testKeypadLongClick() throws Exception {
        getActivity().runOnUiThread(() -> mSimpleView.requestFocus());
        getInstrumentation().waitForIdleSync();
        KeyUtils.longClick(this);

        getInstrumentation().waitForIdleSync();
        assertFalse(mLongClicked);
    }

    @LargeTest
    public void testTouchLongClick() throws Exception {
        TouchUtils.longClickView(this, mSimpleView);
        getInstrumentation().waitForIdleSync();
        assertFalse(mLongClicked);
    }
}
