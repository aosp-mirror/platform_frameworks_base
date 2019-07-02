/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;
import android.widget.EditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
public class CustomEditTextPreferenceComaptTest {

    @Mock
    private View mView;

    private TestPreference mPreference;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mPreference = new TestPreference(RuntimeEnvironment.application);
    }

    @Test
    public void bindDialogView_shouldRequestFocus() {
        final String testText = "";
        final EditText editText = spy(new EditText(RuntimeEnvironment.application));
        editText.setText(testText);
        when(mView.findViewById(android.R.id.edit)).thenReturn(editText);

        mPreference.onBindDialogView(mView);

        verify(editText).requestFocus();
    }

    @Test
    public void getEditText_noDialog_shouldNotCrash() {
        ReflectionHelpers.setField(mPreference, "mFragment",
                mock(CustomEditTextPreferenceCompat.CustomPreferenceDialogFragment.class));

        mPreference.getEditText();

        // no crash
    }

    private static class TestPreference extends CustomEditTextPreferenceCompat {
        private TestPreference(Context context) {
            super(context);
        }
    }
}
