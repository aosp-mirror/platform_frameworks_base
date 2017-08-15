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
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;

import com.android.frameworks.coretests.R;

/**
 * Exercises {@link android.view.View}'s disabled property.
 */
public class DisabledTest extends ActivityInstrumentationTestCase<Disabled> {
    private Button mDisabled;
    private View mDisabledParent;
    private boolean mClicked;
    private boolean mParentClicked;

    public DisabledTest() {
        super("com.android.frameworks.coretests", Disabled.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final Disabled a = getActivity();
        mDisabled = (Button) a.findViewById(R.id.disabledButton);
        mDisabledParent = a.findViewById(R.id.clickableParent);
        getInstrumentation().runOnMainSync(
                new Runnable() {
                    @Override
                    public void run() {
                        mDisabled.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                mClicked = true;
                            }
                        });
                        mDisabledParent.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                mParentClicked = true;
                            }
                        });
                    }
                });
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        mClicked = false;
        mParentClicked = false;
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mDisabled);
        assertNotNull(mDisabledParent);
        assertFalse(mDisabled.isEnabled());
        assertTrue(mDisabledParent.isEnabled());
        assertFalse(mDisabled.hasFocus());
    }

    @MediumTest
    public void testKeypadClick() throws Exception {
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();
        assertFalse(mClicked);
        assertFalse(mParentClicked);
    }

    @LargeTest
    public void testTouchClick() throws Exception {
        TouchUtils.clickView(this, mDisabled);
        getInstrumentation().waitForIdleSync();
        assertFalse(mClicked);
        assertFalse(mParentClicked);
    }
}
