/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.test.ActivityInstrumentationTestCase2;

import androidx.test.filters.MediumTest;

public class MutateDrawableTest extends ActivityInstrumentationTestCase2<MutateDrawable> {
    private View mFirstButton;
    private View mSecondButton;

    public MutateDrawableTest() {
        super("com.android.frameworks.coretests", MutateDrawable.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mFirstButton = getActivity().findViewById(com.android.frameworks.coretests.R.id.a);
        mSecondButton = getActivity().findViewById(com.android.frameworks.coretests.R.id.b);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mFirstButton);
        assertNotNull(mSecondButton);
        assertNotSame(mFirstButton.getBackground(), mSecondButton.getBackground());
    }

    @MediumTest
    public void testDrawableCanMutate() throws Exception {
        assertNotSame(mFirstButton.getBackground().getConstantState(),
                mSecondButton.getBackground().getConstantState());
    }
}
