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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManagerFake;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.util.InjectionInflationController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public final class ClockManagerTest extends SysuiTestCase {

    private static final String BUBBLE_CLOCK = BubbleClockController.class.getName();
    private static final Class<?> BUBBLE_CLOCK_CLASS = BubbleClockController.class;

    private ClockManager mClockManager;
    private ContentObserver mContentObserver;
    private DockManagerFake mFakeDockManager;
    @Mock InjectionInflationController mMockInjectionInflationController;
    @Mock PluginManager mMockPluginManager;
    @Mock SysuiColorExtractor mMockColorExtractor;
    @Mock ContentResolver mMockContentResolver;
    @Mock SettingsWrapper mMockSettingsWrapper;
    @Mock ClockManager.ClockChangedListener mMockListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        LayoutInflater inflater = LayoutInflater.from(getContext());
        when(mMockInjectionInflationController.injectable(any())).thenReturn(inflater);

        mFakeDockManager = new DockManagerFake();
        mClockManager = new ClockManager(getContext(), mMockInjectionInflationController,
                mMockPluginManager, mFakeDockManager, mMockColorExtractor, mMockContentResolver,
                mMockSettingsWrapper);

        mClockManager.addOnClockChangedListener(mMockListener);
        mContentObserver = mClockManager.getContentObserver();
    }

    @After
    public void tearDown() {
        mClockManager.removeOnClockChangedListener(mMockListener);
    }

    @Test
    public void dockEvent() {
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        assertThat(mClockManager.isDocked()).isTrue();
    }

    @Test
    public void undockEvent() {
        mFakeDockManager.setDockEvent(DockManager.STATE_NONE);
        assertThat(mClockManager.isDocked()).isFalse();
    }

    @Test
    public void getCurrentClock_default() {
        // GIVEN that settings doesn't contain any values
        when(mMockSettingsWrapper.getLockScreenCustomClockFace()).thenReturn(null);
        when(mMockSettingsWrapper.getDockedClockFace()).thenReturn(null);
        // WHEN settings change event is fired
        mContentObserver.onChange(false);
        // THEN the result is null, indicated the default clock face should be used.
        assertThat(mClockManager.getCurrentClock()).isNull();
    }

    @Test
    public void getCurrentClock_customClock() {
        // GIVEN that settings is set to the bubble clock face
        when(mMockSettingsWrapper.getLockScreenCustomClockFace()).thenReturn(BUBBLE_CLOCK);
        // WHEN settings change event is fired
        mContentObserver.onChange(false);
        // THEN the plugin is the bubble clock face.
        assertThat(mClockManager.getCurrentClock()).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    @Test
    public void getCurrentClock_badSettingsValue() {
        // GIVEN that settings contains a value that doesn't correspond to a
        // custom clock face.
        when(mMockSettingsWrapper.getLockScreenCustomClockFace()).thenReturn("bad value");
        // WHEN settings change event is fired
        mContentObserver.onChange(false);
        // THEN the result is null.
        assertThat(mClockManager.getCurrentClock()).isNull();
    }

    @Test
    public void getCurrentClock_dockedDefault() {
        // WHEN dock event is fired
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        // THEN the result is null, indicating the default clock face.
        assertThat(mClockManager.getCurrentClock()).isNull();
    }

    @Test
    public void getCurrentClock_dockedCustomClock() {
        // GIVEN settings is set to the bubble clock face
        when(mMockSettingsWrapper.getDockedClockFace()).thenReturn(BUBBLE_CLOCK);
        // WHEN dock event fires
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        // THEN the plugin is the bubble clock face.
        assertThat(mClockManager.getCurrentClock()).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    @Test
    public void getCurrentClock_badDockedSettingsValue() {
        // GIVEN settings contains a value that doesn't correspond to an available clock face.
        when(mMockSettingsWrapper.getDockedClockFace()).thenReturn("bad value");
        // WHEN dock event fires
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        // THEN the result is null.
        assertThat(mClockManager.getCurrentClock()).isNull();
    }

    @Test
    public void getCurrentClock_badDockedSettingsFallback() {
        // GIVEN settings contains a value that doesn't correspond to an available clock face, but
        // locked screen settings is set to bubble clock.
        when(mMockSettingsWrapper.getDockedClockFace()).thenReturn("bad value");
        when(mMockSettingsWrapper.getLockScreenCustomClockFace()).thenReturn(BUBBLE_CLOCK);
        // WHEN dock event is fired
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        // THEN the plugin is the bubble clock face.
        assertThat(mClockManager.getCurrentClock()).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }
}
