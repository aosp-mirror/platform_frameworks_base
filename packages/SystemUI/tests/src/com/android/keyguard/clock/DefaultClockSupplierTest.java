/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ClockPlugin;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public final class DefaultClockSupplierTest extends SysuiTestCase {

    private static final String BUBBLE_CLOCK = BubbleClockController.class.getName();
    private static final Class<?> BUBBLE_CLOCK_CLASS = BubbleClockController.class;

    private DefaultClockSupplier mDefaultClockSupplier;
    @Mock SettingsWrapper mMockSettingsWrapper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDefaultClockSupplier = new DefaultClockSupplier(mMockSettingsWrapper,
                LayoutInflater.from(getContext()));
    }

    @Test
    public void get_default() {
        // GIVEN that settings doesn't contain any values
        when(mMockSettingsWrapper.getLockScreenCustomClockFace()).thenReturn(null);
        when(mMockSettingsWrapper.getDockedClockFace()).thenReturn(null);
        // WHEN get is called
        ClockPlugin plugin = mDefaultClockSupplier.get();
        // THEN the result is null, indicated the default clock face should be used.
        assertThat(plugin).isNull();
    }

    @Test
    public void get_customClock() {
        // GIVEN that settings is set to the bubble clock face
        when(mMockSettingsWrapper.getLockScreenCustomClockFace()).thenReturn(BUBBLE_CLOCK);
        // WHEN get is called
        ClockPlugin plugin = mDefaultClockSupplier.get();
        // THEN the plugin is the bubble clock face.
        assertThat(plugin).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    @Test
    public void get_badSettingsValue() {
        // GIVEN that settings contains a value that doesn't correspond to a
        // custom clock face.
        when(mMockSettingsWrapper.getLockScreenCustomClockFace()).thenReturn("bad value");
        // WHEN get is called
        ClockPlugin plugin = mDefaultClockSupplier.get();
        // THEN the result is null.
        assertThat(plugin).isNull();
    }

    @Test
    public void get_dockedDefault() {
        // GIVEN docked
        mDefaultClockSupplier.setDocked(true);
        // WHEN get is called
        ClockPlugin plugin = mDefaultClockSupplier.get();
        // THEN the result is null, indicating the default clock face.
        assertThat(plugin).isNull();
    }

    @Test
    public void get_dockedCustomClock() {
        // GIVEN docked and settings is set to the bubble clock face
        mDefaultClockSupplier.setDocked(true);
        when(mMockSettingsWrapper.getDockedClockFace()).thenReturn(BUBBLE_CLOCK);
        // WHEN get is called
        ClockPlugin plugin = mDefaultClockSupplier.get();
        // THEN the plugin is the bubble clock face.
        assertThat(plugin).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    @Test
    public void get_badDockedSettingsValue() {
        // GIVEN docked and settings contains a value that doesn't correspond to
        // an available clock face.
        mDefaultClockSupplier.setDocked(true);
        when(mMockSettingsWrapper.getDockedClockFace()).thenReturn("bad value");
        // WHEN get is called
        ClockPlugin plugin = mDefaultClockSupplier.get();
        // THEN the result is null.
        assertThat(plugin).isNull();
    }

    @Test
    public void get_badDockedSettingsFallback() {
        // GIVEN docked and settings contains a value that doesn't correspond to
        // an available clock face, but locked screen settings is set to bubble
        // clock.
        mDefaultClockSupplier.setDocked(true);
        when(mMockSettingsWrapper.getDockedClockFace()).thenReturn("bad value");
        when(mMockSettingsWrapper.getLockScreenCustomClockFace()).thenReturn(BUBBLE_CLOCK);
        // WHEN get is called
        ClockPlugin plugin = mDefaultClockSupplier.get();
        // THEN the plugin is the bubble clock face.
        assertThat(plugin).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }
}
