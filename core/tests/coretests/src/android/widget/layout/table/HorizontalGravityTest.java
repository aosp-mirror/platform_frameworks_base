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

package android.widget.layout.table;

import android.test.ActivityInstrumentationTestCase;
import android.test.ViewAsserts;
import android.view.View;

import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

/**
 * {@link android.widget.layout.table.HorizontalGravity} is
 * setup to exercise tables in which cells use horizontal gravity.
 */
public class HorizontalGravityTest extends ActivityInstrumentationTestCase<HorizontalGravity> {
    private View mReference;
    private View mCenter;
    private View mBottomRight;
    private View mLeft;

    public HorizontalGravityTest() {
        super("com.android.frameworks.coretests", HorizontalGravity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final HorizontalGravity activity = getActivity();
        mReference   = activity.findViewById(R.id.reference);
        mCenter      = activity.findViewById(R.id.center);
        mBottomRight = activity.findViewById(R.id.bottomRight);
        mLeft        = activity.findViewById(R.id.left);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mReference);
        assertNotNull(mCenter);
        assertNotNull(mBottomRight);
        assertNotNull(mLeft);
    }

    @MediumTest
    public void testCenterGravity() throws Exception {
        ViewAsserts.assertHorizontalCenterAligned(mReference, mCenter);
    }

    @MediumTest
    public void testLeftGravity() throws Exception {
        ViewAsserts.assertLeftAligned(mReference, mLeft);
    }

    @MediumTest
    public void testRightGravity() throws Exception {
        ViewAsserts.assertRightAligned(mReference, mBottomRight);
    }
}
