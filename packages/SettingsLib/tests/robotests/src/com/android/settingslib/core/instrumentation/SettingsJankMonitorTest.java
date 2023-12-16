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

package com.android.settingslib.core.instrumentation;

import static com.android.internal.jank.Cuj.CUJ_SETTINGS_TOGGLE;
import static com.android.settingslib.core.instrumentation.SettingsJankMonitor.MONITORED_ANIMATION_DURATION_MS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.view.View;

import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.SwitchPreference;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.jank.Cuj.CujType;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.settingslib.testutils.OverpoweredReflectionHelper;
import com.android.settingslib.testutils.shadow.ShadowInteractionJankMonitor;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowInteractionJankMonitor.class, SettingsJankMonitorTest.ShadowBuilder.class})
public class SettingsJankMonitorTest {
    private static final String TEST_KEY = "key";

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private View mView;

    @Mock
    private RecyclerView mRecyclerView;

    @Mock
    private PreferenceGroupAdapter mPreferenceGroupAdapter;

    @Mock
    private SwitchPreference mSwitchPreference;

    @Mock
    private ScheduledExecutorService mScheduledExecutorService;

    @Before
    public void setUp() {
        ShadowInteractionJankMonitor.reset();
        when(ShadowInteractionJankMonitor.MOCK_INSTANCE.begin(any())).thenReturn(true);
        OverpoweredReflectionHelper
                .setStaticField(SettingsJankMonitor.class,
                        "scheduledExecutorService",
                        mScheduledExecutorService);
    }

    @Test
    public void detectToggleJank() {
        SettingsJankMonitor.detectToggleJank(TEST_KEY, mView);

        verifyToggleJankMonitored();
    }

    @Test
    public void detectSwitchPreferenceClickJank() {
        int adapterPosition = 7;
        when(mRecyclerView.getAdapter()).thenReturn(mPreferenceGroupAdapter);
        when(mPreferenceGroupAdapter.getPreferenceAdapterPosition(mSwitchPreference))
                .thenReturn(adapterPosition);
        when(mRecyclerView.findViewHolderForAdapterPosition(adapterPosition))
                .thenReturn(new RecyclerView.ViewHolder(mView) {
                });
        when(mSwitchPreference.getKey()).thenReturn(TEST_KEY);

        SettingsJankMonitor.detectSwitchPreferenceClickJank(mRecyclerView, mSwitchPreference);

        verifyToggleJankMonitored();
    }

    private void verifyToggleJankMonitored() {
        verify(ShadowInteractionJankMonitor.MOCK_INSTANCE).begin(ShadowBuilder.sBuilder);
        assertThat(ShadowBuilder.sView).isSameInstanceAs(mView);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mScheduledExecutorService).schedule(runnableCaptor.capture(),
                eq(MONITORED_ANIMATION_DURATION_MS), eq(TimeUnit.MILLISECONDS));
        runnableCaptor.getValue().run();
        verify(ShadowInteractionJankMonitor.MOCK_INSTANCE).end(CUJ_SETTINGS_TOGGLE);
    }

    @Implements(InteractionJankMonitor.Configuration.Builder.class)
    static class ShadowBuilder {
        private static InteractionJankMonitor.Configuration.Builder sBuilder;
        private static View sView;

        @Resetter
        public static void reset() {
            sBuilder = null;
            sView = null;
        }

        @Implementation
        public static InteractionJankMonitor.Configuration.Builder withView(
                @CujType int cuj, @NonNull View view) {
            assertThat(cuj).isEqualTo(CUJ_SETTINGS_TOGGLE);
            sView = view;
            sBuilder = mock(InteractionJankMonitor.Configuration.Builder.class);
            when(sBuilder.setTag(TEST_KEY)).thenReturn(sBuilder);
            return sBuilder;
        }
    }
}
