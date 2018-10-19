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

import static com.android.settingslib.core.instrumentation.Instrumentable.METRICS_CATEGORY_UNKNOWN;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.fragment.app.FragmentActivity;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;


@RunWith(SettingsLibRobolectricTestRunner.class)
public class VisibilityLoggerMixinTest {

    @Mock
    private MetricsFeatureProvider mMetricsFeature;

    private VisibilityLoggerMixin mMixin;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        mMixin = new VisibilityLoggerMixin(TestInstrumentable.TEST_METRIC, mMetricsFeature);
    }

    @Test
    public void shouldLogVisibleOnResume() {
        mMixin.onResume();

        verify(mMetricsFeature, times(1))
                .visible(nullable(Context.class), eq(MetricsProto.MetricsEvent.VIEW_UNKNOWN),
                        eq(TestInstrumentable.TEST_METRIC));
    }

    @Test
    public void shouldLogVisibleWithSource() {
        final Intent sourceIntent = new Intent()
                .putExtra(VisibilityLoggerMixin.EXTRA_SOURCE_METRICS_CATEGORY,
                        MetricsProto.MetricsEvent.SETTINGS_GESTURES);
        final Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(sourceIntent);
        mMixin.setSourceMetricsCategory(activity);
        mMixin.onResume();

        verify(mMetricsFeature, times(1))
                .visible(nullable(Context.class), eq(MetricsProto.MetricsEvent.SETTINGS_GESTURES),
                        eq(TestInstrumentable.TEST_METRIC));
    }

    @Test
    public void shouldLogHideOnPause() {
        mMixin.onPause();

        verify(mMetricsFeature, times(1))
                .hidden(nullable(Context.class), eq(TestInstrumentable.TEST_METRIC));
    }

    @Test
    public void shouldNotLogIfMetricsFeatureIsNull() {
        mMixin = new VisibilityLoggerMixin(TestInstrumentable.TEST_METRIC, null);
        mMixin.onResume();
        mMixin.onPause();

        verify(mMetricsFeature, never())
                .hidden(nullable(Context.class), anyInt());
    }

    @Test
    public void shouldNotLogIfMetricsCategoryIsUnknown() {
        mMixin = new VisibilityLoggerMixin(METRICS_CATEGORY_UNKNOWN, mMetricsFeature);

        mMixin.onResume();
        mMixin.onPause();

        verify(mMetricsFeature, never())
                .hidden(nullable(Context.class), anyInt());
    }

    @Test
    public void activityShouldBecomeVisibleAndHide() {
        ActivityController<TestActivity> ac = Robolectric.buildActivity(TestActivity.class);
        TestActivity testActivity = ac.get();
        MockitoAnnotations.initMocks(testActivity);
        ac.create().start().resume();
        verify(testActivity.mMetricsFeatureProvider, times(1)).visible(any(), anyInt(), anyInt());
        ac.pause().stop().destroy();
        verify(testActivity.mMetricsFeatureProvider, times(1)).hidden(any(), anyInt());
    }

    public static class TestActivity extends FragmentActivity {
        @Mock
        MetricsFeatureProvider mMetricsFeatureProvider;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            VisibilityLoggerMixin mixin = new VisibilityLoggerMixin(
                    TestInstrumentable.TEST_METRIC, mMetricsFeatureProvider);
            getLifecycle().addObserver(mixin);
            super.onCreate(savedInstanceState);
        }
    }

    private final class TestInstrumentable implements Instrumentable {

        public static final int TEST_METRIC = 12345;

        @Override
        public int getMetricsCategory() {
            return TEST_METRIC;
        }
    }
}
