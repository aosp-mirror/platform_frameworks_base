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

package android.widget;

import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase2;
import android.test.TouchUtils;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Exercises {@link android.widget.RadioGroup}'s check feature.
 */
public class RadioGroupPreCheckedTest extends ActivityInstrumentationTestCase2<RadioGroupActivity> {
    public RadioGroupPreCheckedTest() {
        super(RadioGroupActivity.class);
    }

    @MediumTest
    public void testRadioButtonPreChecked() throws Exception {
        final RadioGroupActivity activity = getActivity();

        RadioButton radio = (RadioButton) activity.findViewById(R.id.value_one);
        assertTrue("The first radio button should be checked", radio.isChecked());

        RadioGroup group = (RadioGroup) activity.findViewById(R.id.group);
        assertEquals("The first radio button should be checked", R.id.value_one,
                group.getCheckedRadioButtonId());
    }
    
    @LargeTest
    public void testRadioButtonChangePreChecked() throws Exception {
        final RadioGroupActivity activity = getActivity();

        RadioButton radio = (RadioButton) activity.findViewById(R.id.value_two);
        TouchUtils.clickView(this, radio);
        
        RadioButton old = (RadioButton) activity.findViewById(R.id.value_one);

        assertFalse("The first radio button should not be checked", old.isChecked());
        assertTrue("The second radio button should be checked", radio.isChecked());

        RadioGroup group = (RadioGroup) activity.findViewById(R.id.group);
        assertEquals("The second radio button should be checked", R.id.value_two,
                group.getCheckedRadioButtonId());
    }
}
