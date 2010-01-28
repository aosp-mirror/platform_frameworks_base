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

package android.widget.layout.linear;

import android.app.Activity;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.ViewAsserts;
import android.view.View;
import android.widget.Button;

import com.android.frameworks.coretests.R;
import android.widget.layout.linear.BaselineAlignmentCenterGravity;

public class BaselineAlignmentCenterGravityTest extends ActivityInstrumentationTestCase<BaselineAlignmentCenterGravity> {
    private Button mButton1;
    private Button mButton2;
    private Button mButton3;

    public BaselineAlignmentCenterGravityTest() {
        super("com.android.frameworks.coretests", BaselineAlignmentCenterGravity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final Activity activity = getActivity();
        mButton1 = (Button) activity.findViewById(R.id.button1);
        mButton2 = (Button) activity.findViewById(R.id.button2);
        mButton3 = (Button) activity.findViewById(R.id.button3);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mButton1);
        assertNotNull(mButton2);
        assertNotNull(mButton3);
    }

    @MediumTest
    public void testChildrenAligned() throws Exception {
        final View parent = (View) mButton1.getParent();
        ViewAsserts.assertTopAligned(mButton1, parent);
        ViewAsserts.assertTopAligned(mButton2, parent);
        ViewAsserts.assertTopAligned(mButton3, parent);
        ViewAsserts.assertBottomAligned(mButton1, parent);
        ViewAsserts.assertBottomAligned(mButton2, parent);
        ViewAsserts.assertBottomAligned(mButton3, parent);
        ViewAsserts.assertTopAligned(mButton1, mButton2);
        ViewAsserts.assertTopAligned(mButton2, mButton3);
        ViewAsserts.assertBottomAligned(mButton1, mButton2);
        ViewAsserts.assertBottomAligned(mButton2, mButton3);
    }
}
