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

import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Pair;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class MetricsFeatureProviderTest {
    @Mock
    private LogWriter mLogWriter;

    private Context mContext;
    private MetricsFeatureProvider mProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mProvider = new MetricsFeatureProvider();
        List<LogWriter> writers = new ArrayList<>();
        writers.add(mLogWriter);
        ReflectionHelpers.setField(mProvider, "mLoggerWriters", writers);
    }

    @Test
    public void logDashboardStartIntent_intentEmpty_shouldNotLog() {
        mProvider.logDashboardStartIntent(mContext, null /* intent */,
                MetricsEvent.SETTINGS_GESTURES);

        verifyNoMoreInteractions(mLogWriter);
    }

    @Test
    public void logDashboardStartIntent_intentHasNoComponent_shouldLog() {
        final Intent intent = new Intent(Intent.ACTION_ASSIST);

        mProvider.logDashboardStartIntent(mContext, intent, MetricsEvent.SETTINGS_GESTURES);

        verify(mLogWriter).action(
                eq(mContext),
                eq(MetricsEvent.ACTION_SETTINGS_TILE_CLICK),
                anyString(),
                eq(Pair.create(MetricsEvent.FIELD_CONTEXT, MetricsEvent.SETTINGS_GESTURES)));
    }

    @Test
    public void logDashboardStartIntent_intentIsExternal_shouldLog() {
        final Intent intent = new Intent().setComponent(new ComponentName("pkg", "cls"));

        mProvider.logDashboardStartIntent(mContext, intent, MetricsEvent.SETTINGS_GESTURES);

        verify(mLogWriter).action(
                eq(mContext),
                eq(MetricsEvent.ACTION_SETTINGS_TILE_CLICK),
                anyString(),
                eq(Pair.create(MetricsEvent.FIELD_CONTEXT, MetricsEvent.SETTINGS_GESTURES)));
    }
}
