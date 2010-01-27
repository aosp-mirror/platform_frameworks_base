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

import android.view.Include;
import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.View;
import android.view.ViewGroup;

public class IncludeTest extends ActivityInstrumentationTestCase<Include> {
    public IncludeTest() {
        super("com.android.frameworks.coretests", Include.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    @MediumTest
    public void testIncluded() throws Exception {
        final Include activity = getActivity();

        final View button1 = activity.findViewById(R.id.included_button);
        assertNotNull("The layout include_button was not included", button1);

        final View button2 = activity.findViewById(R.id.included_button_overriden);
        assertNotNull("The layout include_button was not included with overriden id", button2);
    }

    @MediumTest
    public void testIncludedWithLayoutParams() throws Exception {
        final Include activity = getActivity();

        final View button1 = activity.findViewById(R.id.included_button);
        final View button2 = activity.findViewById(R.id.included_button_overriden);

        assertTrue("Both buttons should have different width",
                button1.getLayoutParams().width != button2.getLayoutParams().width);
        assertTrue("Both buttons should have different height",
                button1.getLayoutParams().height != button2.getLayoutParams().height);
    }

    @MediumTest
    public void testIncludedWithVisibility() throws Exception {
        final Include activity = getActivity();
        final View button1 = activity.findViewById(R.id.included_button_visibility);

        assertEquals("Included button should be invisible", View.INVISIBLE, button1.getVisibility());
    }

    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @MediumTest
    public void testIncludedWithSize() throws Exception {
        final Include activity = getActivity();
        final View button1 = activity.findViewById(R.id.included_button_with_size);

        final ViewGroup.LayoutParams lp = button1.getLayoutParams();
        assertEquals("Included button should be 23dip x 23dip", 23, lp.width);
        assertEquals("Included button should be 23dip x 23dip", 23, lp.height);
    }
}
