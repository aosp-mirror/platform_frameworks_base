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

package com.android.systemui.dreams.appwidgets;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.view.Gravity;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.appwidgets.dagger.AppWidgetComponent;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ComplicationPrimerTest extends SysuiTestCase {
    @Rule
    public final LeakCheckedTest.SysuiLeakCheck mLeakCheck = new LeakCheckedTest.SysuiLeakCheck();

    @Rule
    public SysuiTestableContext mContext = new SysuiTestableContext(
            InstrumentationRegistry.getContext(), mLeakCheck);

    @Mock
    Resources mResources;

    @Mock
    AppWidgetComponent mAppWidgetComplicationComponent1;
    @Mock
    AppWidgetComponent mAppWidgetComplicationComponent2;

    @Mock
    ComplicationProvider mComplicationProvider1;

    @Mock
    ComplicationProvider mComplicationProvider2;

    final ComponentName mAppComplicationComponent1 =
            ComponentName.unflattenFromString("com.foo.bar/.Baz");
    final ComponentName mAppComplicationComponent2 =
            ComponentName.unflattenFromString("com.foo.bar/.Baz2");

    final int mAppComplicationGravity1 = Gravity.BOTTOM | Gravity.START;
    final int mAppComplicationGravity2 = Gravity.BOTTOM | Gravity.END;

    final String[] mComponents = new String[]{mAppComplicationComponent1.flattenToString(),
            mAppComplicationComponent2.flattenToString() };
    final int[] mPositions = new int[]{mAppComplicationGravity1, mAppComplicationGravity2};

    @Mock
    DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    AppWidgetComponent.Factory mAppWidgetComplicationProviderFactory;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mAppWidgetComplicationProviderFactory.build(eq(mAppComplicationComponent1), any()))
                .thenReturn(mAppWidgetComplicationComponent1);
        when(mAppWidgetComplicationComponent1.getAppWidgetComplicationProvider())
                .thenReturn(mComplicationProvider1);
        when(mAppWidgetComplicationProviderFactory.build(eq(mAppComplicationComponent2), any()))
                .thenReturn(mAppWidgetComplicationComponent2);
        when(mAppWidgetComplicationComponent2.getAppWidgetComplicationProvider())
                .thenReturn(mComplicationProvider2);
        when(mResources.getIntArray(R.array.config_dreamComplicationPositions))
                .thenReturn(mPositions);
        when(mResources.getStringArray(R.array.config_dreamAppWidgetComplications))
                .thenReturn(mComponents);
    }

    @Test
    public void testLoading() {
        final ComplicationPrimer primer = new ComplicationPrimer(mContext,
                mResources,
                mDreamOverlayStateController,
                mAppWidgetComplicationProviderFactory);

        // Inform primer to begin.
        primer.onBootCompleted();

        // Verify the first component is added to the state controller with the proper position.
        {
            final ArgumentCaptor<ConstraintLayout.LayoutParams> layoutParamsArgumentCaptor =
                    ArgumentCaptor.forClass(ConstraintLayout.LayoutParams.class);
            verify(mAppWidgetComplicationProviderFactory, times(1))
                    .build(eq(mAppComplicationComponent1),
                    layoutParamsArgumentCaptor.capture());

            assertEquals(layoutParamsArgumentCaptor.getValue().startToStart,
                    ConstraintLayout.LayoutParams.PARENT_ID);
            assertEquals(layoutParamsArgumentCaptor.getValue().bottomToBottom,
                    ConstraintLayout.LayoutParams.PARENT_ID);

            verify(mDreamOverlayStateController, times(1))
                    .addComplication(eq(mComplicationProvider1));
        }

        // Verify the second component is added to the state controller with the proper position.
        {
            final ArgumentCaptor<ConstraintLayout.LayoutParams> layoutParamsArgumentCaptor =
                    ArgumentCaptor.forClass(ConstraintLayout.LayoutParams.class);
            verify(mAppWidgetComplicationProviderFactory, times(1))
                    .build(eq(mAppComplicationComponent2),
                    layoutParamsArgumentCaptor.capture());

            assertEquals(layoutParamsArgumentCaptor.getValue().endToEnd,
                    ConstraintLayout.LayoutParams.PARENT_ID);
            assertEquals(layoutParamsArgumentCaptor.getValue().bottomToBottom,
                    ConstraintLayout.LayoutParams.PARENT_ID);
            verify(mDreamOverlayStateController, times(1))
                    .addComplication(eq(mComplicationProvider1));
        }
    }

    @Test
    public void testNoComponents() {
        when(mResources.getStringArray(R.array.config_dreamAppWidgetComplications))
                .thenReturn(new String[]{});

        final ComplicationPrimer primer = new ComplicationPrimer(mContext,
                mResources,
                mDreamOverlayStateController,
                mAppWidgetComplicationProviderFactory);

        // Inform primer to begin.
        primer.onBootCompleted();


        // Make sure there is no request to add a widget if no components are specified by the
        // product.
        verify(mAppWidgetComplicationProviderFactory, never()).build(any(), any());
        verify(mDreamOverlayStateController, never()).addComplication(any());
    }

    @Test
    public void testNoPositions() {
        when(mResources.getIntArray(R.array.config_dreamComplicationPositions))
                .thenReturn(new int[]{});

        final ComplicationPrimer primer = new ComplicationPrimer(mContext,
                mResources,
                mDreamOverlayStateController,
                mAppWidgetComplicationProviderFactory);

        primer.onBootCompleted();

        // Make sure there is no request to add a widget if no positions are specified by the
        // product.
        verify(mAppWidgetComplicationProviderFactory, never()).build(any(), any());
        verify(mDreamOverlayStateController, never()).addComplication(any());
    }
}
