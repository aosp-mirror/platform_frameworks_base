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

import android.widget.layout.table.VerticalGravity;
import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;
import android.test.ViewAsserts;
import android.view.View;

/**
 * {@link android.widget.layout.table.VerticalGravity} is
 * setup to exercise tables in which cells use vertical gravity.
 */
public class VerticalGravityTest extends ActivityInstrumentationTestCase<VerticalGravity> {
    private View mReference1;
    private View mReference2;
    private View mReference3;
    private View mTop;
    private View mCenter;
    private View mBottom;

    public VerticalGravityTest() {
        super("com.android.frameworks.coretests", VerticalGravity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final VerticalGravity activity = getActivity();
        mReference1 = activity.findViewById(R.id.reference1);
        mReference2 = activity.findViewById(R.id.reference2);
        mReference3 = activity.findViewById(R.id.reference3);
        mTop        = activity.findViewById(R.id.cell_top);
        mCenter     = activity.findViewById(R.id.cell_center);
        mBottom     = activity.findViewById(R.id.cell_bottom);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mReference1);
        assertNotNull(mReference2);
        assertNotNull(mReference3);
        assertNotNull(mTop);
        assertNotNull(mCenter);
        assertNotNull(mBottom);
    }

    @MediumTest
    public void testTopGravity() throws Exception {
        ViewAsserts.assertTopAligned(mReference1, mTop);
    }

    @MediumTest
    public void testCenterGravity() throws Exception {
        ViewAsserts.assertVerticalCenterAligned(mReference2, mCenter);
    }

    @Suppress
    @MediumTest
    public void testBottomGravity() throws Exception {
        ViewAsserts.assertBottomAligned(mReference3, mBottom);
    }
}
