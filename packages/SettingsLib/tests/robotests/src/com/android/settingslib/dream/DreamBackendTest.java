/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.settingslib.dream;


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowSettings.ShadowSecure.class})
public final class DreamBackendTest {
    private static final int[] SUPPORTED_DREAM_COMPLICATIONS = {1, 2, 3};
    private static final List<Integer> SUPPORTED_DREAM_COMPLICATIONS_LIST = Arrays.stream(
            SUPPORTED_DREAM_COMPLICATIONS).boxed().collect(
            Collectors.toList());

    @Mock
    private Context mContext;
    @Mock
    private ContentResolver mMockResolver;
    private DreamBackend mBackend;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getApplicationContext()).thenReturn(mContext);
        when(mContext.getContentResolver()).thenReturn(mMockResolver);

        final Resources res = mock(Resources.class);
        when(mContext.getResources()).thenReturn(res);
        when(res.getIntArray(
                com.android.internal.R.array.config_supportedDreamComplications)).thenReturn(
                SUPPORTED_DREAM_COMPLICATIONS);
        when(res.getStringArray(
                com.android.internal.R.array.config_disabledDreamComponents)).thenReturn(
                new String[]{});
        mBackend = new DreamBackend(mContext);
    }

    @After
    public void tearDown() {
        ShadowSettings.ShadowSecure.reset();
    }

    @Test
    public void testComplicationsEnabledByDefault() {
        assertThat(mBackend.getComplicationsEnabled()).isTrue();
        assertThat(mBackend.getEnabledComplications()).containsExactlyElementsIn(
                SUPPORTED_DREAM_COMPLICATIONS_LIST);
    }

    @Test
    public void testEnableComplicationExplicitly() {
        mBackend.setComplicationsEnabled(true);
        assertThat(mBackend.getEnabledComplications()).containsExactlyElementsIn(
                SUPPORTED_DREAM_COMPLICATIONS_LIST);
        assertThat(mBackend.getComplicationsEnabled()).isTrue();
    }

    @Test
    public void testDisableComplications() {
        mBackend.setComplicationsEnabled(false);
        assertThat(mBackend.getEnabledComplications()).isEmpty();
        assertThat(mBackend.getComplicationsEnabled()).isFalse();
    }
}

