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

import com.android.frameworks.coretests.R;
import android.widget.layout.linear.HorizontalOrientationVerticalAlignment;

public class BaselineAlignmentSpinnerButton extends ActivityInstrumentationTestCase<HorizontalOrientationVerticalAlignment> {
    private View mSpinner;
    private View mButton;

    public BaselineAlignmentSpinnerButton() {
        super("com.android.frameworks.coretests", HorizontalOrientationVerticalAlignment.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final Activity activity = getActivity();
        mSpinner = activity.findViewById(R.id.reminder_value);
        mButton = activity.findViewById(R.id.reminder_remove);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mSpinner);
        assertNotNull(mButton);
    }

    @MediumTest
    public void testChildrenAligned() throws Exception {
        ViewAsserts.assertBaselineAligned(mSpinner, mButton);
    }
}
