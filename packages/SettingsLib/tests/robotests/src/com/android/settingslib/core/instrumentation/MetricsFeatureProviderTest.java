/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Pair;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class MetricsFeatureProviderTest {
    private static int CATEGORY = 10;
    private static boolean SUBTYPE_BOOLEAN = true;
    private static int SUBTYPE_INTEGER = 1;
    private static long ELAPSED_TIME = 1000;

    @Mock private LogWriter mockLogWriter;
    @Mock private VisibilityLoggerMixin mockVisibilityLogger;

    private Context mContext;
    private MetricsFeatureProvider mProvider;

    @Captor
    private ArgumentCaptor<Pair> mPairCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mProvider = new MetricsFeatureProvider();
        List<LogWriter> writers = new ArrayList<>();
        writers.add(mockLogWriter);
        ReflectionHelpers.setField(mProvider, "mLoggerWriters", writers);

        when(mockVisibilityLogger.elapsedTimeSinceVisible()).thenReturn(ELAPSED_TIME);
    }

    @Test
    public void logDashboardStartIntent_intentEmpty_shouldNotLog() {
        mProvider.logDashboardStartIntent(mContext, null /* intent */,
                MetricsEvent.SETTINGS_GESTURES);

        verifyNoMoreInteractions(mockLogWriter);
    }

    @Test
    public void logDashboardStartIntent_intentHasNoComponent_shouldLog() {
        final Intent intent = new Intent(Intent.ACTION_ASSIST);

        mProvider.logDashboardStartIntent(mContext, intent, MetricsEvent.SETTINGS_GESTURES);

        verify(mockLogWriter).action(
                eq(mContext),
                eq(MetricsEvent.ACTION_SETTINGS_TILE_CLICK),
                anyString(),
                eq(Pair.create(MetricsEvent.FIELD_CONTEXT, MetricsEvent.SETTINGS_GESTURES)));
    }

    @Test
    public void logDashboardStartIntent_intentIsExternal_shouldLog() {
        final Intent intent = new Intent().setComponent(new ComponentName("pkg", "cls"));

        mProvider.logDashboardStartIntent(mContext, intent, MetricsEvent.SETTINGS_GESTURES);

        verify(mockLogWriter).action(
                eq(mContext),
                eq(MetricsEvent.ACTION_SETTINGS_TILE_CLICK),
                anyString(),
                eq(Pair.create(MetricsEvent.FIELD_CONTEXT, MetricsEvent.SETTINGS_GESTURES)));
    }

    @Test
    public void action_BooleanLogsElapsedTime() {
        mProvider.action(mockVisibilityLogger, CATEGORY, SUBTYPE_BOOLEAN);
        verify(mockLogWriter).action(eq(CATEGORY), eq(SUBTYPE_BOOLEAN), mPairCaptor.capture());

        Pair value = mPairCaptor.getValue();
        assertThat(value.first instanceof Integer).isTrue();
        assertThat((int) value.first).isEqualTo(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS);
        assertThat(value.second).isEqualTo(ELAPSED_TIME);
    }

    @Test
    public void action_IntegerLogsElapsedTime() {
        mProvider.action(mockVisibilityLogger, CATEGORY, SUBTYPE_INTEGER);
        verify(mockLogWriter).action(eq(CATEGORY), eq(SUBTYPE_INTEGER), mPairCaptor.capture());

        Pair value = mPairCaptor.getValue();
        assertThat(value.first instanceof Integer).isTrue();
        assertThat((int) value.first).isEqualTo(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS);
        assertThat(value.second).isEqualTo(ELAPSED_TIME);
    }
}
