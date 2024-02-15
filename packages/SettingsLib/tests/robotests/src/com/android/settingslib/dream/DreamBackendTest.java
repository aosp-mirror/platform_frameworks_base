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


import static com.android.settingslib.dream.DreamBackend.COMPLICATION_TYPE_DATE;
import static com.android.settingslib.dream.DreamBackend.COMPLICATION_TYPE_HOME_CONTROLS;
import static com.android.settingslib.dream.DreamBackend.COMPLICATION_TYPE_TIME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.provider.Settings;

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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowSettings.ShadowSecure.class})
public final class DreamBackendTest {
    private static final int[] SUPPORTED_DREAM_COMPLICATIONS =
            {COMPLICATION_TYPE_HOME_CONTROLS, COMPLICATION_TYPE_DATE,
                    COMPLICATION_TYPE_TIME};
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
        when(res.getStringArray(
                com.android.internal.R.array.config_loggable_dream_prefixes)).thenReturn(
                new String[]{});
        mBackend = new DreamBackend(mContext);
    }

    @After
    public void tearDown() {
        ShadowSettings.ShadowSecure.reset();
    }

    @Test
    public void testComplicationsEnabledByDefault() {
        setControlsEnabledOnLockscreen(true);
        assertThat(mBackend.getComplicationsEnabled()).isTrue();
        assertThat(mBackend.getEnabledComplications()).containsExactlyElementsIn(
                SUPPORTED_DREAM_COMPLICATIONS_LIST);
    }

    @Test
    public void testEnableComplicationExplicitly() {
        setControlsEnabledOnLockscreen(true);
        mBackend.setComplicationsEnabled(true);
        assertThat(mBackend.getEnabledComplications()).containsExactlyElementsIn(
                SUPPORTED_DREAM_COMPLICATIONS_LIST);
        assertThat(mBackend.getComplicationsEnabled()).isTrue();
    }

    @Test
    public void testDisableComplications() {
        setControlsEnabledOnLockscreen(true);
        mBackend.setComplicationsEnabled(false);
        assertThat(mBackend.getEnabledComplications())
                .containsExactly(COMPLICATION_TYPE_HOME_CONTROLS);
        assertThat(mBackend.getComplicationsEnabled()).isFalse();
    }

    @Test
    public void testHomeControlsDisabled_ComplicationsEnabled() {
        setControlsEnabledOnLockscreen(true);
        mBackend.setComplicationsEnabled(true);
        mBackend.setHomeControlsEnabled(false);
        // Home controls should not be enabled, only date and time.
        final List<Integer> enabledComplications =
                Arrays.asList(COMPLICATION_TYPE_DATE, COMPLICATION_TYPE_TIME);
        assertThat(mBackend.getEnabledComplications())
                .containsExactlyElementsIn(enabledComplications);
    }

    @Test
    public void testHomeControlsDisabled_ComplicationsDisabled() {
        setControlsEnabledOnLockscreen(true);
        mBackend.setComplicationsEnabled(false);
        mBackend.setHomeControlsEnabled(false);
        assertThat(mBackend.getEnabledComplications()).isEmpty();
    }

    @Test
    public void testHomeControlsEnabled_ComplicationsDisabled() {
        setControlsEnabledOnLockscreen(true);
        mBackend.setComplicationsEnabled(false);
        mBackend.setHomeControlsEnabled(true);
        final List<Integer> enabledComplications =
                Collections.singletonList(COMPLICATION_TYPE_HOME_CONTROLS);
        assertThat(mBackend.getEnabledComplications())
                .containsExactlyElementsIn(enabledComplications);
    }

    @Test
    public void testHomeControlsEnabled_ComplicationsEnabled() {
        setControlsEnabledOnLockscreen(true);
        mBackend.setComplicationsEnabled(true);
        mBackend.setHomeControlsEnabled(true);
        final List<Integer> enabledComplications =
                Arrays.asList(
                        COMPLICATION_TYPE_HOME_CONTROLS,
                        COMPLICATION_TYPE_DATE,
                        COMPLICATION_TYPE_TIME
                );
        assertThat(mBackend.getEnabledComplications())
                .containsExactlyElementsIn(enabledComplications);
    }

    @Test
    public void testHomeControlsEnabled_lockscreenDisabled() {
        setControlsEnabledOnLockscreen(false);
        mBackend.setComplicationsEnabled(true);
        mBackend.setHomeControlsEnabled(true);
        // Home controls should not be enabled, only date and time.
        final List<Integer> enabledComplications =
                Arrays.asList(
                        COMPLICATION_TYPE_DATE,
                        COMPLICATION_TYPE_TIME
                );
        assertThat(mBackend.getEnabledComplications())
                .containsExactlyElementsIn(enabledComplications);
    }

    private void setControlsEnabledOnLockscreen(boolean enabled) {
        Settings.Secure.putInt(
                mContext.getContentResolver(),
                Settings.Secure.LOCKSCREEN_SHOW_CONTROLS,
                enabled ? 1 : 0);
    }
}
