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

import android.view.StubbedView;
import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.UiThreadTest;
import android.view.View;
import android.view.ViewStub;

public class ViewStubTest extends ActivityInstrumentationTestCase<StubbedView> {
    public ViewStubTest() {
        super("com.android.frameworks.coretests", StubbedView.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    @MediumTest
    public void testStubbed() throws Exception {
        final StubbedView activity = getActivity();

        final View stub = activity.findViewById(R.id.viewStub);
        assertNotNull("The ViewStub does not exist", stub);
    }

    @UiThreadTest
    @MediumTest
    public void testInflated() throws Exception {
        final StubbedView activity = getActivity();

        final ViewStub stub = (ViewStub) activity.findViewById(R.id.viewStub);
        final View swapped = stub.inflate();

        assertNotNull("The inflated view is null", swapped);
    }

    @UiThreadTest
    @MediumTest
    public void testInflatedId() throws Exception {
        final StubbedView activity = getActivity();

        final ViewStub stub = (ViewStub) activity.findViewById(R.id.viewStubWithId);
        final View swapped = stub.inflate();

        assertNotNull("The inflated view is null", swapped);
        assertTrue("The inflated view has no id", swapped.getId() != View.NO_ID);
        assertTrue("The inflated view has the wrong id", swapped.getId() == R.id.stub_inflated);
    }

    @UiThreadTest
    @MediumTest
    public void testInflatedLayoutParams() throws Exception {
        final StubbedView activity = getActivity();

        final ViewStub stub = (ViewStub) activity.findViewById(R.id.viewStubWithId);
        final View swapped = stub.inflate();

        assertNotNull("The inflated view is null", swapped);

        assertEquals("Both stub and inflated should same width",
                stub.getLayoutParams().width, swapped.getLayoutParams().width);
        assertEquals("Both stub and inflated should same height",
                stub.getLayoutParams().height, swapped.getLayoutParams().height);
    }
}
