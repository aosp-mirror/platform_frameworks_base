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

import com.android.frameworks.coretests.R;
import android.widget.layout.linear.BaselineAlignmentZeroWidthAndWeight;
import android.widget.layout.linear.ExceptionTextView;

import android.app.Activity;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.widget.Button;

public class BaselineAlignmentZeroWidthAndWeightTest extends ActivityInstrumentationTestCase<BaselineAlignmentZeroWidthAndWeight> {
    private Button mShowButton;

    public BaselineAlignmentZeroWidthAndWeightTest() {
        super("com.android.frameworks.coretests", BaselineAlignmentZeroWidthAndWeight.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final Activity activity = getActivity();
        mShowButton = (Button) activity.findViewById(R.id.show);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mShowButton);        
    }

    @MediumTest
    public void testComputeTexViewWithoutIllegalArgumentException() throws Exception {
        assertTrue(mShowButton.hasFocus());

        // Pressing the button will show an ExceptionTextView that might set a failed bit if
        // the test fails.
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();

        final ExceptionTextView etv = (ExceptionTextView) getActivity()
                .findViewById(R.id.routeToField);
        assertFalse("exception test view should not fail", etv.isFailed());
    }
}
