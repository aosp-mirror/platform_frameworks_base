/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.SystemClock;
import android.text.format.DateUtils;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
public class UptimePreferenceControllerTest {
    @Mock
    private Context mContext;
    @Mock
    private Lifecycle mLifecycle;
    @Mock
    private PreferenceScreen mScreen;
    @Mock
    private Preference mPreference;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mPreference).when(mScreen)
                .findPreference(AbstractUptimePreferenceController.KEY_UPTIME);
    }

    @Test
    public void testDisplayPreference() {
        final AbstractUptimePreferenceController uptimePreferenceController =
                new ConcreteUptimePreferenceController(mContext, mLifecycle);

        uptimePreferenceController.displayPreference(mScreen);

        // SystemClock is shadowed so it shouldn't advance unexpectedly while the test is running
        verify(mPreference).setSummary(
                DateUtils.formatElapsedTime(SystemClock.elapsedRealtime() / 1000));
    }

    @Test
    public void testUptimeTick() {
        final AbstractUptimePreferenceController uptimePreferenceController =
                new ConcreteUptimePreferenceController(mContext, null /* lifecycle */);

        uptimePreferenceController.displayPreference(mScreen);

        verify(mPreference, times(1)).setSummary(any(CharSequence.class));

        uptimePreferenceController.onStart();

        verify(mPreference, times(2)).setSummary(any(CharSequence.class));

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        verify(mPreference, times(3)).setSummary(any(CharSequence.class));

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        verify(mPreference, times(4)).setSummary(any(CharSequence.class));
    }

    private static class ConcreteUptimePreferenceController
            extends AbstractUptimePreferenceController {
        private ConcreteUptimePreferenceController(Context context,
                Lifecycle lifecycle) {
            super(context, lifecycle);
        }
    }
}
