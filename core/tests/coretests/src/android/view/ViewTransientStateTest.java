/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.app.Activity;
import android.test.ActivityInstrumentationTestCase;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

/**
 * Exercise set View's transient state
 */
public class ViewTransientStateTest extends ActivityInstrumentationTestCase<ViewTransientState> {

    View mP1;
    View mP2;
    View mP3;

    public ViewTransientStateTest() {
        super("com.android.frameworks.coretests", ViewTransientState.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final Activity a = getActivity();
        mP1 = a.findViewById(R.id.p1);
        mP2 = a.findViewById(R.id.p2);
        mP3 = a.findViewById(R.id.p3);
    }

    @UiThreadTest
    @MediumTest
    public void testSetTransientState1() throws Exception {
        mP3.setHasTransientState(true);
        mP2.setHasTransientState(true);
        mP3.setHasTransientState(false);
        mP2.setHasTransientState(false);
        assertFalse(mP3.hasTransientState());
        assertFalse(mP2.hasTransientState());
        assertFalse(mP1.hasTransientState());
    }

    @UiThreadTest
    @MediumTest
    public void testSetTransientState2() throws Exception {
        mP3.setHasTransientState(true);
        mP2.setHasTransientState(true);
        mP2.setHasTransientState(false);
        mP3.setHasTransientState(false);
        assertFalse(mP3.hasTransientState());
        assertFalse(mP2.hasTransientState());
        assertFalse(mP1.hasTransientState());
    }

    @UiThreadTest
    @MediumTest
    public void testSetTransientState3() throws Exception {
        mP2.setHasTransientState(true);
        mP3.setHasTransientState(true);
        mP3.setHasTransientState(false);
        mP2.setHasTransientState(false);
        assertFalse(mP3.hasTransientState());
        assertFalse(mP2.hasTransientState());
        assertFalse(mP1.hasTransientState());
    }

    @UiThreadTest
    @MediumTest
    public void testSetTransientState4() throws Exception {
        mP2.setHasTransientState(true);
        mP3.setHasTransientState(true);
        mP2.setHasTransientState(false);
        mP3.setHasTransientState(false);
        assertFalse(mP3.hasTransientState());
        assertFalse(mP2.hasTransientState());
        assertFalse(mP1.hasTransientState());
    }
}
