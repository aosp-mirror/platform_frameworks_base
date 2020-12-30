/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
public class RestrictedTopLevelPreferenceTest {

    private Context mContext;
    private RestrictedTopLevelPreference mPreference;
    private RestrictedPreferenceHelper mHelper;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mPreference = spy(new RestrictedTopLevelPreference(mContext));
        mHelper = spy(new RestrictedPreferenceHelper(mContext, mPreference, null));
        ReflectionHelpers.setField(mPreference, "mHelper", mHelper);
    }

    @Test
    public void setEnabled_disabledByAdmin_shouldCallSetDisabledByAdmin() {
        when(mHelper.isDisabledByAdmin()).thenReturn(true);

        mPreference.setEnabled(true);

        verify(mHelper).setDisabledByAdmin(any());
    }

    @Test
    public void setEnabled_notDisabledByAdmin_shouldNotCallSetDisabledByAdmin() {
        when(mHelper.isDisabledByAdmin()).thenReturn(false);

        mPreference.setEnabled(true);

        verify(mHelper, never()).setDisabledByAdmin(any());
    }

    @Test
    public void setDisabledByAdmin_shouldNotCallSetEnabled() {
        mPreference.setDisabledByAdmin(new RestrictedLockUtils.EnforcedAdmin());

        verify(mPreference, never()).setEnabled(anyBoolean());
    }
}
