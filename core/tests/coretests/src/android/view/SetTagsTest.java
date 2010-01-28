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

import com.android.frameworks.coretests.R;
import android.test.suitebuilder.annotation.MediumTest;

import android.test.ActivityInstrumentationTestCase2;
import android.widget.Button;

/**
 * Exercises {@link android.view.View}'s tags property.
 */
public class SetTagsTest extends ActivityInstrumentationTestCase2<Disabled> {
    private Button mView;

    public SetTagsTest() {
        super("com.android.frameworks.coretests", Disabled.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mView = (Button) getActivity().findViewById(R.id.disabledButton);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mView);
    }

    @MediumTest
    public void testSetTag() throws Exception {
        mView.setTag("1");
    }

    @MediumTest
    public void testGetTag() throws Exception {
        Object o = new Object();
        mView.setTag(o);

        final Object stored = mView.getTag();
        assertNotNull(stored);
        assertSame("The stored tag is inccorect", o, stored);
    }

    @MediumTest
    public void testSetTagWithKey() throws Exception {
        mView.setTag(R.id.a, "2");
    }

    @MediumTest
    public void testGetTagWithKey() throws Exception {
        Object o = new Object();
        mView.setTag(R.id.a, o);

        final Object stored = mView.getTag(R.id.a);
        assertNotNull(stored);
        assertSame("The stored tag is inccorect", o, stored);
    }

    @MediumTest
    public void testSetTagWithFrameworkId() throws Exception {
        boolean result = false;
        try {
            mView.setTag(android.R.id.list, "2");
        } catch (IllegalArgumentException e) {
            result = true;
        }
        assertTrue("Setting a tag with a framework id did not throw an exception", result);
    }

    @MediumTest
    public void testSetTagWithNoPackageId() throws Exception {
        boolean result = false;
        try {
            mView.setTag(0x000000AA, "2");
        } catch (IllegalArgumentException e) {
            result = true;
        }
        assertTrue("Setting a tag with an id with no package did not throw an exception", result);
    }

    @MediumTest
    public void testSetTagInternalWithFrameworkId() throws Exception {
        mView.setTagInternal(android.R.id.list, "2");
    }

    @MediumTest
    public void testSetTagInternalWithApplicationId() throws Exception {
        boolean result = false;
        try {
            mView.setTagInternal(R.id.a, "2");
        } catch (IllegalArgumentException e) {
            result = true;
        }
        assertTrue("Setting a tag with an id with app package did not throw an exception", result);
    }
}
