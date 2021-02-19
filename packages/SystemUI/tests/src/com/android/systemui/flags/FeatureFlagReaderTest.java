/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;

import androidx.annotation.BoolRes;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.wrapper.BuildInfo;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class FeatureFlagReaderTest extends SysuiTestCase {
    @Mock private Resources mResources;
    @Mock private BuildInfo mBuildInfo;
    @Mock private SystemPropertiesHelper mSystemPropertiesHelper;

    private FeatureFlagReader mReader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mSystemPropertiesHelper.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        defineFlag(FLAG_RESID_0, false);
        defineFlag(FLAG_RESID_1, true);

        initialize(true, true);
    }

    private void initialize(boolean isDebuggable, boolean isOverrideable) {
        when(mBuildInfo.isDebuggable()).thenReturn(isDebuggable);
        when(mResources.getBoolean(R.bool.are_flags_overrideable)).thenReturn(isOverrideable);
        mReader = new FeatureFlagReader(mResources, mBuildInfo, mSystemPropertiesHelper);
    }

    @Test
    public void testCantOverrideIfNotDebuggable() {
        // GIVEN that the build is not debuggable
        initialize(false, true);

        // GIVEN that a flag has been overridden to true
        overrideFlag(FLAG_RESID_0, true);

        // THEN the flag is still false
        assertFalse(mReader.isEnabled(FLAG_RESID_0));
    }

    @Test
    public void testCantOverrideIfNotOverrideable() {
        // GIVEN that flags are not overrideable
        initialize(true, false);

        // GIVEN that a flag has been overridden to true
        overrideFlag(FLAG_RESID_0, true);

        // THEN the flag is still false
        assertFalse(mReader.isEnabled(FLAG_RESID_0));
    }

    @Test
    public void testReadFlags() {
        assertFalse(mReader.isEnabled(FLAG_RESID_0));
        assertTrue(mReader.isEnabled(FLAG_RESID_1));
    }

    @Test
    public void testOverrideFlags() {
        // GIVEN that flags are overridden
        overrideFlag(FLAG_RESID_0, true);
        overrideFlag(FLAG_RESID_1, false);

        // THEN the reader returns the overridden values
        assertTrue(mReader.isEnabled(FLAG_RESID_0));
        assertFalse(mReader.isEnabled(FLAG_RESID_1));
    }

    @Test
    public void testThatFlagReadsAreCached() {
        // GIVEN that a flag is overridden
        overrideFlag(FLAG_RESID_0, true);

        // WHEN the flag is queried many times
        mReader.isEnabled(FLAG_RESID_0);
        mReader.isEnabled(FLAG_RESID_0);
        mReader.isEnabled(FLAG_RESID_0);
        mReader.isEnabled(FLAG_RESID_0);

        // THEN the underlying resource and override are only queried once
        verify(mResources, times(1)).getBoolean(FLAG_RESID_0);
        verify(mSystemPropertiesHelper, times(1))
                .getBoolean(fakeStorageKey(FLAG_RESID_0), false);
    }

    private void defineFlag(int resId, boolean value) {
        when(mResources.getBoolean(resId)).thenReturn(value);
        when(mResources.getResourceEntryName(resId)).thenReturn(fakeStorageKey(resId));
    }

    private void overrideFlag(int resId, boolean value) {
        when(mSystemPropertiesHelper.getBoolean(eq(fakeStorageKey(resId)), anyBoolean()))
                .thenReturn(value);
    }

    private String fakeStorageKey(@BoolRes int resId) {
        return "persist.systemui.flag_testname_" + resId;
    }

    private static final int FLAG_RESID_0 = 47;
    private static final int FLAG_RESID_1 = 48;
}
